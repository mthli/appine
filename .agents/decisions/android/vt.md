# Terminal sessions (android/vt) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: android/vt"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12)
>
> Decisions on the tabs' sessions made before android/vt was split from android/terminal keep their `android/terminal` tags and live in `.agents/decisions/android/terminal.md`: D2, D5, D6, D8, D16, D17, D32, D33, D35–D39 and D42–D49.

## Active

### D1: android/vt owns Kotlin sessions and console lifecycle
- **What**: android/vt owns VtSession.kt and ConsolePty.kt, session events/state, asynchronous clipboard answers, restore fallback and session-side terminal calls, with native state and mechanisms in android/vtcore.
- **Why**: The session map mixed Kotlin lifecycle with native protocol, parsing and locking details.
- **Tradeoffs**: Session/native changes require both maps, while frame encoding and rendering stay in android/vtrender and interaction in android/vtview.
- **Watch out**: Preserve callback threading, exactly-once console closing and snapshot-result contracts across the boundary.
- **Source**: d42013d; 9015b244, 81f60bef; d053be24; 39e55b4b

### D2: A tab keeps only the part of its transcript a save keeps
- **What**: `transcriptText` takes the last `Tabs.MAX_TRANSCRIPT` characters, keeps them and returns them, so a kept and a fresh transcript of the same screen are the same string, and `Tabs` makes the constant `internal` for it while still cutting what it saves.
- **Why**: The transcript kept for saves while the screen is unchanged or on the alternate screen held the whole scrollback's text, of which saving keeps only the last 256 K characters.
- **Tradeoffs**: The formatter and JNI still make the whole text once per save before it is cut, and `VtSession` now depends on a constant of android/service's `Tabs`.
- **Watch out**: A cut can start inside a surrogate pair or a line, as `Tabs`'s own cut already could.
- **Source**: ca5f5c2

### D3: Background tabs' Kitty images are cut by lowering the storage limit
- **What**: `appine_vt_drop_images` sets `GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT` to `KITTY_TRIMMED` (4 MiB) and back to `KITTY_STORAGE` (32 MiB) on both screens, called by `VtNative.dropImages(tab)` under the tab's lock (`Tab::term`) when the service trims background images (android/service D4), leaving the transcript and the snapshot state alone.
- **Why**: A tab's Kitty images (up to 32 MiB a screen) could not be given back, and libghostty-vt's C API can neither compress, drop nor count them; only lowering its storage limit evicts.
- **Tradeoffs**: Images are deleted outright with their placements, shown ones included, a program placing an image again by id finds it gone, and a restore from the snapshot saved before the cut brings the deleted images back.
- **Watch out**: A libghostty-vt upgrade could change how lowering the limit evicts or what a limit change marks as mutated, and the view's frames rely on the placements generation changing.
- **Source**: c1a37da

### D4: VtNative is the shared cursor-style namespace

- **What**: VtNative defines all five cursor styles, including frame-only hollow and lock values, and the view/frame/renderer code references them.
- **Why**: Repeated numeric cursor definitions could drift.
- **Tradeoffs**: Two frame-only values are not accepted by setCursor.
- **Watch out**: Keep the numbers aligned with GhosttyCursorStyle and vt.c’s lock value.
- **Source**: aaa1e7d5; 20137e4c; d053be24; 39e55b4b

### D5: Fish and nushell use their own prompt marks and inherited environment

- **What**: No extra fish/nushell startup file is installed because their shells supply marks and ConsolePty’s TERM, LANG and NMAP_UNPRIVILEGED survive login.sh’s su.
- **Why**: The maps incorrectly grouped fish with unmarked shells and implied it needed environment injection.
- **Tradeoffs**: Shells bypassing the profile show no motd, and nushell declares no tap-cursor behavior.
- **Watch out**: Marks were observed in containers and environment propagation was verified by code, not in the app.
- **Source**: a7d8c9f5; 20137e4c; d053be24; 39e55b4b

### D6: Ash continuation parsing excludes background writer runs

- **What**: With appine_lf=output, B-marked ash input treats its CR/CR/LF row breaks as continuations and other foreground line feeds as output starts; writer-tagged background runs are bracketed with output and continuation/input marks without ending input or starting command timing. For shells with incomplete marks, VtSession queries native busy state and uses a foreground-process title when available.
- **Why**: Long command rows and background job output otherwise incorrectly changed prompt state and busy/timing behavior.
- **Tradeoffs**: Parsing depends on busybox row breaks and ONLCR, and native handling rejects taps above the resumed row after background output.
- **Watch out**: OSC/DCS strings are tracked but split CSI sequences remain a risk, old edited profiles keep I marks, and no-job-control writers cannot be distinguished by process group.
- **Source**: a809c5a8, daab4446; 20137e4c; d053be24; 39e55b4b, acc5a7a0, 6e0c0f16

### D7: Bash hooks move to a companion script without changing terminal parsing

- **What**: The profile and Alpine’s system bashrc source appine.bashrc, recognized profile hashes migrate existing unedited installations, and terminal parsing retains ash support.
- **Why**: New Alpine bash defaults need prompt recognition and nested interactive-shell timing.
- **Tradeoffs**: Nested bash depends on Alpine’s hook and remains unmarked on Debian.
- **Watch out**: PROMPT_COMMAND replacement or edited profiles can prevent integration; broader prompt and redraw checks remain outstanding.
- **Source**: 7aa817af; 20137e4c; d053be24; 39e55b4b

### D8: Sessions expose a stable BROWSER launcher without transport state

- **What**: ConsolePty sets BROWSER=/usr/local/bin/xdg-open while browser transport remains outside the terminal parser.
- **Why**: CLI discovery needs a conventional launcher independent of a tab's lifetime.
- **Tradeoffs**: Programs may ignore or override BROWSER.
- **Watch out**: A preserved custom launcher can behave differently from the Appine client.
- **Source**: 8e211142; d053be24; 39e55b4b

### D9: Hanging up invalidates queued writes and preserves console ownership intervals

- **What**: Shut down the writer before closing the PTY, check hangup on submission and execution, and retain console ownership epochs for kill attribution.
- **Why**: A recycled console can receive stale writes or kill reports.
- **Tradeoffs**: Session teardown coordinates the writer and PTY.
- **Watch out**: Every exit and release path must close once.
- **Source**: 6f2776f3

### D10: Terminal status expiry follows event order

- **What**: Process expiry records in queue order and keep terminal success or failure visible until observed.
- **Why**: A later expiry could otherwise erase an unseen result.
- **Tradeoffs**: Status records may survive until the UI consumes them.
- **Watch out**: Expiry markers must correspond to the originating command.
- **Source**: 568b6a48

### D11: Clipboard requests belong to a bounded generation of one session

- **What**: Coalesce up to 64 targets under a 60-second waiting deadline and compare session and native generation tokens before accepting an answer.
- **Why**: A reset or delayed dialog can otherwise answer an obsolete request.
- **Tradeoffs**: Excess requests get an empty answer and opening a prompt cancels the waiting deadline.
- **Watch out**: Observe native generation changes before accepting delayed callbacks.
- **Source**: f3d226d5, 565f4a4e

### D12: `VtSession.takeTranscript` runs on the service worker with `@Volatile lastTranscript`

- **What**: `VtSession` replaces `transcriptText` with a worker-called `takeTranscript()` method and marks `lastTranscript` `@Volatile`, matching `resolvedLogin`, `takeSnapshot()`, and `savedSnapshot`.
- **Why**: `VtNative.transcript` is mutex-protected and safe to invoke from the service worker just like `takeSnapshot()`, so transcript capture need not run on the main thread.
- **Tradeoffs**: Main-thread callers no longer have a direct transcript property (none existed outside `TerminalService.saveTabs`).
- **Watch out**: A session released on the main thread while a queued worker save runs sees `VtNative.transcript` return null and falls back to `lastTranscript`, matching `takeSnapshot()`.
- **Source**: 2f321b12

## Superseded
- ~~Background output at an ash prompt ending input like Enter (a809c5a8)~~ → replaced by **D6** in daab4446 (2026-11-13)
- ~~D1 ownership of VtNative.kt, vt.rs and native terminal sections of vt.c~~ → replaced by **D1** in 81f60bef (2026-11-14)
