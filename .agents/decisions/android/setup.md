# Rootfs, settings and login (android/setup) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: android/setup"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12). D1–D8 were seeded by hand from the retired design doc
> §11.1 and §11.4 and the round records in `docs/rounds/`, so their sources cite rounds; later
> entries cite commits.

## Constraint index (compressed active decisions)

> Full text of D10, D11, D15, D23: `git show 221a89b12e1dffa7b1432b9076078c1bffde46cb:.agents/decisions/android/setup.md`

- **D10** — Every build exposes execution mode in Debugging with a distinct current-mode summary and restart confirmation, disables it without a running kernel and persists Tier 0 when chosen; switching ends sessions and device layout/switching remain unverified (62a8c07; cf4f5e20; 20137e4c; d053be24, 39e55b4b)
- **D11** — CJK enlargement is an always-available on/off toggle with state summaries and no intermediate scale, affecting every narrow-rendered wide glyph rather than only CJK characters (0478dd5; 75123eef (shared labels and default); d053be24, 39e55b4b)
- **D15** — Rootfs uses KTX edit with commit=true for synchronous import records, while standard Preferences replace handwritten toggle accessibility; TalkBack behavior remains unverified (4c1a3ce; f8822b8b; 20137e4c; d053be24, 39e55b4b)
- **D23** — The always-available default-on volume-key tab-switch setting forwards to the service with English/Chinese summaries, has no modifier mode and falls back to system volume without another tab; device/localization coverage remains limited (61df6915; 20137e4c; d053be24, 39e55b4b)

> Full text of D14, D16, D22: `git show 5bd37526030bd7d7f70d048c38917b27d83f8c4b:.agents/decisions/android/setup.md`

- **D14** — Rootfs follows startup order and Settings separates its frame from fragment actions, while historical profile texts stay beside their callers and OLD_PROFILE must remain after UNMARKED_PROFILE because initialization is ordered (a546fea; 6c5f921 (the profile moved to an asset); f8822b8b; 20137e4c; d053be24, 39e55b4b)
- **D16** — Guest prose says Appine while appine: prefixes and paths stay lowercase, login rewrites the rules comment but the import-only motd leaves existing systems with their previous spelling (3f04bf5 (with android/terminal D28), 39e55b4b)
- **D22** — Settings omits density from configChanges and recreates on display-size changes, rebinds and renders afterward, retaining AndroidX login/cursor list dialogs while other dialogs close (e12f560; f8822b8b; 20137e4c; d053be24, 39e55b4b)

> Full text of D7, D21: `git show ec9ad94b862479935821fbfad2affa7bbdba355e:.agents/decisions/android/setup.md`

- **D7** — Before startup, write loopback-only /etc/network/interfaces only if missing and add osclock to the OpenRC boot runlevel only when no clock service exists, preserving user files and avoiding global rc_provide cycles and hwclock on a guest without an RTC. (round 187 (`187-app-init`))
- **D21** — Settings exposes Block, Bar or Underline and default-on blinking at any time via the service, with a current-shape summary and English/Chinese labels, while the unfocused view still draws a hollow cursor and the grouping and Chinese layout lack device confirmation. (77d7f36)

> Full text of D1, D3, D6, D25, D26, D35: `git show c4db5db249c93e2e122d88ef60153f67c94a1f62:.agents/decisions/android/setup.md`

- **D1** — First launch imports the bundled Alpine minirootfs from the APK without asking, Settings can switch to Debian 13 (`debian:trixie`) downloaded on demand from GitHub (slow from China without a proxy), and Ubuntu is not offered (rounds 99, 190)
- **D3** — Switching or resetting downloads and verifies the new tarball in the background while the old system keeps running, replacing the store only when ready (falling back to bundled Alpine on import failure, with a 30 s cancel join and restart-from-zero if killed) (round 190)
- **D6** — `login.sh` switches with `su <account> -c 'cd …; exec "$SHELL" -l'` (using `--session-command` when util-linux `su` offers it) so restored tabs keep their directory and job control, Ctrl-C skips Debian sudo install until next login, and package commands read from `/dev/null` (rounds 194, 206)
- **D25** — Settings summaries stay concise by omitting download sizes and reset distribution names while showing the resolved username (or root) for automatic login, keeping operational detail in switch confirmations (b2f96a63; 20137e4c; d053be24; 39e55b4b)
- **D26** — Screen-off policy uses a nonpersistent `ListPreference` and shared Material choice dialog for SLEEP, BUSY and ALWAYS synced with notification toggles, with vendor presentation and accessibility unverified (a3f7507e, 1e5de64e; 20137e4c; d053be24; 39e55b4b)
- **D35** — Settings exposes tab-list visibility as a service-backed nonpersistent `SwitchPreferenceCompat` before volume-key switching, with device layout and accessibility unverified (5c49c5a7; 39e55b4b)

> Full text of D8, D12, D28, D31, D32, D33, D36, D38, D39, D41: `git show 1ea0c1ef9d686d7126bec2029fc928d4f6fdfc6b:.agents/decisions/android/setup.md`

- **D8** — Only one rootfs is installed at a time with packages upgraded by the user (`apk`/`apt`), while Appine refreshes per-start integration files (D20, D30), switching discards old files, and rootfs export/import is planned for v1.x (round 99 (`99-choose-rootfs`); design doc §11.4; 8e211142; d053be24; 39e55b4b)
- **D12** — Settings uses nonpersistent service-driven AndroidX Preferences in a Material page (`liftOnScrollTargetViewId` on the preference RecyclerView, stale account loads discarded, scroll restored after insets and state), with deprecated target-fragment choice dialogs and device/TalkBack behavior unverified (55be399; f8822b8b; dd200589; 20137e4c; d053be24; 39e55b4b)
- **D28** — About works without service binding, listing Telegram, Project website and Privacy policy with URL summaries and unpinned Custom Tabs plus Version (`name (code)`) opening `PLAY_STORE_URL` (`io.github.mthli.appine`) via `com.android.vending` then HTTPS fallback with the missing-handler toast, while device/store routing remains unverified (d0a74e94, 0b3c5d8a; d053be24; 39e55b4b; 5f950434, 13675d1d, 5b213f09; 1afcffbc)
- **D31** — External file access renders service state, requires a Material confirmation on every enable (discarded on recreation), disables immediately, and is disabled by privacy mode (D32), with physical-device and accessibility behavior unverified (d0ddf170; d053be24; 39e55b4b)
- **D32** — The nonpersistent privacy switch confirms enabling before authentication, authenticates disabling without restoring file access, leaves the preference unchanged on cancel or unavailable auth, replays deferred notices, and protects Settings dialogs with secure-window helpers (f61072f3, 9c6c51a7; d053be24; 39e55b4b)
- **D33** — Save to file and Share prepare one authorized ZIP before the save picker, held single-use in a fragment ViewModel and deleted on cancellation, abandonment or completion, while process death discards authorization, provider failures may leave partial files, and cloud/slow transfers remain untested (c7309c0c, 9c6c51a7; d053be24; 39e55b4b)
- **D36** — Enable notifications appears first in Android only while app notifications are disabled, using the shared helper without service binding and refreshing on creation, permission results and resume, while Android 11–12, vendor navigation and TalkBack remain unverified (ce38e763; 39e55b4b)
- **D38** — App battery usage sits below notifications with separate summaries, refreshes visibility on resume and opens package details on tap, though vendors may move or rename those controls (7a931d97)
- **D39** — `appine-gpu` sets `VK_DRIVER_FILES` to the packaged Venus manifest and execs the opt-in command, requiring launcher updates if a distribution renames its Venus manifest (9cafc90b)
- **D41** — Debugging exposes a nonpersistent Vulkan GPU switch between Execution mode and Export diagnostic logs, enabled only while a kernel runs without a distro switch, confirming enable and disable with `showPrivate` restart dialogs and showing "Unavailable on this device" without a probed renderer, with non-English/Chinese layout and TalkBack unverified (b605a0a3)

## Active (full detail)

### D2: Tarballs are trusted by pinned digests, whatever the source
- **What**: Pinned version, length and sha256 validate tarballs from ordered sources; resume continues on 206, restarts on 200 and fails the source without resetting on other statuses, while a failed attempt already at the full length proceeds to verification.
- **Why**: With the digest fixed in the app, every source is equally trustworthy; a Debian signature would add no trust beyond the digest the app already carries.
- **Tradeoffs**: Distro updates require new pins, transient resume errors can exhaust Debian’s sole source, and partial files do not survive the download call.
- **Watch out**: A non-200 reply to an unsupported Range ends the source, and only the final hash checks the 206 payload’s correctness.
- **Source**: round 99 (`99-choose-rootfs`); design doc §11.4; 30d0fab3; 20137e4c; d053be24; 39e55b4b

### D4: Mirror selection uses completed probes before publication

- **What**: Choose the fastest completed probe at configuration time, breaking ties in distro order and falling back to the official mirror.
- **Why**: A post-import worker could race store replacement.
- **Tradeoffs**: Faster late answers are discarded.
- **Watch out**: Slow networks can leave the official source configured.
- **Source**: rounds 99 (`99-choose-rootfs`), 190 (`190-settings-default-alpine`), 203 (`203-mirror-keeps-lines`); a1367531; 20137e4c; d053be24; 39e55b4b, 568b6a48

### D5: Tabs log in as the first regular user by default, who is set up to become root
- **What**: The login setting defaults to "auto" (the regular account with the lowest UID, else root); the first time a regular user becomes the login account it is added to wheel with doas (Alpine, installed offline from the bundled package) or to sudo (Debian, installed online), and NOPASSWD rules are written only while the account has no password.
- **Why**: The user wanted desktop-like behaviour where `adduser` makes new tabs log in as that user; Alpine's root is locked, so such a user could not `su` back or run `apk add`; the setup follows Alpine's own `setup-user -a`, and the user chose "ask only if a password is set".
- **Tradeoffs**: Only tabs opened by the app run the setup (`su -` and ssh logins do not); "no password" is judged from the shadow field alone.
- **Source**: rounds 193 (`193-login-account`), 206 (`206-debian-sudo-progress`); design doc §11.1

### D9: Distributions, first boot, logins and guest shell integration form android/setup
- **What**: android/setup covers `Rootfs.kt`, guest login/OpenRC/shell-integration/browser-launcher/GPU-launcher assets and `scripts/fetch-rootfs`, with `To verify` kept to unverified residuals, while `SettingsActivity.kt` belongs to `android/settings` and service lifecycle stays in `android/service`.
- **Why**: `android/setup.md` reached 49,174 B (106.7% of one read) while mixing guest rootfs/shell setup with the Settings UI.
- **Tradeoffs**: Changes spanning both rootfs/account logic and the Settings UI touch both `android/setup` and `android/settings`, pre-split Settings decisions remain here, and sibling concerns stay in `android/privacy`, `android/licenses`, `android/terminal`, `android/vtcore`, `android/vt` and `android/build`.
- **Watch out**: Changes to `Distro` metadata or `regularAccounts` must check `android/settings` consumers, while in-place shadow edits can still delay NOPASSWD refresh and shell-hook/device checks remain open.
- **Source**: 12c4afa (bug list since updated by 237d663, d03c9b6); 5d506bfb; a1367531; 7aa817af; f8822b8b; 20137e4c; 8e211142; 9c6c51a7; 81f60bef; d053be24; 60790c14; 39e55b4b; e4d7bbe5

### D13: Login rules, admin setup, mirror choice and downloads fail closed
- **What**: NOPASSWD rules are refreshed at every login, root's included, and granted only when the account's shadow field after any leading `!` is empty or `*`; an admin is matched with `grep -F` and recorded only once `id -nG` shows the group (else retried at the next login); an import-only mirror write leaves edited sources alone (D4); and a download fails as soon as a source sends more than the pinned length.
- **Why**: Locked accounts with a password, missing shadow entries and a missing `/etc/shadow` counted as passwordless, root logins never rewrote the rules, failed group changes were recorded, names were matched as regexes, a mirror that failed to write was recorded and fetch had no cap.
- **Tradeoffs**: A password set inside a running session keeps NOPASSWD until the refresh of D19 runs, or until the next login when nothing in `/etc` was renamed, and a locked account without a hash still counts as passwordless, as `adduser -D` accounts must.
- **Watch out**: Shadow formats other than Alpine's and Debian's (`!`, `!!`, `*`, `!*`) are judged by the same rule, and the Debian sudo path was not run in a container, only shared with the Alpine path.
- **Source**: 711b034; d03c9b6 (the refresh, D19); a1367531; 20137e4c; d053be24; 39e55b4b

### D17: An import records its distro first and keeps the tarball until the store is in place
- **What**: `prepare` commits `KEY_DISTRO` for the app's store before importing and deletes the tarball only after the rename and directory sync, and `Rootfs.importing` returns that record (Alpine when there is none) for a launch without a store (android/service D16).
- **Why**: The distro was recorded only just before the store was renamed into place and the tarball was deleted right after importing, so a kill midway left nothing saying which distro was being imported and could lose the download.
- **Tradeoffs**: A kill between deleting the old store and `prepare` starting still resumes the old distro; the cache holds the tarball a little longer during configure and sync.
- **Watch out**: Fallback prepare records Alpine before importing it, so a failed Debian download is not retried at every later storeless launch; the pre-prepare deletion window still resumes the previously recorded distro.
- **Source**: 237d663; 5d506bfb (recorded correction); a1367531; 20137e4c; d053be24; 39e55b4b

### D18: Rootless podman uses the host network, and container storage keeps the packages' defaults
- **What**: Before every start (android/service D17) `Rootfs.ensureContainersConfig` writes `containers.rootless.conf.d/appine.conf` (`netns = "host"`) when missing and nothing else, so podman's overlay driver and docker's overlayfs snapshotter, the packages' defaults, run on the kernel's overlayfs rootful and rootless.
- **Why**: Without TUN, pasta and slirp4netns cannot start for an ordinary user, and users had to find and edit the right files themselves; once the kernel had overlayfs, forcing vfs and the native snapshotter only cost every container a full copy of its image.
- **Tradeoffs**: Rootless containers share the host's network; a store set up by an earlier build keeps its vfs `storage.conf` and native `daemon.json` (no migration, as the user asked for an unreleased app), and on Alpine the package's own `storage.conf` then sits in `storage.conf.apk-new`.
- **Watch out**: A distribution whose podman or docker package defaults to another driver, or a future default needing overlay features Appine lacks (redirects, metacopy, idmapped layers), would fail without the old fallback.
- **Source**: 0eb9e19, 869de68

### D19: Login-rule refresh tracks input metadata and retries failures

- **What**: Sample the rule inputs on first poll and subsequent changes, then read accounts and publish rules under one lock, acknowledging only successful refreshes.
- **Why**: Editing an account file in place may leave directory entries unchanged.
- **Tradeoffs**: Failed refreshes run again.
- **Watch out**: A failed sample must never become the acknowledged baseline.
- **Source**: d03c9b6, d5b808e8

### D20: Profile migration installs companion prompt hooks without overwriting user edits
- **What**: Recognized unmodified profiles migrate by text/hash, install bashrc/ashrc and zshrc companions before the profile, integrate bash through the profile and Alpine’s system bashrc, expose ash hooks through an unset ENV, and source /etc/profile.d/appine.zshrc to register precmd/preexec inside an anonymous function using emulate -L zsh, with sanitized title reporting in D34. Current hooks escape control characters and backslashes, integrate login and nested zsh and Debian bash, mark continuation prompts, and preserve ash's ENV through stock doas rules while respecting customized profiles.
- **Why**: Prompt features need marks after user startup files and in supported nested shells, while new Alpine logins use bash.
- **Tradeoffs**: Customized profiles and doas rules take precedence, and an asynchronous theme or overwritten PS1 can still remove marks.
- **Watch out**: Record replaced profile hashes for upgrades and install nested zsh integration on a subsequent kernel start if the shell was added to a running store.
- **Source**: 6c5f921, 2b04a43; a7d8c9f5; a809c5a8; 7aa817af; 20137e4c; d053be24; b04ebd4e; 9f9ba8d7; 39e55b4b; 4a10bc49, 4309ef58, acc5a7a0, 6e0c0f16

### D24: New Alpine imports install bundled bash with serialized package setup

- **What**: Alpine imports cache four pinned bash packages, select bash for root and install offline under `/run/appine-install.lock`, preserving existing systems and shell fallback while serializing bash/doas and waiting for apk/apt locks.
- **Why**: New systems need full bash compatibility without network access or package contention breaking first login.
- **Tradeoffs**: The APK gains about 0.86 MB and first-login work, existing users keep their shells, and apt update may wait until interrupted.
- **Watch out**: Retain a usable fallback and retry cache on failure, refresh disappearing Alpine pins, and keep network sudo installation outside the bundled-install lock.
- **Source**: 7aa817af; 20137e4c; d053be24; 39e55b4b

### D27: New Alpine imports stage an independent offline procps-ng bundle

- **What**: Imports cache pinned procps-ng dependencies separately from bash, install before account selection under the install lock using apk’s package database, re-add procps-ng by name and remove the cache only after success.
- **Why**: New systems need procps tools offline regardless of the selected shell.
- **Tradeoffs**: Existing stores are not migrated and first login performs another transaction.
- **Watch out**: Failed bundles must survive for retry, later user removal must stay respected, and disappearing upstream packages require refreshed pins.
- **Source**: 410ef192; 20137e4c; d053be24; 39e55b4b

### D29: New Alpine imports stage an independent bubblewrap bundle

- **What**: Imports cache SHA-256-pinned bubblewrap 0.12.0-r0 and libcap2 2.78-r0, install before account selection under the shared lock, check apk state, re-add bubblewrap by name and delete the cache only on success.
- **Why**: New Alpine systems need offline sandbox tooling without depending on the bash or procps bundle.
- **Tradeoffs**: Existing stores receive no automatic installation and first login gains another package transaction.
- **Watch out**: Retain retry state without blocking usable login, respect later user removal and refresh pins when mirrors remove old builds.
- **Source**: 0815b3e4; d053be24; 39e55b4b

### D30: Every kernel start refreshes the guest browser client and configuration

- **What**: Before each kernel start, setup installs the static client and current endpoint configuration, replacing only the known Appine OSC prototype while preserving custom launchers and symlinks, and a recognized stock profile exports /usr/local/bin/xdg-open when executable and BROWSER is empty or unset.
- **Why**: Existing stores and long-lived shells need current browser connection information.
- **Tradeoffs**: Guest users share the capability and preserved custom launchers keep responsibility for their own behavior.
- **Watch out**: Migration needs a kernel restart and preserves edited profiles, shells bypassing the profile need their own setup, and Android authorization remains unverified.
- **Source**: 8e211142; d053be24; 39e55b4b; b560bc31

### D34: Prompt and execution hooks report sanitized shell titles

- **What**: bash, ash and zsh restore sanitized PWD through OSC 2 at primary prompts, while bash PS0 and deduplicated zsh preexec report command text with bash history used only when its counter advances.
- **Why**: Tabs need useful directory and command titles even when programs emit none.
- **Tradeoffs**: Command titles describe submitted lines rather than live processes, history-excluded bash commands use bash, and ash has no command-title hook.
- **Watch out**: Existing shells need reloading, custom profiles may not migrate, prompt/history frameworks can interfere, and ash must restore its sanitized title after its visible prompt.
- **Source**: b04ebd4e, 9f9ba8d7; 39e55b4b

### D37: New systems receive a concise package and Codex welcome guide

- **What**: The import-time welcome template gives apk or apt update, search, install and help commands plus three plain Codex installation commands and the issues URL.
- **Why**: The welcome message should provide a usable starting guide.
- **Tradeoffs**: The guide takes more rows and existing systems retain their previous message.
- **Watch out**: Package availability and Codex requirements can change and the example was not run on either distribution; git was added in 0f0cceab and removed from the example in d565c954, both without a MODULE Decision.
- **Source**: 39ba9e3a, 0f0cceab; 39e55b4b; d565c954

### D40: Alpine stores run one-time offline OpenRC setup on both new imports and upgrades

- **What**: Offline OpenRC setup checks `apk`, resumes any staged installer or sources missing packages from the APK, adds `openrc` to world by name, removes the installer and cache and records per-store completion only after verifying the default runlevel and service lock directory (marking preinstalled OpenRC complete immediately).
- **Why**: Services require OpenRC's runlevel state before first login, and existing upgraded Alpine stores can lack both OpenRC and the original import caches.
- **Tradeoffs**: First launch adds a package transaction, and the first upgrade migration also installs OpenRC for users who removed it prior to upgrading.
- **Watch out**: Preserve the staged installer across interrupted setup for retry; custom `inittab` entries or existing package constraints can prevent completion.
- **Source**: d0ff9a31, 7e85e8dc

### D42: Alpine stores seed bash startup files once for `/etc/skel`, root and existing regular users

- **What**: `Rootfs.ensureBashStartup` runs on new Alpine imports and once per existing Alpine store via `/var/lib/appine/bash-startup-done`, writing `/etc/skel/.bash_profile` and `.bashrc` (`overwrite = false`) and seeding missing `.bash_profile` (unless `.bash_profile`, `.bash_login` or `.profile` exists) and `.bashrc` owned by `uid:gid` for `root` and existing regular `/bin/bash` accounts (UID 1000–65533, non-negative GID) whose home already exists as a non-regular entry.
- **Why**: Alpine login tabs run `bash -l`, which skips `~/.bashrc` without a login file, and neither Alpine's minirootfs nor its `bash` package ships `/etc/skel` or user bash startup files.
- **Tradeoffs**: Missing or symlinked regular-user homes are skipped rather than created or dereferenced, intentional dotfile deletions are respected after the marker is written, and stores that wrote the marker before `125f3d00` are not re-run.
- **Watch out**: A non-directory replacing `/etc/skel` fails fresh-import or direct-caller startup before boot (existing stores degrade under D47), non-directory home entries rely on `exists` failing with `ENOTDIR`, and live `TerminalService` upgrade cold starts and 16 KB-page devices remain unverified.
- **Source**: 9f3e93ed, 125f3d00; 12ac97cf

### D43: Login setup gates locked commands and restores SIGINT before exec

- **What**: Run privileged setup only after flock succeeds, accept interruptions at cancellable installation waits, and restore default SIGINT before starting the shell.
- **Why**: Ctrl-C during bootstrap could skip required work or leak ignored signals into the session.
- **Tradeoffs**: Short setup steps run with SIGINT ignored.
- **Watch out**: Every shell exec path must restore SIGINT.
- **Source**: 6f2776f3, 9b179de1

### D44: The download inventory also describes generated assets

- **What**: The fetch script's assets mode emits filenames, digests and APK destinations while its ordinary output keeps packages ahead of tarballs.
- **Why**: Packaging and downloads need the same pinned inputs.
- **Tradeoffs**: Bundle destinations are maintained with the pins.
- **Watch out**: Shared dependencies may need multiple offline destinations.
- **Source**: d10f2973

### D45: Rootfs generations guard deferred file publication

- **What**: Advance the generation under the reset write lock and recheck it when publishing an import.
- **Why**: A completed read can belong to a store that has since been replaced.
- **Tradeoffs**: A reset can invalidate otherwise complete work.
- **Watch out**: The generation check and publication need the same lock.
- **Source**: f495f2cc

### D46: Login admin setup serializes under `/run/appine-rules.lock` and gates `bash`/`doas` completion on world registration

- **What**: First-login admin setup and atomic `20-wheel.conf` updates share root-only `/run/appine-rules.lock` with defensive `admins` deduplication in `refresh_rules`, while `install_bash` and `install_doas` create root-only `/run/appine-install.lock` under `umask 077` and remove the `bash` bundle or create `doas-installed` only after `apk_add <name>` succeeds (preserving `/bin/bash` launch on partial failure, custom non-apk `doas`, user removals, and Debian `sudo`).
- **Why**: Concurrent first logins could race to duplicate `/var/lib/appine/admins` rows or corrupt `20-wheel.conf`, and partial local-APK installs could drop the `bash` bundle or mark `doas` installed before apk world registration succeeded.
- **Tradeoffs**: A partial `apk_add <name>` failure triggers one extra fast offline registration check on the next login, and first-login admin setup briefly contends with `--refresh-rules` on `/run/appine-rules.lock`.
- **Watch out**: On-device `MachineTest` verification remains pending, and `install_doas` assumes any apk-managed `doas` can be re-added offline by name with `/dev/null` repositories.
- **Source**: 5cff644e

### D47: Per-start rootfs configuration isolates sub-steps and degrades on existing stores while failing fast on fresh imports

- **What**: `Rootfs.prepareStart` runs all seven per-start helpers and their independent sub-steps through `runSetupStep`, `writeStep`, `readMarker`, shared path constants, and `cachePackage` reuse, returning whether `/etc/appine/browser` was written, leaving `bash-startup-done` unwritten until all seeding writes succeed, throwing via `FAIL_FAST` on fresh imports, and reporting `IOException`s to `onFailure` on existing stores.
- **Why**: Obstructed paths, symlinked directories, unreadable markers, or full-disk errors in optional per-start writes previously aborted startup on existing stores and locked users out of a repair shell.
- **Tradeoffs**: Permanently obstructed paths on an existing store (such as a symlinked `/etc/skel` before `bash-startup-done` exists) are retried and reported on every start until repaired.
- **Watch out**: Direct callers of individual `ensure*` helpers still fail fast unless they pass `onFailure`, and unreadable one-time markers skip their migration instead of re-seeding user-deleted files.
- **Source**: 12ac97cf, f7d164db

### D48: Rootfs setup batches operations in one `StoreAccess` session and skips unchanged rewrites

- **What**: `Rootfs` setup helpers run through an internal `StoreAccess` backed by `Native.withStore`, skipping byte-identical overwrites for `/var/cache/appine/doas.apk` and `/usr/local/libexec/appine-open-url` (`writeIfChanged`), skipping `xdg-open.sh` and `appine-gpu.sh` reads/writes when a non-legacy regular file exists, returning `StartPreparation(browserReady, openRcDone)` from `prepareStart`, and reading `/etc/passwd` once in `loginAccount`.
- **Why**: `Rootfs.prepareStart` previously incurred roughly thirteen exclusive `Store::open` and `fdatasync` cycles per Alpine cold start while unconditionally rewriting unchanged binaries and re-reading launcher assets.
- **Tradeoffs**: Checking equality reads `doas.apk` and `appine-open-url` on each cold start instead of blindly overwriting them, and `prepareStart` changes its return type from `Boolean` to `StartPreparation`.
- **Watch out**: Byte-identical files keep their existing mode and owner, and an obstructed `openrc-setup-done` marker returns `openRcDone = false` without reporting a `prepareStart` error so `ensureOpenRc` handles it.
- **Source**: 641769ab

## Superseded

- ~~The execution tier is chosen in Settings in every build (5c34951, round 190)~~ → replaced by **D10** in 62a8c07 (2026-11-04)
- ~~`ensureContainersConfig` also chooses vfs in `/etc/containers/storage.conf` (rewriting an active `driver = "overlay"` line) and the native snapshotter in `/etc/docker/daemon.json` (0eb9e19)~~ → replaced by **D18** in 869de68 (2026-11-08)
- ~~A password set inside a running session keeps NOPASSWD until the next tab logs in (D13's tradeoff, 711b034, and D9's watch-out, 12c4afa)~~ → replaced by **D19** in d03c9b6 (2026-11-09)
- ~~`OLD_PROFILE` is built from `PROFILE`, the profile kept as a Kotlin string (D14's watch-out, a546fea)~~ → replaced by **D20** in 6c5f921 (2026-11-10)
- ~~D4’s manual mirror selection and rewriting of built-in hosts in existing sources~~ → replaced by **D4** in a1367531 (2026-11-12)
- ~~D10’s debug-only execution-tier row~~ → replaced by **D10** in cf4f5e20 (2026-11-13)
- ~~D12’s inflated custom Settings rows and hand-written switch accessibility~~ → replaced by **D12** in f8822b8b (2026-11-13)
- ~~D15’s custom toggle accessibility compat wrapper~~ → replaced by **D12** in f8822b8b (2026-11-13, implicit refinement)
- ~~D20’s ash marks written only by the login profile~~ → replaced by **D20** in a809c5a8 (2026-11-13, existing-entry match; the cited a7d8c9f5 Decision describes zsh)
- ~~D20’s bash hooks kept inline in the login profile~~ → replaced by **D20** in 7aa817af (2026-11-13, implicit refinement)
- ~~The keep-awake switch introduced by a3f7507e~~ → replaced by **D26** in 1e5de64e (2026-11-13, predecessor predates structured Decision records)
- ~~The immediate system-directory toggle summarized under D12~~ → replaced by **D31** in d0ddf170 (2026-11-14)
- ~~Inline zsh integration with eval-based registration under D20 in a7d8c9f5~~ → replaced by **D20** in 4a10bc49 (2026-11-16), retaining the existing loading scope and prompt marks.
- ~~Stage OpenRC exclusively during import of a new Alpine store in d0ff9a31~~ → replaced by **D40** in 7e85e8dc (2026-11-19).
- ~~Open the HTTPS Google Play details URL for `BuildConfig.APPLICATION_ID` in About's Version action in 0b3c5d8a~~ → replaced by **D28** in 1afcffbc (2026-11-21).
- ~~Leave pre-existing regular Alpine accounts untouched during one-time bash startup seeding in 9f3e93ed~~ → replaced by **D42** in 125f3d00 (2026-11-21).
- ~~D9 combined ownership of Rootfs and SettingsActivity (12c4afa)~~ → replaced by **D9** and android/settings **D1** in e4d7bbe5 (2026-11-22).
- ~~Refreshing login rules only when /etc directory entries change~~ → replaced by **D19** in d5b808e8 (2026-11-24)
- ~~Replacing a stock mirror configuration after rootfs publication~~ → replaced by **D4** in 568b6a48 (2026-11-24)
- ~~Leaving login or nested zsh and nested Debian bash without Appine hooks~~ → replaced by **D20** in acc5a7a0 (2026-11-25)
- ~~Losing the ash ENV hook under stock doas -s~~ → replaced by **D20** in 6e0c0f16 (2026-11-26)
- ~~Failing startup before boot on existing-store per-start configuration write errors (including a non-directory `/etc/skel` in D42)~~ → replaced by **D47** in 12ac97cf (2026-11-26, implicit refinement)
