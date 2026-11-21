package main

import (
	"context"
	"os/exec"
	"strings"
	"sync"
	"time"
)

const (
	ackGrace     = 800 * time.Millisecond
	wakeRetry    = 3 * time.Second
	maxWakeTries = 3
)

// Some Android builds freeze Appine even with its foreground service running.
// The launch activity resumes the same service. The watcher only wakes it
// when a request is unacknowledged.
type wakeWatch struct {
	mu       sync.Mutex
	changed  chan struct{}
	pending  int
	deadline time.Time
	tries    int
}

func (w *wakeWatch) sent() {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.pending++
	if w.pending == 1 {
		w.progress()
	}
}

func (w *wakeWatch) accepted() {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.pending > 0 {
		w.pending--
		w.progress()
	}
}

func (w *wakeWatch) progress() {
	w.tries = 0
	w.deadline = time.Now().Add(ackGrace)
	select {
	case w.changed <- struct{}{}:
	default:
	}
}

func (w *wakeWatch) next() time.Time {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.pending == 0 || w.tries >= maxWakeTries {
		return time.Time{}
	}
	return w.deadline
}

func (w *wakeWatch) stalled(now time.Time) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.pending == 0 || w.tries >= maxWakeTries || now.Before(w.deadline) {
		return false
	}
	w.tries++
	w.deadline = now.Add(wakeRetry)
	return true
}

func (w *wakeWatch) run(ctx context.Context, wake func(context.Context)) {
	timer := time.NewTimer(0)
	defer timer.Stop()
	for {
		var timeout <-chan time.Time
		deadline := w.next()
		if deadline.IsZero() {
			timer.Stop()
		} else {
			timer.Reset(time.Until(deadline))
			timeout = timer.C
		}
		select {
		case <-ctx.Done():
			return
		case <-w.changed:
		case now := <-timeout:
			if w.stalled(now) {
				launchCtx, cancel := context.WithTimeout(ctx, wakeRetry)
				wake(launchCtx)
				cancel()
			}
		}
	}
}

// Oplus background freezing can suspend a foreground service with a partial
// wake lock. Its launcher briefly marks the app active without taking focus.
// Refresh only while a user has an interactive shell open.
func refreshForegroundActivity(ctx context.Context, wake func(context.Context)) {
	version, err := exec.CommandContext(ctx, "/system/bin/getprop", "ro.build.version.oplusrom").Output()
	if err != nil || strings.TrimSpace(string(version)) == "" {
		return
	}
	refreshActivityWhileOpen(ctx, 3*time.Second, wake)
}

func refreshActivityWhileOpen(ctx context.Context, interval time.Duration, wake func(context.Context)) {
	timer := time.NewTicker(interval)
	defer timer.Stop()
	for ctx.Err() == nil {
		launchCtx, cancel := context.WithTimeout(ctx, interval)
		wake(launchCtx)
		cancel()
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
		}
	}
}
