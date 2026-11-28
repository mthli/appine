# android/service Map

> Static understanding snapshot, not a decision history.
> See `.agents/decisions/android/service.md` for the paired decision history (it still holds the decisions on notifications, fault reports and diagnostics made before they were split out into `android/reports`).
>
> Verified: 2026-11-26 (full refresh: 17 adversarial claims checked independently — 14 confirmed and 3 partial corrected, plus 5 confirmed bugs/debt items recorded below)
>
> Maintained: 2026-11-28 (targeted verification only since the full mapping; latest change: LAN address lookup for port mapping snapshots)

## Responsibilities

`TerminalService` owns the guest kernel, its browser endpoint and all terminal sessions across activity stops and recreation. It coordinates import, boot, shutdown, reboot and distro replacement. It decides when guest activity needs a wake lock, when tabs need saving or trimming, and where kernel events and program requests should go. It builds foreground notification **id 1**.

The module comprises `TerminalService.kt`, `BrowserBridge.kt`, `LanAddresses.kt`, `Settings.kt` and `Tabs.kt` under `android/app/src/main/java/io/github/mthli/appine/`. `android/setup` supplies the store and guest setup. `android/reports` owns channels, non-ongoing notifications, fault reports and diagnostics.

## Key types

| Type | Role |
| --- | --- |
| `TerminalService.LocalBinder`, `Listener` | Same-process service reference and main-thread UI callbacks. A Settings listener counts for visibility and idle exit. Foreground tab routing requires a terminal activity. |
| `ServiceLifecycle` | Main-thread lifecycle flags, wake-lock decision and completion of start, replacement, restart and exit. |
| `StartedKernel` | Startup handoff of `handle`, nullable `browser`, `booted`, `gpuAvailable` and optional `setupError`. `machine == 0L` means there is no published kernel. |
| `Switch` | Distro and volatile byte counts/cancellation flag. `switching` tracks the visible download, `downloader` its unfinished thread, `pendingReplace` a completed choice awaiting a start result. |
| `ConsoleTracker` | Per-kernel monitor and epoch pairing console lifetimes with kill batches. |
| `GuestActivity`, `RunPolicy` | CPU sample and activity time for the BUSY grace period, and the persisted SLEEP/BUSY/ALWAYS choice. |
| `BackgroundImageTrim` | Ordered pending tab IDs with departure times, plus unseen IDs already trimmed and eligible for another pass. |
| `BrowserBridge` | One kernel's listening socket, credential, active client and main-thread dispatch callback. |
| `Settings`, `Tabs` | Private `settings` preferences and `files/tabs/` recovery store. `Tab` holds login, cwd, transcript and snapshot name. `Saved` adds layout, selection and time. `Killed` pairs a PID with a nullable layout. |

Main owns the session list and lifecycle flags. `starting` covers import/private setup, `transitioning` the restart/replacement gap, `booting` the published kernel's outstanding boot check, and `exiting` the shutdown. After `destroyed` is set, `start`, `replace`, `restart` and `exitAll` return at entry, and main drops late import progress and notification updates. The single `appine-worker` orders poll batches, route updates, saves, trims, stop/shutdown and store reset. Start, boot, download and browser I/O have separate threads.

## Public entry points

### Android and UI

- The manifest declares a non-exported `specialUse` service. `onCreate` enters the foreground. `onBind` returns the local binder. `onStartCommand` handles `EXIT_ALL`, `CANCEL_SWITCH`, `TOGGLE_KEEP_AWAKE` and legacy report-copy intents, and returns `START_NOT_STICKY`. Android also calls `onConfigurationChanged`, `onTrimMemory` and `onDestroy`.
- `MainActivity` and `SettingsFragment` start and bind, call `addListener` on connection and `removeListener` on stop. Listeners receive `onProgress`, `onMachineReady(error)`, `onSessionsChanged`, `onTabStatus`, `onSettingsChanged`, `onNotice`, `onRestarting`, `onExitAll` and the program callbacks below. `takeNotice()` consumes the single pending message.
- The activity opens and closes tabs with `createSession(account = login, cwd = null)` (passing `current?.cwd()` for new tabs) and `removeSession`, assigns `current`, and reads `sessions` and restore `focus`. Clients can inspect `machine`, `gpuAvailable`, `startError`, `progress` and `switching`, invoke `exitAll`, call `switchTo` when `canSwitch`, or cancel an active download with `cancelSwitch`.
- `portMappings` reads live bindings on the serial worker when the terminal requests them and delivers the result on main. For nonempty bindings it also looks up the phone’s LAN IP on the worker. The dialog and listen notifications share Wi-Fi and Ethernet address selection, preferring the default network and IPv4 within each network. A missing or replaced kernel yields null for both results.

### Settings and side effects

| Preference | Default and service behavior |
| --- | --- |
| `tier`, `gpu` | Tier 1 and GPU off in all builds. Changed values in `setTier`/`setGpu` persist and request a restart of a published kernel. Unchanged values return. `gpuAvailable` is the live result and clears on detach/destruction. |
| `login` | `auto`, also accepts `root` or a named account. `setLogin` changes new tabs, refreshes Files roots and tells listeners. `clearLogin` removes the key on replacement. |
| `run_policy`, `idle_policy`, `keep_awake` | Valid strings are `sleep`, `busy`, `always`. An absent or unknown policy falls back to legacy true for ALWAYS, false/missing for SLEEP. A policy write removes `keep_awake`. Non-ALWAYS writes remember the idle choice. Only `busy` in `idle_policy` selects BUSY, otherwise SLEEP. |
| `privacy_mode`, `file_access` | Both off. Access requires the saved opt-in and privacy off. The old `system_documents` key is ignored. Enabling privacy clears `file_access` in the same edit. `Privacy.changeMode` revokes grants and clears existing detail notifications on enable, refreshes roots, then signals listeners. Disabling privacy leaves access off. |
| `fill_wide` | Off. `setFillWide` writes the CJK two-cell preference and calls `settingsChanged` for the activity to apply it. |
| `cursor_style`, `cursor_blink` | Block, blinking on. Shapes persist as `block`, `bar` or `underline`, with block as fallback. `setCursorStyle`/`setCursorBlink` apply both defaults to every session. Unstarted sessions retain them until creation. A program can select its own cursor. |
| `volume_keys_switch_tabs`, `show_tab_list` | Both on. `setVolumeKeysSwitchTabs`/`setShowTabList` persist the choice and call `settingsChanged`. The activity reads the former on each volume-key press and the latter on UI refresh. |

`setFileAccess(true)` returns during privacy mode. Otherwise it writes the choice, revokes provider read/write grants on disable, refreshes roots and calls `settingsChanged`. Settings confirms before enabling. `settingsChanged` updates id 1 and the UI listeners. The service privacy listener calls this same method. Direct preference writes bypass service-setter effects.

## Data flow / lifecycle

### Service creation and the previous run

`onCreate` registers the privacy listener, creates the four channels through `Notifications`, and calls `startForeground` with id 1 (explicit `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34+). It calls `Tabs.takeKilled` before starting a kernel. Without a `files/tabs/running` marker, `Tabs.clear` deletes the directory and resets the layout cache. With a marker, the files stay in place. An empty legacy marker or an unparsable PID gives zero. A failed layout read is logged and leaves `Killed.saved` null.

For a positive PID, `lastExit` asks for the most recent matching `ApplicationExitInfo` for this package. The seven outcomes from `endedText` are low memory, excessive resource use, user-requested/stopped, package updated (API 34+), Java/native crash, ANR, or generic Android stop. The last covers unknown PID, absent record, lookup failure and other reasons. A crash or ANR goes to `Notifications.crash` immediately, even without a readable layout.

`startOrImport` calls `start(null)` when `Rootfs.isReady` finds the store's `meta.db`. Otherwise `Rootfs.importing` selects the last distro for which import began, defaulting to bundled Alpine. A UI returning during shutdown uses the same decision.

### Private startup and kernel publication

`start` clears `startError`, sets `starting`, updates the lock and captures tier/GPU. Given a distro, `appine-start` calls `Rootfs.prepare` with progress delivered on main while the service is alive and `Rootfs::probeMirrors` for import-time sources. Setup records the distro, configures a temporary store and publishes it by rename. `startKernel` still treats this attempt as an import after the store is in place.

`prepareStartBridge` opens `BrowserBridge` before `Native.start`. `Rootfs.prepareStart` receives the optional endpoint configuration and runs network/container settings, login tools, Alpine bash startup, the GPU command if enabled, browser files if configured, the profile and an Alpine `openrc-setup-done` precheck in one store session. Its `StartPreparation` reports `browserReady` (whether it wrote the capability to `/etc/appine/browser`) and `openRcDone`.

| Startup path | Failure behavior |
| --- | --- |
| Fresh import | An `IOException` opening the endpoint or reported by a setup step aborts the attempt. Any acquired bridge closes. |
| Existing store | Each reported setup failure goes to `Log.w` and `Diagnostics.record` without a UI notice. Failure to open the bridge leaves it null. Failure to write the capability closes it and makes `StartedKernel.browser` null. Other failed helpers can coexist with a configured endpoint. |
| Escaping error | An exception escaping configuration closes the bridge. Failure during subsequent private kernel setup closes it and calls `Native.stop` if a handle was acquired. |

`Rootfs.access.write` excludes provider opens during `Native.start(store, tier, gpu)`. Availability is `gpu && Native.hasGpu(handle)`. Before publication, Alpine runs `Rootfs.ensureOpenRc` on the start thread unless `prepareStart` already confirmed `openRcDone` (an absent or obstructed marker leaves `openRcDone` false). It checks the setup marker and installed package, and installs or resumes staged setup when needed. That path calls `Rootfs.boot`, records that inittab ran, verifies the default runlevel and marks setup complete. A post-boot verification failure retains `booted = true`.

OpenRC setup I/O failure aborts an import and stops the private kernel. On an existing store the callback logs it and sets `setupError` for a visible notice, allowing a repair terminal. `Rootfs.boot` runs the guest `PREFER_OSCLOCK` shell command without a console, preserving existing clock-service choices. It ignores exit status, logs exceptions, then calls `Native.boot` for inittab.

Main clears `starting`. After destruction it updates the wake lock for either result. On success it also closes the endpoint and runs `Native.shutdown` on an `appine-stop` thread. If the service is alive with an exit pending, it closes the returned endpoint and calls `finishExit` with the actual boot flag, or zero handle on failure.

`started` installs the bridge, console tracker, login rules and auto-roots watcher, publishes `machine` and GPU availability, sets `booting`, resets guest activity, clears progress and refreshes roots. Tabs can now use the kernel. A `pendingReplace` is consumed here **before** setup notices, marker, tab restore, boot thread, network callback or poll.

Otherwise main updates the lock, delivers `setupError` via `tell`, calls `markRunning`, restores pending tabs once and arms the minute save. `appine-boot` calls `Rootfs.boot` only if setup did not already boot, then reads failed services either way. Its main completion checks the handle before clearing `booting`, resetting activity, updating the lock and reporting failures. Main registers the default-network callback, posts the first poll, refreshes id 1 and calls `onMachineReady(null)` without waiting for boot completion.

### Failed start and distro recovery

`startFailed` logs and records the exception before choosing recovery. A failed non-Alpine import with **no ready store** takes precedence. `Rootfs.deleteTarball` attempts to remove only that distro's tarball and `.part`. The service leaves a fallback notice and starts Alpine with any `pendingReplace` still pending. A startup failure after store publication does not qualify.

Otherwise it consumes `pendingReplace` if present. With none, it clears progress, sets `startError`, updates lock and notification, calls `onMachineReady(error)` and checks `stopIfFailed`. That check stops a kernel-less failed service only once listeners and `switching` are absent and no start/transition/exit is active. Listener removal and a failed or cancelled download recheck it. A visible Settings UI can initiate replacement to recover.

### Polling, console ownership and routes

Every two seconds the main-thread `poll` captures the live handle and its tracker, login rules and auto-roots watcher. It calls `Native.bklStall` on main before queuing the batch. This read avoids the big kernel lock (BKL) and sends any watchdog record to `Notifications.stall` while the worker can be blocked. The handler schedules the next tick without waiting for delivery.

The worker reads `takeListens`, an epoch-tagged `takeKills`, `takePower`, `processes` and `takeChangedDirs`. A non-null directory array goes to `Documents.dirsChanged`. `Documents.AutoRootsWatcher.poll` also runs when the array is null. With a ready store, file access and `auto` login, it baselines the resolved home, checks passwd metadata and the home's parent, and refreshes roots on a changed home. `Rootfs.LoginRules.poll` samples its own inputs, avoids overlapping refreshes, spawns any needed guest refresh on the worker and acknowledges the inputs after a separate waiter sees success. Last come `guestCpuTime` and an elapsed-realtime timestamp.

Main discards its posted batch if the handle changed. Document and login-rule work has already run. Otherwise main samples CPU, updates the lock, dispatches listens and kills, then handles power. Reboot calls `restart`, halt/off call `exitAll`. In the default branch, idle exit requires zero running sessions, zero listeners and zero sampled guest processes (init and zombies excluded). A restored tab still waiting to start counts as running. An ended tab may remain listed.

`ConsoleTracker.open` increments its epoch before opening. `take` drains `Native.takeKills` with the current epoch under the same monitor. `close` closes the console, drains the entire kill queue, records the inclusive closing epoch, then advances it before unlocking. It forwards the drain outside the monitor. Main checks the original handle. Poll has an epoch-zero fallback if no tracker exists.

`reportKill` requires seven tab-separated fields and numeric console and PID. `sessionForKill` matches a still-listed session through `ownedConsoleAt`. The console number must match and be nonnegative. The observation must fall at or after its positive opening epoch and at or before its closing epoch (zero while open). Reuses of a number have disjoint intervals. An ended tab can own an earlier report. Only successful insertion of the yellow `[appine]` line into the tab in front suppresses an ordinary notification. An alternate screen can refuse it. `bug` records always go to `Notifications.kill` with the report fields.

The default-network callback queues `Native.setRoutes` on the worker. `routes` serializes hex destination/prefix, hex gateway or `-`, and interface with link-property fallback. It skips missing interfaces and, from API 33, non-unicast routes. Network loss sends an empty array.

### Running policy and notification

`updateWakeLock` acquires or releases a non-reference-counted `PARTIAL_WAKE_LOCK` tagged `appine:keep-awake`. After destruction, every policy releases any held lock. While the service is alive, transitions are `starting`, `transitioning`, `booting` and `exiting`.

| Policy | When to hold |
| --- | --- |
| SLEEP | Never. |
| ALWAYS | A published kernel or any transition. |
| BUSY | Any transition, active `switching` download, or a published kernel within guest activity grace. |

`GuestActivity` compares guest CPU nanoseconds including exited threads. A first or changed value (in either direction) renews activity. An unchanged valid sample preserves it. A negative value renews grace and clears the baseline. Samples older than `activeAt` are ignored first. Delivery at least two seconds late resets grace at receipt and discards the sample. Publication, same-machine boot completion and a policy change also reset it. At 30 seconds idle `isBusy` becomes false. A completed poll can release or reacquire the lock while Android is awake. Polling cannot wake a suspended device.

Notification 1 opens `MainActivity`. Its ordinary title uses the first matching row.

| Condition | Title |
| --- | --- |
| Exiting | “Stopping services…” |
| No machine and non-null `progress` | Preparation or import progress text |
| No machine, no progress and `startError` | “Cannot start” |
| Live machine and zero running sessions | “Running in the background” |
| Otherwise | Running-session count, including zero during startup |

The “Cannot start” body shows the error reason. Every other state shows the selected policy label. Expanded `BigTextStyle` uses exactly the same text. Privacy mode always uses “Privacy mode” for the title and the policy label for both bodies, even when unlocked. A switch adds Cancel and, outside privacy, download subtext and determinate progress for a positive total. A download alongside a live kernel keeps the machine's state title.

Exit all is always available. Unless exiting, one action offers ALWAYS from SLEEP/BUSY, or the remembered idle policy from ALWAYS. `setRunPolicy` writes a changed choice, resets activity, updates the lock and calls `settingsChanged`. `updateNotification` returns if destroyed. On configuration change the service reposts id 1 only when `Notifications.createChannels` detects changed locales and refreshes channel names.

### Sessions and guest requests

`createSession` pairs a `ConsolePty` (machine, login, cwd and tracker) with a `VtSession` (next ID and `sessionEvents`) and applies cursor defaults. The first view size starts the shell. Opening, closing or finishing a shell refreshes the count and debounces a save. Title changes only notify listeners. `removeSession` releases the tab, removes its unshown/trim state, clears `current` if necessary and cancels its notification.

`inFront(session)` requires identity with `current`, a registered `MainActivity` and `!Privacy.locked(this)`. With privacy enabled, an app still locked, an unavailable device or the absence of a resumed protected page closes that gate. Assigning `current` cancels the selected tab's notification and bell/status bookkeeping, and marks done/error records seen when in front.

| Event | Routing |
| --- | --- |
| Bell or OSC 9/777 notification | In front, call listeners' `onBell` for haptics. Else delegate to `Notifications.bell` or `program`. Background bells are throttled to one per second per tab. |
| Progress or OSC 7501 status | Notify listeners via `onTabStatus`, then call `Notifications.status`. Changed blocked/done/error records can post the tab notification. In front, done/error count as seen. When an eligible status disappears, withdraw only the notification it still owns. A posted bell, program notice or qualifying finished command clears that ownership. |
| OSC 133 command finish | `onCommandFinished` forwards the exit code and duration when the tab is outside the foreground. `Notifications.commandFinished` posts at **10 seconds or longer**. Unknown, zero and nonzero codes select finished, succeeded and failed respectively. |
| OSC 52 write | Any tab can write unless `Privacy.locked`. Null clears the primary clip. Text sets an app-labelled plain-text clip. Only copies on API levels below 33 add an app toast. |
| OSC 52 read | The session queues the read. In front, the service offers `onClipboardRead(sessionId)` until a listener accepts. Unclaimed reads wait until the owning tab can claim them or the first read's 60-second deadline answers empty. On selection/resume/focus the activity can retry. It checks both current-session references, resume, focus and privacy before claiming and reading. Claim cancels the timer. Empty text is answered directly, other text gets a prompt. Release clears the queue and timer. |
| tmux control detach | `onTmuxDetached` attempts `showMessage` in that tab (the alternate screen can suppress it). The `[appine]` notice explains that the session continues and `tmux attach` reattaches. No notification fallback. |

### Guest browser endpoint

`BrowserBridge` listens on `127.0.0.1` with an OS-selected port and backlog four. Its configuration is the port and random UUID token on separate LF-terminated lines. A single `appine-browser` daemon handles one client at a time. Requests carry no tab ID. Programs in a multiplexer can use the guest launcher independently of terminal output.

`serve` reads two strict UTF-8 lines sharing one two-second deadline. The credential limit is **64 bytes**, the URL limit **8192**, excluding LF. Only an authenticated, parseable lower-case `http` or `https` URI with a host, no whitespace or ISO controls, and port absent or 0–65535 reaches dispatch. A normal reply is `OK` or a single `ERROR` line. A disconnected or timed-out socket can end without a reply.

`launch` posts a `FutureTask` and waits up to **another two seconds**. The task checks closure. The service requires a live machine, `!destroyed` and a listener accepting `onOpenUrl`. `MainActivity` checks privacy, window focus and browser intent acceptance. The worker removes the callback and uses `cancel(false)` after the wait. Already executing UI work may finish. `close` marks the endpoint closed once and closes the server and active socket, interrupting socket I/O and gating queued dispatch.

### Saving and restoring tabs

`saveTabs` captures live tabs and unshown restored entries on main (`s.tab == 0L` keeps `unshown`, otherwise removes it). The selected index is relative to running tabs, default zero. On the worker, each tab calls `takeTranscript` (falling back to the unshown transcript while unstarted), and a nonnegative console supplies `Native.consoleCwd` and `snapshotOf`. A null `takeSnapshot` reuses `savedSnapshot`, empty clears it, and new bytes go to `Tabs.writeSnapshot`. For `auto`, the first save caches `Rootfs.loginAccount`'s resolved name in `VtSession.resolvedLogin`.

| File in `files/tabs/` | Contract |
| --- | --- |
| `running` | `markRunning` writes this process's PID before restoring tabs or replacing the old index. |
| `t-<hash>.txt` | Last 256 × 1024 Kotlin characters. The hex digest is the first 128 bits of SHA-256 over their UTF-8 bytes. Load follows the indexed name, including legacy `t<i>.txt`. Missing file gives empty text. |
| `s-<hash>.snap` | Same digest convention over raw snapshot bytes, then DEFLATE at `BEST_SPEED`. Read errors and results over **16 MiB inflated** log a warning and return null. |
| `index.json` | `vt` is `VtNative.version().orEmpty()`. `time`, `current` and ordered `tabs` describe the layout. Entries contain login, cwd (empty for absent), transcript filename and optional snapshot filename. A legacy `name` key is ignored. |

Snapshot and layout writers synchronize on `Tabs`, reuse existing blobs, and rename new temporary files into place. `Tabs.save` writes transcripts before the index, compares the exact serialized `"$current:$entries"` to `writtenLayout` and rewrites the index if different or absent. A match leaves the previous save time. Only after the index step does it prune unreferenced transcripts, snapshots and their matching temporary files. A kill before index replacement can leave extra blobs while the old index still names its own files.

Saves run every minute, two seconds after the last tab open/close/shell end, on last-listener removal and on qualifying memory pressure. Restore creates a fresh session for each saved login and cwd. Matching VT version and file existence qualify a snapshot for lazy inflation on first show. Failed reads or decode fall through to a new terminal with the normalized transcript. A yellow notice gives the previous exit reason and save time before new shell output. `focus` uses the saved index, falling back to the last tab. Guest processes from the previous run are lost.

### Memory trimming

When `current` changes, `BackgroundImageTrim` removes the selected ID from both sets and records the previous tab if still open and started. `MAX_RETAINED` (two) caps recently left, started tabs awaiting the 60-second (`DELAY_MS`) trim deadline; overflow evicts the oldest pending ID and queues it for immediate trim. `trimBackgroundImages` drains due IDs and schedules the next deadline. Revisiting a pending tab cancels its deadline. `periodicSave` re-trims already-trimmed, still-unseen IDs before each minute save since background output can add images.

The last listener leaving saves at once and arms a separate 60-second `trimWhenHidden` timer. A returning listener cancels it. `onTrimMemory` at or above `TRIM_MEMORY_RUNNING_LOW`, except `TRIM_MEMORY_UI_HIDDEN`, calls `saveTabs()` before `trimMemory()` so the serial worker re-compresses any cold scrollback pages touched by transcript formatting. With a live kernel the full trim selects started VT handles other than `current`, marks their IDs trimmed and cancels the background deadline. The worker runs `Native.trimMemory`, `VtNative.dropImages` for the selected handles, then `VtNative.compressAll` for cold scrollback. Each image trim temporarily reduces Kitty storage to 4 MiB per screen. Selection and hide timers operate without a system pressure callback.

### Exit, restart, replacement and destruction

`detach` closes the bridge and unregisters network callbacks. It clears `machine`, `gpuAvailable` and `booting` before releasing tabs so the UI sees the departing kernel. It updates the lock, cancels poll and minute save, releases sessions, drops per-machine trackers/watchers and calls `sessionsChanged`, which cancels `saveSoon`. `releaseSessions` releases each VT, cancels its notification and clears `sessions`, `unshown`, `current` and image tracking. The caller receives the old handle.

- `exitAll` marks exiting once, cancels any switch, clears `pendingReplace`, calls `onExitAll`, then clears UI listeners before detach. A start or transition in progress finishes the exit. Otherwise `finishExit` queues graceful `Native.shutdown` for a published kernel. A start result explicitly marked unbooted uses `Native.stop`. Main clears `exiting` and updates the lock. After destruction the completion ends there. Otherwise it clears saved tabs unless a killed layout awaits restore, then calls `startOrImport` for a newly attached listener or `stopSelf` otherwise. If `finishExit` is entered after destruction, it clears `exiting`, releases any lock and uses `appine-stop` for a nonzero handle, honoring its boot flag.
- `restart` needs a published machine. It sets `transitioning`, detaches, calls `onRestarting` and queues shutdown. On return it clears `transitioning`. After destruction it only updates the lock. A live service calls `finishExit(0)` if exiting, otherwise `start(null)`.
- `canSwitch` requires neither a visible switch nor an unfinished downloader, plus a machine or `startError`. `switchTo` sets both download references. `appine-download` calls `Rootfs.download` while the current guest stays available. `cancelSwitch` marks the download cancelled, clears `switching`, updates lock/UI and checks `stopIfFailed`. The `downloader` slot stays occupied until completion. Only a still-current switch may use its result. A download failure other than cancellation leaves a notice.
- A completed download replaces immediately if a machine or `startError` exists. Otherwise, during `starting` or `transitioning`, it sets `pendingReplace`. `replace` clears login, revokes document grants and refreshes roots, sets `transitioning`, detaches and clears `startError`. The worker hard-stops any old kernel, resets the store, revokes grants and refreshes roots again. Main clears `transitioning`. After destruction it only updates the lock. A live service starts the selected distro even if exit arrived during reset, then hands the start result to the pending exit. If deletion leaves the old `meta.db` in place, startup can reopen the previous distro.
- `onDestroy` removes the privacy listener, sets `destroyed`, closes the bridge, cancels any switch and removes trim, poll and save callbacks. It clears `Tabs` unless a killed layout awaits restore, unregisters network monitoring, clears the handle and GPU availability, releases sessions and drops watchers. A remaining handle gets best-effort shutdown on the worker before executor shutdown and wake-lock release. The explicit UI-listener clear belongs to `exitAll`. A private start finishing after destruction uses `appine-stop`.

## Dependencies (inbound / outbound)

### Inbound

- Android lifecycle and network callbacks, notification actions, and the service declaration in `android/build`.
- `MainActivity` (`android/terminal`) and `SettingsFragment` (`android/settings`) bind and listen. The activity connects tabs and preferences to UI controls.
- `VtSession.Events` and `ConsolePty` (`android/vt`) deliver program events and console transitions, and `android/vt` reads `Tabs.MAX_TRANSCRIPT`. `android/setup` uses `BrowserBridge.CONFIG_PATH`, and the guest browser client in `android/native` connects using that launcher configuration.
- `android/documents`, `android/privacy`, `android/terminal`, `android/settings` (`Settings.privacyMode`) and `android/reports` read `Settings`. `Privacy` also writes the mode.

### Outbound

- `android/native` exposes kernel JNI through `Native`. `android/setup` owns store locking, import, boot helpers, distro/cache operations, account resolution and login-rule refresh.
- `android/documents` supplies directory/root notifications, the auto-roots watcher and grant revocation. `android/privacy` supplies lock state and mode-change callbacks.
- `android/vt` owns sessions, PTYs and clipboard queues. `android/vtcore` supplies VT version, snapshots, compression and image trimming. Tab controls, extra keys, file import, view, gestures and rendering belong to their respective UI modules.
- `android/reports` supplies channels, event notifications, legacy report copying and diagnostics. Android supplies exit records, foreground notifications, wake locks, network routes, clipboard and preferences.

## Invariants and gotchas

- Poll ticks can accumulate behind a held BKL and run back to back. Console epochs distinguish reused numbers within a kernel. Handle checks distinguish different kernels.
- A listener added after `exitAll` clears the list represents a returning UI, even if the original activity has yet to reach `onStop`.
- A pending replacement preserves the killed run's layout. Restore can request a login or directory absent from the replacement distro.
- A kill between `markRunning` and the first new index can pair the new PID with the previous layout. An unchanged layout retains the time of its last index write.
- Under SLEEP a ready-store restart after Exit all can leave “Stopping services…” posted until the next notification refresh. Clearing `exiting` and starting without import progress need not change the wake lock or repost id 1.
- Direct `onDestroy` calls `Tabs.clear` before `worker.shutdown()`, so an already-queued save can recreate an unmarked index and blobs; the next `takeKilled` clears them because no `running` marker is written.

## Confirmed bugs / technical debt

- The image-trim paths exclude `current` when selecting VT handles on main. If the worker is backed up and the user returns to one of those tabs after selection, the queued `dropImages` can trim images in the now-visible tab. It does not recheck selection at execution.
- An `auto` tab's first save resolves the account independently of `login.sh` starting its shell. If account selection changes between those events, `resolvedLogin` can persist a different user and restore the tab under that account.
- `saveTabs` captures live sessions and transcripts on main and then queries `Native.consoleCwd` by console number on the worker without checking console epochs, so a reused console number can pair an older tab's transcript with a newer tab's working directory.
- `Rootfs.reset` logs failed deletion without aborting `replace`; if the old `meta.db` survives, `Rootfs.prepare` returns early and startup can reopen the previous distro instead of importing the selected one.

## Open questions

None.

## To verify

- Check id 1 on a device for a long startup error in collapsed and expanded layouts, privacy redaction during a download, and a locale change while the service is running.
- Check BUSY lock release and reacquisition across physical device suspend, and foreground bell/program haptics with system touch feedback enabled and disabled.
