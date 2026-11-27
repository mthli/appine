# Terminal service (android/service) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: android/service"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12). D1–D6 were seeded by hand from the retired design
> doc §11.2 and the round records in `docs/rounds/`, so their sources cite rounds; later entries
> cite commits.

## Constraint index (compressed active decisions)

> Full text of D13: `git show ec9ad94b862479935821fbfad2affa7bbdba355e:.agents/decisions/android/service.md`

- **D13** — Preference writes use the androidx.core edit extension with asynchronous apply, as required by the lint build. (4c1a3ce)

> Full text of D10, D15, D16, D22, D30: `git show c4db5db249c93e2e122d88ef60153f67c94a1f62:.agents/decisions/android/service.md`

- **D10** — Every build honors the saved execution tier (default Tier 1), `setTier` persists changes and restarts the kernel, bug reports keep Tier numbers, and a Tier 0 preference saved by an earlier debug build applies in release builds (62a8c07; cf4f5e20; 20137e4c; d053be24; 39e55b4b)
- **D15** — Copied bug reports open with `Appine <version> (<code>, <build type>)`, while the `[appine]` in-tab tag before restore, kill and tmux notices stays lowercase and must change with `android/setup` D16 if ever capitalized (3f04bf5; 77d7f36)
- **D16** — Without a ready store, `startOrImport` (shared by `onCreate` and post-Exit-all restart) resumes `Rootfs.importing(this)` with the existing Alpine fallback on download/import failure, needing network again if a resumed Debian tarball left cache (237d663; 6560af6)
- **D22** — `cursor_style` and `cursor_blink` live in `settings` preferences; `setCursorStyle` and `setCursorBlink` update every live session under its lock on the main thread and notify listeners, while unstarted sessions store values until started (77d7f36)
- **D30** — `show_tab_list` defaults to true in `Settings` and notifies activity listeners through service setters, so direct `Settings` writes bypass listener notification (5c49c5a7; 39e55b4b)

> Full text of D1, D2, D6, D11, D12, D14, D18, D24, D29, D31: `git show 1ea0c1ef9d686d7126bec2029fc928d4f6fdfc6b:.agents/decisions/android/service.md`

- **D1** — `TerminalService` runs as a `specialUse` foreground service owning the kernel handle and every session across Activity recreation, and watches UI visibility to trim memory (D4) because Android 14+ omits `onTrimMemory` (rounds 07 (`07-m5`), 187 (`187-app-init`))
- **D2** — Closing the last tab only finishes the Activity while the 2 s poll stops the service when no sessions, UI or guest processes remain, running inittab shutdown on `exitAll`/poweroff/halt and clearing listeners immediately in `exitAll` so a fast shutdown cannot restart the kernel (round 187 (`187-app-init`); design doc §11.2)
- **D6** — Kill reports go to their controlling console tab as yellow lines (or to a notification when away, full-screen or tabless), while Appine bugs and failed OpenRC boot services always notify with copyable reports subject to D28 privacy checks; the unimplemented-paths field remains untested on devices (rounds 195 (`195-kill-reports`), 205 (`205-copy-bug-report`), 207 (`207-report-kernel-log`); 99b4216; f61072f3; d053be24; 39e55b4b)
- **D11** — `Settings` keeps `fill_wide` default-off and `volume_keys_switch_tabs` default-on (preserving saved true values) with service setters notifying listeners; `MainActivity` rereads `fill_wide` on start and volume behavior on each press, and writes bypassing the service skip listeners (0478dd5; 0553229 (read through `Settings`); 61df6915; 75123eef; 20137e4c; d053be24; 39e55b4b)
- **D12** — `TerminalService` orders types/state, creation/start, commands/binding, sessions/saving, reports/routes, memory/settings, replacement and shutdown while keeping Kotlin initializer order safe and running `started`/`startFailed` only after start's main-thread post clears `starting` and checks `exiting` (a546fea; 4de8aacd; e35f9f7c; 20137e4c; d053be24; 39e55b4b)
- **D14** — `markRunning` writes the pid so restored tabs explain kills via `getHistoricalProcessExitReasons` (seven reasons) and crashes/ANRs post notification 3 with a copyable report (retained evidence lives in D29); low-memory kills require `isLowMemoryKillReportSupported`, and low-memory/background/ANR paths and Chinese strings remain untested (a1f47cc; c7309c0c; d053be24; 39e55b4b)
- **D18** — The 2 s poll calls lock-free `Native.bklStall` directly on the main thread (never taking the BKL) to post notification 4 ("is stuck" then silently "was stuck" with total and kernel log if undismissed, with live seconds updates left open) (64a6107 (with kernel/process D38))
- **D24** — Shared builders create non-ongoing notifications, ICU formats failed-service lists, and changed locales rename channels and repost the ongoing notification while bug reports stay English and posted notifications keep their language; the locale-change path remains untested on devices (aed99afb; 20137e4c; d053be24; 39e55b4b)
- **D29** — `Diagnostics` retains own-PID logs and fault reports for the current and previous runs within 2 MiB and exports metadata, exit records and bounded traces independently of the kernel worker, isolating pending saves from the three-archive share cache; sudden death can lose final logcat lines and retained messages can contain names and paths (c7309c0c; d053be24; 39e55b4b)
- **D31** — The ongoing notification shows the current state title and running-policy body, except non-private startup failures show "Cannot start" with the error in both body layouts (privacy mode always shows privacy title and policy); exit/progress precedence and vendor layouts remain unverified on devices (c7e338a5, c97b00bf)

## Active (full detail)

### D3: Screen-off behavior is SLEEP, BUSY or ALWAYS independently of battery exemption
- **What**: Persist SLEEP, BUSY or ALWAYS with legacy false/true mapping and a remembered non-ALWAYS notification toggle; BUSY compares guest CPU every 2 s, releases after 30 s idle, protects transitions/downloads and renews grace for unavailable or delayed samples.
- **Why**: Idle terminals should save power while users can explicitly keep workloads running with a partial wake lock, independently of the optional battery exemption offered by android/terminal D69.
- **Tradeoffs**: Network waits and scheduled work may need ALWAYS, polling cannot wake a suspended device, and completed polls determine lock transitions.
- **Watch out**: Reject pre-reset samples and treat CPU changes rather than monotonic growth as activity; real-device suspend and power savings remain unverified, and repeated sampling delays prolong wakefulness.
- **Source**: round 191 (`191-keep-awake`); a3f7507e; 1e5de64e; 20137e4c; d053be24; 39e55b4b; 7a931d97

### D4: Hidden and pressured sessions trim memory and background images
- **What**: After 60 seconds without the UI, trim kernel memory and compress scrollback on the worker, and trim noncurrent Kitty images on pressure or after the background-tab grace period.
- **Why**: A grace period avoids unnecessary retranslations and image loss during a brief switch while still reclaiming memory on Android versions that omit pressure callbacks.
- **Tradeoffs**: The two most recently left tabs keep their images for up to 60 seconds and older tabs can be reduced immediately to 4 MiB per screen.
- **Watch out**: Clear current when a tab closes and keep periodic re-trims cheap when storage is already below the limit.
- **Source**: round 37 (`37-trim-memory`); 3f45ad0, c1a37da, 19e8be6f

### D5: After a kill, tabs come back on their saved screens without their processes
- **What**: A `running` marker detects an abnormal end; each running tab's login (an "auto" tab as the account `Rootfs.loginAccount` resolves at its first save, kept in `VtSession.resolvedLogin`), foreground cwd, plain-text scrollback (last 256 K, in transcripts named by their content) and, when its terminal changed, a deflated libghostty-vt snapshot (`s-*.snap`, named by content, android/terminal D44) are saved on the worker every minute, when the UI hides, on memory pressure and after tabs open, close or end, with `VtNative.version` in the index; on restart each tab gets a new shell in its directory below its restored screen (the snapshot, read at the tab's first show, else the transcript) and a notice, snapshots from another version are dropped, and saved tabs are kept until a killed run's tabs have been restored; a `name` key in an index from an earlier version is ignored. Tab layout saves compare the complete serialized layout, eliminating hash collisions.
- **Why**: v1 restores the layout, directories and screens but not programs, and says so; plain transcripts lost colours, images and layout; kills mostly happen in the background, hence the save on hide; unchanged files are not rewritten so the minute timer does not wear the flash; an "auto" tab resolved again at restore could become another user after accounts changed.
- **Tradeoffs**: Programs are not restored; each save may write both a transcript and a snapshot; a libghostty-vt upgrade sends tabs back to their transcripts once; tabs never shown since the restore keep their old files; names saved by earlier versions are lost at the next restore; an account added between a tab's opening and its first save (up to a minute) can still be the one recorded.
- **Watch out**: Old indexes load only because fields are looked up by name and extra keys are skipped, and no test reads such an index; the first save of each "auto" tab reads `/etc/passwd` through the store; an index naming a snapshot deleted by other means falls back to the transcript silently (logged); the Tabs lock protects the last serialized layout.
- **Source**: round 194 (`194-restore-tabs`); design doc §11.2; 2af55e2, 89ad551; 7c35183; 3beb038; 600700b; 807e9ba1; 20137e4c; d053be24; 39e55b4b, 6f2776f3, 6560af61

### D7: The service is a module of its own, split from the app by responsibility
- **What**: android/service holds `TerminalService.kt`, `BrowserBridge.kt`, `Settings.kt` and `Tabs.kt` (keeping ongoing foreground notification id 1 and routing decisions), while `Notifications.kt` and `Diagnostics.kt` belong to `android/reports` and the remaining app areas stay in their responsibility-based modules.
- **Why**: `android/service.md` reached 41,395 B (89.8% of one read) while mixing foreground-service lifecycle with notification rendering and independent diagnostic retention.
- **Tradeoffs**: Notification-policy changes touching both routing in `TerminalService` and formatting in `Notifications` span two maps, pre-split report/diagnostic decisions remain here, and sibling responsibilities stay in `kernel/boot`, `android/vt`, `android/vtcore`, `android/vtview`, `android/vtrender` and `android/tabbar`.
- **Watch out**: Ongoing notification id 1 in `TerminalService` must stay disjoint from `android/reports`'s notification ID ranges, and a shell's kill report can still reach a newly opened tab that reused the freed console number before the next poll.
- **Source**: 12c4afa (bug list since updated by 89ad551, 3beb038, d9e1f0c, 6560af6); 0553229 (files, D23); 8e211142; 9015b244; c7309c0c, 8a17e4a9, 81f60bef; d053be24; 60790c14; 39e55b4b; e4d7bbe5

### D8: The service holds `VtSession`s and releases them before the kernel stops
- **What**: `sessions`, `unshown`, `focus` and `current` are `VtSession`s; saving reads each session's console and login on the worker (no separate pty map); `removeSession`, `detach` and `onDestroy` release sessions (hang up and free the emulator) before the kernel is shut down or stopped; title changes and ends arrive through `VtSession.Events` as `onSessionsChanged`; `releaseSessions` centralizes detach/onDestroy release, notification removal and clearing `sessions` and `unshown`. ConsoleTracker drains kill reports on close and matches reused console numbers to session ownership epochs under a tracker-before-BKL lock order.
- **Why**: The service held Termux `TerminalSession`s directly with a separate session → `ConsolePty` map, so the libghostty-vt terminal could not be one of its tabs (android/terminal D8).
- **Tradeoffs**: Listeners get no per-text-change callback; tabs redraw their view directly, and closing a console drains pending kills.
- **Watch out**: `ConsolePty.console` keeps its number after close, so routing must check the ownership epoch; ended tabs keeping the service alive, saved tabs lost or paired with other tabs' transcripts and colliding kill notification ids were fixed in 89ad551 (D9).
- **Source**: 6ffb912; 89ad551; 6560af6 (reports to ended tabs, recorded in d9e1f0c); e35f9f7c, 6f2776f3, 807e9ba1

### D9: Exit all, restart and replace finish around an ongoing start instead of racing it
- **What**: While `starting` or `transitioning` is set, Exit all only marks and detaches and the start, restart or replace finishes the exit; the wake lock is held throughout, a download finished during a restart waits in `pendingReplace` until the new kernel is up or has failed, a failed start says so in the notification and stops the service once no UI is left and no switch is downloading (checked again when a download fails or is cancelled), `onDestroy` cancels a switch and sets `destroyed`, after which `updateNotification` posts nothing, and kill notifications take ids counting up from 100000.
- **Why**: Nothing tied the start thread, restart and replace to Exit all, the wake lock or a finished download, so an exit during a start left a kernel running after `onDestroy`, reboots dropped the wake lock and downloads finishing mid-reboot were lost.
- **Tradeoffs**: An exit during a distro replacement still imports the chosen distro first (and waits for a long import), a kill shown in a background tab now also notifies, and after a failed start the service and its notification stay without a UI while a switch downloads.
- **Watch out**: Every new path that ends `starting` or `transitioning` must check `exiting` (a map invariant), every new way of posting notification 1 must go through `updateNotification`, and kill notification ids from a new service instance can still collide with old ones after wrapping.
- **Source**: 89ad551; 6560af6 (a switch's completion crashing a destroyed service, recorded in d9e1f0c)

### D17: The containers configuration is written before every start
- **What**: Startup invokes `Rootfs.ensureContainersConfig` after `ensureNetworkConfig` (android/setup D18) via `Rootfs.prepareStart`, so stores imported by earlier versions receive it too.
- **Why**: The containers configuration must exist before the guest starts, like the network configuration.
- **Tradeoffs**: Adds a store check, and a write when the file is missing, on every start.
- **Watch out**: Store write errors abort fresh imports but degrade quietly on existing stores under D37.
- **Source**: 0eb9e19, 12ac97cf

### D19: Bells and OSC 9/777 notifications reach the user per tab; clipboard writes go through the service
- **What**: A bell from the tab in front (`current` with the terminal Activity listening) goes to the listeners' `onBell`; otherwise bells, at most one per second per tab, and OSC 9/777 notifications become one notification per tab on a new `terminal` channel (ids from 200000 plus the tab's id), titled by OSC 777's title or the program's title with "Tab N" as subtext, opening that tab and removed when it is shown or closed; OSC 9/777 from the tab in front invokes onBell with its 100 ms throttle and touch-feedback setting, while new terminal channels enable vibration and both terminal and problems channels request high importance under their existing IDs; clipboard writes from any tab that passes the privacy gate set or clear the clipboard, with a toast only before Android 13, and clipboard reads from the tab in front go to the listeners' `onClipboardRead` while unclaimed reads wait for the owning tab until expiry.
- **Why**: Bells and notifications matter most when the user is not looking at the tab, which only the service knows, and the clipboard needs a context that outlives the Activity.
- **Tradeoffs**: Foreground feedback uses the bell REJECT pattern and existing channels retain their settings; the system may bundle tab notifications with the ongoing one, so the first tap expands the bundle; on Android 13 and later, background tabs allowed by the privacy gate can overwrite the clipboard with only the system's own confirmation, and a read from a background tab can remain pending.
- **Watch out**: Existing channels keep user settings and heads-up alerts depend on Do Not Disturb and vendor behavior. Physical feedback depends on device settings and remains unverified, a bell flood still posts to the main thread, and per-tab notification limits allow several tabs to notify independently.
- **Source**: d89b5fa (with android/terminal D36); 600700b (clipboard reads); f61072f3; d053be24; 2ff6f22c; 39e55b4b; a13a8b98, f3d226d5

### D21: Program statuses and finished commands notify through the tab's notification
- **What**: `notifyStatus` posts the tab's notification (D19's) once per status for blocked, done and error, titled by the record's or the tab's title with the status in words and its message, and withdraws it when the status goes without the user unless a bell or OSC 9/777 replaced it (`statusNotified`); `onCommandFinished` posts it for a command of at least 10 s in a tab not in front ("Command succeeded", "Command failed" or "Command finished", the time taken in ICU's short units and a failure's exit code), replacing a status notification; in the tab in front done and error count as seen at once, and listeners hear `onTabStatus`.
- **Why**: A phone shows one tab at a time, so a program waiting for the user, finishing or failing, or a long command ending in another tab or with the app away, goes unnoticed, and only the service knows which tab the user sees.
- **Tradeoffs**: Statuses, finished commands, bells and OSC 9/777 share one notification per tab and replace each other; no setting turns command notices off, and their text cannot name the command; the record's app name is never shown.
- **Watch out**: CLIs that send both OSC 9 and 7501 for one event alert twice, the withdrawal relies on every other path that posts the tab's notification clearing `statusNotified` first, and command notices depend on each shell’s prompt integration (android/setup D20 and android/vt D5–D7).
- **Source**: 22c8ab0, 5256ae2; a7d8c9f5; a809c5a8; 7aa817af (prompt integration)

### D23: Notifications and settings live in classes of their own; the service keeps the decisions
- **What**: `Notifications(service)` owns the channels, every notification id but the ongoing one (1), the stall, crash, boot-failure, listen and kill notifications with the bug report and its "copy report" action (the service hands it the intent), and each tab's notification with the bell throttle, status de-duplication and stall state, while the service still decides what is told (the tab in front, a kill shown in its tab); `Settings` is an object over the `settings` preferences with getters, plain setters and `clearLogin`, whose values the service's getters delegate to and its setters write before acting, with file_access replacing the old system_documents contract (D27); saving tabs and switching distros stay in the service, coupled to its start and worker.
- **Why**: TerminalService mixed the kernel's lifecycle, sessions and saving with notification building (channels, ids, "copy report", per-tab de-duplication) and the preferences' keys and defaults, and exported static settings only because the preferences lived there.
- **Tradeoffs**: A notification change can need both files (the decision in the service, the text in Notifications), and settings have two entry points: `Settings` for reading anywhere and the service for changes with effects.
- **Watch out**: Notification ids are split between the service (the ongoing 1) and Notifications' companion, so a new id must be checked against both, and writing a setting through `Settings` instead of the service skips its effects (wake lock, Files roots, cursor to every tab, restart).
- **Source**: 0553229; d0ddf170; d053be24; 39e55b4b

### D25: Mirror selection finishes before the new rootfs is published

- **What**: Pass the probe factory into rootfs preparation and use its completed measurements during import.
- **Why**: A worker modifying a live store raced replacement.
- **Tradeoffs**: Late results cannot improve the selected mirror.
- **Watch out**: Probe workers must remain independent of the published store.
- **Source**: a1367531; 20137e4c; d053be24; 39e55b4b, 568b6a48

### D26: A kernel owns an authenticated loopback browser endpoint

- **What**: Each kernel gets a bounded authenticated loopback endpoint that dispatches browser requests to the focused UI and closes on failure, detach or service destruction.
- **Why**: Multiplexer clients need browser access independent of terminal escape forwarding or tab identity.
- **Tradeoffs**: Every guest process able to read the configuration shares the capability, and requests carry no originating tab.
- **Watch out**: Isolated guest network namespaces cannot reach the endpoint, and a slow client can delay its serial worker.
- **Source**: 8e211142; d053be24; 39e55b4b

### D27: External file access requires a fresh default-off opt-in

- **What**: Settings.fileAccess defaults false, ignores system_documents and is forced false during privacy mode; service changes save the gate, revoke provider grants when disabled and notify roots and listeners. Replacement revokes provider access and refreshes roots both before reset and after reset completes.
- **Why**: The former visibility preference cannot authorize the broader exposure of both document roots.
- **Tradeoffs**: Users must opt in again, and enabling privacy clears the saved choice without restoring grants afterward.
- **Watch out**: Writes bypassing the service miss revocation or notifications, and already-open descriptors and copied data remain outside revocation.
- **Source**: d0ddf170, f61072f3 (android/documents); d053be24; 39e55b4b, 6e9bd7a7

### D28: Privacy redacts notifications and authenticates report copying

- **What**: Privacy mode shows the privacy title and selected running policy on the ongoing notification, hides session, event and startup-error details, clears old detail notifications and guards guest clipboard access, while ordinary reports use a non-exported receiver and private reports a non-exported authentication activity with a delivery-time lock check.
- **Why**: Background workloads should continue without exposing private content through notifications or clipboard callbacks.
- **Tradeoffs**: Exit, cancellation and running-policy controls remain available without authentication, old detail notifications are discarded, and copying a report need not start a kernel.
- **Watch out**: Keep legacy delivery checks aligned; receiver clipboard delivery and report-specific authentication still need device-variant coverage.
- **Source**: f61072f3; d053be24; 39e55b4b; c7e338a5, c97b00bf

### D32: Vulkan GPU mode is persisted default-off and controls per-start launcher setup and diagnostics

- **What**: `Settings` stores `gpu` (default false), `TerminalService` exposes `gpu`, `gpuAvailable` and `setGpu`, calls `Rootfs.ensureGpuCommand` only when `gpu` is true, clears `gpuAvailable` in `detach` and `onDestroy`, and appends `, Vulkan GPU` to `Diagnostics.environment` (reused by `Notifications.bugReport`).
- **Why**: The service must avoid installing the GPU launcher or probing `:venus` unless opted in, track live renderer availability per kernel and report GPU mode in diagnostics.
- **Tradeoffs**: Disabling the toggle leaves any previously installed `/usr/local/bin/appine-gpu` in the rootfs, a running kernel requires restart to change GPU mode, and future launcher revisions need an upgrade path.
- **Watch out**: Direct `Settings` writes bypassing `TerminalService` will not trigger a restart until the next kernel launch.
- **Source**: 9cafc90b, b605a0a3

### D33: First-boot OpenRC setup publishes the kernel atomically while upgrade failures open a repair terminal

- **What**: On first-boot OpenRC setup, `TerminalService` transfers the kernel handle, browser endpoint and boot state together after setup while keeping ordinary boots asynchronous, and on existing-store upgrade failures surfaces the error while carrying boot state forward so `inittab` runs once and a repair terminal opens (keeping import-time errors fatal).
- **Why**: Publishing the kernel handle early let tabs race OpenRC setup, while aborting startup on an existing-store migration error locked users out of repairing their system.
- **Tradeoffs**: The first tab waits for `inittab` during initial setup, and services may be unavailable in a repair session.
- **Watch out**: Exits during startup must release the browser endpoint and shut down the right kernel, and a persistent migration error recurs at startup until repaired.
- **Source**: d0ff9a31, 7e85e8dc

### D34: Startup invokes one-time Alpine bash startup seeding before `Native.start`

- **What**: `TerminalService.startKernel` runs `Rootfs.ensureBashStartup` via `Rootfs.prepareStart` alongside the other per-start rootfs helpers before starting the kernel, relying on its per-store marker for fast no-ops on subsequent starts and Debian.
- **Why**: Existing Alpine stores imported before bash startup seeding need one-time `.bash_profile`/`.bashrc` creation without resetting the rootfs.
- **Tradeoffs**: Adds one store marker check on Alpine startup, failing fresh imports on seeding I/O errors while degrading quietly on existing stores under D37.
- **Watch out**: Obstructed paths on an existing store keep retrying and logging on each start until repaired, while fresh-import seeding failures still abort startup.
- **Source**: 9f3e93ed, 12ac97cf

### D20: Each machine polls its own login rules and document roots

- **What**: Capture rule state per machine, queue rule refresh on the service worker, wait for process exit separately, and poll the auto-root watcher with changed directories.
- **Why**: Directory notifications alone miss in-place account edits and obsolete machines can outlive queued work.
- **Tradeoffs**: The worker and exit waiter have separate lifetimes.
- **Watch out**: Detach and destroy must clear the watcher.
- **Source**: d03c9b6, d5b808e8, ae1e05e0

### D35: Clipboard reads wait for the owning session to reach the foreground

- **What**: Offer a foreground session's read to listeners until one claims it and let the session retain unclaimed reads until expiry.
- **Why**: A background tab can ask before the user returns.
- **Tradeoffs**: The guest may wait for an answer.
- **Watch out**: Removing a session must release its timer and requests.
- **Source**: d10f2973, f3d226d5, ab417943

### D36: Abandon only the failed distro's tarball during fallback

- **What**: Delete the abandoned distro cache when initial store preparation fails and the service falls back to Alpine.
- **Why**: A failed download can occupy storage after fallback.
- **Tradeoffs**: A retry of the abandoned distro may download again.
- **Watch out**: Normal termination and retry paths must preserve their tarballs.
- **Source**: 9b179de1

### D37: Existing-store per-start configuration failures degrade quietly while fresh imports fail fast

- **What**: `TerminalService.startKernel` uses `prepareStartBridge` (with an immutable `val bridge` and nullable `StartedKernel.browser`) to run `Rootfs.prepareStart`, aborting and closing `BrowserBridge` on fresh-import I/O errors while quietly logging and recording (`Log.w` and `Diagnostics.record`) skipped steps on existing stores and closing `BrowserBridge` if opening it or writing `/etc/appine/browser` fails before calling `Native.start`.
- **Why**: Existing stores need the same repair-terminal guarantee as OpenRC setup without leaking an unconfigured `BrowserBridge` or nagging users who intentionally customized guest paths.
- **Tradeoffs**: Users whose `/etc/appine/browser` write failed see browser-launch errors in guest tools rather than a startup dialog and must inspect diagnostic logs.
- **Watch out**: Non-`IOException` errors still close `BrowserBridge` and abort startup, and `Native.start` remains the fatal integrity check for a corrupt or unopenable store.
- **Source**: 12ac97cf, f7d164db

### D38: `ServiceLifecycle` centralizes main-thread lifecycle flags and post-destroy guards

- **What**: `internal class ServiceLifecycle` (with top-level `StartedKernel` accepting `Closeable?`) owns lifecycle flags, `wantsWakeLock` (always `false` after `destroyed`), and `completeStart`/`completeTransition`/`finishExit`/`completeExit`, guarding `start`, `replace`, `restart`, `exitAll`, and late import progress against `destroyed` with JUnit 4 regression tests.
- **Why**: `replace()` and `restart()` main-thread continuations lacked `destroyed` guards, risking wake-lock leaks via `start()` or uncaught `RejectedExecutionException` on the shut-down worker after `onDestroy()`.
- **Tradeoffs**: Adds a small helper class and callback indirection in `TerminalService.kt` compared to inline early returns.
- **Watch out**: Future lifecycle callbacks added to `TerminalService` must route through `ServiceLifecycle` or check `lifecycle.destroyed` to avoid reintroducing post-destroy wake-lock or executor bugs.
- **Source**: 0160070e

### D39: `startKernel` skips `ensureOpenRc` when `prepareStart` confirms OpenRC is already done

- **What**: `TerminalService.startKernel` captures `StartPreparation.openRcDone` from `Rootfs.prepareStart` and skips calling `Rootfs.ensureOpenRc` when true while preserving `browserReady` handling and falling back to `ensureOpenRc` whenever the marker is absent or obstructed.
- **Why**: Calling `Rootfs.ensureOpenRc` right after `Native.start` opened a second concurrent store connection on every normal Alpine cold start solely to check `/var/lib/appine/openrc-setup-done`.
- **Tradeoffs**: Couples `startKernel`'s OpenRC fast-skip to `prepareStart`'s precheck immediately before `Native.start`.
- **Watch out**: Relies on nothing deleting `/var/lib/appine/openrc-setup-done` between `prepareStart` and `Native.start`.
- **Source**: 641769ab

### D40: `saveTabs` captures transcripts on the worker and runs before `trimMemory` on memory pressure

- **What**: `saveTabs()` captures only the live session list and `unshown` restored records (`s.tab == 0L`) on the main thread, calls `session.takeTranscript()` alongside `consoleCwd` and `snapshotOf` on `worker` in one pass, and `onTrimMemory()` enqueues `saveTabs()` before `trimMemory()`.
- **Why**: Calling `VtSession.transcriptText` on the main thread synchronously decompressed cold scrollback pages under `Tab.term`, and running `trimMemory()` before `saveTabs()` re-inflated pages just queued for `compressAll()`.
- **Tradeoffs**: A process kill while the worker queue is backed up leaves an in-flight transcript save unwritten, matching `cwd`, snapshots, and `Tabs.save`.
- **Watch out**: Serialization latency on the service worker remains shared across save and trim operations.
- **Source**: 2f321b12

## Superseded

- ~~A `Session` interface in the service, with `createSession` building a `TermuxSession` or a `VtSession` by `VtNative.available` (6ffb912)~~ → narrowed to `VtSession` only by android/terminal **D8** in b38d29c (2026-11-04)
- ~~A tab's name among D5's saved fields, saved again after each rename (2af55e2, 89ad551)~~ → dropped from **D5** in 7c35183 (2026-11-05), with renaming gone from the terminal
- ~~The trim releases only the kernel's caches (D4, round 37)~~ → replaced by **D4** in 3f45ad0 (2026-11-10)
- ~~Restored tabs show their old scrollback as plain text, losing colours (D5's tradeoff, round 194)~~ → replaced by **D5** in 600700b (2026-11-10, no SUPERSEDES declared)
- ~~Settings read across modules through the static `TerminalService.fillWide(context)`, `login()` and `showSystemDocuments()` (D11, 0478dd5)~~ → replaced by **D23** in 0553229 (2026-11-12)
- ~~D3’s boolean keep-awake preference and notification toggle~~ → replaced by **D3** in 1e5de64e (2026-11-13)
- ~~D10’s release-only Tier 1 enforcement~~ → replaced by **D10** in cf4f5e20 (2026-11-13)
- ~~D11’s default-on fill_wide preference~~ → replaced by **D11** in 75123eef (2026-11-13)
- ~~D12’s former ordering of settings followed directly by exit and stop paths~~ → replaced by **D12** in 4de8aacd (2026-11-13)
- ~~D23 system_documents getter/setter contract from 0553229~~ → replaced by **D23 and D27** in d0ddf170 (2026-11-14)
- ~~D19 dropping foreground OSC 9/777 requests~~ → replaced by **D19** in 2ff6f22c (2026-11-15; background routing remains unchanged)
- ~~No exemption request anywhere in the app under D3~~ → narrowed by **android/terminal D69** in 7a931d97 (2026-11-16, implicit cross-module change).
- ~~The generic privacy body under D28~~ → replaced by **D28 and D31** in c7e338a5 (2026-11-17).
- ~~Always showing the policy body for a non-private startup failure in c7e338a5~~ → replaced by **D31** in c97b00bf (2026-11-17, recovered headingless block).
- ~~Unconditionally calling `Rootfs.ensureGpuCommand` during every per-start guest setup in 9cafc90b~~ → narrowed by **D32** in b605a0a3 (2026-11-21, implicit refinement).
- ~~D7 combined ownership of TerminalService, Notifications.kt and Diagnostics.kt (12c4afa, 0553229, c7309c0c)~~ → replaced by **D7** and android/reports **D1** in e4d7bbe5 (2026-11-22).
- ~~Matching saved layouts by 32-bit hash and assigning a reused console number to the open or most recently ended tab~~ → replaced by **D5** and **D8** in 6f2776f3 (2026-11-23)
- ~~Using one directory-change list as the sole input to login-rule and document refresh~~ → replaced by **D20** in d5b808e8 (2026-11-24)
- ~~Starting a post-import mirror thread and joining it on replacement~~ → replaced by **D25** in 568b6a48 (2026-11-24)
- ~~Reducing background Kitty image storage only under memory pressure~~ → replaced by **D4** in 19e8be6f (2026-11-25)
- ~~Fail startup on per-start container or bash-startup store write errors (D17 in 0eb9e19, D34 in 9f3e93ed)~~ → replaced by **D37** in 12ac97cf (2026-11-26)
