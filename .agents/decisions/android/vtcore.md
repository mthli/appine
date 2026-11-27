# Native terminal implementation (android/vtcore) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: android/vtcore"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12)
>
> Decisions made before android/vtcore was split from android/vt retain their `android/vt` tags in `.agents/decisions/android/vt.md`: D1, D3, D4 and the parsing side of D5–D7; earlier `android/terminal` decisions remain in `.agents/decisions/android/terminal.md`: D2, D5, D6, D8, D16, D17, D32, D33, D35–D39 and D42–D49.

## Active

### D1: Native state, locking and terminal protocols stay together

- **What**: android/vtcore owns VtNative.kt, all vt.rs and vt.c except registered interaction and frame/rendering sections, keeping prompt parsing, snapshots and compression beside shared terminal state.
- **Why**: Native implementation needs an explicit owner distinct from Kotlin session lifecycle.
- **Tradeoffs**: The map remains large because another split would duplicate shared-state and locking contracts; native runtime integration belongs to android/native and build configuration to android/build.
- **Watch out**: Assign every new vt.c section explicitly and preserve inherited device/protocol verification gaps; the 2026-11-11 mapping is inherited through android/vt, not independently repeated.
- **Source**: 81f60bef; d053be24; 60790c14; 39e55b4b

### D2: Resize output settles only after quiet parser-ground capture

- **What**: Grid changes mark output pending and subsequent nonempty feeds refresh its timestamp, with frame capture clearing it only after 32 ms of quiet at parser ground and without a synchronized-output hold.
- **Why**: A nonempty read can be a cursor update or incomplete synchronized-output prefix rather than a complete repaint.
- **Tradeoffs**: Settling adds a short delay without postponing resize or SIGWINCH.
- **Watch out**: Quiet time is a heuristic and later delayed output can follow an apparently settled burst.
- **Source**: 2e73a06a; 39e55b4b

### D3: Oversized vtcore map sections are condensed in place rather than splitting `vt.c` further

- **What**: `android/vtcore.md` condenses `Key types`, unused `libghostty-vt` API catalogs, `Invariants and gotchas` and the repeated `tmux -CC` note by role without dropping verified facts, reducing it from 42,862 B to 33,861 B (73.5% of one read) with 26.1% baseline drift.
- **Why**: The map reached 93.0% of the single-read budget while `vt.c` was already partitioned across four modules.
- **Tradeoffs**: Individual `AppineVt` struct fields and unused `libghostty-vt` C API symbols are summarized by role instead of enumerated exhaustively.
- **Watch out**: Further growth pushing the map past 80% would make extracting scrollback compression and snapshot state into a separate module necessary.
- **Source**: e0719858

### D4: Native command marks drive status expiry

- **What**: Queue expiry at command completion and primary prompt start, retaining foreground sampling for unmarked shells.
- **Why**: Status needs the same boundary in native code and the Kotlin queue.
- **Tradeoffs**: Shells without marks depend on sampling.
- **Watch out**: Keep expiry markers aligned with queued status events.
- **Source**: 568b6a48

### D5: Ash prompt taps follow the row where input resumed

- **What**: Distinguish a same-row resume from a new row after background output and reject taps above the resumed input row.
- **Why**: Background writes can move the cursor without creating editable prompt text.
- **Tradeoffs**: The parser retains the resumed row.
- **Watch out**: Clear the marker on prompt and reset transitions.
- **Source**: acc5a7a0

### D6: Foreground sampling supplies busy state for partially marked shells

- **What**: Keep atPrompt tied to OSC 133 and probe the foreground process after a newline for ash and unmarked shells, giving explicit titles priority.
- **Why**: A replaced PS1 can hide shell command boundaries.
- **Tradeoffs**: Short commands can evade sampling and fallback completion lacks an exit code.
- **Watch out**: Release the terminal lock before acquiring the BKL.
- **Source**: 6e0c0f16

### D7: Native terminal reset invalidates outstanding clipboard reads

- **What**: Advance a generation under the terminal lock and discard buffered reads on both RIS and explicit reset.
- **Why**: A queued response could otherwise cross a reset boundary.
- **Tradeoffs**: JNI and Kotlin carry the native generation.
- **Watch out**: Cancel unfinished escape sequences on explicit reset.
- **Source**: 565f4a4e

### D8: `vt-pump` batches console run-state reads, caches foreground lookups, and truncates transcripts in C

- **What**: `on_render_hold` records only `held` and `held_since`, `vt-pump` reads output plus password/foreground identity in one `Machine::read_console_run_state` call with `FgCache::resolve` (using `fg_pgrp` for status expiry and skipping or caching `console_foreground_process`), and `appine_vt_transcript` formats a bounded tail (`format_transcript_tail`, `utf8_tail_offset`, `keep_text_slice`) truncated to 256 Ki UTF-16 code units before JNI.
- **Why**: Per-read console polling took four BKL locks and queried guest `mm.args` even for OSC 133 shells, `on_render_hold` allocated a full render state under `Tab.term`, and `appine_vt_transcript` decompressed and copied up to 10 MiB across JNI only for Kotlin to keep the tail.
- **Tradeoffs**: `FgCache` misses in ash or unmarked shells still query `console_foreground_process` once per foreground transition, and sparse short-line scrollback falls back to full formatting after testing the tail window.
- **Watch out**: A guest process mutating its own `argv` memory in place without `execve` or `PR_SET_NAME` keeps the cached fallback title until a foreground change or probe timeout.
- **Source**: 2f321b12

## Superseded

- ~~Counting ash prompt rows above the input resumed after background output (android/vt D6)~~ → replaced by **D5** in acc5a7a0 (2026-11-25)
