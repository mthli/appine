# Appine Android app

The Gradle project for the Android shell (design doc §3 and the `android/*` maps; terminals in the `kernel/tty` map):

- `app/`: the Kotlin app. `TerminalService` is a `specialUse` foreground service that
  owns the guest kernel and the terminal sessions; `MainActivity` shows them with tabs
  and a row of extra keys. The terminal emulator is libghostty-vt from Ghostty, linked into
  `libappine.so` (`hosts/android/src/vt.c`, `vt.rs`), and drawn by `VtView` with the bundled
  JetBrains Mono font. `Rootfs` imports Alpine from the APK assets on first launch,
  picks the fastest mirror, and can replace it with Debian (downloaded and checked
  against a pinned sha256 digest). `SettingsActivity`, opened from the tab bar's menu,
  switches the distribution, resets the rootfs, chooses the package mirror, the login
  account and the execution mode (Tier 1 by default; Tier 0 for troubleshooting in both debug
  and release builds).
  On the first launch of a new or upgraded Alpine system, Appine checks for OpenRC.
  If it is missing, Appine installs it offline and boots it before opening a tab. An existing
  installation keeps its configuration. After setup, users can remove OpenRC with `apk del`.
  If setup fails on an upgrade, the terminal opens for repairs and the next start retries.
  Each tab runs `assets/login.sh` as root: it picks the
  login account (the first regular user by default), gives a regular user doas or sudo the
  first time it logs in (the doas package from `scripts/fetch-rootfs` is in the assets),
  installs the bundled bash, procps-ng and bubblewrap packages offline on a new Alpine system,
  and switches to it with `su` (`--session-command` where su is util-linux's, so that the
  shell keeps its controlling terminal and job control). `Native` is the JNI interface to
  `libappine.so`, which is built from `hosts/android` with cargo-ndk.

## Building

Requirements: the Android SDK with NDK 29.0.14206865, JDK 17 or later, Go 1.26 or later,
`rustup target add aarch64-linux-android aarch64-unknown-linux-musl`,
`cargo install cargo-ndk`, Zig 0.17.0 on `PATH`
(or in `ZIG`), the rootfs tarball (`scripts/fetch-rootfs`) and the libghostty-vt source release
with its Zig packages (`scripts/fetch-ghostty`), both run from the repository root. The Zig
build then runs offline. Zig also builds the static Linux browser launcher from
`hosts/android/open-url.c`; its musl libc notice is included in the APK.

Gradle resolves Zig from `zig.path` in `android/local.properties` first, then the `ZIG`
environment variable, then `zig` on `PATH`. A missing or blank local value falls back to
the environment. Both the browser launcher and Rust native build use this setting.
For Android Studio on a Mac with Homebrew, add this to the ignored `local.properties`:

```properties
zig.path=/opt/homebrew/bin/zig
```

```sh
./gradlew installDebug                 # build and install on a connected arm64 device
./gradlew connectedDebugAndroidTest    # instrumentation tests on a device or emulator
./gradlew assembleRelease              # build app/build/outputs/apk/release/app-release.apk
```

Both APKs build `libappine.so` for `arm64-v8a`. Debug uses Cargo's `release` profile to keep
the interpreter fast. Release uses `android-release` for cross-crate LTO and a smaller library.

The release APK uses R8 and resource shrinking and is signed with the local debug key.
`app/proguard-rules.pro` preserves JNI methods and the `VtSession.on*` callbacks Rust looks up
by name. Archive `app/build/outputs/mapping/release/mapping.txt` with its APK to decode stack traces.

Run the instrumentation tests on both a 4K-page
and a 16K-page system image. `downloadedDistributionsRun` downloads Debian
(about 50 MB) and the browser bridge test installs tmux/screen in a separate temporary store. Both require
`-Pandroid.testInstrumentationRunnerArguments.network=true`. An emulator started
with `-http-proxy` sends connections to 127.0.0.1 to the proxy too, which breaks
`downloadsResumeWhereTheyBrokeOff`. Setting the system proxy instead
(`adb shell settings put global http_proxy 10.0.2.2:<port>`) only affects the app's own
HTTP downloads and leaves 127.0.0.1 alone; clear it afterwards with
`adb shell settings put global http_proxy :0`. Deleting the setting instead leaves
`global_http_proxy_host` and `global_http_proxy_port` behind, and the proxy stays in effect
(with an empty exclusion list, so 127.0.0.1 goes to the proxy too) until the next reboot.

## Sharing Android folders

Open **Settings → Folder sharing** to add, edit or stop a share. The system
folder picker authorizes a read-only share. With root permission, Appine uses
its own folder picker and can enable writes to the original Android files.

Set the mount point for each folder. Leaving it blank uses `/mnt/android`.
Use a different path for each additional folder, for example `/mnt/downloads`
or `/mnt/photos`. Destinations must be absolute, cannot overlap, and must be
empty if they already exist. Shares saved with the earlier app keep their
original `/mnt/appine-android` path.

Open a share to copy its mount point. Saved mounts are restored when the guest
boots. The APK supplies the FUSE client for Alpine and Debian, and the device
loopback bridge works offline without SSH.

## Running commands from other apps

Enable **Terminal access** in Appine Settings. It is off by default. Enabling
it lets apps on this device run Linux commands in Appine. Commands use the
active distribution and the configured Linux login account. Appine privacy mode pauses terminal access.

### Use the Android shell client

Open **Settings → Integrate into system PATH** and choose one option.
Either action turns on **Terminal access** when it succeeds. Appine privacy
mode pauses access.

- **Install module automatically** uses the device's Root manager to install
  the bundled CLI. Restart Android for `/system/bin/appine` to appear. KernelSU
  3 and later requires a system-mounting metamodule. If `appine` is missing
  after reboot, turn off **Unmount modules** for the Android terminal app in
  KernelSU and reopen that terminal. The same Appine settings row offers
  **Uninstall module** after installation.
- **Copy shell command** adds the installed executable path and `exec -it -- bash`
  to the clipboard. Paste the full command into an Android shell to enter
  Appine. Copy again after an update, since Android may move the executable.

```sh
appine exec -it -- bash
appine exec -- sh -c 'echo output; echo diagnostic >&2; exit 37' >out.txt 2>err.txt
echo "$?"
```

The client reuses the running Linux instance. On a cold start it starts the
service and waits for Linux while the Android terminal stays in front. The
client uses the device's loopback interface. Disable **Terminal access** in
Appine to stop connections and running sessions.
On devices that freeze Appine in the background, the client can resume it
when a command or terminal input is waiting. Copy the shell command from the
updated APK to use this behavior. The system PATH module can be reinstalled
from Settings to update its client.

With `-it`, the command has its own Linux PTY and receives keyboard input,
Ctrl+C and window-size changes. Its output streams share the PTY. An open shell
holds a partial wake lock until it exits or disconnects. On Oplus builds,
the client also refreshes Android's foreground activity state for the duration
of the shell to prevent background freezing. Without
`-it`, stdout and stderr are separate and stdin is `/dev/null`.

### Build a standalone Android client

Build with Go 1.26 or later and the Android NDK. On a Linux x86-64 host, set
`ANDROID_NDK_HOME` to the NDK directory and run this from the repository root.

```sh
cd contrib/android-exec
CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android30-clang" \
  CGO_ENABLED=1 GOOS=android GOARCH=arm64 \
  go build -buildmode=pie -trimpath -ldflags '-s -w' -o appine .
```

For APKs built with a custom application ID, set `APPINE_PACKAGE` to that ID.

### Android app integration

Let `PKG` be the installed APK's application ID, `io.github.mthli.appine` by default.

1. Declare a `uses-permission` for `PKG.permission.RUN_COMMAND` and request
   that runtime permission. Declare `PKG` in `queries` if the client checks
   whether Appine is installed.
2. Bind with action `PKG.RUN_COMMAND` and explicit component
   `PKG/io.github.mthli.appine.ExecCommandService`. Use `BIND_AUTO_CREATE`
   from a visible Activity to allow cold starts. If the device blocks a cold
   bind, open Appine's launcher Activity and retry the binding.
3. Send a Messenger message with `what = 1`, a `replyTo` Messenger, and
   `argv` as a `String[]` in the message's data Bundle. Include the command
   as `argv[0]`. Keep the binding until all results are read.
4. Read the reply with `what = 2`. This contains either `error` as a string
   or `result` as a `ParcelFileDescriptor`. Drain and close the descriptor on
   a worker thread. Each frame has a one-byte kind and a big-endian
   four-byte byte count before its payload.

| Kind | Payload |
| --- | --- |
| 1 | Raw stdout bytes |
| 2 | Raw stderr bytes |
| 3 | Four-byte big-endian exit code, including `128 + signal` for a signal exit |
| 4 | UTF-8 message for a bridge failure |

The final frame is kind 3 or 4. Each request accepts up to 256 arguments and
32 KiB of UTF-8 arguments.

## Open source licenses

The notices under Settings → Open source licenses are checked-in resources in
`app/src/main/assets/notices/`; Gradle does not generate them. `index.json` records each
entry's stable ID, project name, version, short license label and HTML file. The optional
`packageCount` produces the localized Alpine summary, and `kind: "license"` identifies a
standalone license text, hidden from the main list but reachable through component detail
links. Each HTML document links to the shared `style.css`. The reader
supplies `/theme/colors.css` from the current Material theme, opens external web links in
Custom Tabs and needs no network connection to read a notice.

Keep notices in the same change as dependency updates:

- Appine's notice includes the repository's `LICENSE`, copyright, warranty and source link.
- Fuchsia, libghostty-vt and its linked components, and JetBrains Mono retain their original
  notices; `licenses/` keeps the upstream source copies for comparison.
- Rust entries cover the normal dependencies reachable from `appine-android` on
  `aarch64-linux-android`, excluding workspace members, build dependencies and proc-macros.
  Record the chosen MIT license when offered as an alternative, and retain the upstream
  expression and any additional notices in the detail page. Keep separate entries for
  simultaneously linked versions, such as jni-sys 0.3 and 0.4.
- `android.html` lists the resolved Android runtime libraries, including transitive ones;
  check both debug and release runtime classpaths when changing the version catalog.
- `alpine.html` records the bundled rootfs and doas/bash/procps/bubblewrap/OpenRC packages, with versions,
  license expressions and aports commits. Compare with the artifacts Gradle selects in
  `target/rootfs`, and count shared packages only once.
- Preserve license wording, copyright notices and all required component notices when
  editing HTML. Use paragraphs and headings for reflow; do not shrink fixed-width text.
  Keep IDs stable across upgrades, and update the separate source bundles as before
  (`scripts/release-sources`).

`LicenseTest` checks coverage and offline rendering/scroll restoration without starting the
kernel or changing its store. It does not detect every dependency upgrade; reviewing the
resolved dependencies and bundled package metadata remains part of updating the static files.

```sh
./gradlew assembleDebug lintDebug
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.mthli.appine.LicenseTest
```
