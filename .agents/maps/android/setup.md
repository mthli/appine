# android/setup Map

> Static understanding snapshot, not a decision history.
> See `.agents/decisions/android/setup.md` for the paired decision history (it still holds the decisions on the Settings screen made before it was split out into `android/settings`).
>
> Verified: 2026-11-26 (full mapping, 15 claims independently checked across package verification, store publication, per-start setup, OpenRC, account selection and shell integration)
>
> Maintained: 2026-11-28 (targeted verification only since the full mapping; latest change: Bash history persistence across forced stops)

## Responsibilities

`android/setup` provides the guest system and its account setup:

- the distributions (Alpine from the APK, Debian downloaded), downloading and verifying tarballs,
  importing them into a new store, and writing first-boot configuration, which on Alpine makes
  bash the login shell;
- files refreshed before every kernel start (network interfaces, rootless podman's network, the
  bundled doas package, the profile script and the files of ash's, bash's and zsh's prompt marks for the
  terminal's shell integration), one-time OpenRC setup on Alpine, the osclock script run before boot and the boot log's failed services;
- package mirrors: built-in lists, concurrent throughput probes during import and one sources write in the
  temporary store, plus user-selected presets on a running system;
- resolving the login account and the per-tab login script (`login.sh`), including the first-login
  admin setup, installing the bundled bash, procps-ng and bubblewrap, and the NOPASSWD rules;
- the store lock shared by the kernel start, store deletion and the Files provider.

Root: `android/app/src/main/java/io/github/mthli/appine/Rootfs.kt`,
`android/app/src/main/assets/login.sh`, `android/app/src/main/assets/install-openrc.sh`, `android/app/src/main/assets/profile.sh`,
`android/app/src/main/assets/ashrc.sh`, `android/app/src/main/assets/bashrc.sh`,
`android/app/src/main/assets/zshrc.sh`, `android/app/src/main/assets/xdg-open.sh`, `android/app/src/main/assets/appine-gpu.sh`, `scripts/fetch-rootfs`.

Outside the module: the settings screen (`android/settings`); when imports, switches, restarts and
logins happen, and the `settings` preferences (`android/service`); the import and
`write_file`/`read_file` implementations (`kernel/store`, `kernel/vfs`); bundling the assets
(`android/build`); what the terminal does with the prompt marks (`android/vtcore`, `android/vtview`,
`android/vtgestures`) and the Kotlin session consuming their state and events (`android/vt`).

## Key types

- `Rootfs` — object: `Distro` (`ALPINE`, `DEBIAN`: label, download, mirrors, probe path),
  `Download` (urls, sha256, size), `Cancelled` and the private `Oversize` (both `IOException`s),
  `Account` (name, uid, gid, home from `/etc/passwd`), `StartPreparation` (`browserReady`,
  `openRcDone`), internal `StoreAccess` (session read/write abstraction), `access` (a `ReentrantReadWriteLock`).
  - `ALPINE`: labelled with the branch the APK bundles (`BuildConfig.ALPINE_BRANCH`); no download;
    mirrors are URLs ending in `/alpine` (dl-cdn, Tsinghua, USTC, Aliyun), probed at the branch's
    `main/aarch64/APKINDEX.tar.gz`.
  - `DEBIAN`: "Debian 13"; one pinned GitHub URL (debuerreotype's `debian:trixie`) with fixed
    length and sha256; mirrors are host names (deb.debian.org, Tsinghua, USTC, Aliyun), probed at
    `debian/dists/trixie/InRelease` over https.
  - `Mirror` provides the localized preset label and host for each distro.
  - The first mirror of each list is the official one: the importer uses it when no successful probe is ready.
- Preferences: file `rootfs`, owned here (`distro`); the `settings` file is `android/service`'s
  `Settings`, displayed and edited through `android/settings`.
- `login.sh` — `sh -c <script> appine-login <login> <cwd>`, run as root on each tab's console, or
  without a console with `--refresh-rules`.
- `profile.sh` — copied verbatim (comments included) to `/etc/profile.d/appine.sh`; `Rootfs` keeps
  unmarked version texts (`OLD_PROFILE`, `UNMARKED_PROFILE`) and later SHA-256s (`REPLACED_PROFILES`)
  for stock-profile detection.
- `ashrc.sh`, `bashrc.sh`, `zshrc.sh` — copied verbatim to `/etc/profile.d/appine.{ashrc,bashrc,zshrc}`
  (non-`.sh` so `/etc/profile` skips them directly) for shell prompt marks and titles: ash via `ENV`;
  bash via the profile, Alpine `/etc/bash/appine.sh` (`BASH_HOOK`), and Debian `/etc/bash.bashrc`
  (`DEBIAN_BASH_HOOK`, appended once); zsh via the profile (Alpine `/etc/zsh/zprofile`, Debian
  `/etc/appine/zsh/.zshenv` from `ZSHENV` selected via `ZDOTDIR` in `login.sh`) and `/etc/zsh/zshrc`
  (`ZSH_RC_HOOK`, installed once after `zsh` exists). `Rootfs.writeProfile(context, store, distro)`
  writes the three companions, `/etc/appine/zsh/.zshenv`, Alpine's `/etc/bash/appine.sh`, then the
  profile, and runs `ensureShellRcHooks`.
- Guest-side state: `/var/lib/appine/admins` (recorded admins), `/var/lib/appine/doas-installed`,
  `sudo-installed`, `bash-startup-done`, `debian-bashrc-hook-done` and `zsh-rc-hook-done`
  (markers), `/etc/doas.d/20-wheel.conf`, the NOPASSWD rules (`/etc/doas.d/30-appine.conf` or
  `/etc/sudoers.d/appine`), `/etc/appine/zsh/.zshenv`, `/var/cache/appine/doas.apk`,
  `/var/cache/appine/{bash,procps,bubblewrap,openrc}/` (packages until installed),
  `/run/appine-install.lock` and `/run/appine-rules.lock`.

## Public entry points

- `Rootfs.prepare(distro)` (with an optional store, used by the instrumentation tests), `download`,
  `fetch`, `tarball`, `deleteTarball`, `isReady`, `installed`, `importing`, `store`, `reset`, `access`.
- Before every start: `prepareStart` (one `Native.withStore` session running `ensureNetworkConfig`,
  `ensureContainersConfig`, `ensureLoginTools`, `ensureBashStartup`, `ensureGpuCommand`,
  `ensureBrowser`, `updateProfile`, and Alpine's `openrc-setup-done` precheck in order; fatal on
  fresh imports, independently degrading on existing stores; returns `StartPreparation`); around
  boot: `ensureOpenRc`, `boot`, `PREFER_OSCLOCK`, `failedServices`.
- Mirrors: the service supplies `probeMirrors` to `prepare`. The default imports offline with official sources when the tarball is local. Settings uses `packageSources` and `applyMirror` on the running machine.
- Accounts: `loginAccount`, `regularAccounts`, `LoginRules.poll` (with `REFRESH_RULES`).
- `scripts/fetch-rootfs [outdir]` (build-time download of the Alpine minirootfs and the bundled
  packages into `target/rootfs` by default; prints each package's path, then the tarball's last).
  `--assets` lists their pinned filenames, SHA-256 digests and APK destinations offline.
- `login.sh` arguments: login setting (`auto`, `root` or an account name) and an optional start
  directory; or `--refresh-rules`.

## Data flow / lifecycle

### Tarballs

- Alpine: `scripts/fetch-rootfs` downloads the bundled packages from the branch's `main`
  repository (doas, and bash with readline, libncursesw and ncurses-terminfo-base, each checked by
  its pinned sha256 only; also procps-ng, libproc2, libintl, utmps-libs and skalibs-libs, sharing
  the ncurses dependencies; also bubblewrap and libcap2, openrc, openrc-user, ifupdown-ng and bridge; apk checks signatures at install) and, unless a copy with the
  pinned sha256 is already there, the minirootfs: its GPG signature is
  checked when both gpg and gpgv are installed (a failed signature download counts as bad; without
  gpg a warning), then its pinned sha256. `--assets` lists bundle inputs offline. Gradle verifies those inputs and bundles
  them as `rootfs.tgz`, `doas.apk` and `{bash,procps,bubblewrap,openrc}/<file name>`.
  `BuildConfig.ALPINE_BRANCH` comes from the pinned tarball name. `prepare` copies the tarball asset to the cache.
- Debian (`download`): a cached `cacheDir/<distro>.tar.gz` with the pinned length and sha256 is
  reused; else `fetch` tries the sources in order (probed by speed when there are several; Debian
  has one), checking for cancellation before each, moving to the next on any error but a cancel,
  and throwing the last error.
- One source: 30 s timeouts; written to `.part` while hashing (64 KiB reads, cancellation checked
  before each, progress every 0.5%); an attempt that failed after getting data is resumed with
  `Range` up to 5 times, and one that got none ends the source. A resume answered 206 continues,
  one answered 200 starts again from zero, and any other status ends the source like an attempt
  that got no data. An attempt that broke off with the whole pinned length received is not resumed
  but verified at once (a `Range` from the end would be answered 416). More than the pinned length
  stops the source at once (no resume). Length and sha256
  are checked and `.part` is renamed into place; any other exit deletes `.part` (a kill can leave
  it), and every call starts from zero, truncating it, so nothing resumes across calls or kills. A
  206 reply's range is not checked; the final hash catches a wrong one.
- A switch's download can be cancelled (between reads; a blocked connect or read waits for its
  timeout); a download inside `prepare` cannot.

### Import

- `prepare` (does nothing for a ready store, one whose `meta.db` exists):
  1. for the app's store, commit the distro it imports (`distro`), the only record;
  2. delete `<store>.tmp`;
  3. get the tarball into the cache: Alpine's asset copied over it, unchecked; others through
     `download`;
  4. start the supplied mirror probes, import into `<store>.tmp` (`Native.importRootfs`),
     `configure` it and write package sources from the results available then;
  5. sync the store (`syncfs`), rename it to the real store, fsync `files/` (a failure only
     logged) and only then delete the tarball.
- `installed` is the recorded distro of a ready store (Alpine when there is no record); while
  there is no store, `importing` names the distro an import was last started for (Alpine when
  there is none), which the service imports at the next launch. `reset` deletes `rootfs.tmp` and
  the store under the write lock and leaves the preferences and the cache alone.
- `configure` writes, with mode 0644 for new files (an overwritten file keeps its mode, and an
  existing entry that is not a regular file fails the import):
  - always: `hostname` (`localhost`), `motd`, and
    `/etc/profile.d/appine.ashrc`, `/etc/profile.d/appine.bashrc`, `/etc/profile.d/appine.zshrc`, on Alpine
    `/etc/bash/appine.sh`, and then `/etc/profile.d/appine.sh` (the assets' `ashrc.sh`,
    `bashrc.sh`, `zshrc.sh` and `profile.sh`: motd, `TERM`, `LANG`, `NMAP_UNPRIVILEGED`, the browser default below,
    `ENV` naming the ash file unless one is set or the file is unreadable, and for bash, busybox ash and zsh only a
    coloured prompt with prompt marks, below; other shells keep the distro's prompt);
  - Alpine: `hosts` only when absent (the minirootfs ships one, so nothing is written), and no
    `resolv.conf` (the kernel writes one pointing at the DNS stub at every start when it is
    missing); bash as the login shell (`useBash`): the bundled packages from the assets' `bash/`
    into `/var/cache/appine/bash/`, and, when there were any, root's `/etc/passwd` shell changed
    from `/bin/sh` to `/bin/bash` (any other shell is kept) and `ensureBashStartup` run so
    `/etc/skel` and `/root` receive `.bash_profile` (sourcing `~/.bashrc`) and `.bashrc` (an
    interactive guard leaving `PS1` and history unchanged) and `/var/lib/appine/bash-startup-done`
    is recorded. Systems imported before keep busybox ash; accounts busybox's `adduser` makes
    later take the shell of `$SHELL` or of the account running it (so from a root tab they get
    bash too) and copy `/etc/skel`; procps-ng and its six dependencies are
    staged in `/var/cache/appine/procps/` by `cachePackages`, also used for bash and bubblewrap
    with libcap2 (`/var/cache/appine/bubblewrap/`). Existing stores do not receive these import-time files;
  - Debian: `hosts` and `resolv.conf` (the DNS stub) always.

### Before every start and boot

- `ensureGpuCommand` installs `/usr/local/bin/appine-gpu` (0755) when missing (called only when
  Vulkan GPU is enabled, skipping asset reads/writes if a regular file exists); it locates the
  packaged Alpine or Debian Venus manifest and execs the command with `VK_DRIVER_FILES` set.
- `ensureBrowser` installs `xdg-open.sh` as `/usr/local/bin/xdg-open` (0755) on either distro,
  preserving custom launchers (replacing only the legacy Appine OSC helper by SHA-256 and skipping
  reads/writes if a non-legacy regular file exists). Each start refreshes static
  `/usr/local/libexec/appine-open-url` (0755, skipping byte-identical writes) and `/etc/appine/browser`
  (0644: current loopback port and capability, shared by guest users). The shim execs that client;
  `ConsolePty` sets `BROWSER` (`android/vt`), and the login profile also exports it when unset/empty
  and executable (restoring discovery after `su -`, preserving nonempty custom values; interactive
  Debian login zsh gets it via `ZDOTDIR` shim; shells reading neither need own config). Each call
  rereads config and sends one HTTP(S) URL without TTY or tab token; success means Android intent
  acceptance, not page load or login.
- After import, `TerminalService.startKernel` calls `Rootfs.prepareStart` before opening the kernel
  under the provider write lock: one `Native.withStore` session runs network, containers, login
  tools, bash startup, GPU launcher (if enabled), browser endpoint/launcher, profile/rc-hooks, and
  Alpine's `openrc-setup-done` precheck. On fresh imports (`importing = true`), the first I/O error
  aborts startup. On existing stores (`importing = false`), each helper and independent sub-step
  (`doas.apk` vs `20-wheel.conf`, `/etc/skel` vs per-user homes, `appine-open-url`/`browser`/`xdg-open`,
  profile companions, Debian bash vs zsh hooks, or store open) catches `IOException`, reports to
  `onFailure` and continues so symlinks, custom files or full disk never block a repair terminal.
  `StartPreparation(browserReady, openRcDone)` lets `TerminalService` close an unconfigured
  `BrowserBridge` and skip `ensureOpenRc` on normal Alpine starts when `openrc-setup-done` is a
  regular file (absent/obstructed leaves `openRcDone` false). Unreadable one-time markers
  (`bash-startup-done`, `debian-bashrc-hook-done`, `zsh-rc-hook-done`) report to `onFailure` and skip
  migration rather than aborting or re-seeding user-deleted files; `ensureBashStartup` writes
  `bash-startup-done` only if every seed write succeeded. Network defaults to loopback-only and
  rootless Podman selects host networking (pasta/slirp4netns need TUN); both preserve existing files.
- Alpine refreshes bundled `doas.apk` (skipping byte-identical writes) and upgrades stock `20-wheel.conf`
  to preserve ash's `ENV`. Once per store, `ensureBashStartup` seeds missing startup files in `/etc/skel`
  and existing absolute homes for root and UID 1000–65533 bash accounts with valid ownership (existing
  `.bash_profile`/`.bash_login`/`.profile` take precedence; marker preserves later user deletions).
- `updateProfile` upgrades known stock profiles, writing companion scripts before the profile and
  preserving edited profiles; a readable edited profile still gets independent one-time rc hooks, while
  an unreadable/missing profile skips them. Direct `updateProfile` callers run Alpine's stock doas-rule
  migration first (`prepareStart` skips the duplicate since `ensureLoginTools` already ran it).
- Storage is left to the packages: podman's overlay driver and docker's (containerd's) overlayfs
  snapshotter run on the kernel's overlayfs, rootful and rootless. A `storage.conf` choosing vfs or
  a `daemon.json` choosing native that an earlier build wrote stays until the user removes it.
- On Alpine, `ensureOpenRc` checks `/var/lib/appine/openrc-setup-done`. Unmarked stores check apk
  offline: if OpenRC is already installed (and no staged `/var/cache/appine/install-openrc.sh` from
  an interrupted install or older app exists), it writes only the marker and leaves boot async.
  Otherwise it rebuilds its private package cache from APK OpenRC + bubblewrap's `libcap2`, installs
  missing deps via a temporary virtual package, records `openrc` in world, removes the virtual package,
  runs inittab before tabs open, verifies the lock dir and default runlevel, deletes cache/installer,
  and writes the marker (later removal persists). Migration failure on existing stores reports the
  error, allows repair login, and retries next start; on fresh imports it propagates.
- Every boot runs `PREFER_OSCLOCK` without a console: adds `osclock` to the `boot` runlevel when
  `rc-update` and `osclock` exist and no runlevel has `hwclock`/`osclock`/`swclock` (dangling link
  counts); thread waits, ignores exit status, logs exceptions only. On subsequent boots tabs can log
  in while inittab runs. Afterward, `failedServices` lists services `/var/log/boot.log` marks failed
  for the service notification.

### Prompt marks

- Integrated bash, ash and zsh set OSC 2 to full `$PWD` (control chars → `?`) at each primary prompt
  (bash in `__appine_prompt`, zsh in precmd, ash after visible PS1 + B; secondary prompts do not
  replace it). Before execution, bash and zsh set OSC 2 to the sanitized command line once per
  pipeline/compound command; for ash (no preexec) and unmarked shells, `android/vtcore` falls back to
  the foreground process cmdline when no OSC 0/2 is set. Programs may override OSC 0/2 between prompts
  until the next primary prompt restores `$PWD`. Bash appends `$(__appine_preexec)` to `PS0` once,
  snapshots `HISTCMD` at each prompt, and reads `history 1` without timestamp only when history is
  enabled and `HISTCMD` advanced (else reports `bash`, e.g. ignored duplicate/space-prefixed/
  `HISTIGNORE`/disabled history), without a `DEBUG` trap; zsh uses preexec's
  command arg directly. Tab bar still shows numbers (titles appear via long press / TalkBack). Existing
  shells keep loaded hooks until restarted or full profile reloaded; sourcing `ashrc` alone into an
  already-marked old PS1 skips its guard.
- For `android/vtcore` shell integration, login shells mark prompts with OSC 133:
  - bash: profile sets coloured PS1 and reads `appine.bashrc`, putting `__appine_prompt` first in
    `PROMPT_COMMAND` (scalar or bash 5.1+ array, once per load). Interactive shells enable `histappend`
    so tabs append on exit. At each primary prompt `history -a` appends the session's new entries
    when history is enabled, `HISTFILE` is nonempty and `HISTFILESIZE` is not zero. A new shell can
    recall those entries after a force-stop. Before each prompt the hook emits D with
    exit status and A (`redraw=last;cl=line`) on a fresh line, wraps PS1 (P `k=i`, `k=c` after `\n`
    escapes and command-substitution-free literal newlines, … B) and PS2 (`k=s`), and appends C to
    `PS0` whenever missing (re-wrapping PS1 set later by `~/.bashrc`; plain `PROMPT_COMMAND`
    assignment drops it). Nested interactive bash (`bash`, `su` without `-l`) loads `appine.bashrc`
    via Alpine `/etc/bash/appine.sh` (from `/etc/bash/bashrc`) or Debian's one-time `DEBIAN_BASH_HOOK`
    in `/etc/bash.bashrc` (gated by `/var/lib/appine/debian-bashrc-hook-done`).
  - busybox ash (1.37 has no `PROMPT_COMMAND` or preexec): PS1 embeds D with `$?` (expanded on each
    prompt/redraw), A with `cl=line`, `P;k=c` with `appine_lf=output` after `\n` escapes in multi-line
    prompts, and trailing B; PS2 uses P `k=s` + B. Because ash emits no C and wraps long commands
    itself, A and P set `appine_lf=output` so `android/vtcore` distinguishes ash line-wraps (continue
    input) from other line feeds (end input). `__appine_ash_path` formats physical dir (abbreviated
    under `HOME`) for stock PS1 and full logical `PWD` for title, replacing controls with `?` and
    doubling backslashes before BusyBox prompt expansion (custom prompts keep their formatting). Wraps
    unmarked PS1/PS2 only in busybox ash; sourced by profile after PS1 and via `ENV` for every
    interactive ash (login shells after `/etc/profile` and `~/.profile`, marking `~/.profile` PS1s;
    later or custom-`ENV` PS1s go unmarked).
  - zsh: profile sets coloured PS1 and reads `appine.zshrc`. On Debian (`/etc/zsh/zprofile` skips
    `/etc/profile`), `login.sh` launches login `zsh` with `ZDOTDIR=/etc/appine/zsh` when unset;
    `/etc/appine/zsh/.zshenv` unsets `ZDOTDIR`, sources `~/.zshenv`, and on non-Alpine interactive
    login shells sources `/etc/profile.d/appine.sh` in sh emulation (`__appine_profile_seen` prevents
    double `/etc/motd` if `~/.zprofile` also sources it). Once `zsh` is installed, `ensureShellRcHooks`
    appends/writes `ZSH_RC_HOOK` in `/etc/zsh/zshrc` once (gated by `/var/lib/appine/zsh-rc-hook-done`)
    for nested interactive `zsh`. `__appine_precmd` (kept last in `precmd_functions`) emits D with
    status once per prompt, restores saved clean prompts, and wraps `PS1` (`A;cl=line`, `k=c` after
    `$'\n'`, … B), `PS2` (`k=s`, `k=c` after `$'\n'`, … B), non-empty `RPROMPT` (`k=r` single-line /
    `k=c` multi-line, … B) and `RPS2` (`k=s` … B) in `%{ %}` (doubling trailing bare `%`, or emitting
    `A;cl=line` directly when `prompt_percent` is unset / `nopromptpercent`). `__appine_preexec` (`preexec_functions`) restores
    clean prompts and emits C + command title. Reloading deduplicates both hooks while preserving
    others (even in sh emulation); zle redraws re-emit A and B from PS1.
  - `cl=line` tells the terminal that the three line editors move the cursor with the left and
    right arrows, so a tap on the typed command moves the cursor there.
- Every tab login shell reads the profile (root `-l`; others `su <account> -c … exec "$SHELL" -l`,
  including Debian zsh via `.zshenv`). `ENV` is exported from profile and `appine.ashrc` (when unset)
  and pinned via `setenv { ENV=/etc/profile.d/appine.ashrc }` in Alpine's `20-wheel.conf`/`30-appine.conf`
  so nested interactive ash (`ash`, Alpine `sh`, `su`, `doas -s`/`doas sh`) keeps marks. Nested
  non-login `bash` and `zsh` (once `ensureShellRcHooks` runs) also get marks. Other shells get none
  (fish/nushell emit their own; `android/vtcore` falls back to foreground-process detection for
  unmarked shells and overwritten ash PS1); shells skipping the profile still inherit `TERM`, `LANG`,
  `NMAP_UNPRIVILEGED` from the console (`android/vt`), without motd.
- The shared profile avoids zsh array syntax; only zsh sources `appine.zshrc`, whose anonymous
  registration function and hooks use `emulate -L zsh` so Alpine sh emulation does not alter array
  filtering/indexing and caller options are restored.

### Mirrors

- After obtaining the tarball, `prepare` starts the service's `probeMirrors` measurements in
  distro list order, one task per mirror. Each measures throughput, checking the 512 KiB and
  5 s thresholds between reads. The import proceeds while these tasks run.
- After local configuration, the importer chooses the highest completed successful speed, breaking
  ties in distro list order and falling back to the official mirror if no speed is available.
  It writes Alpine's `main` and `community` to `/etc/apk/repositories`, or Debian's deb822
  HTTP sources for trixie, updates and security to `/etc/apt/sources.list.d/debian.sources`.
- The importer requests cancellation of outstanding probes before syncing and renaming the
  temporary store. Probe tasks only measure throughput. A ready store skips the entire import,
  so users can edit or remove sources in the guest.
- On demand, Settings inspects the source file through a guest shell. An exact SHA-256 match identifies
  a preset. Missing files and symlinks have distinct fingerprints and count as custom.
- Manual selection stages the import template beside the destination with mode 0644, rechecks the
  displayed fingerprint and renames it over the destination. Replacing a symlink preserves its target.
  Rename publishes a complete file. A concurrent guest editor can still write between the fingerprint
  check and rename.

### Accounts and login

- `loginAccount`: `auto` is the regular account (UID 1000–65533, a shell other than empty,
  `nologin` or `false`) with the lowest UID, else root; `root`; or a named passwd entry, falling
  back to root. `regularAccounts` lists the same accounts for `android/settings`. `login.sh` picks
  the account by the same rules, except that it does not require a numeric GID or all seven fields.
- `login.sh` ignores `SIGINT` (`trap '' INT`) by default so Ctrl-C cannot disrupt short setup
  commands (passwd lookup, group checks, shadow reads, rules publication, or the `su --help`
  probe), temporarily catches `SIGINT` (`trap 'interrupted=1' INT`, restoring default disposition
  in child processes) only around cancellable package-lock waits (`flock 9` in `locked`, `apk add
  --wait 3600`) and Debian `apt-get` update/sleep/install calls, and restores `trap - INT` right
  before `exec` into the user shell or `su`. It chooses wheel, doas and
  `/etc/doas.d/30-appine.conf` on Alpine (`/etc/alpine-release`), else sudo, sudo and
  `/etc/sudoers.d/appine`, picks the account, and:
  - every login, root's included: rewrite the NOPASSWD rules for recorded admins still in the
    group that have no password: a shadow field that, after any leading `!`s, is empty or `*`
    (`adduser -D` writes `!`); `passwd -l`'s `!` before a hash still counts as a password, and so
    do a missing shadow entry and an unreadable `/etc/shadow`. Duplicate or blank rows in
    `/var/lib/appine/admins` are skipped defensively. The file is written beside it,
    made 0440 and renamed into place; with nobody qualifying, or without the rules' directory
    (on Debian, until sudo is installed), it is deleted;
  - the first service poll for each machine runs `--refresh-rules` as root without a console.
    Later polls compare the inode, size, mtime and ctime of `/etc`, `/etc/passwd`, `/etc/group`,
    `/etc/shadow` and `/var/lib/appine/admins`. A changed or unavailable sample triggers
    another refresh. The worker starts the process and a separate thread waits for its exit.
    While the waiter is alive, polling starts no second refresh. A successful exit stores the
    sample taken before spawn. The next poll samples again. A missing sample always retries;
  - before choosing the account: install procps-ng, then bubblewrap offline when their cache directories
    exist, under the install lock, testing `apk info -e <package>` (BusyBox already provides ps/top/free),
    re-add each by name for upgrades, and delete its cache under the lock only on success. Failure
    leaves the cache for the next login and still opens a shell; later user removal is respected.
    This works with any login shell; `--refresh-rules` returns before installing;
  - every login whose passwd shell is `/bin/bash` while `/var/cache/appine/bash` exists: under
    the install lock, rechecks the bundle, installs offline if `! apk info -e bash` or
    `[ ! -x /bin/bash ]`, adds `bash` by name (world + `apk upgrade`), and deletes the bundle only on
    success (respecting later removal). If world registration fails after local install, still execs
    `/bin/bash` and retries next login; if `/bin/bash` is missing, falls back to `/bin/sh` and retries;
  - root: `cd` to the directory (else root's home, else `/`) and `exec` root's passwd shell (or
    `/bin/sh` when it is not executable) as a login shell;
  - any non-root account absent from `/var/lib/appine/admins` (literal match): under
    `/run/appine-rules.lock`, rechecks `admins`, adds to `wheel` (Alpine: atomically writes
    `permit persist setenv { ENV=/etc/profile.d/appine.ashrc } :wheel` to `20-wheel.conf` when absent
    or unmodified stock `permit persist :wheel`, or appends once to a custom file lacking both;
    `refresh_rules` and `Rootfs.ensureLoginTools`/`updateProfile` also upgrade stock) or `sudo`
    (Debian), and appends to `admins` once in the group (else warns and retries next login);
  - any non-root account while its marker is missing: on Alpine (`/var/lib/appine/doas-installed`),
    under the install lock, rechecks marker, installs `/var/cache/appine/doas.apk` if `doas` not on
    PATH, runs `apk_add doas` if just installed or apk-managed (`apk info -e doas`, leaving custom
    non-apk `doas` alone), and touches marker only on success (retrying partial installs, respecting
    later removal); on Debian (`/var/lib/appine/sudo-installed`), if `sudo` not on PATH, installs from
    network with apt progress (Ctrl-C skips until next login; no install lock; `apt_update` retries
    every 2 s on package-lists lock, printing once and showing other errors/warnings after; `apt-get
    install` waits up to 1 h via `DPkg::Lock::Timeout`), then touches marker once `sudo` is on PATH;
  - the install lock: a `flock` on `/run/appine-install.lock` held around each install of a bundled
    package (`locked` runs `cancellable flock 9 && "$@"` so commands never run if acquiring the lock
    fails), so concurrent logins install bash, doas, procps-ng and bubblewrap one after another,
    each first rechecking completion state under the lock; each `apk add` (`apk_add`) that fails
    on apk's own lock, held by an apk the user runs, prints that another apk is running and is
    retried with `--wait 3600`, so the login waits until that apk ends; Ctrl-C stops the wait and
    skips the install until the next login;
  - switch with `su <account> -c '<cd dir or home>; exec "$SHELL" -l'` (`--session-command` when
    util-linux su offers it), adding `-s /bin/sh` when the account's shell is not executable (su
    would otherwise fail and the tab close); package commands read from `/dev/null` so early typing
    reaches the shell.

## Dependencies (inbound / outbound)

### Inbound

- `TerminalService` (`android/service`: start, import, switch, reset, the mirror probe, per-start
  files, boot, the poll's `LoginRules`, login accounts), `SettingsFragment` (`android/settings`:
  `Distro`, `installed`, `regularAccounts`, `loginAccount`, package source inspection and selection), `Documents` (`android/documents`:
  `loginAccount`, `installed`, `isReady`, `access`), `ConsolePty` (`android/vt`: runs `login.sh`),
  the instrumentation tests (`fetch`, `prepare` into another store with supplied probe results, `updateProfile`,
  `PREFER_OSCLOCK`).

### Outbound

- `Native.importRootfs`, `syncRootfs`, `withStore`, `storeWriteFile`, `storeReadFile`,
  `writeFile` (creates parent directories, does not follow symlinks), `readFile` (null when
  missing), `loginRuleInputs`, `spawn` and `waitFor` (`android/native`);
  `HttpURLConnection` (system proxy); `BuildConfig` (Alpine branch); the bundled assets
  (`android/build`).

## Invariants and gotchas

- Reset advances the in-process rootfs generation under the write lock. Terminal imports check
  this generation before publishing a staged file.
- `Rootfs.access`: provider calls hold the read lock; starting the kernel and deleting the store
  hold the write lock, so the kernel's exclusive boot open never overlaps a provider open. The
  import (including package sources in the temporary store) and the per-start files run without it.
- A store becomes ready only when the synced import is renamed into place, so a half-imported
  `rootfs.tmp` never counts and is deleted by the next `prepare` or `reset`.
- An import interrupted by a kill (a first run, a distro switch, a reset) is redone with the same
  distro on the next launch, from the verified tarball still in the cache or by downloading it
  again with the usual progress; if that fails and the service falls back to Alpine,
  `Rootfs.deleteTarball` deletes the abandoned cached tarball (and any `.part` file) before
  starting the Alpine fallback import.
- Current imports into the default app store commit the distro record before importing.
  Legacy stores without the preference default to Alpine. An import into a separate test store
  leaves the preference alone but still uses and deletes the shared cached tarball.
- `writeFile` while the kernel runs is seen at once (the store's commit counter invalidates kernel
  caches).
- `updateProfile` replaces only the versions it knows, compared whole (`OLD_PROFILE` and
  `UNMARKED_PROFILE` by text, later ones by sha256 in `REPLACED_PROFILES`): changing `profile.sh`
  must add the sha256 of the text it replaces (the committed asset, byte for byte), or stores keep
  the version they have; `ashrc.sh`, `bashrc.sh` and `zshrc.sh` are written only with the profile,
  so a change to any companion alone reaches no existing store (change `profile.sh` with it); and
  the motd is written only at import, so it is never updated.
- apk takes its database lock without waiting unless given `--wait` (which apk 3 accepts though
  its help does not list it): hence the install lock and the retry with `--wait`; a tab whose login
  waits for an apk the user runs shows only that line until the apk ends or Ctrl-C. apt 3's
  `DPkg::Lock::Timeout` covers dpkg's frontend lock but not the package lists' lock, which
  `apt-get update` takes, hence the retries there; apt shows its own wait only on a terminal. The
  branch's mirrors keep only each package's latest build, so a rebuild of a pinned package makes a
  fresh `scripts/fetch-rootfs` fail until it is pinned anew.
- The marks rely on the shell redrawing its prompt on SIGWINCH (the terminal clears the prompt's
  rows on a resize; `android/vtcore`): bash, busybox ash and zsh do.
- `ensure_admin` and `refresh_rules` share root-only `/run/appine-rules.lock` (never nested with
  `/run/appine-install.lock`; both created under `umask 077`, with `ensure_admin` and `locked`
  restoring caller umask before modifying files/running commands). `refresh_rules` holds the lock
  across account reads through publication/removal, deduplicates `/var/lib/appine/admins`, and tries
  to remove stale grants on publication failure. Reading accounts and publishing in the rules subdir
  do not alter sampled inputs; service polls every 2 s independently of Files app notifications.
- `login.sh` runs without job control (its children share its process group), so `SIGINT` stays
  ignored except inside `cancellable` waits/installs (`locked` uses a braced group rather than a
  subshell so `interrupted=1` propagates while fd 9 still closes on exit) and is reset with
  `trap - INT` immediately before every `exec`.

## Confirmed bugs / technical debt

None.

## Open questions

None.

## To verify

- Alpine `.bash_profile`/`.bashrc` seeding on 16 KB-page devices and live `TerminalService` upgrade cold starts.
- Android device execution and end-to-end migration of the extracted `zshrc.sh` companion.
- Browser defaults and Codex authorization flow on Android (existing stores get profile updates on restart only if unmodified).
- Shell command titles with custom prompt frameworks, history changes in later `PROMPT_COMMAND` entries, and live upgrades of already-running shells.
- Alpine doas lexical ordering of `/etc/doas.d/*.conf` (`30-appine.conf` after `20-wheel.conf`), doas reading only `*.conf`, and sudo skipping dotted filenames (relied on by temporary rules files).
- Debian in-app bash marks and prompt tools that rewrite `PS1` after `__appine_prompt` each time.
- In-app zsh marks with third-party prompt frameworks (oh-my-zsh, powerlevel10k).
