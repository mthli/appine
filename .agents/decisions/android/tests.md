# Android instrumentation (android/tests) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: android/tests"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12)
>
> Decisions on android/tests made before it was split from android/native keep their `android/native` tags and live in `.agents/decisions/android/native.md`: D5, D8, the test portions of D9, D12–D14, D16, D19 and D23–D32.

## Active

### D1: Android instrumentation has its own maintenance boundary

- **What**: android/tests owns instrumentation source and verification entry points, while runtime behavior and build configuration retain their existing owners.
- **Why**: The extracted subsystem needs a focused architecture reference with explicit integration boundaries.
- **Tradeoffs**: Pre-split decisions remain in the parent snapshot and related changes may require several maps.
- **Watch out**: The split preserves inherited verification and open issues; it is not a fresh full audit.
- **Source**: 60790c14; 39e55b4b

### D2: GPU instrumentation exercises packaged drivers, discovery and worker teardown end to end

- **What**: `GpuTest` exercises packaged Alpine and Debian drivers for compute, mapping lifetime, default Venus-plus-llvmpipe discovery versus Venus-only `appine-gpu`, concurrent clients, allocator and retirement worker exit on peer death, restart and unsupported startup.
- **Why**: Host probes and compute outputs alone cannot establish driver discovery, guest integration or context resource cleanup across exits.
- **Tradeoffs**: Positive cases require capable hardware and fixtures, perform an extra compute run for discovery, and poll thread state while asynchronous cleanup finishes.
- **Watch out**: Fixtures must keep the software driver for discovery checks, thread exit cannot account for vendor-driver allocations, and device coverage depends on tested drivers and page sizes.
- **Source**: 87c7d9db, 9cafc90b, e94c49df

### D3: Alpine OpenRC first-login and upgrade migration have offline instrumentation coverage

- **What**: `MachineTest` and `LicenseTest` test offline failure/retry, starting `osclock` via `rc-service`, cache cleanup, persistent user removal, dependency-only upgrade migrations, interrupted setup with a repair login, isolated one-boot kernels and Alpine license-catalog coverage.
- **Why**: Package presence alone does not prove first-login service startup or upgrade preservation of ownership, configuration and user removal across restarts.
- **Tradeoffs**: Automated checks run offline with `osclock` and synthetic upgrade fixtures rather than live network packages.
- **Watch out**: Docker interoperability needs separate end-to-end verification when Alpine packages change, and custom user package sets can hit different `apk` dependency conflicts.
- **Source**: d0ff9a31, 7e85e8dc

### D4: Unrelated tests disable GPU probing while `GpuTest` verifies the default-off Settings toggle

- **What**: Non-GPU instrumentation tests pass `gpu = false` to `Native.start`, GPU tests pass `gpu = true` with `Native.hasGpu` checks, and `GpuTest.disabledByDefault` and `GpuTest.toggleRestartsKernel` verify Settings dialogs, cancellation, restart, render-node presence/absence and cleanup.
- **Why**: Instrumentation must verify the default-off contract and end-to-end Settings restart flow without paying `:venus` probe overhead in unrelated suites.
- **Tradeoffs**: `toggleRestartsKernel` drives `SettingsActivity` against the real app store and service and requires an unlocked device screen.
- **Watch out**: Coverage ran on Galaxy S23 (API 36), and the unavailable-device summary path was exercised only on probe failure rather than permanently unsupported hardware.
- **Source**: b605a0a3

### D5: Alpine bash startup seeding is tested across new imports and existing regular accounts

- **What**: `MachineTest` verifies `.bashrc` loading, `adduser -D` `/etc/skel` ownership and non-interactive `bash -lc` on new Alpine imports, and tests existing Alpine stores for regular `/bin/bash` accounts (`alice`), homeless accounts (`carol`), symlinked `.profile` (`dave`), `/bin/sh` accounts (`erin`), custom `.bashrc` preservation and deletion persistence.
- **Why**: Both fresh Alpine imports and upgraded stores with pre-existing accounts and dotfiles need on-device regression coverage for ownership, loading and non-shadowing rules.
- **Tradeoffs**: Tests run against isolated temporary stores with bundled bash installed up front rather than driving `TerminalService` across an app upgrade, adding slight runtime.
- **Watch out**: Verified on a 4 KB-page API 36 device (SM-S9110) only; 16 KB-page devices and live `TerminalService` upgrade cold starts remain uncovered.
- **Source**: 9f3e93ed, 125f3d00

### D6: Provider tests cover canonical IDs, root visibility and retired grants

- **What**: Use isolated accounts and documents to exercise invalid homes, noncanonical paths, stale direct and alias grants, a live grant and an unaffected sibling.
- **Why**: Path reuse and missing homes must not broaden access.
- **Tradeoffs**: Framework grant cases require Android instrumentation.
- **Watch out**: Keep an unaffected sibling as a control for overbroad revocation.
- **Source**: 6e9bd7a7, 68b70897

### D7: Terminal input tests synchronize on visible session state

- **What**: Use shared event fixtures to check console ownership, focus, stale paste and clipboard controls, and completion of IME composition.
- **Why**: Delayed UI callbacks can reach another session.
- **Tradeoffs**: Activity and IME interactions need Android instrumentation.
- **Watch out**: Assert both the preedit contents and a single final write to the terminal.
- **Source**: 6f2776f3, 3a90a0ef, d10f2973, 1dab8c16, ae1e05e0

### D8: Import tests observe visibility at stream close and reset

- **What**: Close the source before checking publication, race a competing name, and verify reset and cancellation with only the completed file surviving.
- **Why**: Final contents alone miss early publication and lock contention.
- **Tradeoffs**: The tests coordinate stream and worker events.
- **Watch out**: Callback exceptions retain their identity.
- **Source**: f6a9510b, f495f2cc

### D9: Setup tests cover account edits, interrupted login and fallback cleanup

- **What**: Edit account files in place, retry failed rule refreshes and verify SIGINT, tarball cleanup and JNI errno translation in isolated fixtures.
- **Why**: Successful first login alone misses retry and failure semantics.
- **Tradeoffs**: The tests create or modify a disposable store.
- **Watch out**: Check ordinary errors as well as missing-file behavior.
- **Source**: d5b808e8, 9b179de1

### D10: Import tests control probe completion before rootfs publication

- **What**: Inject completed and pending mirror probes into isolated imports and send ordered status reports through the console pump.
- **Why**: Real network timing cannot deterministically exercise late probe results.
- **Tradeoffs**: Debian coverage needs its tarball and the network-test flag.
- **Watch out**: Simulated throughput verifies selection policy only.
- **Source**: 568b6a48

### D11: Shell integration tests exercise real prompt boundaries

- **What**: Use literal and symlinked directories, multiline zsh prompts, ash background output and doas or overwritten PS1 to check marks, title fallback and input positioning.
- **Why**: Prompt protocols depend on the actual shell output.
- **Tradeoffs**: Fixture coverage depends on shell availability.
- **Watch out**: Check restoration after the command and frame capture across a synchronized-output hold.
- **Source**: 4309ef58, acc5a7a0, 6e0c0f16

### D12: Android service and provider tests observe their external contracts

- **What**: List enough entries to exercise JNI reference lifetime, edit passwd to invalidate the auto root, and distinguish notifications with remapped host ports.
- **Why**: Small listings and fixed account fixtures miss these regressions.
- **Tradeoffs**: Provider and service cases exercise framework state.
- **Watch out**: A remapping must preserve the distinct notification keys.
- **Source**: ae1e05e0

### D13: Per-start configuration degradation is verified in JVM unit and isolated-store instrumentation tests

- **What**: `StartConfigTest` (using a file-private `Harness`) tests `prepareStartBridge` and `Rootfs.runSetupStep` for fresh-import abort, independent step degradation, bridge closure on open or config failure and non-`IOException` propagation, while `MachineTest.degradedPerStartConfigAllowsExistingStoreToBoot` exercises symlink-obstructed paths, marker reads, bridge closure and shell boot in an isolated Alpine store with concise local helpers.
- **Why**: Degraded per-start behavior, bridge cleanup, marker obstruction and fresh-import fail-fast semantics need automated verification across both pure-Kotlin orchestration and real rootfs stores.
- **Tradeoffs**: Instrumentation execution requires a device or emulator with pre-fetched `target/` assets while JVM tests cover only pure-Kotlin orchestration, with minor indirection through local test helpers.
- **Watch out**: `MachineTest.degradedPerStartConfigAllowsExistingStoreToBoot` has been compile- and lint-checked only and still needs on-device execution.
- **Source**: 12ac97cf, f7d164db

### D14: Batched per-start preparation adds `FakeStoreAccess` JVM tests and `StartPreparation` checks

- **What**: `MachineTest.degradedPerStartConfigAllowsExistingStoreToBoot` consumes `StartPreparation` and asserts `openRcDone == false`, while three new `StartConfigTest` cases over `FakeStoreAccess` verify unchanged-asset skipping, upgrade rewrites, obstructed `openrc-setup-done` fallback, and fail-fast versus degraded error handling.
- **Why**: Batched `prepareStart` return values, write skipping, upgrade overwrites, and marker fallback behavior require deterministic regression coverage.
- **Tradeoffs**: JVM unit tests mock `StoreAccess` rather than exercising the JNI `withStore` bridge directly.
- **Watch out**: `MachineTest` instrumentation was compile- and lint-checked only and still requires execution on an ARM64 emulator or device.
- **Source**: 641769ab

### D15: `TerminalResizeTest` keeps software pixel checks and adds hardware `RenderNode` recording coverage

- **What**: `TerminalResizeTest` retains its software `sameAs()` preview tests unchanged, lifts `renderer`, `rowNodes`, and `drawHardware` helpers onto `Fixture`, and adds `hardwareRecordingPopulatesAndRecoversAfterDiscard` to test viewport `RenderNode` population and recovery after `discardDisplayLists()`.
- **Why**: Software `Canvas(Bitmap)` draws only exercise `VtRenderer`'s software fallback and leave hardware `RenderNode` recording and discard recovery untested in instrumentation.
- **Tradeoffs**: The hardware test verifies display-list population and recovery rather than GPU-rasterized pixels.
- **Watch out**: The new instrumentation case was compile- and lint-checked only and still requires `connectedDebugAndroidTest` on a device or emulator.
- **Source**: e98ff62c

### D16: Mode 2026 shell-integration tests assert incremental redraws after holds

- **What**: `VtShellIntegrationTest.twoPhaseFrameCaptureHandlesSyncHoldAndConcurrentOutput` asserts `dirty != VtFrame.DIRTY_FULL` and `rowCount < rows` on both `afterHold` and `burstFrame`.
- **Why**: Text-only visibility assertions would not catch regressions that fall back to full-screen redraws after synchronized-output holds.
- **Tradeoffs**: Assertions run inside the existing test case with negligible overhead.
- **Watch out**: The updated test was compile-checked via `assembleDebugAndroidTest` only and still needs `connectedDebugAndroidTest` on a device or emulator.
- **Source**: 2f321b12
