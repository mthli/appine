package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"testing"
	"time"
)

func frame(kind byte, payload []byte) []byte {
	header := binary.BigEndian.AppendUint32([]byte{kind}, uint32(len(payload)))
	return append(header, payload...)
}

func TestResultSeparatesBinaryStreamsAndExitStatus(t *testing.T) {
	out := []byte{'a', 0, 0xff}
	errOut := []byte("diagnostic\n")
	stream := append(frame(1, out), frame(2, errOut)...)
	stream = append(stream, frame(1, []byte("tail"))...)
	stream = append(stream, frame(3, binary.BigEndian.AppendUint32(nil, 37))...)

	var stdout, stderr bytes.Buffer
	code, err := result(bytes.NewReader(stream), &stdout, &stderr, nil, nil)
	if err != nil || code != 37 {
		t.Fatalf("result = %d, %v; want exit 37", code, err)
	}
	if !bytes.Equal(stdout.Bytes(), append(out, []byte("tail")...)) || !bytes.Equal(stderr.Bytes(), errOut) {
		t.Fatalf("stdout = %q, stderr = %q", stdout.Bytes(), stderr.Bytes())
	}
}

func TestResultRequiresTerminalFrame(t *testing.T) {
	stream := bytes.NewReader(frame(1, []byte("partial")))
	var stdout, stderr bytes.Buffer
	code, err := result(stream, &stdout, &stderr, nil, nil)
	if err == nil || code != 125 || stdout.String() != "partial" {
		t.Fatalf("result = %d, %v with stdout %q", code, err, stdout.String())
	}
}

func TestParseExecTTY(t *testing.T) {
	for _, flags := range [][]string{{"-it"}, {"-ti"}, {"-i", "-t"}} {
		args := append([]string{"exec"}, flags...)
		args = append(args, "--", "bash", "-l")
		argv, tty, err := parseExec(args)
		if err != nil || !tty || len(argv) != 2 || argv[0] != "bash" || argv[1] != "-l" {
			t.Fatalf("parseExec(%q) = %q, %v, %v", args, argv, tty, err)
		}
	}
	if _, _, err := parseExec([]string{"exec", "-i", "--", "bash"}); err == nil {
		t.Fatal("accepted an incomplete interactive flag combination")
	}
}

func TestTerminalReadyBeforeExit(t *testing.T) {
	stream := append(frame(5, nil), frame(3, binary.BigEndian.AppendUint32(nil, 7))...)
	ready := 0
	var stdout, stderr bytes.Buffer
	code, err := result(bytes.NewReader(stream), &stdout, &stderr, func() error {
		ready++
		return nil
	}, nil)
	if err != nil || code != 7 || ready != 1 {
		t.Fatalf("result = %d, %v with %d readiness callbacks", code, err, ready)
	}
}

func TestTerminalAcknowledgmentsCanPrecedeReadiness(t *testing.T) {
	stream := append(frame(6, nil), frame(5, nil)...)
	stream = append(stream, frame(6, nil)...)
	stream = append(stream, frame(3, binary.BigEndian.AppendUint32(nil, 0))...)
	ready, accepted := 0, 0
	var out bytes.Buffer
	code, err := result(bytes.NewReader(stream), &out, &out, func() error {
		ready++
		return nil
	}, func() { accepted++ })
	if code != 0 || err != nil || ready != 1 || accepted != 2 {
		t.Fatalf("result %d, %v; got %d ready and %d acknowledgment frames", code, err, ready, accepted)
	}
}

func TestWakeWatchRequiresUnacknowledgedInput(t *testing.T) {
	watch := &wakeWatch{changed: make(chan struct{}, 1)}
	if !watch.next().IsZero() || watch.stalled(time.Now()) {
		t.Fatal("idle connection requested a wake")
	}
	watch.sent()
	first := watch.next()
	watch.sent()
	if !watch.next().Equal(first) {
		t.Fatal("more input postponed the first wake")
	}
	if watch.stalled(first.Add(-time.Nanosecond)) || !watch.stalled(first) {
		t.Fatal("did not wake at the acknowledgment deadline")
	}
	if watch.stalled(first) {
		t.Fatal("repeated wake before the retry deadline")
	}
	watch.accepted()
	if watch.next().IsZero() {
		t.Fatal("stopped watching while one frame was still pending")
	}
	watch.accepted()
	if !watch.next().IsZero() {
		t.Fatal("continued watching after the input was acknowledged")
	}
}

func TestWakeWatchStopsAfterRepeatedUnansweredLaunches(t *testing.T) {
	watch := &wakeWatch{changed: make(chan struct{}, 1)}
	watch.sent()
	for i := 0; i < maxWakeTries; i++ {
		if deadline := watch.next(); deadline.IsZero() || !watch.stalled(deadline) {
			t.Fatalf("missed wake attempt %d", i+1)
		}
	}
	if !watch.next().IsZero() || watch.stalled(time.Now().Add(time.Hour)) {
		t.Fatal("unresponsive peer caused unbounded wake attempts")
	}
}

func TestForegroundRefreshStopsWithSession(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	entered := make(chan struct{})
	stopped := make(chan struct{})
	go func() {
		defer close(stopped)
		refreshActivityWhileOpen(ctx, time.Minute, func(ctx context.Context) {
			close(entered)
			<-ctx.Done()
		})
	}()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("session did not refresh foreground activity")
	}

	cancel()
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("foreground refresh outlived the session")
	}
}
