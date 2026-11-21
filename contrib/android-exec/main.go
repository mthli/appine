// appine is the Android shell client for Appine's local command socket.
package main

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"golang.org/x/term"
)

// The side-by-side APK can use a matching binary via -X main.defaultPackage=...share.
var defaultPackage = "io.github.mthli.appine"

func targetPort(pkg string) (string, error) {
	switch pkg {
	case "io.github.mthli.appine":
		return "38662", nil
	case "io.github.mthli.appine.share":
		return "38663", nil
	default:
		return "", errors.New("APPINE_PACKAGE must name the standard or side-by-side Appine APK")
	}
}

func connect(pkg, port string) (net.Conn, error) {
	address := net.JoinHostPort("127.0.0.1", port)
	dial := func() (net.Conn, error) { return net.DialTimeout("tcp4", address, time.Second) }
	c, dialErr := dial()
	if dialErr == nil {
		return c, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := launchAppine(ctx, pkg); err != nil {
		return nil, err
	}
	for ctx.Err() == nil {
		if c, err := dial(); err == nil {
			return c, nil
		}
		time.Sleep(150 * time.Millisecond)
	}
	return nil, errors.New("Appine command socket is unavailable. Enable Terminal access in Appine Settings.")
}

func launchAppine(ctx context.Context, pkg string) error {
	component := pkg + "/io.github.mthli.appine.CommandStartActivity"
	launcher := []string{"/system/bin/am"}
	if prefix := os.Getenv("PREFIX"); filepath.IsAbs(prefix) {
		am := filepath.Join(prefix, "bin", "am")
		if _, err := os.Stat(am); err == nil {
			launcher[0] = am
		}
	}
	// Android permits executing the system shell. It can read the Termux am
	// script even when the calling Android UID cannot exec files in app data.
	launcher = append(launcher, "start", "-n", component)
	cmd := exec.CommandContext(ctx, "/system/bin/sh", launcher...)
	cmd.WaitDelay = time.Second
	output, err := cmd.CombinedOutput()
	if err != nil && !strings.Contains(string(output), "Starting:") {
		detail := strings.TrimSpace(string(output))
		if detail == "" {
			detail = err.Error()
		}
		return fmt.Errorf("Android could not open Appine (%s)", detail)
	}
	return nil
}

type window struct {
	rows, cols uint16
}

func terminalSize() window {
	cols, rows, err := term.GetSize(int(os.Stdout.Fd()))
	if err != nil || cols < 1 || rows < 1 || cols > 65535 || rows > 65535 {
		return window{rows: 24, cols: 80}
	}
	return window{rows: uint16(rows), cols: uint16(cols)}
}

func sendRequest(conn net.Conn, size window, argv []byte) error {
	if err := conn.SetWriteDeadline(time.Now().Add(4 * time.Second)); err != nil {
		return err
	}
	request := binary.BigEndian.AppendUint16([]byte("APX3"), size.rows)
	request = binary.BigEndian.AppendUint16(request, size.cols)
	request = binary.BigEndian.AppendUint32(request, uint32(len(argv)))
	request = append(request, argv...)
	if _, err := conn.Write(request); err != nil {
		return err
	}
	return conn.SetWriteDeadline(time.Time{})
}

func result(reader io.Reader, stdout, stderr io.Writer, ready func() error, acknowledge func()) (int, error) {
	var header [5]byte
	for {
		if _, err := io.ReadFull(reader, header[:]); err != nil {
			return 125, errors.New("Appine disconnected before returning an exit code")
		}
		size := binary.BigEndian.Uint32(header[1:])
		if size > 32768 {
			return 125, errors.New("invalid Appine result frame")
		}
		switch header[0] {
		case 1, 2:
			writer := stdout
			if header[0] == 2 {
				writer = stderr
			}
			if _, err := io.CopyN(writer, reader, int64(size)); err != nil {
				return 125, err
			}
		case 3:
			if size != 4 {
				return 125, errors.New("invalid Appine exit status")
			}
			var encoded [4]byte
			if _, err := io.ReadFull(reader, encoded[:]); err != nil {
				return 125, err
			}
			status := binary.BigEndian.Uint32(encoded[:])
			if status > 255 {
				return 125, errors.New("invalid Appine exit code " + strconv.FormatUint(uint64(status), 10))
			}
			return int(status), nil
		case 4:
			message := make([]byte, size)
			if _, err := io.ReadFull(reader, message); err != nil {
				return 125, err
			}
			return 125, errors.New(string(message))
		case 5:
			if size != 0 || ready == nil {
				return 125, errors.New("unexpected Appine terminal readiness frame")
			}
			if err := ready(); err != nil {
				return 125, err
			}
			ready = nil
		case 6:
			if size != 0 || acknowledge == nil {
				return 125, errors.New("unexpected Appine acknowledgment")
			}
			acknowledge()
		default:
			return 125, errors.New("unknown Appine result frame")
		}
	}
}

func runInteractive(
	ctx context.Context, conn net.Conn, initial window, watch *wakeWatch, wake func(context.Context),
) (int, error) {
	fd := int(os.Stdin.Fd())
	var saved *term.State
	signals := make(chan os.Signal, 1)
	resizes := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM, syscall.SIGHUP, syscall.SIGQUIT)
	signal.Notify(resizes, syscall.SIGWINCH)
	done := make(chan struct{})
	refreshCtx, stopRefresh := context.WithCancel(ctx)
	var refreshDone chan struct{}
	defer func() {
		stopRefresh()
		if refreshDone != nil {
			<-refreshDone
		}
		close(done)
		if saved != nil {
			term.Restore(fd, saved)
		}
		signal.Stop(resizes)
		signal.Stop(signals)
	}()

	var interrupted atomic.Int32
	go func() {
		select {
		case sig := <-signals:
			interrupted.Store(int32(sig.(syscall.Signal)))
			conn.Close()
		case <-done:
		}
	}()
	var writes sync.Mutex
	send := func(kind byte, payload []byte) bool {
		packet := binary.BigEndian.AppendUint32([]byte{kind}, uint32(len(payload)))
		packet = append(packet, payload...)
		writes.Lock()
		defer writes.Unlock()
		watch.sent()
		if _, err := conn.Write(packet); err != nil {
			conn.Close()
			return false
		}
		return true
	}
	code, err := result(conn, os.Stdout, os.Stderr, func() error {
		var err error
		saved, err = term.MakeRaw(fd)
		if err != nil {
			return err
		}
		refreshDone = make(chan struct{})
		go func() {
			defer close(refreshDone)
			refreshForegroundActivity(refreshCtx, wake)
		}()
		go func() {
			last := initial
			for {
				size := terminalSize()
				if size != last {
					payload := binary.BigEndian.AppendUint16(nil, size.rows)
					payload = binary.BigEndian.AppendUint16(payload, size.cols)
					if !send(2, payload) {
						return
					}
					last = size
				}
				select {
				case <-resizes:
				case <-done:
					return
				}
			}
		}()
		go func() {
			buffer := make([]byte, 4096)
			for {
				n, err := os.Stdin.Read(buffer)
				if n > 0 && !send(1, buffer[:n]) {
					return
				}
				if err != nil {
					conn.Close()
					return
				}
			}
		}()
		return nil
	}, watch.accepted)
	if sig := interrupted.Load(); sig != 0 {
		return 128 + int(sig), nil
	}
	return code, err
}

const usage = "usage: appine exec [-it] -- COMMAND [ARG...]"

func parseExec(args []string) (argv []string, tty bool, err error) {
	if len(args) == 0 || args[0] != "exec" {
		return nil, false, errors.New(usage)
	}
	var interactive, terminal bool
	for index, arg := range args[1:] {
		switch arg {
		case "-i":
			interactive = true
		case "-t":
			terminal = true
		case "-it", "-ti":
			interactive, terminal = true, true
		case "--":
			argv = args[index+2:]
			if len(argv) > 0 && interactive == terminal {
				return argv, terminal, nil
			}
			return nil, false, errors.New(usage)
		default:
			return nil, false, errors.New(usage)
		}
	}
	return nil, false, errors.New(usage)
}

func run(args []string) (int, error) {
	if len(args) == 1 && (args[0] == "--help" || args[0] == "-h") {
		fmt.Println(usage)
		return 0, nil
	}
	argv, tty, err := parseExec(args)
	if err != nil {
		return 2, err
	}
	var size window
	if tty {
		if !term.IsTerminal(int(os.Stdin.Fd())) || !term.IsTerminal(int(os.Stdout.Fd())) {
			return 2, errors.New("-it requires a terminal on stdin and stdout")
		}
		size = terminalSize()
	}
	data, err := json.Marshal(argv)
	if err != nil || len(argv) > 256 || len(data) > 32768 {
		return 2, errors.New("command exceeds the Appine argument limit")
	}
	pkg := os.Getenv("APPINE_PACKAGE")
	if pkg == "" {
		pkg = defaultPackage
	}
	port, err := targetPort(pkg)
	if err != nil {
		return 2, err
	}
	conn, err := connect(pkg, port)
	if err != nil {
		return 125, err
	}
	defer conn.Close()
	ctx, cancel := context.WithCancel(context.Background())
	watchDone := make(chan struct{})
	defer func() {
		cancel()
		<-watchDone
	}()
	watch := &wakeWatch{changed: make(chan struct{}, 1)}
	var launching atomic.Bool
	wake := func(ctx context.Context) {
		if launching.CompareAndSwap(false, true) {
			defer launching.Store(false)
			launchAppine(ctx, pkg)
		}
	}
	go func() {
		defer close(watchDone)
		watch.run(ctx, wake)
	}()
	watch.sent()
	if err := sendRequest(conn, size, data); err != nil {
		return 125, err
	}
	if tty {
		return runInteractive(ctx, conn, size, watch, wake)
	}
	return result(conn, os.Stdout, os.Stderr, nil, watch.accepted)
}

func main() {
	code, err := run(os.Args[1:])
	if err != nil {
		fmt.Fprintln(os.Stderr, "appine:", err)
		if code == 0 {
			code = 125
		}
	}
	os.Exit(code)
}
