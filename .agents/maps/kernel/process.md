# kernel/process Map

> Static understanding snapshot, not a decision history.
> See `.agents/decisions/kernel/process.md` for the paired decision history (also holds pre-split decisions on execve, namespaces, futexes, time, boot, and bug containment / BKL watchdog).
>
> Verified: 2026-11-27 (full mapping: 5 concerns; 17 claims checked by 1 independent verifier — 13 confirmed, 4 partial, 0 refuted, all patched; 3 confirmed bugs recorded)
>
> Maintained: 2026-11-28 (targeted verification only since the full mapping; latest change: host access to live port mappings)

## Responsibilities

`kernel/process` runs guest threads and processes inside the app's single host process: task/group
lifecycle (`clone`/`clone3`, run loop, exit, reparenting, reaping, `wait`, pgrps/sessions, pidfds,
`kcmp`), the BKL and borrow checking, syscall dispatch and sweeps, the no-BKL fast path, unified
wait/wake-up, host `Machine` stats/signals/waits/host-truncation sync, host-thread scheduling, and
remaining process-wide calls in `syscall/misc.rs` (CPU accounting, rusage/rlimits, `prctl`,
`personality`, host/domain names, `sysinfo`, `getcpu`, `getrandom`).

Root: `core/kernel/src/task.rs`, `process.rs`, `kernel.rs` (except `kernel/boot`'s `INIT`,
`kernel/time`'s `stop_checks`/`resume_checks`, `kernel/bug`'s `Hold*`/`Entry`/`Doing`/`doing`/
`done`), `cell.rs`, `syscall/proc.rs` (except execve/unshare), `syscall/fast.rs` (except futex),
`machine.rs` (except `kernel/boot` lifecycle, `kernel/time`'s `timers`, `kernel/bug`'s
`take_kills`/`stall`), `syscall/sched.rs`, `syscall/mod.rs` dispatcher (except `kernel/signal`'s
restart/ENOSYS, `kernel/time` time helpers, `kernel/bug`'s `TEST_SYSCALL`), and `syscall/misc.rs`'s
process-wide calls (excluding `kernel/time`, `kernel/boot`, `kernel/signal`, `kernel/futex`,
`kernel/ns`; `membarrier`/`umask`/cap-seccomp prctls live in `kernel/mmu`/`kernel/vfs`/`kernel/cred`).

## Key types

### Tasks and processes

- `Task` — per-thread state: `tid` (`Cell`, changed by non-leader execve), `serial`, `tgid`,
  `start_ticks`, `cred`, signals (`pending`/`sigmask`/`saved_sigmask`), `killed`/`stop_pending`/
  `frozen`/`pdeathsig`/`exit_status`/`exit_started`/`vfork`/`no_new_privs`, `syscall` (`SYSCALLS`
  seq + nr), CPU/ctxt bases (`clock`/`cpu_base`/`cpu_prev`/`ctxt_base`/`host_tid`), scheduling
  (`nice`/`sched`/`ioprio`/`deadline`/`uclamp`/`timerslack`/`affinity`), `robust_list`, ptrace/
  seccomp (`trace`/`syscall_traced`/`stepping`/`step_again`/`seccomp`/`seccomp_on`/`seccomp_allow`),
  `event`/`sleeping`, `pid_ns_children`/`ns` (`NsProxy`), `TaskRes` (procfs snapshot of `mm`/`fds`/
  `fs`), and private `TaskState` (`mm`, `fds`, `fs` (`FsContext`: root, cwd, umask, `mnt_ns`),
  `group`, `vfs`, `altstack`, `clear_child_tid`, `vfork_done`, `restart`, `fast_sleep`, `sem_undo`).
- `TaskSync` — 64-byte-aligned lock-free `in_guest`, engine `Kicker`, and atomic `KernelEntry`
  (syscall nr or `u64::MAX`, x0–x5, sp, pc for `/proc/<pid>/syscall`; set on sleeping fast syscalls
  and BKL exits, skipped on non-sleeping fast syscalls).
- `ThreadGroup` — one process: `tgid`, `ppid`, `parent_thread` (`real_parent`), `pgid`, `sid`,
  live `threads` (leader first while alive), `children` (zombies included), `exiting`/`single_exit`/
  `leader_status`/`zombie`/`leader_exit`/`leader_serial`/`leader_no_new_privs`, `exit_signal`,
  `stopped`/`stop_pending`/`stop_signal`/`stop_report`/`cont_report`/`frozen`/`killable`,
  `dumpable`/`dumpable_root`/`mm_user_ns`, `oom_score_adj`/`oom_score_adj_min`, `personality`/
  `execed`/`thp_disable`/`coredump_filter`/`core`, own/children CPU time and max RSS (`rss_sampled`),
  `child_subreaper`, `rlimits`, `cred` (main thread's, retained after exit), `pid_ns`, `cgroup`,
  `leader_tracer`/`exit_tracer`/`leader_seccomp`, `aio`, `ctty`/`tty_old_pgrp`, `exe`, timers,
  `serial`.
- Default rlimits: stack 8 MB/unlim, core 0/unlim, nofile 1024/4096, memlock 8 MB, nproc half of
  default `threads-max`, sigpending 16384, msgqueue 819200 (charged by `kernel/ipc`), nice/rtprio 0,
  rest unlimited.
- `ExitStatus` (`Exited(i32)` | `Signaled { signal, pc, fault_addr, core_dumped }`), `CpuTime`
  (monotonic total/sys ns via `cpu_adjust`), `Rusage`, `task::pool` (<= 8 idle host threads, 5 s
  linger; BKL-queued jobs notify on BKL release).

### Global state, locking, waiting and the fast path

- `Kernel` — BKL-guarded globals: `tasks`, `groups`, `pid_nss`, `held_reapers`, `waiters`,
  `pid_max`/`next_tid`/`threads_max`/`nr_open`/`mount_max`/`pipe_max_size`, `watched`, `init_ns`,
  `ipcs`/`ipc_dead`, `net_nss`, `cgroups`, `epoll_watches`, `unsharing`, `host_routes`, `kills`
  (`kernel/bug`), `fast` epoch `Arc`, and subsystem tables.
- `Shared` — BKL `Mutex<Kernel>`, `Hold`/spinners/`stall` (`kernel/bug`), `fast` epoch, sharded
  `Futexes` (`FutexesRef`), `timers` event, `dns_answers`, `checks_idle`.
- `Bkl` (guard running release hooks on `Drop`), `Cx` (kernel-entry context), `cell::RefCell`
  (per-thread borrow-counted `RefCell`).
- `wake`: channels `FILE`, `OTHER`, `CHILD`, `WATCHED`, `EXITED`, `LOCK`, `SIGNAL` (plus internal
  `OBJECTS`); `wake::Object` is an object address (`wake::of`/`of_value`); `Waiter` is
  `(Arc<Event>, interest, Option<Box<[wake::Object]>>)` in `Kernel::waiters`.
- `fast::Fast` — per-thread no-BKL snapshot: `epoch`, `traced`, `paths` (`Cached`, root/cwd inos,
  `eaccess_cred`, `access_cred`), `tid`, translated `ns_tgid`/`ns_ppid`/`ns_tid`/`uid`/`euid`/
  `gid`/`egid`, `cpu_clock`, `mm` id, `shared_maps`, `vdso` `(addr, svc)`, time-ns offsets.

## Public entry points

### Syscalls

- Lifecycle: `clone` (220), `clone3` (435), `exit` (93), `exit_group` (94), `set_tid_address` (96),
  `wait4` (260), `waitid` (95), `setsid` (157), `setpgid` (154), `getpgid` (155), `getsid` (156),
  `getpid`/`getppid`/`gettid` (172/173/178), `pidfd_open`/`getfd` (434/438), `kcmp` (272).
- Scheduling: `getpriority`/`setpriority` (141/140), `ioprio_set`/`get` (30/31),
  `sched_setparam`/`getparam` (118/121), `sched_setscheduler`/`getscheduler` (119/120),
  `sched_setaffinity`/`getaffinity` (122/123), `sched_yield` (124), `sched_get_priority_max`/`min`
  (125/126), `sched_rr_get_interval` (127), `sched_setattr`/`getattr` (274/275).
- Misc: `times` (153), `getrusage` (165), `getrlimit`/`setrlimit`/`prlimit64` (163/164/261),
  `prctl` (167: name, pdeathsig, child_subreaper, dumpable, no_new_privs, timerslack, timing,
  thp_disable, speculation_ctrl), `personality` (92), `sethostname`/`setdomainname` (161/162),
  `getcpu` (168), `sysinfo` (179), `getrandom` (278).

### Internal and host entry points

- Core API: `task::{start, release_mm, current}`, `process::{finish_group, finish_reported,
  tracer_released}`, `Shared::{lock, fast_changed}`, `Kernel::{open_files, open_files_matching,
  fast_changed}`, `block`/`block_for`, `Cx::{wait*, unlocked, install_fd, reserve_fd, grow_stack*}`,
  `request_wake*`, `signal_later`/`flush_signals`, `syscall::{dispatch, copy_result,
  retry_user_pages, tl_sweep}`.
- Host `Machine`: `processes`, `cpu_time_ns`, `rootfs_stats`, `dbt_stats`, `signal`,
  `wait`/`try_wait`/`wait_or_power`, `set_routes`, `trim_memory`, `sync`, `port_mappings`, `take_listens`,
  `take_changed_dirs`, console ops (`open`/`read`/`read_run*`/`write`/`resize`/`close`/
  `foreground*`/`cwd`/`password_input`), crate-internal `store_changed` and `truncate_host_object`.
- Sysctls/procfs: `threads-max`, `pid_max`, `ns_last_pid`, `/proc/<pid>/task/<tid>/children`.

## Data flow / lifecycle

### Tasks and host threads

- Guest threads run 1:1 on host pthreads. Exited threads return to `task::pool` (<= 8 idle, 5 s)
  and engine `Cpu`s to the spare list (<= 8).
- `thread_main` wraps `run` in `catch_unwind` (calling `kernel::done()` afterwards; uncaught panics
  abort). Under the BKL at startup, `run` sets `CURRENT`, records `clock`/`cpu_base`/`host_tid`/
  `ctxt_base`, calls `sched::host_started` (re-applying nice, ioprio with reset on reuse, and
  cgroup-clamped affinity if restricted or reused), builds `Fast`, and attaches Tier 1 `Dbt`
  outside the BKL.
- `Machine::cpu_time_ns` saturating-adds `Kernel.cpu.total_ns` and live tasks' `cpu_ns` under the
  BKL without `/proc` (excludes UI/timer CPU; exit panics can skip credit, so compare deltas).
- `Machine::rootfs_stats` resolves paths under one BKL hold via `resolve_mountpoint`
  (`Offline::HostStat`, keeping mount expiry): `Some(Stat)` for store/overlay, `None` for
  `ENOENT`/`ENOTDIR`, `EOPNOTSUPP` for guest-only nodes.
- `truncate_host_object` locks running kernels sharing a store in `RUNNING` order (scoped threads so
  TLS `HELD` stays distinct per kernel), runs `Mm::file_truncated` + `Mm::sync_except(None)` on
  affected `Mm`s, then calls `file.set_len(0)` before unlocking.

### clone and clone3

- Global TIDs come from `alloc_tid`: sequentially below `Kernel::pid_max` (default `PID_MAX =
  4 << 20`, writable in `pid_max_min()..=PID_MAX` where `pid_max_min() = (RESERVED_PIDS + 1).max(
  host_cpus().len() * 8)`), wrapping to `RESERVED_PIDS` (300, or `INIT + 1` when `pid_max <= 300`).
  `ns_last_pid` clamps `next_tid` into `(INIT + 1)..=pid_max`. `choose_pid` validates `clone3`
  `set_tid` levels (`1..pid_max`, 1 required for empty child pidns, `CAP_SYS_ADMIN` or
  `CAP_CHECKPOINT_RESTORE` in level owner, `EEXIST` if taken).
- `clone` uses low 32 flag bits (`CLONE_PIDFD | CLONE_PARENT_SETTID` → `EINVAL`). `clone3` requires
  `64 <= size <= PAGE_SIZE` (`EINVAL` < 64, `E2BIG` > page or non-zero past 88 B), rejects unknown
  flags, `CLONE_DETACHED`, `exit_signal` > 64 or with `CLONE_THREAD`/`CLONE_PARENT`,
  `CLONE_SIGHAND | CLONE_CLEAR_SIGHAND`, mismatched `stack`/`stack_size`, `set_tid_size >
  MAX_PID_NS_LEVEL`, `CLONE_PIDFD | CLONE_PARENT_SETTID` when `pidfd == ptid`, and bad
  `CLONE_INTO_CGROUP` (`size < 88` → `EINVAL`, non-cgroup2-dir fd → `EBADF`). No separate `fork`/
  `vfork` syscalls exist on aarch64.
- Invalid combos (`EINVAL`): `CLONE_THREAD` without `CLONE_SIGHAND`, `CLONE_SIGHAND` without
  `CLONE_VM`, `CLONE_PIDFD` with `CLONE_THREAD`/`CLONE_DETACHED`, `CLONE_PARENT` from a pidns init,
  `CLONE_THREAD` with `CLONE_NEWPID` or differing `pid_ns_children`, `CLONE_NEWIPC | CLONE_SYSVSEM`,
  `CLONE_NEWNS`/`CLONE_NEWUSER` with `CLONE_FS`, `CLONE_NEWUSER | CLONE_THREAD`. Cross-cgroup
  `CLONE_INTO_CGROUP | CLONE_THREAD` is `EOPNOTSUPP`.
- Limits: `RLIMIT_NPROC` counts live tasks per kernel real uid across user namespaces (exempt:
  `ruid == 0`, `CAP_SYS_RESOURCE`, `CAP_SYS_ADMIN`); `threads_max` binds everyone including root
  (`EAGAIN`; default: RAM MB capped at half host fd limit, min 20, 4096 if unknown; writable
  `20..=0x3fffffff`); cgroup `pids.max` and freezer are checked before `choose_pid`. Host
  `Machine::spawn` skips these limits.
- Address space is shared (`CLONE_VM`) or quiesced and forked (`kernel/cow`); `fds` and `fs` are
  shared or copied (`fds` capped to `nr_open`). New groups take `ppid`/`parent_thread`/`exit_signal`
  from caller (or caller's parent on `CLONE_PARENT`). Child CPU copies regs (`x[0] = 0`), optional
  stack/TLS; `CLONE_VM` without `CLONE_VFORK` clears `altstack`. Inherits `sigmask`, `comm`,
  creator's `cred`, scheduling/uclamp/timerslack/ioprio/affinity, `no_new_privs`, `seccomp`
  (publishing atomic cache), and ptrace options.
- Registration: `register_pid` across pidns levels, writes `ptid`/`ctid`, installs CLOEXEC `pidfd`
  (`Cx::install_fd`), then `task::start` spawns/queues host thread, adds to `group.threads`
  (joining active `group.stopped`/`stop_pending` and kicking), calls `mm.add_user`, inserts into
  `k.tasks`, and notifies `cgroup::task_changed`. Failures release pid and close pidfd. Frozen
  children are woken to stop on entry. Non-thread children join `k.groups` and parent's `children`.
- Ptrace reports `PTRACE_EVENT_FORK`/`VFORK`/`CLONE`. For `CLONE_VFORK`, parent calls
  `cgroup::enter_frozen`, waits via `wait_alone` (`TASK_KILLABLE`) until `vfork_done` or `killed`,
  clears `Task::frozen` unless group is freezing, and reports `PTRACE_EVENT_VFORK_DONE`; child's
  `Task::vfork` stays set until `release_mm` so OOM skips it.

### Run loop

- Entering guest mode: `shared.resume_checks()`, `entry.leave()`, `in_guest = true` (`Relaxed`),
  `fence(SeqCst)`, `Relaxed` generation loads (pairing with `Mm::sync`/`shootdown`'s `SeqCst`
  bump-then-read; `fence(Acquire)` precedes `tlb_flush` on TLB change), re-attaches `Dbt` on
  `space` change, flushes TLB on `tlb` change, invalidates missed DBT code ranges on `code` change
  (once per shared `CodeSpace`).
- Non-sleeping fast syscalls stay in guest mode and skip the header, polling only `generations.code`
  for un-kicked Tier 1 code-only invalidations (TLB/space changes kick via `cpu->pending`).
- Execution: `PTRACE_SINGLESTEP` runs `Cpu::step` (or reports in place when `step_again` is set;
  completed step calls `clear_exclusive()` before `ptrace::step_done`); else Tier 1 `Dbt::run`
  (unless HW breakpoints active) or Tier 0 `Cpu::run`.
- `Exit::Svc` tries `Fast::syscall` unlocked under `bug::catch`: `InGuest` continues directly;
  `LeftGuest` clears local `in_guest` and re-enters via header; `Fallback` sets `KernelEntry` (if
  not already set by fast sleep), clears `in_guest` (`Release`), records `kernel::doing`, locks BKL,
  and calls `handle`. Fast-path panics clear `in_guest`/`sleeping`, call `bug::kill`, and exit.
- `handle`: checks `killed`, drains `ivau_page` (`invalidate_code` accepts page 0 directly; only
  `ivau_page` treats 0 as unset), handles `Svc` (clearing `fast_sleep` afterwards), `Kick`
  (signals), `Undefined` (`SIGILL`), `Breakpoint`/`Watchpoint`/`HwBreakpoint` (`SIGTRAP`),
  `Alignment`/`StackAlignment` (`SIGBUS`), `HostFault` (`bug::kill`), and `Page` faults (`kernel/mm`:
  CoW break, already mapped, `try_fault_in`, FUSE `fill`, past-EOF `SIGBUS`/remap, stack growth,
  else `page_fault_signal` `SIGSEGV`/`SIGBUS` with synthesized ESR).
- `Flow::Exec` refreshes `generations` and re-attaches `Dbt` outside BKL. `Flow::Exit` calls
  `ptrace::exit` if `!exit_started && !k.stopping` (sibling `PTRACE_EVENT_EXIT`) and
  `syscall::flush_at_exit` under BKL. Every BKL exit calls `fast.refresh` if `fast_epoch` moved.

### Syscall dispatch and sweeps

- Under BKL: saves `orig_x0`, `cx.grow_stack_to(sp)`, `ptrace::syscall_start`/`syscall_entry`
  (`NO_SYSCALL` = `u64::MAX` pre-sets `ENOSYS` when traced/filtered), `seccomp::check` (`Run`/
  `Skip`/`Restart`/`Exit`; skipped calls run `syscall_exit`, `syscall_stepped`, `forget_syscall`
  unless `Restart`, `signal::deliver`).
- Translates pid args (`pid_args`: invisible → `NO_PID = 0x3fff_ffff`, negative pgids negated,
  `fcntl(F_SETOWN, -1)` → `-ns.find(1)`), starts fresh FUSE token, prefaults read/write/pread/
  pwrite/vectored buffers into FUSE mappings, clears `mm::take_wanted`, enters `InSyscall`
  (`Task::syscall = (SYSCALLS seq, nr)`).
- Handler loop retries `FUSE_RETRY` from start up to 16384 times (`EIO` past limit): serves
  `mm::take_wanted()` via `fuse::fill` (`EINTR` → `ERESTARTSYS`, else `EFAULT`) or `vfs::fuse::serve`
  (recording FUSE opens in `exec_opens` and calling `exec_opens.retry()`). Ptrace/seccomp see one
  syscall. `exit`/`exit_group` (calling `ptrace::exit`) and `rt_sigreturn` return before sweeps and
  `Mm::sync`. Failed exec closes `exec_opens`.
- `post_syscall_sweeps` checks `tl_sweep` (`FUSE_RELEASED`, `UNIX_DEFERRED`, `VFS_RELEASED`),
  `Vfs::has_sweep_work`, `Kernel::ipc_dead`, `cgroups.has_pending`, `fuse::SWEEP`, and
  `mm::has_unmapped_fills` in one branch; when dirty, runs pending sweeps in order: (1)
  `fuse::flush_released`, (2) `unix::collect_deferred`, (3) `fuse::sweep`, (4)
  `mount::collect_released` (detached mounts, unlinked tmpfs, store releases, closed sparse memfd
  sizes), (5) `Kernel::collect_ipc`, (6) `mount::sweep_overlays`, (7) `cgroup::notify_changes`.
- Drops `InSyscall`. Successful exec runs `mm.sync(&sync)` unconditionally then `exec_done`
  (`ptrace::exec_hw_flush`, doomed → `signal::deliver`, else `ptrace::exec`, `syscall_exit`,
  `syscall_stepped`, `signal::deliver`). Normal syscalls translate result pids
  (`96|155|156|157|172|173|178`, `fcntl(F_GETOWN)`), call `poll::drained` on `EAGAIN` or short
  `read`/`write`/`sendto`/`recvfrom`, write `x[0]`, run `mm.sync(&sync)` when `mm.needs_sync()`,
  then `ptrace::syscall_exit` (if `exit_stop`), `syscall_stepped`, `signal::deliver`.
- `copy_result`/`retry_user_pages` fills cold FUSE output pages after event/message consumption
  without re-running the operation (`EFAULT` on failure).

### Exit, reparenting and reaping

- `exit` sets `exit_status` (and `leader_status` for leader); `exit_group`/fatal signals set
  `ThreadGroup::exiting` once, mark all threads `killed`, and wake them. Idempotent `begin_exit`
  runs before any `PTRACE_EVENT_EXIT`, letting the last live thread to enter exit set `g.exiting`
  and `g.single_exit = true`.
- `task::exit` runs in `bug::catch`-contained stages under BKL:
  - Stage 1: core regs, `dbt_stats` (`add_stats` including `fast_loops`), `cpu_self` into `k.cpu`/
    `group.cpu`, `leader_exit` + `leader_serial` (if leader), last-thread `sample_rss`, `release_mm`
    (robust list, shared-mm `clear_child_tid` zero + wake, `vfork_done` + clear `Task::vfork` +
    `signal_later`, `mm.remove_user`), `ptrace::tracee_exited`, `seccomp::exited` (saves
    `leader_no_new_privs` and `leader_seccomp`). On panic: idempotent salvage `bug::catch` completes
    `vfork_done` and `remove_user` before `bug::kill`.
  - If `mm.has_fills()`: runs `syscall::flush_mappings` (releasing/re-locking BKL) so final robust/
    `clear_child_tid` writes reach FUSE before exit becomes visible.
  - Removes from `k.tasks`, `release_pid(tid)`, `futex::pi_owners_exited`, `ipc::reap_shm`, removes
    from `g.threads`, `cgroup::task_changed`.
  - Non-last thread: hands `parent_thread == tid` children to `alive_thread` (spliced to tail),
    sends `pdeathsig`, calls `signal::retarget` and `signal::participate_group_stop`, releases
    `sem_undo` if last sharer (`Rc::strong_count == 1`).
  - Last thread calls `finish_group` (each step contained): (1) release `aio`; (2) if pidns init,
    mark `ns.dead` (`ENOMEM` on future registrations) and `SIGKILL` other members; reparent children
    to nearest `child_subreaper` in same pidns level with live threads (mid-exit still eligible),
    else non-dead pidns init, else `INIT` (`exit_signal = SIGCHLD`; bumps `fast_changed` if any live
    child's `ppid` changed; `INIT` frees zombies immediately; adopting `exit_tracer` clears
    `exit_tracer` and drops duplicate `trace_exits`; other non-tracer-held zombies call
    `hand_to_parent`); (3) `pdeathsig`; (4) `sem_undo` if last sharer; (5) `release_ctty` (session
    leader drops PTY → `SIGHUP` to fg pgrp, or if already hung up `SIGHUP`+`SIGCONT` to
    `tty_old_pgrp`). Pidns init with remaining members defers `finish_reported` via `hold_reaper`
    until `release_pid` sees all other members reaped.
  - `finish_reported` (steps 6–9): records `k.watched`, calls `ptrace::group_finished` (non-parent
    tracer holds zombie via `exit_tracer` until collected and `tracer_released` calls
    `hand_to_parent`), else `hand_to_parent`, calls `ptrace::tracer_exited`, sends `SIGHUP`+
    `SIGCONT` to newly orphaned stopped pgrps, requests `wake::CHILD` (and `wake::WATCHED`).
  - `hand_to_parent`: missing parent or `INIT` reaps immediately; untraced `SIGCHLD` with explicit
    `SIG_IGN` reaps silently, `SA_NOCLDWAIT` notifies and reaps; else sets `zombie = Some(status)`
    and notifies if `exit_signal != 0`.
  - Final cleanup: `mqueue_closed` (`Rc::strong_count(&fds) <= 3` sets `fds_gone`), resets
    `task.ns = k.init_ns`, drops `(group, task)` under BKL, `fuse::flush_released`, `unix::collect`
    (`last || fds_gone || unix::take_deferred()` and `!unix_inflight.is_empty()`), `collect_unused`,
    `collect_ipc`, `cgroup::notify_changes`, `request_wake_on(wake::EXITED)`, clears `CURRENT` under
    BKL. CPU state frees outside BKL.
- `wait4`/`waitid`: `wait4` rejects `pid == i32::MIN` (`ESRCH`); `waitid` supports `P_PIDFD`
  (`ECHILD` if reaped, `EAGAIN` if nonblocking without `WNOHANG`) and `WNOWAIT`. Scans `children`
  (target, `__WNOTHREAD`, `eligible_child` exit-signal check unless traced by caller; zombies count
  only when `want.exited`; `exit_tracer` zombies keep parent waiting), reaping zombies (adding CPU/
  maxrss to parent's `child_*`), then stop/continue reports, then `tracees` (ptrace-stops need no
  `WUNTRACED`/`__WALL`), then `trace_exits`. No match → `ECHILD`; else blocks on `wake::CHILD`
  (`ERESTARTSYS` on interrupt). Writes results via `copy_result` (`EFAULT` after consumption on
  failure).
- Exited leader keeps its tgid registered until group reap; `leader_exit`, `leader_serial`, and
  `leader_no_new_privs` back procfs and zombie CPU-clock reads until reap or non-leader execve
  (currently skipped when the non-leader is already the sole thread; see Confirmed bugs).

### The BKL

- Single `Mutex<Kernel>` protecting non-atomic `Rc`/`RefCell` objects. Held for BKL exits, timer
  thread, and `Machine` calls; never held in guest code.
- Released mid-syscall only by waits and `Cx::unlocked` (`Send` closure; `Relock` re-locks BKL and
  clears `sleeping` on return/unwind). `unlocked` uses: host fd/stdio I/O, regular-file
  non-`O_APPEND` I/O (`f_pos_lock`), `fsync`/`sync`/`syncfs`/`O_SYNC`/`O_DSYNC`, regular-file and
  loop-device `sync_file_range`, `MS_SYNC` `msync`, `madvise` (`NORMAL`/`RANDOM`/`SEQUENTIAL`/
  `WILLNEED`/`POPULATE_READ`/`POPULATE_WRITE`), regular-file `copy_file_range`/`sendfile`,
  `make_private` store copy, `execve` image build, GPU render-node ops, BKL-path `sched_yield`,
  test hook.
- Acquisition: `try_lock`, else up to `(host_cpus / 2).clamp(1, 4)` spinners spin for <= 30 µs
  (clock checked every 64 iterations) reading only `Hold::held` (on its own 64-byte cache line ahead
  of 64-byte `HoldInfo`), else `Mutex::lock` (poison accepted). Sets `held`, records `HoldInfo`
  (relaxed stores, no clock), sets `HELD = Shared::id()`, calls `mm::defer_unmaps()`.
- Release (`Bkl::drop`): (1) `tty::has_closed()` → `bug::catch(hang_up_closed)`; (2) `k.ipc_dead` →
  `bug::catch(collect_ipc)` (loops until clear); (3) `has_pending_wakeups()` →
  `bug::catch(flush_wakeups)` (notifies `epoll_watches`, matches waiters, keeps cleared `PENDING`
  capacity <= 1024; on panic wakes all waiters directly); (4) clears `held`, unlocks, clears `HELD`,
  calls `pool::notify_pending()`, `flush_signals()`, `mm::release_unmapped()`, `mm::spare::refill()`.
- Borrow checks: `cell::RefCell` counts per-thread borrows; `block_for` and `Cx::unlocked` assert 0
  in debug builds and release builds with `borrow-check`. Store also checks `bkl_holder()`.

### Fast path (`syscall::fast`)

- Runs unlocked when `!traced`, `ivau_page == 0`, `!syscall_traced`, `!stepping`, and seccomp allows
  (off, vDSO clock stub, or `seccomp::always_allowed`):
  - `futex`: `WAIT`/`WAKE`/`WAIT_BITSET`/`WAKE_BITSET` with non-zero bitset, `FUTEX_CLOCK_REALTIME`
    only on `WAIT_BITSET`, 4-byte-aligned writable page; `FUTEX_PRIVATE_FLAG` skips `shared_maps`
    lookup, non-private requires `!in_shared_map`.
  - `nanosleep`, `clock_nanosleep` (`REALTIME`/`MONOTONIC`/`BOOTTIME`, flags `0 | TIMER_ABSTIME`).
  - `clock_gettime` (host clocks with time-ns offsets, or own CPU clock by `ns_tid`), `clock_getres`
    (host clocks), `gettimeofday`.
  - `sched_yield` (clears `in_guest`, yields host thread, returns `LeftGuest`).
  - ID queries (`getpid`, `getppid`, `gettid`, `getuid`, `geteuid`, `getgid`, `getegid`) gated by
    `fast_epoch`.
  - `newfstatat`/`statx`/`readlinkat`/`faccessat`/`faccessat2` when `fast_epoch` matches, root is
    `ROOT_MOUNT` store dir, `Cached::usable`, path is non-empty absolute or `AT_FDCWD`-relative
    staying in metadata cache across no other mount, and creds have `searches_all()` (`faccessat`/
    `faccessat2` use `eaccess_cred` with `AT_EACCESS` else `access_cred = for_access()`, falling
    back if `!searches_all()`).
- Rebuilt under BKL when `fast_epoch` advances via `fast_changed` (`chdir`/`fchdir`/`chroot`,
  `set_fs`, `set_cred`, mount/detach changes, `setns`, `unshare(CLONE_NEWNS)`, `execve`, `uid_map`/
  `gid_map` writes, live child reparenting). Fallbacks leave written guest memory intact; panics
  kill the process.
- Fast `futex` uses `Shared::futexes` sharded locks and flushes signals immediately. Early-woken
  timed waits (`futex` WAIT* with timeout, `nanosleep`, `clock_nanosleep`) pass `Expiry` in
  `TaskState::fast_sleep` to BKL fallback (untimed `futex` WAIT* falls back without setting it);
  `task::handle` clears `fast_sleep` unconditionally after `dispatch`.

### Waiting and waking

- Host-fd waits use `Event::poll_with` (`ppoll` on `Event` + host fds); fd-less waits park in
  `Event::wait` (`FUTEX_WAIT_PRIVATE`). Wall-clock `Expiry` deadlines recompute their `Instant`
  each pass so clock jumps adjust waits; relative `CLOCK_BOOTTIME` sleeps count suspend time.
- `block_for`: asserts 0 borrows, registers waiter if `interest != 0` (sorting `objects` for binary
  search), drops BKL, waits/polls (`EINTR` → wake), re-locks BKL, clears `Event` if signaled/error/
  still signaled, deregisters. `Machine::wait_deadline` uses thread-local `EVENT` and lazily
  resolves at most one `Option<wake::Object>` under the existing BKL hold only when blocking.
- Wake requests buffer per thread in `PENDING` (ignored when `HELD == 0`). At BKL release,
  `flush_wakeups` notifies `epoll_watches` and queues `signal_later` for matching channels or `FILE`
  waiters with `None` or matching object; signals fire after unlock. Clock jumps signal task events
  directly.
- Registered objects cover pipes, PTYs, eventfd, timerfd, inotify, signalfd, epoll, socket queues,
  mqueues, seccomp notifs, klog, FUSE conns, host consoles, AIO polls. SysV IPC, group `SIGCONT`,
  console close, and teardown wake all channels. Signals kick `Kicker` and signal `Event`; signals
  blocked everywhere request `wake::SIGNAL`.
- `/proc/<pid>/stat` shows `S` while `Task::asleep()` (`sleeping && !event.is_signaled()`), turning
  `R` once signaled (before BKL re-lock); host-fd/timeout wakes read `S` until BKL re-lock; fast
  sleeps read `R` on return; `Cx::unlocked` reads `S` while unsignaled. Precedence: `Z` (including
  exited leader on process line), `t`, `T`, `S`/`R`.

### Scheduling, accounting and misc calls

- `nice`, `affinity` (<= 64 host CPUs, clamped to cgroup `cpuset.cpus`), and `ioprio` (`CLONE_IO`
  shares `Rc<Cell<u16>>`) apply to host threads: raised nice floors at `-4` (Android UI),
  `SCHED_IDLE` maps to host nice 19, RT policies (`FIFO`/`RR`/`DEADLINE`) are recorded only, RT I/O
  becomes host BE. `SCHED_RESET_ON_FORK` resets RT children to `SCHED_OTHER`/0, clamps nice `>= 0`,
  resets `uclamp` to `(0, 1024)`.
- Owner checks: `setpriority`/`sched_setaffinity` accept same euid (as target euid/ruid) or
  `CAP_SYS_NICE` over target user ns; `sched_setscheduler`/`setparam`/`setattr`/`set_deadline`
  accept same owner or initial-ns `CAP_SYS_NICE` (same for raising nice past `RLIMIT_NICE`);
  `ioprio_set` checks target ruid == caller euid/ruid or initial-ns `CAP_SYS_NICE` (RT class needs
  `CAP_SYS_ADMIN`/`CAP_SYS_NICE`). `PRIO_USER` translates uid in caller's user ns and skips
  invisible pids (`who == 0`: caller ruid for `PRIO_USER`/`ioprio_get`, root for `ioprio_set`).
- RT limits: without initial-ns `CAP_SYS_NICE`, `set_policy` enforces target group's soft
  `RLIMIT_RTPRIO` for `SCHED_FIFO`/`SCHED_RR` (`> 0` to change RT policy, `<= rlim` to raise,
  lowering allowed), checks `RLIMIT_NICE` whenever leaving `SCHED_IDLE` for any other policy
  (including RT), and forbids clearing `SCHED_RESET_ON_FORK` (`EPERM`). `SCHED_DEADLINE` is handled
  by `set_deadline` (requires initial-ns `CAP_SYS_NICE`, `1024 <= runtime <= deadline <= period`,
  period 0 → deadline, no `apply_host`).
- `sched_setattr`: 48..=4096 B (`0` → 48; out-of-range or non-zero past 56 writes 56 + `E2BIG`);
  `KEEP_POLICY`/`KEEP_PARAMS` match Linux 6.1; `SCHED_RESET_ON_FORK` in `policy` → `EINVAL`;
  `uclamp` needs size >= 56, `<= 1024` or `u32::MAX`, `lo <= hi`. Non-deadline policies validate via
  `set_policy(..., false)` before `setpriority` (`OTHER`/`BATCH` when `!keep_params`; `EACCES` →
  `EPERM`) and commit `t.sched` + `apply_host` only after both succeed (with `keep_params`, only
  `SCHED_RESET_ON_FORK` updates, skipping `apply_host`). `sched_getattr` always reports nice
  (deadline fields only for `SCHED_DEADLINE`). `sched_rr_get_interval`: 100 ms for `SCHED_RR`, else
  0. Guest `tpidr_el0` stays in `CpuState` (host `TPIDR_EL0` untouched).
- `getrusage` throttles RSS sampling to 10 ms (`sample_rss_throttled`); `sample_rss_at_exec` skips
  anon mincore for freshly forked (`!execed && now <= start_ticks + 1`) processes; exit is
  unthrottled. `times` reports boottime ticks + self/children CPU ticks. `prlimit` requires self,
  matching ruid/euid/suid & rgid/egid/sgid, or `CAP_SYS_RESOURCE` (`NOFILE` hard limit <= `nr_open`).
  `sethostname`/`setdomainname` need `CAP_SYS_ADMIN` in UTS owner and length `0..=64`; `getcpu`
  returns lowest affinity bit (max 63) and node 0; `sysinfo` returns time-ns boottime (rounded up),
  loads, host meminfo, task count; `getrandom` rejects `RANDOM | INSECURE` and copies 64 KiB chunks
  via `copy_result` (partial count on later fault). `kcmp` checks `may_ptrace` on both tids and
  compares `FILE`/`VM`/`FILES`/`FS`/`SIGHAND`/`IO`/`EPOLL_TFD` (`KCMP_SYSVSEM` always returns 0).

## Dependencies (inbound / outbound)

### Inbound

- `syscall/mod.rs`, `kernel/boot`, `kernel/time`, `kernel/bug`, CLI/Android hosts (`Machine`),
  `kernel/signal`, `kernel/ptrace`, `kernel/procfs`, blocking syscalls, `kernel/mmu` (`TaskSync`,
  `quiesce`), `kernel/mount` (`fast_changed`), `kernel/exec`, `kernel/ns`, `kernel/poll`,
  `kernel/tty` terminal bridge (`block_for`).

### Outbound

- `engine`/`engine/dbt` (`Cpu`, `Kicker`, `Dbt`, `DbtStats`); `kernel/bug` (`catch`, `host_call`,
  `kill*`, `report`, `fails`, `trap*`, `Hold`, `doing`/`done`); `kernel/mm`/`cow`/`hostmem`/`mmu`
  (`translate`, `fork`, `cow_break`, `file_truncated`, `defer_unmaps`/`release_unmapped`/
  `spare::refill`, `quiesce`, `sync*`, `generations`); `kernel/exec`, `kernel/ns`, `kernel/futex`,
  `kernel/time`, `kernel/boot`, `kernel/vfs`, `kernel/mount`, `kernel/fuse`, `kernel/cred`,
  `kernel/cgroup`, `kernel/ipc`, `kernel/aio`, `kernel/oom`, `kernel/signal`, `kernel/ptrace`,
  `kernel/poll`, `kernel/tty`, `kernel/unix`, `appine_hal`, `std::thread`/`Mutex`.

## Invariants and gotchas

- Lock order: BKL → futex bucket lock(s) (ascending order for multi-key ops); never reverse.
  `truncate_host_object` locks kernels in `RUNNING` order on distinct host threads.
- Touch/clone/drop `Rc`/`RefCell` kernel objects (including `CURRENT` at exit) only under BKL; hold
  no `cell::RefCell` borrow across BKL release.
- Wake-ups fire post-BKL and `Event`s are level-triggered (cleared post-relock): waiters must loop
  and re-check. Zero-interest waits wake only on direct signals; `note_wake` ignores `HELD == 0`.
- Tasks leave `Kernel::tasks` and `Mm` users under BKL before freeing `Cpu` (kicks rely on this).
- `begin_exit` is idempotent before `PTRACE_EVENT_EXIT`; `exit_started` prevents duplicate
  `PTRACE_EVENT_EXIT` between `exit`/`exit_group` and `task::run`'s `Flow::Exit`.
- Fast path must never touch guest memory after clearing `in_guest` (`Mm::sync` waits only for
  in-guest threads). Single-stepped tasks must fall back (`Cpu::step` ignores `cpu->pending`).
- Mutators of creds, tids, live `ppid`, mounts/cwd/root, or `uid_map`/`gid_map` must call
  `fast_changed()`; futex/clock fast paths rely on self-refresh instead of checking `fast_epoch`.
- BKL entries call `kernel::doing`/`done`; `Hold::held` stays cache-line isolated from `HoldInfo`;
  run loop calls `resume_checks` before guest code; guest times use `kernel/time`'s `timespec()`.
- `invalidate_code` accepts page 0 directly; never pass `0` as a no-op.
- Handlers may rerun on `FUSE_RETRY`: mutate nothing before last walk, prefault I/O buffers, or
  handle cold pages locally via `copy_result`/`retry_user_pages`.
- Sweep producers must set their dirty flag; no FUSE node may be held between syscalls outside
  `in_use`/handles/in-flight requests; `InSyscall` drops before stops so overlay nodes stay unpinned.
- Clear `Task::vfork` with `vfork_done`; clear `leader_serial`/`leader_no_new_privs` with
  `leader_exit` on non-leader execve.
- `sched_setattr` keeps `setpriority` independent of uncommitted `t.sched` and checks target group
  limits.

## Confirmed bugs / technical debt

- `kcmp(KCMP_SYSVSEM)` hardcodes `(0, 0)` (always returning 0) instead of comparing
  `TaskState::sem_undo` identities, so unrelated processes with distinct `SEM_UNDO` lists falsely
  compare equal.
- `syscall::sched::target` returns `Ok(None)` for zombies/leader-exited processes and `cred_of(None)`
  returns `Cred::root()`, so `sched_setscheduler`/`setparam`/`setattr`/`setaffinity` on a non-root
  user's own zombie or leader-exited process fail with `EPERM` (and affinity falls back to
  `online_cpus()` instead of the cgroup mask).
- In `syscall/proc.rs` (`kill_other_threads`, crossing into `kernel/exec`), an early
  `others.is_empty()` return precedes `if me != tgid`, so if the leader exited earlier and a sole
  surviving non-leader calls `execve`, it skips `tgid` takeover and leaves `leader_seccomp`,
  `leader_no_new_privs`, `leader_exit`, `leader_serial`, and `leader_tracer` stale.

## Open questions

- Is counting `RLIMIT_NPROC` by kernel uid across user namespaces intended?

## To verify

- Linux 6.1 comparisons from kernel knowledge rather than source (`eligible_child`, `copy_process`
  exit signals, `zap_pid_ns_processes`, `sched_setattr` keep/uclamp flags, `INIT_RLIMITS`; Starnix
  items also observed on native Linux 6.8).
