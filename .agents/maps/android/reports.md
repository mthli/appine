# android/reports Map

> Static understanding snapshot, not a decision history.
> See `.agents/decisions/android/reports.md` for the paired decision history (not yet created; decisions from before the split are D6, D24, D29, and the notification, report and diagnostic parts of D10, D14, D15, D18, D19, D21, D23, D28 and D32 in `.agents/decisions/android/service.md`).
>
> Verified: 2026-11-21 (split out of the android/service full mapping of 2026-11-15; the boundary was checked against the source at the split)
>
> Maintained: 2026-11-28 (targeted verification only since the full mapping; latest change: high importance for new network channels)

## Responsibilities

`android/reports` owns Appine's non-ongoing notifications, fault reports and diagnostic retention:

- notification channels (`sessions`, `network`, `problems`, `terminal`), locale-aware channel
  renaming, and building, posting and cancelling non-ongoing notifications (kill reports, Appine
  bugs, crashes/ANRs of the previous run, BKL stalls, boot service failures, listening ports and
  per-tab bells, program notifications, statuses and finished commands);
- building copyable English bug/crash/stall reports and handling the "Copy report" action via the
  non-exported `Notifications.ReportReceiver` (with `Privacy.locked` checks and handoff to
  `android/privacy`'s `NotificationActivity` under privacy mode);
- process-local diagnostic retention (`Diagnostics`, `DiagnosticFiles`) independent of
  `TerminalService`'s lifetime, bounded logcat and fault-report capture, serialized ZIP archive
  export for Share (`DiagnosticProvider`) and Save.

Root: `android/app/src/main/java/io/github/mthli/appine/Notifications.kt`, `Diagnostics.kt`.

Outside the module: the foreground service, the ongoing notification (id 1), deciding which tab is
in front and what events get notified versus shown as in-tab messages (`android/service`); the
authenticated copy-report activity and privacy lock state (`android/privacy`); the Settings export
UI and SAF save picker (`android/settings`); installing `Diagnostics` at process start
(`android/native`); manifest declarations for `ReportReceiver` and `DiagnosticProvider`
(`android/build`); kernel kill reports and BKL stalls (`kernel/bug`, `kernel/oom`,
`kernel/signal`).

## Key types

- `Notifications` — every notification except the ongoing one (id 1, built by `TerminalService`):
  channels, the stuck kernel, the last run's crash, boot failures, listens, kill reports and their
  "copy report" (report text, `ReportReceiver` and authenticated action), and each tab's
  notification; with state `stallNotified` (the stall last notified), `killNotifications` (next
  kill notification offset, seeded from the clock), `bellNotified` (when each tab's bell last
  notified), `statusNotified` (the program status a tab's notification shows, while it shows one),
  and `channelLocales` (the languages the channels were last named in). `TerminalService` decides
  what is told (the tab in front, a message shown in the tab); `Notifications` builds, posts and
  cancels.
- `Notifications.ReportReceiver` — non-exported broadcast receiver copying ordinary fault reports
  directly to the clipboard without starting `TerminalService` or the kernel.
- `Diagnostics` — process-local log/report retention and serialized archive export, installed from
  `MainApplication` without starting the service or loading `Native`; builds environment metadata
  reused by `Notifications.bugReport`.
- `DiagnosticFiles` — synchronized two-part rotation and snapshotting under
  `noBackupFilesDir/diagnostics` (two 256 KiB parts per stream for current and previous process,
  2 MiB total).
- `DiagnosticProvider` — non-exported `FileProvider` subclass exposing only generated Share
  archives under `cache/diagnostic-exports`.
- Constants: bell notifications at most once a second per tab; kill notification ids from 100000
  (wrapping after 100000); tab notification ids `200000 + tab id mod 100000`; listen keys
  `(tag = "listen:<guest>:<host>", id = 1000 + guest port)`; fixed ids 2 (boot failures), 3
  (last-run crash/ANR), 4 (BKL stall).

## Public entry points

- `Notifications` (called by `TerminalService`): channel creation and locale refresh, `stall`,
  `crash`, `bootFailures`, `listen`, `kill`, `bell`, `program`, `status`, `clearStatus`,
  `commandFinished`, `cancelTab`, `clearNonOngoing` (privacy mode); static
  `Notifications.copyReport` (called by `ReportReceiver`, `NotificationActivity` and legacy
  `TerminalService` intents).
- `Diagnostics`: `install` (from `MainApplication`), `report`/`recordFault` hooks (from
  `Notifications` and `TerminalService`), `environment`, `export(forSave)` (from
  `SettingsFragment`).
- Manifest entry points: `Notifications.ReportReceiver` and `DiagnosticProvider`.

## Data flow / lifecycle

### Diagnostic retention and export

- `Diagnostics` starts from `MainApplication`, without starting the service or loading `Native`.
  `DiagnosticFiles` retains logcat and report streams in `noBackupFilesDir/diagnostics`: two
  256 KiB parts per stream for the current process and one previous process (2 MiB total).
  A shared instance lock protects rotation, appends and snapshots; older bytes are discarded.
  A daemon reader captures only the current PID's main/crash logcat, warnings and errors plus
  INFO for `appine`, `Rootfs` and `TerminalService`. Capture failure is reported in exports.
  Native stderr reaches this stream through the existing JNI redirect (`android/native`); release
  builds still leave detailed kernel debug logging off. Sudden process death can lose the final
  logcat lines.
- Fault reports are saved when built, even if notifications are later dismissed; a stall's ending
  is saved even after its notification was dismissed. Boot failures and start exceptions are saved
  too. The uncaught-exception hook records Java stacks before chaining Android's previous handler.
  Persistence is best effort and never changes normal crash handling.
- `Diagnostics.export` runs separately from the service worker and calls no native kernel API.
  Its ZIP contains environment metadata (including `, Vulkan GPU` when enabled), retained
  logs/reports, up to five Android exit records, and optionally the latest ANR/native-exit trace
  if available and at most 1 MiB (API 31+ native protobuf, otherwise ANR text). Unavailable or
  oversized traces are described in the archive. It does not read terminal transcripts, command
  history, environment variables or guest files; diagnostic messages themselves can contain names,
  paths and error details.
- Generated Share ZIPs live in `cache/diagnostic-exports`; serialized exports retain at most three
  archives, failing if older files cannot be removed. `DiagnosticProvider` is non-exported and
  exposes only that directory through `FileProvider`. `android/settings` grants temporary
  read-only access to one archive after its final privacy check. An intentional share remains
  readable by its recipient while Appine is backgrounded; raw retention files and rootfs are not
  provider roots.
- `Diagnostics.export(forSave = true)` instead uses `cache/diagnostic-saves`, outside the provider
  root and share pruning. Each pending picker/transfer in `android/settings` owns its ZIP and
  deletes it on cancellation, owner destruction or completion. `install` attempts to delete save
  orphans from the previous process.

### Fault, listen and tab notifications

- With privacy enabled, notification event and session details are hidden: non-ongoing titles and
  bodies use generic text, and enabling removes old non-ongoing notifications (tab numbers and
  action labels remain). Report-copy actions target non-exported `Notifications.ReportReceiver`
  normally and non-exported `NotificationActivity` (`android/privacy`) during privacy mode; only
  the latter opens the authentication page. Both copy directly without starting the kernel, and
  all copy paths (including legacy service intents) check `Privacy.locked` at delivery. Reports
  are not copied into notification text.
- Kill reports (one tab-separated line each: console, pid, command, `bug` or `oom`, and three
  fields: for a bug the kernel's report line, its log records and the unimplemented paths, for an
  OOM kill the cgroup path, usage and limit): `TerminalService` delivers in-tab `[appine]` lines
  and asks `Notifications` to post "<command> was killed" unless the message was shown in the tab
  in front, and always for an Appine bug. OOM kills say whether the app's limit or a cgroup's
  `memory.max` (in MiB or GiB) was reached.
- Bugs always notify with "copy report": app version and build, device (Android release and API,
  maker and model, ABI, page size), distro, saved tier and (when enabled) Vulkan GPU, the kernel's
  report line, its kernel log records (up to 10 earlier ones and the report's own, one per line of
  it), and under "Unimplemented paths reached" the 10 the guest reached most since boot, with a
  line for the rest (`kernel/signal`). "Copy report" puts the text on the clipboard (with a toast
  before Android 13) and takes that notification away.
- Crashes of the last run: a crash (Java or native) or ANR record of the killed run posts "Appine
  crashed" or "Appine stopped responding" (id 3) with "copy report": the bug report's app, device,
  distro and tier lines and one line with the pid, the kind (with the signal for a native crash),
  the time in UTC and, for an ANR, Android's description.
- Too-long holds of the BKL (`kernel/bug`'s watchdog): a new stall of the current kernel posts
  "Appine's kernel is stuck" (id 4) with the seconds when first seen (not refreshed) and "copy
  report" (the watchdog's report line); once it has ended, a notification still showing becomes
  "was stuck" with the total seconds and the kernel log record in its report, without alerting
  again (a dismissed one stays dismissed); a stall first seen after it ended posts "was stuck"
  directly.
- Boot failures: `ERROR: <service> failed to start` lines in `/var/log/boot.log` after boot (id 2,
  "N services did not start", with the services listed via ICU's `ListFormatter`).
- Listens: the first `listen()` on a non-loopback address per (guest port, host port) pair and
  kernel (the kernel queues at most 32; one dropped from a full queue is reported only when the
  guest listens on that pair again), with remap text when the ports differ; notifications are keyed
  by `(tag = "listen:<guest>:<host>", id = 1000 + guest port)` (`Notifications.listenKey`) so
  different host-port remappings of the same guest port do not overwrite each other.
- Tab notifications (`Notifications.tab`, on the `terminal` channel): one per tab (id
  `200000 + tab id mod 100000`), replaced by the tab's next one: titled with OSC 777's title or a
  status record's title, else the program's title (`sh` when none), with the body, "The terminal
  bell rang", or for a status what it says (`VtSession.describe`: "Waiting for your approval",
  "Waiting for your answer", "Waiting for credentials", "Waiting for you", "Done", "Failed") and
  its message after a colon, and "Tab N" as subtext. A finished command's is titled "Command
  succeeded" (exit code 0), "Command failed" (another code) or "Command finished" (none reported),
  with "Took <time>." or, after a failure, "Took <time> and exited with code N." (the time in
  ICU's short units in the app's language: hours and minutes, minutes and seconds, or seconds).
  Tapping one opens the activity on that tab (`EXTRA_TAB` with the session id); showing the tab or
  closing it takes it away.
- Channels: `sessions` (low), `network` (high), `problems` (high), `terminal` (high, named
  "Bells, notifications from programs and finished commands"). Their names are in the app's
  language: `onConfigurationChanged` names them anew when the configuration's languages have
  changed (and `TerminalService` reposts the ongoing notification); other notifications keep the
  language they were posted in. The bug report is in English whatever the language.
- Newly created `network`, `terminal` and `problems` channels use `IMPORTANCE_HIGH` for heads-up alerts;
  `terminal` also enables the system's default notification vibration. Existing channels retain
  their settings: Android does not apply increased importance or new vibration defaults after
  channel creation, so older installations need heads-up alerts and vibration enabled in system
  notification settings. Notification permission, channel settings and Do Not Disturb govern
  background alerts; there is no separate vibrator call. Foreground program notifications use view
  haptics instead (`android/service`, `android/vtview`).

## Dependencies (inbound / outbound)

### Inbound

- `TerminalService` (`android/service`: creates `Notifications`, forwards events, polls and config
  changes, records startup errors and boot failures).
- `MainApplication` (`android/native`: calls `Diagnostics.install`).
- `SettingsFragment` (`android/settings`: calls `Diagnostics.export` and grants Share URIs via
  `DiagnosticProvider`).
- `NotificationActivity` (`android/privacy`: calls `Notifications.copyReport` after unlock).
- Instrumentation tests (`android/tests`: `DiagnosticsTest`, notification routing and privacy
  checks).

### Outbound

- `TerminalService` and `Settings` (`android/service`), `Rootfs.installed` (`android/setup`),
  `VtSession` (`android/vt`: title and `describe`), `MainActivity` (`android/terminal`: tap
  `PendingIntent` with `EXTRA_TAB`), `Privacy` and `NotificationActivity` (`android/privacy`).
- Android: `NotificationManager`, `ActivityManager` (exit records and traces), `ClipboardManager`,
  `FileProvider`, ICU `ListFormatter` / `MeasureFormat`, `BuildConfig`.

## Invariants and gotchas

- Notification ids are split between `TerminalService` (ongoing id 1) and `Notifications` (fixed
  ids 2–4, listens `1000+`, kills `100000+`, tabs `200000+`): any new id must be checked against
  both.
- Every path that posts a tab's notification (bell, OSC 9/777, finished command) must clear
  `statusNotified` for that tab so later status withdrawal does not cancel a newer notification.
- All report-copy entry points (`ReportReceiver`, `NotificationActivity` and legacy service
  intents) must check `Privacy.locked` at delivery without starting the kernel.
- `Diagnostics` must never call native kernel APIs or run on `TerminalService`'s worker, so
  exports succeed even when the kernel is stuck or stopped.

## Open questions

- Should a long stall's notification update its seconds while the stall goes on?

## To verify

- Background notification vibration and heads-up alerts for terminal events, listens and problem reports on
  physical devices (other tabs, backgrounded app, new vs existing channels adjusted in system
  settings, Do Not Disturb and vendor-specific suppression).
- Abrupt native-crash capture, real ANR/native trace attachments and vendor logcat availability in
  `Diagnostics` (API 34 instrumentation passed for retention, capture, archive contents and
  provider confinement).
- Channel renaming on language change while the service runs (`onConfigurationChanged`): compiled
  only, not tried on a device.
- Report copying after biometric authentication and vendor notification behavior on physical
  devices (API 34 instrumentation covered routing and stale-report clipboard denial).
- Finished-command notifications on real devices and with durations in hours (tried only on a
  Pixel 4a API 34 emulator).
