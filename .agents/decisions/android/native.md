# JNI bridge and build (android/native) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: android/native"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12). D1–D4 were seeded by hand from the retired design
> doc §11 and the round records in `docs/rounds/`, so their sources cite rounds; later entries cite
> commits.

## Constraint index (compressed active decisions)

> Full text of D11, D17, D18: `git show 5bd37526030bd7d7f70d048c38917b27d83f8c4b:.agents/decisions/android/native.md`

- **D11** — parse_route skips non-ASCII hex fields rather than byte-slicing into a panic, and D15 now turns a JNI panic into a failure result rather than aborting the app (09188ec, 39e55b4b)
- **D17** — takeKills appends cleaned Bug::stubs paths joined by U+001E for service reports, representing only paths observed at death, and MachineTest does not check the field (99b4216 (with android/service D6), 39e55b4b)
- **D18** — Native.bklStall reads the latest watchdog stall without taking the BKL, returns the tab-delimited id/seconds/report/end record or null under guarded!, and its format must stay aligned with TerminalService.reportStall (64a6107 (with android/service D18), 39e55b4b)

> Full text of D7, D13, D14, D21, D22: `git show c4db5db249c93e2e122d88ef60153f67c94a1f62:.agents/decisions/android/native.md`

- **D7** — `com.google.android.material:material:1.14.0` stays a direct version-catalog dependency with AppCompat, `androidx.activity` and CoordinatorLayout transitive, and upgrades must manually review static notices without an allow-list check (55be399; ef7a803; bd580227; d053be24; 39e55b4b)
- **D13** — Fuchsia Starnix's BSD license ships in the static catalog backed by `android/licenses` and checked by `LicenseTest`, so changes to Starnix-derived `tty.rs` code must update the static notice manually (f2d3264; bd580227; d053be24; 39e55b4b)
- **D14** — Static Appine notices and catalog entries use capitalized `Appine` (while asset IDs/paths stay lowercase), and instrumentation asserts `Welcome to Appine` and the `Appine` component lookup (3f04bf5; bd580227; d053be24; 39e55b4b)
- **D21** — Static catalog subtitles keep license names and qualifiers without descriptive suffixes across locales, leaving descriptions and Appine's source URL in details (d35669e1; 20137e4c; bd580227; d053be24; 39e55b4b)
- **D22** — `androidx.preference:preference:1.2.1` is a direct non-KTX catalog dependency whose upgrades must stay compatible with Material/AppCompat and pass notice and lint checks (f8822b8b; 20137e4c; bd580227; d053be24; 39e55b4b)

> Full text of D35: `git show 3ad68857ee83ca9bb227d4d55db8611416ae226b:.agents/decisions/android/native.md`

- **D35** — Native.writeFile uses one JNI symbol and Kotlin zero defaults for optional uid/gid (no Java five-argument overload), and callers must reject negatives before Rust converts to u32 (125f3d00)

> Full text of D2, D3, D8, D9, D10, D16, D19, D20, D23, D27, D29, D30, D31, D32: `git show 1ea0c1ef9d686d7126bec2029fc928d4f6fdfc6b:.agents/decisions/android/native.md`

- **D2** — Debug APKs build `arm64-v8a` with the release profile plus `test-hooks` and `borrow-check` while release APKs use `android-release` without `test-hooks` (checked in `.so`, `android/build` D5), so Rust debug assertions never run on Android (rounds 07 (`07-m5`), 21 (`21-robustness-finish`), 89 (`89-release-borrow-check`); 7b3e57c5)
- **D3** — The APK targets `arm64-v8a` only (minSdk 30, target 37) with `useLegacyPackaging = false` for 16 KB-aligned uncompressed `.so` mapping and `rootfs.tgz` to prevent AAPT unpacking, linking API 30 bionic so newer libc functions fail `dlopen` on older devices (round 07 (`07-m5`); 0709982)
- **D8** — Instrumentation verifies pinned-length download caps (`file:` URLs only), homeless/`/` DocumentsProvider accounts (needing distinct login settings to avoid cached homes), and repeated saved-tab reads before kernel start assuming transcripts are the only `.txt` files in `files/tabs` (544418f, 711b034, d5dbe29; 7c35183)
- **D9** — `Native.kt` and `hosts/android/src/lib.rs` follow a kernel's lifecycle (`MACHINES` opening the kernel section, helpers last) and `MachineTest` groups by app flow, so new JNI functions must be placed in matching order in both files (a546fea, 55d75ac)
- **D10** — `kotlin.compilerOptions.allWarningsAsErrors` and `lint.warningsAsErrors` fail local and CI builds (keeping five external-data lint checks informational), so SDK/AGP/library upgrades or dropped informational issue IDs can surface new build failures (4c1a3ce)
- **D16** — `Native.start` passes `exception_trace: true` for every kernel start so unhandled guest faults reach dmesg bug reports without a UI toggle, and `MachineTest.guestCrashesReachTheKernelLog` expects the `undefined[400000+1000]` mapping layout from `elf()` (7b7f73b)
- **D19** — Prompt-mark instrumentation checks explicit ash B/continuation marks, customized/nested ash, and fresh Alpine bash imports while stripping controls for text checks, so raw-byte assertions must track profile updates and isolated runs need the app stopped to free the store lock (6c5f921, 2b04a43; a809c5a8; 7aa817af; 20137e4c; d053be24; 39e55b4b)
- **D20** — `build.gradle.kts` stays one file (configuration before task classes, which must not capture script-level members) with a libraries-only version catalog (`AGP` separate), `newestFetched` inputs, and static notices replacing `NoticesTask` (D24) (ef7a803; bd580227; d053be24; 39e55b4b)
- **D23** — `Native.guestCpuTime` exposes a guarded guest-only CPU aggregate (-1 when unavailable, conservatively preserving wakefulness) with both-tier/idle/stopped test coverage on an API 34 emulator only, leaving physical hardware and 16 KB pages unverified (1e5de64e; 20137e4c; d053be24; 39e55b4b)
- **D27** — `TerminalInputTest` uses an isolated rootfs, shell-free session (supplied via reflection), and capturing host to verify real IME encoding, confirmation dialogs, and paste handling, leaving real keyboard UI, physical devices, and 16 KB pages unverified (6f5be4da; d053be24; 39e55b4b)
- **D29** — `TerminalFilesTest` runs seven arm64 bundled-rootfs cases covering copies, collisions, sanitization, home confinement, failures, literal shell arguments, and locked-picker retention, leaving cloud/vendor pickers, physical devices, and process interruption unverified (165b5f99; d053be24; 39e55b4b)
- **D30** — Seven isolated view-pixel tests (pre-split, now `android/tests`) verify resize-preview release and detach cleanup via direct layout/draw calls, leaving hardware composition and vendor IME timing unverified (2e73a06a; 39e55b4b)
- **D31** — Isolated bash/ash instrumentation (`ShellTitleTest`, now `android/tests`) and host zsh PTY checks verify PWD restoration, command titles, history fallback, and reload safety, leaving custom prompt frameworks, live upgrades, and other device configurations uncovered (b04ebd4e, 9f9ba8d7; 39e55b4b)
- **D32** — RecyclerView 1.2.1 stays a direct catalog dependency (upgrades must update static notices) with three shell-free title-list tests for identity, 50-tab viewport, and focus (real IME/vendor behavior unproven; owned by `android/build` and `android/tests`) (34348eda; 39e55b4b)

## Active (full detail)

### D1: Kernels cross JNI as handles into a table
- **What**: Kotlin holds a numeric handle; each call looks up the `Arc<Machine>` in a global table and clones it; `stop` and `shutdown` remove the entry, after which calls find nothing and return sentinels or throw.
- **Why**: The first design passed an `Arc` raw pointer and required Kotlin not to use it after `stop`; on a reset, the main thread closed a console after the worker had already freed the kernel and hung on a freed lock.
- **Tradeoffs**: Every call takes the table lock briefly.
- **Watch out**: Drop the table guard before dropping the last `Arc`, since `Machine`'s drop kills processes and joins threads: bind a removed or replaced entry and drop it after the statement, because a temporary is dropped while the statement's guard is still held.
- **Source**: rounds 07 (`07-m5`), 99 (`99-choose-rootfs`); 7f79758

### D4: Released sources are generated by a script, without running downloaded build code
- **What**: `scripts/release-sources` bundles Appine at clean HEAD, vendored crates, aports/checksum-verified upstream sources for GPL origins found in the rootfs and bundled APK metadata, and libghostty-vt’s published source/dependency tarballs without evaluating downloaded build code.
- **Why**: Under GPLv3/AGPLv3 the APK must come with the complete corresponding source, including statically linked crates, and the bundled busybox, apk-tools and other GPL packages need their sources too; evaluating an APKBUILD's `source=` would mean running downloaded shell code, so the files are found through its `sha512sums` instead.
- **Tradeoffs**: Whether the repository is published or a written offer accompanies the APK is still the user's decision.
- **Watch out**: Gradle chooses the newest fetched artifacts while release-sources follows fetch-rootfs pins, so stale local packages can mismatch the archive; fixed aports assumptions and whole-workspace vendoring remain recorded gaps, and the full archive was not generated for the procps or bubblewrap additions.
- **Source**: rounds 192 (`192-license`), 200 (`200-release-sources`); b38d29c; d9e1f0c (gaps recorded); 172be7c; 7aa817af; 410ef192; 20137e4c; 0815b3e4; d053be24; 39e55b4b

### D5: Runtime JNI is separate from Android packaging and instrumentation
- **What**: android/native owns runtime JNI, kernel handles, application initialization and the guest browser entry point, while build configuration and release packaging belong to android/build and instrumentation source to android/tests.
- **Why**: Separating independently maintained subsystems makes the oversized parent map practical to read and maintain.
- **Tradeoffs**: Cross-boundary work follows child maps, while historical decisions keep their original parent tags and stable IDs.
- **Watch out**: The full refresh d9e1f0c recorded open debts: the release build is signed with the debug key and unminified, lib.rs's array loops never delete local references, and the release source bundles have gaps (D4) that matter for AGPL compliance once the APK ships; both risks recorded at the first mapping are fixed (D1, D15); these pre-split qualifications remain discoverable through the child maps and are not resolved by the ownership split.
- **Source**: 12c4afa, 16ac7a2; d9e1f0c (bug list); ef7a803 (versionName 1.0.0, unrecorded); 67d40c70 (version correction); f61072f3; bd580227; 9c6c51a7; 9015b244, 8a17e4a9, 81f60bef; d053be24; 60790c14; 39e55b4b

### D6: libghostty-vt 1.0.0 is built offline with Zig into every Android library and shipped with its notices and sources
- **What**: `scripts/fetch-ghostty` downloads libghostty-vt-1.0.0.tar.gz and its `-deps` tarball with pinned sha256s and runs no Zig; for Android targets build.rs runs `zig build -Demit-lib-vt -Dcpu=baseline -Doptimize=ReleaseFast --system <src>-deps` (Zig 0.17.0) with a global cache of its own in the source (`.zig-global-cache`), adds the Wuffs C directory found by pkg/wuffs's `build.zig.zon` hash to vt.c's include path, and links libghostty-vt.a, vt.c and the system's libjnigraphics into libappine.so; the notices list libghostty-vt 1.0.0, uucode (with the Unicode and Hoehrmann licences), simdutf, Highway, Wuffs, translate-c 2.0.0, Zig's compiler_rt and JetBrains Mono from copies in `android/licenses`.
- **Why**: libghostty-vt (android/terminal D5) now ships as its own release with a stable C ABI, a signed source tarball and a dependency tarball for offline builds, while the app had built it from the whole Ghostty 1.4.0 release with packages gathered by the build's configure phase; it has to end up in libappine.so with the notices and released sources (D4) covering it, and vt.c decodes Kitty PNGs with the linked Wuffs and copies pixels into Android bitmaps.
- **Tradeoffs**: Every Android build needs Zig 0.17.0, and the first build after an upgrade compiles Zig's own artifacts into the new cache (about 2 min); only sha256s are checked, the signatures by hand; an `APPINE_GHOSTTY_SRC` must be writable; the build relies on Ghostty's internal Wuffs package name, the deps tarball's layout and `wuffs-v0.4.c`; licence copies and versions are kept by hand.
- **Watch out**: A global cache that ever held a package archive (such as `~/.cache/zig`) makes Zig 0.17 panic with "reached unreachable code", so the build must keep its own; an upgrade can change the C API silently, need a newer Zig or add linked components the notices miss (list libghostty-vt.a's objects again); build.rs reruns only on vt.c, the source's `LIB_VERSION` and `build.zig.zon` and its environment variables, not when pkg/wuffs's `.zon` changes; the notices test now expects libghostty-vt and translate-c titles, while shared app state or stale emulator stores can still break login tests.
- **Source**: 394aecf, b38d29c, 8811c32, 172be7c; a1367531; 20137e4c; d053be24; 39e55b4b

### D12: Builds package independent offline Alpine bundles
- **What**: The pinned input inventory selects fetched artifacts (android/build), requiring four bash, seven procps and two bubblewrap/libcap2 assets in independent bundles, with shared ncurses payloads duplicated but attribution counted once and package metadata still driving release-source discovery.
- **Why**: Each offline login bundle needs complete dependencies independent of the selected shell or other installers.
- **Tradeoffs**: Bash adds about 0.86 MB, procps about 0.62 MB and bubblewrap/libcap2 53,886 compressed bytes, while notices are now updated manually.
- **Watch out**: Preserve failure/retry, concurrent offline login, independent-install, user-removal and sandbox tests, with full source-archive and 16 KB-page validation still outstanding.
- **Source**: b014e84; 7aa817af; 410ef192; 20137e4c; bd580227, 0815b3e4; d053be24; 39e55b4b

### D15: Every JNI function catches panics
- **What**: `guarded!(name, [throws env,] fallback, { body })` runs each JNI body in `catch_unwind` with its parameters moved in; a panic logs "internal error in <function>" after the hook's message and returns a failure value (-1 for console I/O and int results, -9 from waitFor, 0 from processes and takePower, null for objects, nothing for unit), and the 17 functions Kotlin declares `@Throws` also throw `IOException` unless an exception is pending; the `VtNative` functions use it without throwing (android/terminal D35).
- **Why**: The JNI layer had no `catch_unwind`, so a panic in an unwrapped kernel-layer method or in the bridge itself aborted the whole app (D5's Watch out, an open question in the map).
- **Tradeoffs**: Kernel state a panic left half done (a poisoned lock, a held BKL) is not repaired, so later calls on that machine may fail or wait; Kotlin callers of non-throwing functions see an ordinary failure (an ended console or process) rather than a kernel-bug report.
- **Watch out**: A new JNI function written without `guarded!` aborts again on a panic, and `return` inside a guarded body returns only from the body.
- **Source**: be72f31

### D24: Notices are checked-in catalog and HTML assets with isolated reader tests

- **What**: The APK packages a static JSON catalog, body HTML and shared CSS with upstream references, replacing NoticesTask, generated assets and the allow-list; Browser 1.10.0 and WebKit 1.18.0 are catalog dependencies and LicenseTest covers offline reading/restoration.
- **Why**: Notice content must be explicit repository resources while new browsing and reading APIs remain directly declared.
- **Tradeoffs**: Automatic dependency discovery is gone, so dependency and bundled-package updates must manually synchronize versions, attribution and complete texts.
- **Watch out**: Tests establish asset coverage rather than license correctness; keep resolved dependencies and chosen rootfs/package artifacts aligned with the static inventory.
- **Source**: d0a74e94, bd580227, 0815b3e4; d053be24; 39e55b4b

### D25: A static musl guest client implements browser requests

- **What**: Zig builds an aarch64-linux-musl client that rereads configuration for each call, bounds network waits to five seconds and requires acknowledgment, with packaged notices and integration tests.
- **Why**: Browser requests need reported failures without curl, netcat or guest dynamic-libc assumptions.
- **Tradeoffs**: The APK build gains an executable and a small transport protocol.
- **Watch out**: Timeout means no acknowledgment rather than proof of no launch; Debian and 16 KB-page runtime checks remain outstanding.
- **Source**: 8e211142; d053be24; 39e55b4b

### D26: Privacy registration and provider tests cover the access boundary

- **What**: MainApplication initializes the separate privacy controller with Biometric 1.1.0 and its notice, registers non-exported report targets and tests lifecycle, secure dialogs, action routing, locked clipboard denial and provider consent/revocation.
- **Why**: The shared gate needs an application entry point and regression coverage across notification and document surfaces.
- **Tradeoffs**: Tests use shared preferences/store setup and explicit transient grants, while authentication remains outside the application entry point.
- **Watch out**: API 34 emulator results do not cover real biometrics, vendor windows, persisted picker grants or 16 KB-page devices.
- **Source**: d0ddf170, f61072f3; d053be24; 39e55b4b

### D28: Diagnostics initialize at application startup and share only scoped archives

- **What**: MainApplication installs Diagnostics and a non-exported FileProvider exposes only the share directory through read-only grants, with tests for retention, contents, ownership, confinement and privacy.
- **Why**: Useful evidence must start before the service and only intentionally shared archives may leave private storage.
- **Tradeoffs**: Capture adds startup work and cache use without new permissions or dependencies.
- **Watch out**: API 30 trace attachments, real native-crash/ANR capture and 16 KB-page devices remain unverified.
- **Source**: c7309c0c; d053be24; 39e55b4b

### D33: A private app-UID `:venus` service probes both BLOB sharing directions

- **What**: `GpuService` runs in a private app-UID `:venus` process that probes GPU-export and CPU-export coherent BLOB sharing as needed and uses per-context IPC (including an allocator worker and socket for CPU export and GPU-only import on Mali).
- **Why**: Adreno on Galaxy S23 requires dedicated opaque-fd resources and rejects isolated UIDs, while Mali on Pixel 8 maps coherent type 0 on CPU but permits reimport only as type 1.
- **Tradeoffs**: Requires decoder patches, per-context IPC, and an allocator worker and socket for each CPU-export context.
- **Watch out**: Vendor memory-type compatibility and coherency vary by device and firmware, and physical 16 KiB hardware still needs validation.
- **Source**: 87c7d9db, e94c49df

### D34: `Native.start` gates `:venus` probing on an explicit flag and awaits delayed binds

- **What**: `Native.start(store, tier, gpu)` skips `gpu::renderer` when `gpu` is false, records `has_gpu` in `MACHINES` for `Native.hasGpu`, and `GpuService.connect` binds with `BIND_AUTO_CREATE | BIND_IMPORTANT`, transfers the socket fd at most once via `AtomicReference`, waits up to 3 s when `bindService` returns false, and calls `connection.close()` (including `unbindService`) on failure.
- **Why**: Disabling GPU must skip all `:venus` binding and Vulkan probing, while enabling must report whether probing succeeded and survive OEM-delayed service binds.
- **Tradeoffs**: A genuine non-throwing `bindService` failure waits up to 3 s before falling back to no renderer.
- **Watch out**: OEM schedulers delaying `:venus` beyond 3 s will still miss the probe on that startup.
- **Source**: b605a0a3

### D36: Start the stderr reader before redirecting fd 2

- **What**: Start the logging thread while the pipe writer is open, then redirect stderr only after the thread starts.
- **Why**: A failed thread start could leave stderr connected to a pipe without a reader.
- **Tradeoffs**: Both temporary descriptors need explicit failure handling.
- **Watch out**: Closing the writer too early signals EOF to the reader.
- **Source**: 6f2776f3

### D37: JNI import callbacks borrow the writable fd

- **What**: The native import hands Java a borrowed descriptor and preserves the original exception if its writer fails.
- **Why**: The callback supplies contents while native code controls publication.
- **Tradeoffs**: Descriptor ownership spans the JNI call.
- **Watch out**: A wrapper must leave the native descriptor valid.
- **Source**: f6a9510b

### D38: JNI batches metadata and releases local references per element

- **What**: Read document generations in one store snapshot, return ordered account metadata as longs, and release each temporary Java string while assembling or consuming arrays.
- **Why**: Large directory and report batches can exhaust the JNI local-reference table.
- **Tradeoffs**: Java and Rust share the metadata ordering contract.
- **Watch out**: Changes to the tuple layout must reach every consumer.
- **Source**: 68b70897, d5b808e8, ae1e05e0

### D39: JNI translates browse failures by errno

- **What**: Map missing paths, existing destinations and denied access to distinct Java exceptions and preserve other I/O failures as such.
- **Why**: Provider callers act differently on absence, collision and access denial.
- **Tradeoffs**: The bridge maintains an explicit errno mapping.
- **Watch out**: ENOTDIR follows the missing-path mapping.
- **Source**: 9b179de1

### D40: Stack-scoped thread-local store sessions batch JNI reads and writes

- **What**: `Native.withStore(store, LongConsumer)`, `Native.storeWriteFile`, and `Native.storeReadFile` share one short-lived `Store` via a thread-local `STORE_SESSIONS` map with an RAII `StoreSessionGuard`, an `in_use` re-entrancy guard returning `EBADF`, and shared `read_file_result` error mapping.
- **Why**: Kotlin setup runs conditional read-then-write steps across many files and needs one shared `!Sync` `Store` across JNI calls without leaking connections or sharing handles across threads.
- **Tradeoffs**: Session handles remain valid only synchronously on the callback thread and add three JNI entry points.
- **Watch out**: Passing a session handle across threads or beyond `withStore` fails with `EBADF`, and `withStore` relies on preserving pending Java exceptions via `env.exception_check()` like `browseImport`.
- **Source**: 641769ab

## Superseded

- ~~A `vt` cargo feature behind the `appine.vt` Gradle property, debug only and off by default, probed by `VtNative.available` (394aecf)~~ → replaced by **D6** in b38d29c (2026-11-04)
- ~~AGP pinned to 9.3.3 so Android Studio 2026.1 can import the project (96d2997)~~ → reverted in f6c6cf5 (2026-11-05) once Android Studio 2026.2.2 supported AGP 9.4.1
- ~~minSdk 29 (D3 in part, round 07)~~ → replaced by **D3** in 0709982 (2026-11-07)
- ~~The Ghostty 1.4.0 source with the Zig packages fetched by a configure run and proxy variables removed (D6, 394aecf, b38d29c), and released sources bundling the Ghostty release with its zig-pkg bundle (D4, b38d29c)~~ → replaced by **D6** and **D4** in 172be7c (2026-11-11)
- ~~build.rs passes the Ghostty source directory's name as `APPINE_GHOSTTY` for the snapshot version (600700b)~~ → replaced by android/terminal **D44** in 172be7c (2026-11-11, recorded under android/terminal)
- ~~Material Components and androidx.core declared inline in build.gradle.kts (D7 in part, 55be399)~~ → replaced by **D7** and **D20** in ef7a803 (2026-11-11)
- ~~NoticesTask generation and license allow-list validation, including D7, D12–D14, D20–D22 qualifications~~ → replaced by **D24** in bd580227 (2026-11-14)
- ~~D5 combined ownership of the newly extracted child modules~~ → replaced by **D5** in 60790c14 (2026-11-15; scope only, inherited implementation decisions remain here)
- ~~The shared --release build for every APK in D2~~ → replaced by **android/build D5** in 7b3e57c5 (2026-11-17), retaining the debug hooks.
