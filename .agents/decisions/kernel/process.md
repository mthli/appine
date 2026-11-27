# Processes and threads (kernel/process) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: kernel/process"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12). D1–D13 were seeded by hand from the retired design
> doc §6 and the round records in `docs/rounds/`, so their sources cite rounds; later entries cite
> commits.

## Constraint index (compressed active decisions)

> Full text of D1–D5, D7–D10, D12, D15–D17, D20–D22, D24–D26: `git show 248d79f637c8f05dadc6ef9fdb0733c568c6a468:.claude/decisions/kernel/process.md`

> Full text of D27–D32: `git show 260512d530d136e70f10564e248f7db876f07062:.claude/decisions/kernel/process.md`

> Full text of D33–D37, D39–D46 and D48: `git show 0553229be58764ca5a42f2ea64949373e99d9553:.claude/decisions/kernel/process.md`

- **D1** — Each guest thread is a host pthread scheduled by the host and pooled with its engine CPU state, so every guest thread costs a host thread and an eventfd, and a pooled host thread keeps the previous guest thread's nice, affinity and I/O priority until they are re-applied (rounds 04, 28)
- **D2** — All kernel-layer state sits behind one big kernel lock that guest code runs without and only waits and `Cx::unlocked` release mid-syscall, so kernel objects use non-atomic `Rc`/`RefCell` and nothing may hold them across a release (rounds 04, 29, 36, 38, 109, 189)
- **D3** — Up to half the host CPUs (1 to 4) spin about 30 µs on the BKL before sleeping, reading only its "held" flag and never touching the mutex until the flag reads free (round 188)
- **D4** — Every guest wait is one host `poll()` on the thread's own eventfd plus the host fds it waits on, never a blocking host syscall, and since events are level-triggered waiters must re-check their condition in a loop (design review §6, round 04)
- **D5** — Wakers request channels or objects and only matching waiters are signalled, after the BKL is released, so every file kind must register each object that affects its readiness or its waiters hang (rounds 16, 28, 49)
- **D7** — execve resolves and checks under the BKL but maps segments and builds the stack unlocked, touching only the new address space and self-locked structures (file maps) and re-checking after re-locking whether it was killed (round 28)
- **D8** — `threads-max` (by memory, at most half the host fd limit) binds root too and `RLIMIT_NPROC` defaults to half of it, so a fork bomb cannot exhaust the app's threads and fds, while host-launched processes bypass both by design (round 20)
- **D9** — PID 1 is a built-in init with no guest program that adopts orphans, runs a subset of `/etc/inittab` (no `respawn` or `askfirst`) at boot and shutdown on its own host thread so it never blocks the tabs, and takes root's SIGTERM/SIGUSR1/SIGUSR2 as reboot/halt/poweroff (design review §6, rounds 186, 187)
- **D10** — nice, affinity and I/O priority are per thread and applied to host threads, with raised priority stopping at host nice -4, real-time I/O becoming best-effort and real-time policies only recorded, and they must be re-applied whenever a pooled host thread starts a guest thread (rounds 19, 140)
- **D12** — Kernel pids and credential IDs stay global and pid and user namespaces translate them at every boundary (syscall arguments and results, procfs, siginfo, socket credentials), where a missed translation leaks a global ID into a namespace (rounds 116–123)
- **D15** — Every reader of guest times (`read_timespec`, the fast path, `timer_settime`, `read_itimerval`) goes through `timespec()` in `syscall/mod.rs`, which validates and saturates at KTIME_MAX, and a new reader that bypasses it brings back the `Instant + Duration` overflow panic (96cd8bd, 63b9e59)
- **D16** — A namespace's pid 1 becomes the new parent only while the namespace is not dead, so a dying namespace's orphans fall to the built-in init, which reaps them at once, though a subreaper inside the namespace still takes them first (65c44f6)
- **D17** — execve copies the fd table only above two references (another process sharing it through `CLONE_FILES`), as `unshare_files` does, relying on exiting threads dropping their references under the BKL before `kill_other_threads` returns (68cafce)
- **D20** — A thread leaving a live process hands every shared pending signal to `signal::retarget` once it has left its group, as `exit_signals` does (kernel/signal D16) (22e2b41)
- **D21** — `ThreadGroup::leader_exit` keeps an exited leader's name and CPU time from its exit until reaping, cleared when another thread's execve takes over the tgid, so procfs can show it as a zombie (kernel/procfs D17) (79a60cf)
- **D22** — `RLIMIT_MSGQUEUE` defaults to (819200, 819200) as on Linux, though mq_open does not enforce it (97b1d62)
- **D24** — With `CLONE_PARENT` the child takes the creating thread group's exit signal, while ptrace still picks the fork or clone event from the requested signal, as on Linux (14dede6)
- **D25** — A thread-targeted POSIX timer signal is sent only while its task still exists in the timer's process and is otherwise dropped, though a thread of the same process reusing that number still receives it (d98f60b)
- **D26** — Marking a robust lock owner-died translates its address with copy-on-write broken (or the page faulted in) before the compare-and-swap loop, since a plain translate does not break copy-on-write (kernel/mm's rule for writers through translate) (e4d3386)
- **D27** — `set_policy` follows `__sched_setscheduler`'s checks (reset-on-fork only with a kept policy, clamps against the current ones, `KEEP_PARAMS` validating only, nice only for `SCHED_OTHER` and `SCHED_BATCH`, `EPERM` beyond `RLIMIT_NICE` or for clearing reset-on-fork without `CAP_SYS_NICE`) and `KCMP_IO` compares I/O priority contexts; `sched_setattr` now validates policy before nice and commits only after both succeed (D61) (70c1bc6, dcacfb6)
- **D28** — `Kernel::ucounts` charges a new namespace to (namespace, euid) and each (parent, owner) pair up to the initial namespace, and clone, unshare and open_tree return `ENOSPC` at a `max_*_namespaces` limit before creating anything, so a new namespace kind must be checked and counted where it is created, and dead namespaces leave the counts only at the next check (6d05aa9)
- **D29** — When the first stage of `task::exit` panics, a second `bug::catch` still completes `vfork_done` and calls `remove_user` before the kill, while the rest of the stage is skipped and a salvage over borrowed state is only reported (6d1d9c0)
- **D30** — wait applies one eligibility rule (`__WCLONE` and `__WALL` by exit signal, zombies only for waits taking exits), #! nests five deep, tids wrap to 300 below `PID_MAX`, `appine-run` forwards its first stop signal and kills everything on one at least a second later, and the periodic checks stop through `Shared::stop_checks` with SeqCst on both sides (5d23297)
- **D31** — `loader.rs` reads a host file's `st_dev` and `st_ino` without a cast now that only Linux and Android hosts are built, so a 32-bit or other-libc target would only fail to compile (7c5e636)
- **D32** — Private memory and `FUTEX_PRIVATE_FLAG` operations key futexes by (mm, address) and shared memory by host memory or file, and `uid_map`/`gid_map` writes are checked against their opener as well as the writer, so the no-BKL path and `Mm::futex_key` must agree on which addresses are private and every writable shared mapping must call `note_shared_maps` (f6a82cc)
- **D33** — `FUTEX_WAKE_OP` runs its op on `futex::writable_word` with Linux's comparisons and futex argument rules (left by the fast path to the BKL path), `/proc/stat`'s `processes` counts every task, `PRIO_USER` translates through the caller's user namespace and matches each thread by its own real uid, and `reap_shm` runs at every thread exit and execve, cheap only while no segment is marked for removal (5d7b936, 1be1142)
- **D34** — setpriority and sched_setaffinity accept the same owner (by the target thread's credentials) or `CAP_SYS_NICE` over the target's user namespace, while sched_setscheduler and sched_setattr still require it in the initial namespace, so `syscall/sched.rs`'s two owner checks must stay matched to their syscalls (2aca260, 1be1142)
- **D35** — `MachineOptions::exception_trace` records `debug.exception-trace` before PID 1 exists, the app setting 1 and `appine-run` keeping arm64's default 0, so a new host must choose the value explicitly (7b7f73b)
- **D36** — `Task::vfork` mirrors `vfork_done` for the OOM killer on the timer thread, so every path completing `vfork_done` must clear it too; syscall 448 dispatches to process_mrelease and `FlockHolder` records the taker's tgid (39ea702)
- **D37** — prctl's guarded options given refused arguments return `EINVAL` from an arm of their own so that the prctl and futex default arms count only what is missing (options newer than 6.1 included), and a new guarded prctl arm must join that arm (99b4216)
- **D39** — `Kernel::unsharing` makes files whose shared store object is being copied with the BKL released wait per inode on `wake::LOCK`, and `ExeCache::forget` drops an inode whose data moved to another object (df6ee70)
- **D40** — With nonzero clock offsets, clone, execve and setns put the vDSO's svc stubs back through `Mm::vdso_syscalls`, never undone (an address space shared with a process back in the boot namespace keeps them), the loader maps `[vvar]` below the vDSO, and any new way to enter a time namespace must patch too (16364c0)
- **D41** — execve checks a script for writers (`ETXTBSY`) before looking at its interpreter, though the ELF interpreter and nested scripts are still unchecked, and tells the credential transition when another process shares the fs context, so set-id bits and file capabilities gain nothing then (c3e2f57, 6d9a9e8)
- **D42** — `dispatch()` reruns a syscall on `FUSE_RETRY` (a fresh token per syscall, at most 16384 times, then `EIO`, seen once by ptrace and seccomp) after reading the recorded page or serving the walk queries, so a side effect before a handler's last walk repeats and a handler mapping memory errors to `EFAULT` itself loses the retry (c008749, 3957a03)
- **D43** — The ELF loader follows 6.1's header, `PT_INTERP` and `AT_PHDR` rules, maps segments in order over each other (a shared page takes the later segment's permissions), loads ET_EXEC interpreters at their own addresses and turns late failures into an `Image::doomed` killed with SIGSEGV after the point of no return, while `Machine::spawn` still starts a doomed image at pc 0 (ef68770, 6d9a9e8)
- **D44** — PI futexes keep `PiWaiter`s in the futex queue and the word as the owner record, following `futex_lock_pi_atomic` without priority inheritance or ordering, with the requeue-PI commands `ENOSYS`, no hand-over at execve, and `pi_owners_exited` giving a gone owner's locks to their first waiter with `FUTEX_OWNER_DIED` (edd7f95)
- **D45** — `InSyscall` stamps each BKL syscall's global number on `Task::syscall` from after the entry stop and seccomp until the end-of-syscall sweeps, cleared on every way out of dispatch before stops and signal delivery, so anything waiting inside dispatch after the sweeps must come after the guard is dropped or it holds back the overlay sweep (0d82807)
- **D46** — `ExeFile::ovl` keeps the (mount, node, `Hold`) of a program run through an overlay, so `open_exe_file` can run a removed one from its store inode with the overlay mount's noexec and nosuid, until the overlay instance is gone (`ENOENT`) (9590431)
- **D48** — Each thread carries its own credentials: clone sets the creating thread's, execve sets both of the main thread's copies, and the scheduling, ioprio, `PRIO_USER` and `RLIMIT_NPROC` checks use the target thread's, so code writing `Task.cred` or `ThreadGroup.cred` directly leaves the two apart (1be1142)

> Full text of D50, D51, D56: `git show 3ad68857ee83ca9bb227d4d55db8611416ae226b:.agents/decisions/kernel/process.md`

- **D50** — Mark the vfork parent frozen during the wait and retain the mark through ptrace or error until a still-pending freeze has thawed (b593172)
- **D51** — At the arm64 exec point of no return, clear READ_IMPLIES_EXEC for every program before privileged personality cleanup, and read the brk flag before borrowing the address space (5760479)
- **D56** — Kernel-wide test hook 14 forces TCP filter refusal until the test explicitly disables it, and builds without test hooks always report ordinary host behavior (52dcc177; 20137e4c; d053be24; 39e55b4b)

> Full text of D18, D19, D23, D47, D49, D52–D55, D57–D63: `git show 1ea0c1ef9d686d7126bec2029fc928d4f6fdfc6b:.agents/decisions/kernel/process.md`

- **D18** — Process files read in the order their code runs (global state/BKL/context before waits in `kernel.rs`, lifecycle order in `syscall/proc.rs` and `impl Machine`, dispatch before argument helpers in `syscall/mod.rs`, helpers after callers elsewhere), so new code goes where it runs rather than at file ends (0758e3c; d2b1dc3)
- **D19** — `itimer::Expiry` keeps deadlines as monotonic instants or host wall-clock times (re-derived on every wait pass, never cached across waits), and the timer thread watches the host for clock jumps (hal D11) and wakes all tasks, timerfds (kernel/vfs D23) and message queues (kernel/ipc D3) (63b9e59)
- **D23** — `sched_setattr` resolves keep flags before validation in `sys_sched_setattr` order, records/inherits `uclamp_req` without host effect (`EINVAL` with short structs), and reads short versioned structs into a zeroed full-size buffer (bc44b65, e43c40c)
- **D47** — `cgroup::notify_changes` runs after BKL syscall sweeps, at `task::exit` end, before freezer waits and after timer-thread OOM checks (returning immediately when no observations exist), so membership and freezer changes must be marked before those checkpoints (kernel/procfs D38, kernel/cgroup D2) (affb1e2; 0f5c5132)
- **D49** — `sched_yield` runs on the fast path (D6) by clearing `in_guest` and calling `std::thread::yield_now` (never touching guest memory afterwards) and falls back to yielding inside `Cx::unlocked` on the BKL path (78b3284)
- **D52** — A signal-woken no-BKL timed wait hands its validated deadline to the BKL handler in `TaskState::fast_sleep` (consumed before reading time args and cleared after `dispatch` in `Exit::Svc`), so any new no-BKL wait must record it and hand-overs outliving one dispatch need a separate field (9593bae, 27609fe)
- **D53** — `Machine::spawn` creates the task and starts its host thread before inserting the thread group, init's child and watched slot (detaching the console on failure per kernel/tty D21), and test hook 13 arms `Trap::ThreadStart` (`EAGAIN`) (143bb86)
- **D54** — `Clock::Process` and `Clock::Thread` carry `Task::serial` from a global counter so timers vanish when their target thread exits or process is reaped (zombie clocks stay readable without firing), and all CPU clock decoders must translate pids via `Cx::pid_in`/`Fast::ns_tid` (82433e6)
- **D55** — `TaskSync::KernelEntry` records the syscall number (`u64::MAX` for non-svc), x0–x5, sp and pc on every exit from guest code before the fast path and clears on re-entry for `/proc/<pid>/syscall` (kernel/procfs D40, forgotten on delivery per kernel/signal D27) (f1212d8)
- **D57** — `Machine::cpu_time_ns` saturating-adds exited-thread CPU totals and all live threads' CPU time under the BKL; consumers must compare deltas rather than assume monotonicity because exit-stage panics can skip credit (1e5de64e; 20137e4c; d053be24; 39e55b4b)
- **D58** — The borrow-check guard updates its thread-local count in one TLS access and allows inlining its destructor to reduce overhead when borrow counting is enabled (b65799c8)
- **D59** — `add_stats` in `task.rs` sums `DbtStats::fast_loops` alongside the other Tier 1 counters on thread exit and must be updated whenever new fields are added to `DbtStats` (49585f4e)
- **D60** — `.agents/maps/kernel/process.md` is condensed in place under one read rather than extracting `kernel/sched`, leaving `kernel/sched` as the cleanest next split candidate if scheduling or lifecycle semantics grow back toward 80% (4fcd3e13)
- **D61** — `sched_setattr` validates policy and permissions with `set_policy(..., false)` before running `setpriority`, committing `t.sched` (and `apply_host` when `!keep_params`) only after both succeed, so `setpriority` must stay independent of uncommitted `t.sched` (849bf98a)
- **D62** — `invalidate_code` in `task.rs` accepts guest page 0 directly (restricting the `ivau != 0` sentinel check to the `ivau_page` side-channel merge), so callers must never pass `0` expecting a no-op (0e02d7cd)
- **D63** — `Bkl::drop` wraps `k.flush_wakeups()` in `bug::catch` (waking every waiter directly on panic before unlocking) and `note_wake` ignores unlocked calls instead of writing a process-global fallback, so every production `request_wake*` caller must hold the BKL (15347732)

## Active (full detail)

### D6: A few hot syscalls run without the BKL
- **What**: stat, clock reads, futex wait/wake, nanosleep and sched_yield (D49) have a lock-free fast path that falls back to the BKL path when it cannot finish.
- **Why**: Parallel `lstat` (git's default) and runtime thread pools were serialised by the BKL, making `git status` slower than single-threaded, and spinning threads' sched_yield took the BKL only to return 0.
- **Tradeoffs**: Per-thread caches must be invalidated by a global epoch whenever root, cwd, credentials, mounts or namespaces change.
- **Watch out**: The fast path must stay "in guest" while touching guest memory so shootdowns wait for it, and a call that stops counting as running guest code (sched_yield) must not touch guest memory afterwards.
- **Source**: rounds 25 (`25-stat-without-bkl`), 38 (`38-split-bkl`); 78b3284

### D11: A dedicated timer thread delivers timers and runs periodic checks
- **What**: One host thread in `Machine` sleeps until the earliest deadline and handles interval and POSIX timers, CPU limits, the memory limit, load average, DNS stub answers, eventfd-signalling AIO polls (waiting on their host fds and timerfd deadlines, and off after a kernel-layer panic; kernel/vfs D42), the next expiry of timerfds behind `EPOLLEXCLUSIVE` gates (kernel/poll D4) and other periodic work.
- **Why**: Timer expiry, limits and AIO polls that signal an eventfd (which must complete while no thread is in `io_getevents`) cannot rely on guest threads, and the memory limit can only be checked periodically because the host kernel handles guest page faults directly; when every thread waits, memory cannot grow, so the periodic checks stop.
- **Tradeoffs**: Its work runs under the BKL and competes with guest syscalls; a finite `RLIMIT_CPU` keeps a 10 ms tick; while such AIO polls are pending it listens on every wake channel, so every kernel change wakes it.
- **Watch out**: Anything that changes a deadline or hands it work (the DNS threads, new AIO polls, exclusive gates) must signal its event, and programs keeping many eventfd-signalling polls pending load it.
- **Source**: rounds 19 (`19-deferred`), 20 (`20-robustness`); fe4bb2b, fffe1c1, f5c09f7

### D13: Time namespaces follow Linux 6.1 plus the 6.2 relaxations
- **What**: Monotonic and boottime offsets apply to clock reads and absolute waits; threads and vfork children enter at execve; entering one with offsets (at fork, execve or setns) puts svc stubs over the host vDSO's clock entries so clock reads reach the kernel layer, which adds the offsets (D40).
- **Why**: dockerd adds a time namespace when `/proc/<pid>/ns/time` exists and runc then requires `timens_offsets`; since all clocks (including the vDSO stubs) then passed through the kernel layer, offsets were cheap, and it was the last namespace type missing.
- **Tradeoffs**: Diverges slightly from the 6.1 contract where 6.2 relaxed it, and processes in a namespace with offsets read clocks through svc stubs, slower than the host's vDSO.
- **Watch out**: The fast path caches the offsets and must be invalidated on namespace entry, and a new way to enter a time namespace must patch the vDSO too.
- **Source**: round 162 (`162-time-namespaces`); 16364c0

### D14: kernel/process owns task execution, dispatch, the BKL, waits and accounting
- **What**: kernel/process owns tasks, processes, the BKL, syscall dispatch, the fast path, waits, scheduling and accounting, while bug containment, kill reports, test hooks and the BKL watchdog belong to `kernel/bug`, time and periodic checks to `kernel/time`, machine lifecycle and PID 1 to `kernel/boot`, and the existing exec, namespace, futex and credential boundaries remain.
- **Why**: Even after five splits, `kernel/process.md` remained 42,070 B (91.3% of one read) while still carrying standalone bug-containment and BKL-watchdog files.
- **Tradeoffs**: Shared files (`kernel.rs`, `machine.rs`, `syscall/mod.rs`) carry explicit exceptions in `.agents/MODULES.md`, cross-boundary changes consult several maps, pre-split decisions stay here, and vDSO construction belongs to `kernel/vdso`.
- **Watch out**: BKL acquisition and task entry/exit paths must keep updating `Hold` and calling `kernel::doing`/`done` and `bug::catch` as `kernel/bug` requires, alongside preserving `fast_sleep` handover, BKL charging and vfork freezer accounting.
- **Source**: dd6c288, d2b1dc3, b320f8e (bug list since updated by 1459292, 4e64e74, ce8a9ce and d9e1f0c, and shortened by D29, D30, D32, D43, D44, D52, D53, D54, D61 and D63); cf029f9a, 8a17e4a9; d053be24; 60790c14; 39e55b4b; e4d7bbe5

### D38: A watchdog thread reports long BKL holds without waiting for the BKL
- **What**: Every BKL acquisition records its holder (count, host tid, guest tid, tgid, comm, `Doing` and pc) in the cache-line `Hold`; a `bkl-watchdog` thread samples it every fifth of the threshold (10 s by default, `APPINE_BKL_WATCHDOG` in seconds, 0 off), reports a hold unchanged that long on stderr and in `Shared::stall` (which `Machine::stall` reads without the BKL), and queues end-of-hold lines in `Mutex<PendingLog>` for at most one on-demand `bkl-watchdog-log` drainer thread that acquires the BKL, writes all queued KERN_ERR records to `klog::printk` in FIFO order under `bug::catch`, and exits when empty; nothing is killed.
- **Why**: A hold that never ended froze everything without saying where, taking the BKL inside `ended` blocked the watcher, and spawning a separate logger thread per ended stall let consecutive loggers race for the BKL and write kernel log records out of order.
- **Tradeoffs**: Each acquisition pays a few relaxed stores and each entry a 40-byte thread-local write, durations are accurate only to one look (2 s by default), release-and-retake loops are not caught, and a failed drainer spawn clears and drops queued kernel-log records (while stderr and `Machine::stall` still carry the line).
- **Watch out**: Anything added to `Hold` must stay on its cache line as a plain store, a drainer blocked on an unreleased BKL holds `Arc<Shared>` until freed, a stall storm queues lines in memory while the BKL is contended, and an unending hold still blocks `Machine`'s drop.
- **Source**: 64a6107, d20fd3c (the blocking `ended` recorded in d9e1f0c), 1e6c63a9

### D64: Task and group lifecycle hooks coordinate group stops, sibling ptrace exits, syscall stepping and zombie-leader clocks

- **What**: `ThreadGroup::{stop_pending, leader_serial}` and `Task::{stop_pending, exit_started}` support calling `participate_group_stop` in `task::start`/`task::exit`, `ptrace::exit` in `task::run`'s `Flow::Exit` arm, and `ptrace::syscall_stepped` on skipped syscalls and `exec_done` in `syscall/mod.rs`.
- **Why**: Multi-thread group-stop notification, sibling `PTRACE_EVENT_EXIT`, syscall single-stepping and zombie-leader CPU clocks need shared task/group fields and lifecycle hooks rather than duplicated tracking across `signal`, `ptrace` and `time`.
- **Tradeoffs**: Slightly widens `ThreadGroup` and `Task` and couples `task::run`'s exit path to `ptrace::exit`.
- **Watch out**: Keep `Task::exit_started` synchronized between `exit`/`exit_group` and `task::run` so a traced thread never emits `PTRACE_EVENT_EXIT` twice, and keep `leader_serial` cleared alongside `leader_exit` on leader replacement.
- **Source**: 13dd5ae4

### D65: Flock state belongs to the kernel and waiting to the syscall

- **What**: Keep shared lock ownership in Kernel and handle interruption, nonblocking failure and retry in flock.
- **Why**: Different open descriptions can contend for the same object.
- **Tradeoffs**: The syscall must recheck on wake.
- **Watch out**: Every interrupted wait needs cleanup.
- **Source**: 021c7fc2

### D66: Host operations coordinate with the owning kernels

- **What**: Resolve store and overlay sampling under one BKL hold, preserve mount expiry, and acquire machines sharing a store in registry order for host truncation.
- **Why**: A provider change can affect several running address spaces.
- **Tradeoffs**: Cross-machine operations can take several locks.
- **Watch out**: Keep a stable lock order.
- **Source**: d5b808e8, f495f2cc

### D67: Syscall completion keeps replay and writeback at the correct boundary

- **What**: Replay matched executable opens and reads, leave batched receive retries local, and flush removed mappings at syscall completion and before task removal.
- **Why**: Restarting an entire syscall can repeat events or lose final writes.
- **Tradeoffs**: Handlers distinguish record-local from dispatcher retries.
- **Watch out**: Close outstanding opens when exec fails.
- **Source**: 93a9b204, 4309ef58, b421eac0

### D68: Descriptor limits follow the acting thread group

- **What**: Read NOFILE from the group and use Cx for bounded installation and reservation.
- **Why**: Threads sharing an fd table may belong to groups with different limits.
- **Tradeoffs**: Callers must identify the acting or target group.
- **Watch out**: Reservations count toward both process and system bounds.
- **Source**: 576501d3, 661d5f01

### D69: Child handoff and wait consumption preserve one reportable event

- **What**: Use exit signal, disposition and tracer relationship at reparenting and retain event data across retryable wait copies.
- **Why**: An adopting tracer can also become the real parent.
- **Tradeoffs**: An error after consumption returns EFAULT.
- **Watch out**: Avoid either dropping the only waitable exit or reporting it twice.
- **Source**: 25b0f2a0, 11816722

### D70: Clone and procfs preserve per-task no_new_privs

- **What**: Inherit the task's sticky flag at clone and retain the exited leader's value for procfs.
- **Why**: Group-wide state can misrepresent a sibling.
- **Tradeoffs**: The dead-leader record needs one more field.
- **Watch out**: Use the live task's value when available.
- **Source**: 71790fde

### D71: Dead IPC namespaces trigger deferred collection

- **What**: Share a kernel death flag with IPC namespaces and sweep at syscall completion until it stays clear.
- **Why**: Dropping a namespace during collection can expose another dead one.
- **Tradeoffs**: Most checkpoints need only a flag read.
- **Watch out**: Resources retained by a namespace fd must survive process exit.
- **Source**: 124c9c21

### D72: Guest software-step completion clears its exclusive reservation

- **What**: Clear the reservation in the task loop when a step produces the architectural exception.
- **Why**: The raw CPU stepping API also serves callers without exception-return semantics.
- **Tradeoffs**: The check sits outside the low-level step primitive.
- **Watch out**: Keep direct CPU stepping behavior intact.
- **Source**: d1fc9715

### D73: RT scheduling syscalls enforce the target thread group's `RLIMIT_RTPRIO`

- **What**: `set_policy` enforces the target thread group's soft `RLIMIT_RTPRIO` (requiring `rlim > 0` for RT policy changes and `priority <= rlim` to raise priority while allowing lowering, via a shared target-group `rlimit` closure), keeps `SCHED_DEADLINE` gated by `CAP_SYS_NICE`, keeps the `SCHED_IDLE` exit `RLIMIT_NICE` check unconditional for all transitions, and keeps RT policies recorded-only on host threads (D10).
- **Why**: Unconditionally requiring `CAP_SYS_NICE` for `SCHED_FIFO` and `SCHED_RR` rejected valid unprivileged RT priority changes permitted by `RLIMIT_RTPRIO` under Linux 6.1's `__sched_setscheduler`.
- **Tradeoffs**: Unprivileged threads granted RT policies still run at normal host priority to protect Android UI threads, and unprivileged `SCHED_IDLE` tasks cannot jump to RT policies without `RLIMIT_NICE` allowing their nice value.
- **Watch out**: Preserve atomic validation before committing `t.sched` (D61) and always consult the target thread group's limits rather than the caller's.
- **Source**: 0ddd8d50, ee941aed

### D74: `Kernel::open_files_matching` deduplicates descriptor tables via a pointer set and filters before cloning

- **What**: `Kernel::open_files_matching(pred)` deduplicates fd tables by pointer in a `HashSet` and clones only handles matching the caller's predicate, keeping `open_files()` for whole-kernel scans.
- **Why**: `Kernel::open_files()` previously performed quadratic fd-table deduplication and cloned every open handle across all processes even for single-inode lookups such as `opens_of`.
- **Tradeoffs**: Whole-kernel reconciliation scans allocate a small `HashSet` when many processes exist.
- **Watch out**: Predicates passed to `open_files_matching` must not re-entrantly borrow `RefCell` state already held by the scan.
- **Source**: b38371ff

### D75: Read-only ID syscalls run on the `Fast` path gated by `fast_epoch`

- **What**: `Fast` caches namespace-translated `ns_tgid`, `ns_ppid`, `ns_tid`, `uid`, `euid`, `gid`, and `egid` to dispatch `getpid`, `getppid`, `gettid`, `getuid`, `geteuid`, `getgid`, and `getegid` without the BKL while `fast_epoch` matches, sharing the epoch with `Kernel::fast_changed()` so `uid_map`/`gid_map` writes and live child reparenting in `finish_group` invalidate stale caches.
- **Why**: Frequent read-only ID queries otherwise acquired the BKL solely to return rarely changing namespace-translated values.
- **Tradeoffs**: `Fast` stores the cached IDs and performs an `Acquire` epoch load per fast ID call, and reparenting live children invalidates fast caches once per exiting parent.
- **Watch out**: Every path mutating credentials, tids, `ThreadGroup::ppid` of a live process, or user-namespace ID maps must call `Shared::fast_changed()` or `Kernel::fast_changed()` to avoid serving stale IDs.
- **Source**: a1fe8a19, de152464

### D76: `TaskSync` is cache-line aligned, non-sleeping fast syscalls stay in guest mode, and post-syscall sweeps are dirty-gated

- **What**: `TaskSync` is 64-byte aligned, `FastOutcome::{InGuest, LeftGuest, Fallback}` keeps non-sleeping fast syscalls in guest mode (skipping `KernelEntry`, `in_guest` toggling, `resume_checks`, and TLB/space reloads while Tier 1 polls `generations.code`), full guest entry uses a relaxed `in_guest` store plus `SeqCst` fence and relaxed generation loads (with an `Acquire` fence before TLB flushes), and `post_syscall_sweeps` checks thread-local, per-`Vfs`, per-`Store`, per-`Kernel`, and global dirty flags before running sweeps.
- **Why**: Unaligned `TaskSync` caused false sharing on `in_guest`, non-sleeping fast syscalls paid unnecessary `KernelEntry` and barrier costs, and `post_syscall_sweeps` cloned `Vfs` and borrowed `RefCell`s even when nothing was pending.
- **Tradeoffs**: Non-sleeping fast syscalls leave `KernelEntry` unpopulated (matching Linux's `"running\n"` `/proc/<pid>/syscall` output), and every sweep producer must keep its dirty flag synchronized.
- **Watch out**: Missing a dirty-flag set or early return delays or skips cleanup, and single-stepped tasks must keep returning `Fallback` from `Fast::syscall` because `Cpu::step` does not check `cpu->pending`.
- **Source**: da5ba1ef

### D77: `Bkl::drop` guards panic-catch wrappers and reuses `PENDING` capacity, while `Hold::held` occupies its own cache line

- **What**: `Bkl::drop` skips `bug::catch` when `has_closed()` or `has_pending_wakeups()` is false, retains cleared `PENDING` vector capacity after `flush_wakeups`, and places `Hold::held` on its own 64-byte cache line ahead of 64-byte-aligned `HoldInfo`.
- **Why**: Unconditional `catch_unwind` entries and dropped `PENDING` allocations added overhead to every BKL release, and `Hold::held` false-shared a cache line with `Hold::record`'s stores during BKL spinning.
- **Tradeoffs**: `Hold` grows from 64 bytes to 128 bytes.
- **Watch out**: Keep `Hold::held` isolated from `HoldInfo` fields written by `Hold::record` (and keep `kernel/bug` synchronized with `Hold`'s two-cache-line layout).
- **Source**: de152464

### D78: `Machine::wait_deadline` resolves a single console wake object lazily under the BKL

- **What**: `Machine::wait_deadline` resolves an optional `wake::Object` lazily under its existing BKL hold only when blocking is required (replacing `wait_objects`, with `wait_on`/`wait_until` passing `|_| None`), and `Machine::read_console_run_state` backs `read_console_run_timeout`.
- **Why**: `Machine::read_console`, `read_console_run_timeout`, and `write_console` previously took an extra BKL lock solely to query console wake objects before entering the wait loop.
- **Tradeoffs**: `Machine::wait_deadline` supports at most one wake object per wait, matching current `Machine` callers.
- **Watch out**: Generalize `wait_deadline` from `Option<wake::Object>` back to a slice if a future `Machine` helper must wait on multiple wake objects at once.
- **Source**: 2f321b12

## Superseded

- ~~Deadlines stay plain `Instant`s (D15 in part, 96cd8bd)~~ → replaced by **D19** in 63b9e59 (2026-11-07)
- ~~PI futexes stay `ENOSYS` (D33's tradeoff, 5d7b936)~~ → replaced by **D44** in edd7f95 (2026-11-09)
- ~~bug.rs, debug.rs and syscall/mod.rs are filed in kernel/process though the registry does not list them (D18 in part, 0758e3c)~~ → replaced by **D14** in d2b1dc3 (2026-11-10)
- ~~The futex handler re-reads the timeout of an interrupted wait, and only relative deadlines are handed over (9593bae's tradeoff)~~ → replaced by **D52** in 27609fe (2026-11-10, no SUPERSEDES declared)
- ~~kernel/process also covers execve and the ELF loader, futexes and namespaces (D14, dd6c288, d2b1dc3)~~ → replaced by **D14** in b320f8e (2026-11-11)
- ~~D14 ownership of timers, periodic checks and PID 1~~ → replaced by **D14** in 8a17e4a9 (2026-11-14)
- ~~Comparing observations at each checkpoint under D47~~ → narrowed by **D47 and kernel/cgroup D2** in 0f5c5132 (2026-11-17).
- ~~Spawning a separate `bkl-watchdog-log` thread per ended stall under D38 in d20fd3c~~ → replaced by **D38** in 1e6c63a9 (2026-11-21).
- ~~D14 ownership of bug containment and the BKL watchdog (dd6c288, d2b1dc3, 8a17e4a9)~~ → replaced by **D14** and kernel/bug **D1** in e4d7bbe5 (2026-11-22).
- ~~`sched_setattr` applies policy before validating nice (D27's caveat, 70c1bc6, dcacfb6)~~ → replaced by **D61** in 849bf98a (2026-11-23, no SUPERSEDES declared).
- ~~Keeping `getppid` on the BKL path because reparenting did not bump `fast_epoch` (a1fe8a19)~~ → replaced by **D75** in de152464 (2026-11-27, no SUPERSEDES declared).
- ~~Recording `TaskSync::KernelEntry` on non-sleeping fast-path syscalls (D55 in part, f1212d8)~~ → narrowed by **D76** in da5ba1ef (2026-11-27, no SUPERSEDES declared).
- ~~Keeping `Hold::held` and `HoldInfo` on one 64-byte cache line (D38 in part, 64a6107) and entering `bug::catch` unconditionally on every `Bkl::drop` (D63 in part, 15347732)~~ → refined by **D77** in de152464 (2026-11-27, no SUPERSEDES declared).
