# android/native Map

> Static understanding snapshot, not a decision history.
> See `.agents/decisions/android/native.md` for the paired decision history, including pre-split decisions for android/build and android/tests.
>
> Verified: 2026-11-26 (full refresh: 15 adversarial claims checked by independent verifier — 14 confirmed, 1 partial corrected below, 0 refuted; precision fixes and confirmed bugs/debts recorded)
>
> Maintained: 2026-11-28 (targeted verification only since the full mapping; latest change: listen records carry socket binding details)

## Responsibilities

Runtime JNI, app startup, Venus and the guest browser client span `Native.kt`, `GpuService.kt`, `MainApplication.kt`, `hosts/android/src/{lib,gpu}.rs`, `hosts/android/open-url.c` and the C sources, headers and patches in `hosts/android/gpu/`. GPU `CMakeLists.txt`, manifest and generated rootfs/browser packaging belong to `android/build`, guest shell scripts to `android/setup`, terminal fonts to `android/vtrender`, and instrumentation to `android/tests`.

## Key types

- `MACHINES` is a process-local `Mutex<BTreeMap<jlong, RegisteredMachine>>`. Each entry holds `Arc<Machine>` and `has_gpu: bool`. `NEXT_HANDLE` starts at 1 and allocates by relaxed `AtomicI64::fetch_add` after successful start. `machines()` recovers a poisoned table lock. `machine(handle)` clones the Arc under that lock.
- `AndroidRenderer` owns the capset, JVM service reference, machine-process Vulkan mapper and optional `GpuRoute` (physical-device UUID, CPU export and GPU import indices). Each `AndroidContext` uses a `Channel` holding a Java `GpuConnection`. `AndroidMapping` ties a resource ID to a refcounted `Backing` (address, size, Vulkan memory or SHM, mapper). `CpuExports` holds pending reverse allocations.

## Public entry points

All 44 `@JvmStatic external` methods in `Native.kt` have matching `Java_io_github_mthli_appine_Native_…` symbols in Android-only `lib.rs`. Exactly 22 declare `@Throws(IOException::class)` — seven rootfs calls, twelve browse calls (all except `browseWritten`), `start`, `openConsole`, `spawn`. Omitted result types mean `Unit`. `GpuService.runWorker` and `VtNative` are counted separately.

### Initialization and rootfs

| Signature | Contract |
| --- | --- |
| `private init(verbose: Boolean)` | `Native` loads `appine` and passes `BuildConfig.DEBUG`. |
| `importRootfs(store: String, tarball: String)` | Import tar.gz into a new store in an empty directory. |
| `syncRootfs(store: String)` | Sync the store's host filesystem before rename. |
| `withStore(store: String, action: LongConsumer)` | Open one short-lived store session without a background reaper thread, bind a thread-local handle for the synchronous callback, always release the handle before dropping the store, and preserve any Java exception thrown by `action`. |
| `storeWriteFile(session: Long, path: String, data: ByteArray, mode: Int, overwrite: Boolean, uid: Int = 0, gid: Int = 0)` | Write on an active thread-local `withStore` session (`EBADF` if closed, cross-thread or re-entrant), committing metadata per write without WAL sync. Same mode, ownership and overwrite rules as `writeFile`. |
| `storeReadFile(session: Long, path: String): ByteArray?` | Read a regular file on an active thread-local `withStore` session, returning null on `ENOENT` and `IOException` on other errnos (`EBADF` if invalid). |
| `writeFile(store: String, path: String, data: ByteArray, mode: Int, overwrite: Boolean, uid: Int = 0, gid: Int = 0)` | Cast mode and IDs to `u32`. New files take these IDs and low 12 mode bits. Skip existing entries unless overwriting. A rewritten regular file keeps owner and mode. |
| `readFile(store: String, path: String): ByteArray?` | Return regular-file bytes, null on `ENOENT`, `IOException` for other errnos. |

### Document browsing and mutation

Paths encode percent signs, control characters and invalid UTF-8 bytes as `%XX`. New names are literal UTF-8. Stat fields are tab-separated kind (`d` or `f`), bytes, mtime in milliseconds rounded down, and decimal `mode & 07777`.

| Signature | Contract |
| --- | --- |
| `browseStat(store: String, path: String): String` | Follow symlinks and return directory or regular-file stat fields. |
| `browseGenerations(store: String, paths: Array<String>): LongArray` | Ordered path generations in one snapshot, including symlink traversal. |
| `browseList(store: String, dir: String): Array<String>` | Encoded child name, tab and stat fields per entry. |
| `browseOpen(store: String, path: String): Int` | Transfer a regular-file fd to the caller to adopt. |
| `browseSniff(store: String, paths: Array<String>): BooleanArray` | For the first 1,000 paths, check 4 KiB for UTF-8 without NUL, allowing a truncated final character at the boundary. Remaining results are false. |
| `browseSearch(store: String, dir: String, query: String, limit: Int): Array<String>` | Breadth-first case-insensitive substring search. Encoded path plus stat per match. Scan budget 50,000 entries. |
| `browseImport(store: String, home: String, name: String, uid: Int, gid: Int, writer: IntConsumer): String` | Call the writer with a borrowed fd. Return the encoded name after publication. |
| `browseCreate(store: String, top: String, dir: String, name: String, directory: Boolean, uid: Int, gid: Int): String` | Create under home. Return collision-resolved encoded name. Create and import cast IDs to `u32`. |
| `browseRemove(store: String, top: String, path: String)` | Remove a file, symlink itself or directory tree. |
| `browseRename(store: String, top: String, path: String, name: String): String` | Rename in place without replacement. Return the encoded new name. |
| `browseMove(store: String, top: String, path: String, to: String)` | Move under the same top, preserving the name and refusing replacement. |
| `browseOpenWrite(store: String, top: String, path: String, truncate: Boolean, append: Boolean): Int` | Transfer a read/write fd with independent truncate and append flags. |
| `browseWritten(store: String, top: String, path: String)` | Notify guest of modify/close-write. Ignore store errors. |

### Machine and consoles

| Signature | Contract |
| --- | --- |
| `start(store: String, tier: Int, gpu: Boolean): Long` | DBT for tier 1, interpreter otherwise. Start failure throws `IOException("cannot start: …")` and returns JNI zero. |
| `hasGpu(machine: Long): Boolean` | Read the registration bit without the big kernel lock (BKL). |
| `boot(machine: Long)` | Boot from inittab, waiting for sysinit and wait commands. |
| `setRoutes(machine: Long, routes: Array<String>?)` | Replace the host route list (see Route translation). |
| `openConsole(machine: Long, rows: Int, cols: Int): Int` | Open a PTY, clamping dimensions to 1–65535. |
| `spawn(machine: Long, console: Int, program: String, argv: Array<String>, envp: Array<String>): Int` | Start a session leader with UTF-8 program, argv and envp. Negative console selects host stdio. Return the PID. |
| `read(machine: Long, console: Int, buffer: ByteArray): Int` | Block for output. Copy to offset zero. Return count, 0 at session end, −1 on failure. |
| `write(machine: Long, console: Int, buffer: ByteArray, offset: Int, length: Int): Int` | Copy the region (negative length becomes zero). Retry short writes. Return total or −1 even after partial delivery. |
| `resize(machine: Long, console: Int, rows: Int, cols: Int)` | Clamp as for open and ignore the kernel result. |
| `closeConsole(machine: Long, console: Int)` | Hang up the console and ignore the kernel result. |
| `waitFor(machine: Long, pid: Int): Int` | Wait for a spawned process. Return exit code or negated signal. |
| `consoleCwd(machine: Long, console: Int): String?` | Foreground group cwd with UTF-8 replacement, or null. |
| `trimMemory(machine: Long)` | Trim pooled DBT translations, shared file mappings and VFS caches, then sync. |
| `shutdown(machine: Long)` | Run orderly poweroff, then unregister. |
| `stop(machine: Long)` | Unregister, then force all guest processes to exit. |

### Service poll records

| Signature | Result |
| --- | --- |
| `takeListens(machine: Long): Array<String>?` | Drain guest port, host port, bind address (`*` for dual-stack wildcard), bound interface index and process name, in tab-separated order. |
| `takeKills(machine: Long): Array<String>?` | Seven tab-separated fields. Console (−1 if absent), PID and process name precede either `bug, report, log, stubs` or `oom, cgroup, used bytes, limit bytes`. The app-limit cgroup is empty. Clean TAB, LF, CR and U+001E to spaces within fields, then join log lines and observed stub paths separately with U+001E. |
| `bklStall(machine: Long): String?` | Latest watchdog stall as tab-separated ID, seconds, report and end record (empty while ongoing). TAB, LF and CR in the two text fields become spaces. Uses the stall mutex. Null if unavailable. |
| `takePower(machine: Long): Int` | Consume the request. 0 none, 1 reboot, 2 halt, 3 power off. |
| `processes(machine: Long): Int` | Count live process groups excluding init and zombies. |
| `guestCpuTime(machine: Long): Long` | Under one BKL hold, add live guest-thread nanoseconds to exited-thread CPU time with saturation. Clamp to `Long.MAX_VALUE`. Unavailable is −1. |
| `loginRuleInputs(machine: Long): LongArray?` | One BKL sample of `/etc`, `/etc/passwd`, `/etc/group`, `/etc/shadow`, `/var/lib/appine/admins`, in order. Each contributes inode, byte size, mtime_ns and ctime_ns. Missing path gives four zeros. Failed sample gives null. Files auto-roots uses passwd at indices 4–7. |
| `takeChangedDirs(machine: Long): Array<String>?` | Drain encoded paths of changed store directories. First call enables recording. |

## Data flow / lifecycle

### Process entry and stderr

After `super.onCreate()`, `MainApplication` returns if the process name equals `"$packageName:venus"`. Otherwise it assigns `GpuService.app`, installs `Privacy` (lifecycle and screen-off callbacks), then `Diagnostics` (log capture).

`redirect_stderr` uses a process-local `Once`. After `pipe`, the writer stays open across spawn of `stderr-log`. Only successful spawn permits `dup2(write_end, 2)`, then close of the writer unless already fd 2. Pipe failure returns. Spawn failure closes both ends. Failed `dup2` preserves stderr and closes the writer so the reader sees EOF. Ordinary failures consume the attempt. The reader splits on LF, skips NUL-containing lines and logs INFO under `appine`. After the attempt, verbose init sets `APPINE_LOG=1`.

### Machine lifetime and locking

Under `Rootfs.access.write`, start sets the store and `exception_trace=true`. The default-off GPU flag gates probing. An unavailable renderer ordinarily yields `None` and kernel startup continues. `hasGpu` reports availability at registration, independent of later context failures.

Lookup releases the table guard before kernel work. A cloned Arc survives removal. `stop` removes first, then calls `kill_all`. `shutdown` clones the machine, calls `Machine::shutdown(Power::PowerOff, SHUTDOWN_TIMEOUT)`, and unregisters the handle on normal return; a concurrent `stop` or `shutdown` can unregister it during poweroff. Inittab shutdown commands, when enabled, share a 30 s wait budget. SIGTERM and up to 1 s grace precede forced exit and a wait for all guest threads. Both paths drop the removed entry outside the table lock. The last Arc owner runs `Machine::drop`, which kills, syncs, sets the shutdown flag, wakes and joins the timer, then stops the watchdog.

With a missing handle and valid arguments, open and spawn throw “the machine is stopped”. Read/write and CPU time return −1, wait −9, GPU presence false, power/count zero, cwd/stall/login inputs null. The three event drains allocate empty arrays. Machine-scoped void calls do nothing.

### Store transactions and error propagation

Browse opens a separate store connection per call. Live kernels commit on open and suspend deferral until close. `Rootfs.access` coordinates replacement. Mutations confine writes by real path. After close, `browse_changing` delivers recorded changes for guest inotify, even on a returned error.

Import commits detached staging before calling `IntConsumer.accept(int)` synchronously. `TerminalFiles` duplicates the borrowed fd with `ParcelFileDescriptor.fromFd`. The store syncs the completed file, rechecks the home inode and publishes by collision-safe rename and commit. A callback failure gives the store `EIO` for cleanup. JNI returns with the writer's original exception pending.

`browse_error` maps `ENOENT`/`ENOTDIR` to `FileNotFoundException`, `EEXIST` to `FileAlreadyExistsException`, `EACCES` to `AccessDeniedException`, everything else to `IOException`. Import calls it only after confirming no pending Java exception. Other throwing rootfs and machine calls report kernel errors as plain `IOException`.

### Route translation

Read three whitespace fields, `<hex destination>/<prefix> <hex gateway or -> <interface>`, ignoring extras. Parse even-length ASCII pairs in radix 16. Destination is 4 or 16 bytes. A supplied gateway must match. Prefix parses as `u8` without a family-width check. Metric is 0 for IPv4, 1024 for IPv6 /0, 256 for other IPv6. Skip malformed rows. Empty input installs an empty list. Null restores host routes or interface-based synthesis. Failed array conversion preserves the prior routes.

### Renderer binding and capability probe

Rust creates a Unix `SOCK_SEQPACKET | SOCK_CLOEXEC` pair per probe or context, sets 10 s send/receive timeouts on the app endpoint, attaches to the JVM and passes the worker fd to `GpuService.connect`. Kotlin duplicates it with `fromFd` and uses a direct executor to bind the nonexported app-UID `:venus` service with `BIND_AUTO_CREATE | BIND_IMPORTANT`. True returns immediately. Only false waits up to 3 s for delayed delivery. Interruption restores the interrupt flag. Failed wait or caught binding runtime exception closes the connection and returns null.

`onServiceConnected` takes the fd once via `AtomicReference.getAndSet(null)` and sends a token and fd in a one-way `FIRST_CALL_TRANSACTION`. Finally it closes the duplicate, recycles the parcel and signals the latch. After token validation the `appine-venus` thread loads `appine` and detaches the fd into `runWorker`. Null binding, death and disconnect call idempotent `close`: close pending fd, wake waiters, attempt unbind. Rust drops its original worker fd after the Java call. Null/failed connect gives `ENODEV`. Channel drop shuts IPC and calls Java close if attached.

`worker.c` handles a probe `GPU_OPEN` by trying `ANDROID_BLOB` (GPU export), then `CPU_EXPORT_BLOB` (Mali fallback) if needed. In `probe.c`, two logical devices share one non-dedicated Android Hardware Buffer (AHB) allocation on one non-CPU Vulkan 1.1+ GPU with a compute queue and coherent host-visible memory. Two buffers bind at distinct offsets. The CPU writes a pattern, the GPU copies one host page, then a barrier and fence precede CPU readback. A failed 5 s fence wait calls `vkDeviceWaitIdle`. Probe failure gives `ENODEV`. DMA-BUF, opaque-fd and dedicated controls serve the standalone survey.

Forward maps the import at the coherent export index. Reverse maps the original allocation and imports at the first common AHB/buffer-requirements type. Its reply carries the UUID and both indices. Rust drops the probe channel, creates the local mapper (UUID-constrained in reverse) and reads the decoder capset via `vkr_get_capset`.

### Commands and shared memory

Render-node open creates a render file. `VIRTGPU_CONTEXT_INIT` opens the renderer context. Open/create/submit run outside the BKL. Each context has a service thread, seqpacket stream and Venus decoder context. A mutex serializes create/submit/release. The worker blocks SIGPIPE and initializes virglrenderer once with threaded sync and async fence callbacks.

`transport.h` defines fixed requests/replies. `GPU_OPEN` must be first and unique. `GPU_CREATE` returns blob type and opaque-fd metadata. `GPU_RELEASE` destroys the resource. `GPU_SUBMIT` accepts up to 1 MiB in 16 KiB chunks on rings 0–63. `GPU_REVERSE_ALLOCATOR` installs a second seqpacket pair once. `client.c` receives `SCM_RIGHTS` with `MSG_CMSG_CLOEXEC`, checking reply size, truncation and descriptor count. A reverse blob returns no fd. Broken exchanges shut down the socket and yield `EIO`. Valid replies preserve success, `ENOMEM` and `ENODEV`, mapping other errors to `EIO`.

- For zero blob ID, `mapper.c` requires SHM backed by an exact-size regular file. It attempts to add shrink/grow seals and verifies both before shared read/write mmap.
- `android-blob.patch` enables AHB and adds an external-buffer declaration where absent. Ordinary host-visible allocations without resource import, explicit export or dedication become host-page-rounded BLOBs. Zero and sizes above `UINT32_MAX - host_page_size` fail before rounding. Oversize, shareable and cross-device BLOB exports fail.
- `android_blob.c` sends the full gralloc handle through an auxiliary Unix stream socket. The forward reply's opaque fd is its receiving end. The mapper checks UUID, coherent index and size, sets a 2 s receive timeout and requires a single-layer BLOB of height one and sufficient width. Import checks AHB type bits/size, allocates at driver-reported size and consumes the AHB reference on either result. The mapper maps the requested extent.
- Reverse exposes one physical device/group. The decoder clears host-visible/coherent/cached bits on all types and assigns the CPU type's saved bits to the GPU import index, including in memory-properties2. Both modes hide visibility/caching on noncoherent types. Reverse refuses other host-visible allocation forms. The machine maps its original allocation and the service imports its AHB.
- The reverse socket has 5 s send/receive timeouts at the service end. A mutex serializes ring requests. `reverse.h` carries Venus memory ID and size. `venus-allocate` maps coherent host-page-multiple memory, retains it by ID, sends a ready byte and, on success, the AHB. Blob creation takes the pending entry by blob ID, memory release removes it with a size-zero request, and disconnect clears the table.
- Successfully created resources must have backing covering the requested extent at a 4096-byte-aligned base or Rust releases them with `EIO`. Guest VMAs retain the mapping.

### Fences and teardown

Either a guest request or retained buffers triggers a fence. After command submission, the worker queues a nonblocking pipe writer before submitting the fence so an immediate fence completion can retire it. One serial counter feeds per-ring queues. Completion writes and closes all writers on that ring through the completed serial. With buffers, `venus-retire` keeps the original fd and Arcs, duplicating the fd only for a guest fence. Without buffers, the guest takes its requested fd directly.

Retirement polls indefinitely, retrying EINTR. Readiness, hangup or other poll error releases mappings. Final mapping drop queues ID and backing. Retirement sends release under the context mutex while connected and drops the backing. Its last owner unmaps/frees Vulkan memory or unmaps SHM.

Context drop shuts command IPC immediately and queues disconnect. Dropping reverse exports shuts their socket. Retirement releases the binding on disconnect. The service destroys the context before unlinking its callback record and closing residual fence writers. `venus-teardown.patch` synchronizes the monitor loop, finalizes it before ring teardown and preserves the device object mutex through child destruction.

### Guest browser protocol

The static `/usr/local/libexec/appine-open-url` takes one URL with lowercase `http://` or `https://` prefix, at most 8192 bytes, excluding bytes through ASCII space and DEL. Invalid URL arguments exit before `/etc/appine/browser` is opened. Each valid invocation reads `/etc/appine/browser`: base-10 port parsed via `strtoul` (1–65535; permits leading whitespace or `+`), LF, 36 lowercase hex digits or hyphens, LF, EOF. The server generates a UUID.

Nonblocking IPv4 TCP connects to loopback, checks `SO_ERROR`, sends token and URL lines with `MSG_NOSIGNAL`, then half-closes writes. Network polls share a monotonic 5 s deadline. Up to 159 reply bytes are read until newline, EOF or error. A C-string comparison with `OK\n` returns exit 0 on equality. A newline-terminated `ERROR ` with nonempty printable ASCII text reports that text. Invalid input/configuration, I/O failure, refusal, timeout and missing acknowledgment exit 1 with an `xdg-open:` diagnostic. Timeout cannot cancel a delivered request.

`BrowserBridge` checks token, strict UTF-8 and URI scheme/host/port. Reading and main-thread launch each have a 2 s budget.

## Dependencies (inbound / outbound)

### Inbound

- `TerminalService` starts on `appine-start` (which can also run guest boot during Alpine OpenRC setup) and boots on `appine-boot` if setup did not already boot it. Its main-thread poll reads the stall first. The worker drains events, samples counts/CPU and checks roots/rules. Routes, trim and shutdown also run off the UI thread.
- `Rootfs` prepares the store, spawns setup commands and waits for rules refresh on `appine-login-rules`. `Documents` adopts browse fds and reports closes on `documents-close`. `TerminalFiles` imports on `terminal-import`.
- `ConsolePty` opens/spawns during tab start. `VtSession` writes on `vt-writer` and waits on `vt-waiter`. On `vt-pump`, `VtNative.pump` in `hosts/android/src/vt.rs` looks up the machine for each `read_console_run_timeout`. VT resizes directly. Instrumentation uses `Native.read` and `Native.resize`.
- `android/vtcore` imports machine lookup, string/exception helpers and `guarded!`. `kernel/files` calls the renderer traits and `kernel/hostmem` retains renderer mappings for guest VMAs. `android/setup` installs the browser client. `android/tests` exercises JNI, browser and hardware-gated GPU paths.

### Outbound

- `appine_kernel` supplies machine, store/browse, routing and watchdog APIs.
- JNI/Binder, Unix fd passing, Vulkan, AHB and patched virglrenderer. The `android_os.c` shim reads options via `getenv` and logs WARN under `appine-venus`.
- `MainApplication` calls `android/privacy` and `android/reports`. The browser peer is `android/service`.

## Invariants and gotchas

### Two panic boundaries

Kernel `host_call` reports contained panics and returns `None`. Depending on the call, this yields `EIO`, a start error or an empty event batch. `APPINE_ON_BUG=kill` enables containment, `abort` disables it. Otherwise containment follows `!cfg!(debug_assertions)`, true in both APK profiles. With containment off, the kernel aborts the process.

`guarded!` uses `catch_unwind(AssertUnwindSafe(move || …))` around every JNI body. The JNI guard catches unwinds on the calling thread, including bridge code, unwrapped kernel wait loops, CPU sampling and teardown; timer and watchdog joins discard errors from panics on those threads. After the panic hook writes to stderr, `panicked` logs the function. The throwing form retains the raw thread-local JNI environment and raises `IOException("internal error in <function>")` only if no exception is pending. A caught unwind leaves any partial state changes in place.

| Result after an outer panic | Native methods |
| --- | --- |
| −1 | `browseOpen`, `browseOpenWrite`, `openConsole`, `spawn`, `read`, `write`, `guestCpuTime` |
| −9 | `waitFor` |
| 0 / false | `start`, `takePower`, `processes`, `hasGpu` |
| null | Every reference result, including the three event arrays |
| Unit | Every void method |

### JNI references and ownership

`string` wraps `get_string` as an Option. `strings` copies each input to UTF-8 bytes and deletes its local ref after conversion. A failing conversion exits early. `string_array` deletes each temporary string after attempted insertion, even on set failure. `long_array` fills a primitive array in one region write. JNI errors can return null, a sentinel or Unit with any pending exception preserved. Kotlin defaults on `writeFile` use a single JNI symbol.

## Confirmed bugs / technical debt

- `browseSearch` clamps nonpositive limits to zero, but the store tests the limit after appending a match. Zero can yield one row. Documents requests 200.
- Forward probing omits the winning UUID from its reply. Mapper selection stops at the first device it can create, before checking its coherent types. Multiple GPUs can therefore hide a viable mapper or cause a later import UUID mismatch.
- Socket timeouts do not bound fence retirement or driver idle. An unsignaled fence can retain mappings and delay disconnect/unbind. Probe cleanup can outlive its client.
- `onServiceConnected` ignores the Boolean returned by `transact` before setting connected, so its latch can report success prematurely; a disconnect after fd delivery also leaves `connected` true while `awaitConnected` does not check `closed`, so the delayed-bind path can return an already-closed `GpuConnection` (Rust still catches both at `GPU_OPEN`).
- `takeKills` drains the kernel kill queue before converting strings and building the Java array, so a JNI allocation or conversion failure drops that batch permanently.
- Passing a zero-length buffer to `Native.read` can block on a live console even when output is already queued.
- Void methods such as `writeFile` return silently if argument conversion fails without a pending Java exception.
- Ownership casts accept negative IDs. `Rootfs` parses signed passwd IDs and automatic login filters by UID and login shell and leaves the parsed GID unchecked. A malformed negative GID can reach `browseCreate` or `browseImport` and become a wrapped group ID on disk.

## Open questions

Should forward mapper selection use the probe's UUID on multi-GPU devices?

## To verify

- Verify both AHB routes, guest mmap and teardown on physical 16 KiB-page hardware, including driver stalls and OEM-delayed binds.
- Verify the packaged browser client on Debian and on 16 KiB-page Android.
