# kernel/oom Map

> Static understanding snapshot, not a decision history.
> See `.agents/decisions/kernel/oom.md` for the paired decision history (decisions from before the split are D26, D29, D32, D35 and D44 in `.agents/decisions/kernel/mm.md`).
>
> Verified: 2026-11-27 (full mapping: 13 confirmed, 1 partial corrected, 0 refuted, 2 bugs confirmed)

## Responsibilities

`kernel/oom` measures guest memory and keeps the single host app process under its memory limit (Linux 6.1's `mm/oom_kill.c` and the RSS/pagemap figures of `fs/proc/task_mmu.c`):

- resident memory, page-table, stack, swap, CoW-sharing, and pagemap measures of an address space for procfs (`status`, `statm`, `stat`, `smaps`, `smaps_rollup`, `pagemap`), `rusage` peaks, OOM scoring and kill logs, and of several address spaces together (`Usage`) for child cgroups;
- the OOM killer: app-wide and cgroup (`memory.max`, `memory.high`) polling and limit checks, DBT memory reclaim under pressure, victim selection and swap attribution, `SIGKILL` / `memory.oom.group` kills, `memory.events` counters, OOM reaping, and `/proc/<pid>/oom_score`;
- `process_mrelease` and `Mm::release`, freeing an exiting address space's private memory before its threads finish exiting;
- periodic release of Tier 1 code cache pages dirtied through the writable W^X view.

Root: `core/kernel/src/oom.rs`; in `core/kernel/src/mm.rs`, the resident-memory, swap, page-table and pagemap measures (`resident_in`, `cow_shared`, `pagemap`, `stack_bytes`, `resident_pages`, `peak_resident_pages`, `resident_split`, `pgtables_bytes`, `pgtables_pages`, `exact_swap_pages`, `swappable_nonresident_pages`, `Usage`, `RssPlan`, `FileResidency`) and `Mm::release`; and `process_mrelease` in `core/kernel/src/syscall/mem.rs`.

Outside the module: VMAs and `shmem_like` (`kernel/mm`); host backing, `FileMaps`, `HostRegion` flags and `UNMAPPING` (`kernel/hostmem`); CoW sets, loans and `give_back` (`kernel/cow`); soft-MMU page-table accounting and `shootdown` (`kernel/mmu`); the timer thread and idle stop/resume handshake (`kernel/time`, `kernel/process`); cgroup tree, controller flags and memory files (`kernel/cgroup`); procfs formatting (`kernel/procfs`); group exit (`kernel/signal`) and kill reports (`kernel/bug`).

## Key types

- `Oom` (`Kernel::oom`) — app-wide limit (`limit`, `u64::MAX` means disabled), root peak (`peak: Cell<u64>`), next polling deadline (`next: Option<Instant>`, `None` at start, after `recheck()`, or while all tasks sleep), most recent victim and timestamp (`victim: Option<(Weak<RefCell<ThreadGroup>>, Instant)>`), timed-out still-alive victims (`stuck: Vec<Weak<RefCell<ThreadGroup>>>`), and next writable code-view unmap deadline (`code_views: Option<Instant>`).
- `Events` (`oom::Events`) — cgroup `memory.events` / `memory.events.local` counters (`high`, `max`, `oom`, `oom_kill`, `oom_group_kill`).
- `SwapContext` (`oom.rs`, private) — per-scoring-pass swap attribution mode: `Exact` (readable `/proc/self/pagemap`), `Zero` (unreadable pagemap with host `VmSwap == 0`), or `Fallback { host_swap_pages, total_cand, candidates }` (unreadable pagemap with host `VmSwap > 0`, caching `swappable_nonresident_pages()` per unique live `*const Mm`).
- `RssPlan` (`mm.rs`, private) — `fixed` 4 KiB pages known without `mincore` (borrowed pages and `FileResidency`-cached file pieces) plus host `(addr, len)` `spans` sorted by start and merged across contiguous runs on measurement.
- `FileResidency` (`mm.rs`, private) — process-wide `Mutex<Option<HashMap<FilePiece, (u64, Instant)>>>` cache of file-mapping residency keyed by `(dev, ino, offset, len)` with a 1 s TTL and 1024-entry cap (cleared wholesale when full).
- `Usage` (`mm.rs`) — cross-address-space resident page accumulator for child cgroups (`fixed`, `spans`, `counted: HashSet<FilePiece>`).
- Timing constants (`oom.rs`): `INTERVAL` (20 ms), `NEAR_INTERVAL` (5 ms above 3/4 of a limit), `MEASURE_SHARE` (20, bounding check time to <= 1/20 of the interval), `VICTIM_TIMEOUT` (1 s), `OOM_SCORE_ADJ_MIN` (-1000), `CODE_VIEW_INTERVAL` (1 s); `RssPlan::SMALL` (256 pages = 1 MiB).

## Public entry points

### Resident, swap, and page-table measures

- `Mm::resident_in(addr, len)` — resident 4 KiB pages in a guest range (`smaps`, `smaps_rollup`, OOM kill log).
- `Mm::cow_shared(addr, end)` — `(shared_pages, pss_bytes)` across CoW-lent and CoW-borrowed pages with `users > 1` (`smaps`, `smaps_rollup`).
- `Mm::pagemap(addr, pages)` — `/proc/<pid>/pagemap` 64-bit entries (`PRESENT`, `FILE`, `EXCLUSIVE`; no swap bit or PFN).
- `Mm::stack_bytes()` — total bytes of `MAP_GROWSDOWN` VMAs (`VmStk`, `vm_layout`).
- `Mm::resident_pages()` — total resident 4 KiB pages via `RssPlan::measure` (`/proc/<pid>/stat`, OOM `score_pages`, `proc_score`).
- `Mm::resident_split()` — `(anon, file, shmem)` resident 4 KiB pages (`/proc/<pid>/status`, `/proc/<pid>/statm`).
- `Mm::peak_resident_pages(fresh)` — peak-RSS sample for `ThreadGroup::sample_rss`, `sample_rss_at_exec`, and `sample_rss_throttled` (`ru_maxrss`, `VmHWM`).
- `Mm::pgtables_bytes()` / `Mm::pgtables_pages()` — allocated soft-MMU page-table size (`VmPTE`, OOM badness/log, `memory.stat` `pagetables`).
- `Mm::exact_swap_pages()` — `Option<u64>` exact swapped 4 KiB pages from `/proc/self/pagemap` (`VmSwap`, `SwapContext::Exact`).
- `Mm::swappable_nonresident_pages()` — upper-bound private anonymous non-resident candidate pages for `SwapContext::Fallback` and `proc_score`.
- `Usage::add(&Mm)` / `Usage::pages()` — deduplicated resident 4 KiB pages across multiple address spaces (`cgroup::subtree_rss`).

### OOM killer and code-view release

- `oom::check(k, shared)` / `oom::unmap_code_views(k, shared)` — periodic BKL timer-thread hooks returning the next deadline (`None` when stopped).
- `oom::out_of_memory(k, (domain, used, limit))` — selects and kills a victim (and `memory.oom.group` members) and reaps private memory; also called directly when a child cgroup's `memory.max` is lowered below usage.
- `oom::handle_memory_high(k, id)` — records a `high` event and reclaims idle DBT memory; also called directly when a child cgroup's `memory.high` is lowered below usage.
- `oom::Oom::{measure, recheck}` — reads whole-app footprint while updating root peak; clears `next` so the next timer wakeup measures immediately.
- `oom::default_limit()` / `oom::app_memory()` — half of host physical RAM (`u64::MAX` if unreadable); host `MemoryUsage` (`RssAnon`, `RssShmem - written_code_bytes`, `VmSwap`).
- `oom::proc_score(k, g)` — computes `/proc/<pid>/oom_score` (`0..=2000`).
- `oom::will_free_mem(k, g)` — shared exiting-process predicate used by OOM reaping and wrapped by `process_mrelease`.

### Memory release

- `syscall::mem::process_mrelease(cx, pidfd, flags)` — guest `process_mrelease(2)` syscall handler.
- `Mm::release()` — discards private non-executable, non-reserved pages of a dying address space while leaving VMAs intact.

## Data flow / lifecycle

### Resident memory, swap, and pagemap accounting

- Residency is always queried on host pointers, never guest addresses. `v.host_len()` caps each VMA to its underlying `HostRegion` (`v.len.min(host_available)`), so shared anonymous mappings grown beyond their backing via `mremap` only measure backed pages, and `HostRegion::reserved()` (`len == 0`) yields `host_len() == 0`.
- `Vma::was_written()` gates private anonymous measurements (and `PrivateFile` write checks): on an eager write-probed anonymous VMA (`probe_writes`), it reads `/proc/self/pagemap` (`host_write_observed`: bit 62 swapped, or bit 63 present and bit 56 exclusive) to latch `written` and `has_anon_vma` and clear `probe_writes`; if pagemap reading fails, it conservatively returns `true`.
- `Mm::resident_in` (used by `smaps`, `smaps_rollup`, and OOM `log_killed`) skips `v.reserved()`, `v.foreign()` (`[vvar]`), and unwritten private anonymous VMAs (`!v.was_written()`). For private anonymous VMAs larger than `EAGER_MAX` (1 MiB), it scans only `touched_windows(s)` (2 MiB `LEAF_SPAN` runs containing present PTEs or CoW entries, merged up to 1 GiB `WINDOW_MAX`). It excludes `cow.borrowed` pages from the VMA's own host `mincore` scan and then counts `cow.borrowed` separately in contiguous owner-host runs via `mincore`, so borrowed pages are never double-counted and count only when host-resident. For non-anonymous VMAs with `v.file_id.is_some()`, it prefers `appine_hal::touched_pages` (host pagemap present or swapped bits, avoiding page-cache readahead noise) and falls back to `mincore`; it does not use `FileResidency`.
- `Mm::resident_pages` (`rss_plan()` -> `plan(None)`, used by `/proc/<pid>/stat`, OOM `score_pages`, and `proc_score`) counts every `cow.borrowed` page unconditionally in `fixed`, skips `v.foreign()`, `host_len == 0`, and unwritten private anonymous VMAs, restricts large (`> EAGER_MAX`) private anonymous VMAs to `touched_windows(s)`, caches residency for 1 s in `FileResidency` for unwritten `PrivateFile` and all `Shared` VMAs with `file_id.is_some()` (which can therefore lag by up to 1 s), sorts the remaining host spans, and merges host-contiguous spans into single `mincore` calls (`RssPlan::measure`, chunked to 64 KiB result buffers in `appine_hal::resident_pages`).
- `Mm::resident_split` (used by `/proc/<pid>/status` and `/proc/<pid>/statm`) measures each VMA with the same rules as `resident_pages` (including `FileResidency` and `touched_windows`, without cross-VMA span merging, and passing `v.len` rather than `host_len` to `FileResidency::get`) and splits pages into:
  - `anon`: `cow.borrowed.len()` + written `Backing::Anonymous` + written `Backing::PrivateFile` (the entire VMA, unlike Linux's copied-pages-only accounting);
  - `file`: unwritten `PrivateFile` + non-`shmem_like` `Shared`;
  - `shmem`: `shmem_like` `Shared` (`file_id.is_none()`, `shm.is_some()`, or VMA name starting with `/memfd:` or ending with ` (deleted)`).
- `Usage` (used by `cgroup::subtree_rss` for child cgroups' `memory.current`, `memory.peak`, `memory.stat`, and `measure_memory`) deduplicates address spaces by `Rc::as_ptr` in `subtree_rss` and calls `mm.plan(Some(&mut counted))`: `FileResidency`-cached file pieces (`(dev, ino, offset, v.len)`) are counted at most once per exact piece (overlapping different sub-ranges of one file still count separately), while borrowed pages are emitted as owner-host spans rather than `fixed`; `Usage::pages` sorts and unions overlapping host spans before `RssPlan::measure`, so shared anonymous memory, SysV shm, shared `FileMaps` mappings, and CoW-lent/borrowed host pages count once across the cgroup and only while host-resident.
- `Mm::peak_resident_pages(fresh)` feeds `ThreadGroup::maxrss_kb` (`ru_maxrss` and `VmHWM`): when `fresh` is `true`, it returns `plan.fixed` without running `mincore` on anonymous spans; otherwise `RssPlan::estimate` counts spans totalling `<= 256` pages (1 MiB) as fully resident to avoid `mincore` under the BKL for short-lived small processes, and runs `plan.measure()` above 256 pages. Call sites: `sample_rss` at last-thread exit, `process_mrelease`, and `oom::reap`; `sample_rss_at_exec` before `execve` (passing `fresh = !execed && now <= start_ticks + 1`); `sample_rss_throttled` on `getrusage` for `RUSAGE_SELF` and `RUSAGE_THREAD` (at most every 10 ms; skipped for `RUSAGE_CHILDREN`).
- `Mm::cow_shared` counts CoW-lent and CoW-borrowed pages in `[addr, end)` whose `Loan::users()` exceeds 1 and sums `PAGE_SIZE / users` for `smaps` `Shared_*` and `Pss`; an orphan page with only one borrower left (`users == 1`) is excluded.
- `Mm::pagemap` skips `v.reserved()` (but does not skip `v.foreign()`, so `[vvar]` pages are queried via `page_residency` and reported as `PRESENT | FILE` when resident), marks resident `Backing::Anonymous` pages `PRESENT | EXCLUSIVE` and other resident mappings `PRESENT | FILE`, unconditionally marks `cow.borrowed` pages `PRESENT` (without `EXCLUSIVE`), and clears `EXCLUSIVE` on present `cow.lent` pages. No swap bit or PFN is reported.
- `Mm::pgtables_bytes` / `pgtables_pages` read `Mmu::table_bytes` / `table_pages` (root, intermediate, active and retired L3 tables; monotonically growing until `Mmu` is dropped).
- `Mm::exact_swap_pages` returns `None` when `!appine_hal::pagemap_readable()`; otherwise it sums `/proc/self/pagemap` swap bits (bit 62, via windowed `appine_hal::swapped_pages`) across non-reserved, non-foreign VMAs that are written `Anonymous` (via `touched_windows` if `> EAGER_MAX`, excluding `cow.borrowed`), written `PrivateFile`, or `shmem_like` `Shared`, plus `borrowed_host_runs(&cow)`.
- `Mm::swappable_nonresident_pages` provides the fallback swap upper bound when pagemap is unreadable: it walks written, non-reserved, non-foreign private anonymous VMAs (`touched_windows` when `> EAGER_MAX`), skips `cow.borrowed` via `non_cow_runs`, walks present soft-MMU PTEs via `mmu.next_present` while fast-forwarding across contiguous `Vma::present_run` spans, and counts non-resident 4 KiB pages via `mincore` plus non-resident pages in `borrowed_host_runs(&cow)`. Unmapped, reserved, never-written, file-backed, and `MADV_DONTNEED`-discarded pages are excluded (when pagemap is unreadable, `Mm::discard_range` zaps soft PTEs and resets `present_run` on non-vDSO private anonymous ranges).

### App-wide and cgroup limits, polling, and DBT reclaim

- All guest processes share one host app process; Android's LMK would terminate the entire app under pressure. Because guest faults into host memory bypass the kernel layer, `kernel/oom` enforces limits by polling.
- The app-wide limit (`k.oom.limit`) is set once at boot from `MachineOptions.memory_limit`: `None` (Android host and CLI default) calls `default_limit()` (half of host physical RAM from `sysinfo`, or `u64::MAX` if 0); CLI `--memory-limit none` passes `Some(u64::MAX)`. Guests cannot write root `memory.max` (`EPERM`). If root `footprint()` fails (`None`) or `oom::check` panics inside `bug::catch`, `k.oom.limit` is permanently set to `u64::MAX` while child cgroup limits stay active.
- Root usage (`footprint()`) is host `/proc/self/status` `RssAnon + RssShmem + VmSwap` (or `VmRSS` with 0 shmem on pre-4.5 kernels lacking `RssAnon`), with `appine_engine::written_code_bytes()` saturating-subtracted from `RssShmem` in `app_memory()`. It covers guest memory, translations, and kernel-layer metadata while excluding host file page cache. Child cgroups with the memory controller enabled and numeric `memory.max` or `memory.high` are measured with `cgroup::measure_memory` (`Usage` over subtree address spaces, updating `mem_peak`).
- `oom::check` runs under the BKL on each timer-thread wakeup (followed by `cgroup::notify_changes` so `memory.events` watchers wake):
  1. If `k.oom.limit == u64::MAX` and no child cgroups have numeric `memory.max` or `memory.high`, returns `None` immediately (without calling `stop_checks`).
  2. If `k.oom.next` is in the future, returns `Some(next)`.
  3. If `shared.stop_checks(|| all_sleeping(k))` succeeds, clears `k.oom.next = None` and returns `None`. Every guest task sets `sleeping = true` (`SeqCst`) while waiting in `Cx::wait`, `Cx::unlocked`, or fast-path `sleep`, and clears `sleeping = false` (`SeqCst`) on wake before calling `Shared::resume_checks()` at the top of the run loop when `!in_guest` (non-sleeping fast syscalls stay `in_guest` without toggling `sleeping` or calling `resume_checks`).
  4. Measures the root first via `measure_root`: records the sample in `k.oom.peak`, and if `used > k.oom.limit`, runs `reclaim_dbt_memory()` (`trim_dbt_pool()` to free pooled instances, spare thread workspaces and resident template arena pages, then `unmap_written_code()` to drop writable W^X aliases) and resamples, falling back to the initial `used` if the second read returns `None`.
  5. Iterates child `memory.max` limits until `over.is_some()` (so root over-limit skips child `memory.max` measurements, and at most one OOM scope is handled per round), caching measurements in a per-round map whenever `high_limits` is non-empty.
  6. Iterates child `memory.high` limits (reusing cached measurements when available). When `UNMAPPING == 0` and `cgroup::note_memory_high_state` reports a fresh crossing above `high` or continued growth (`used > mem_high_last`; resets `mem_high_last = 0` when `used <= high`), calls `handle_memory_high` (`high` event + `reclaim_dbt_memory()`, no kills).
  7. Schedules `k.oom.next = Some(now + interval.max(now.elapsed() * 20))`, where `interval` is 5 ms if any measured scope exceeded `limit / 4 * 3` and 20 ms otherwise.
  8. If `over` is `Some((domain, used, limit))`, checks `UNMAPPING != 0` and `waiting_for_victim(k, now)`: if either holds, returns without counting `max` or killing; otherwise increments `max` on `domain` (and ancestors) and calls `out_of_memory`.
- Direct cgroup writes (`cgroup::write`):
  - Writing child `memory.max` calls `k.oom.recheck()` and, if numeric and `used > max`, immediately invokes `out_of_memory(k, (id, used, max))` without incrementing `max` or checking `UNMAPPING` (though `out_of_memory` still checks `waiting_for_victim`). `syscall/fs.rs` also calls `oom.recheck()` and signals `shared.timers` for `memory.max`.
  - Writing child `memory.high` calls `k.oom.recheck()` and, if numeric and `note_memory_high_state` returns `true`, immediately invokes `handle_memory_high` (writing `max` resets `mem_high_last = 0`). `syscall/fs.rs` does not signal `shared.timers` for `memory.high`.

### Victim selection, `oom_score`, kill, and reap flow

- `out_of_memory` guards with `waiting_for_victim(k, now)` (`< 1 s` since `victim` was picked and it still has threads). If an earlier `victim` expired its 1 s timeout while still having threads, it is moved to `k.oom.stuck`; exited entries in `stuck` are pruned.
- `select` / `select_with` iterates `k.groups.values()` (a `BTreeMap` in ascending `tgid` order) within `domain`, skipping `stuck` groups and any process for which `eligible_mm` returns `None`: `tgid == INIT`, `oom_score_adj == -1000`, zombies (no live thread in `k.tasks`), and a `vfork` child (`task.vfork.get()`) whose `parent_thread` still exists with the same `Mm` (`Rc::ptr_eq`, Linux's `in_vfork`).
- Score (`badness_with`, unclamped `i64`): `rss + swap + pgtables_pages + oom_score_adj * (total / 1000)`, where `total` (in 4 KiB pages) is `host_ram.max(limit) / PAGE_SIZE` for `ROOT` and `limit / PAGE_SIZE` for a child cgroup. `select_with` caches `(mem_pages, rss)` per distinct `*const Mm` so `CLONE_VM` sharers are measured once per pass while retaining individual `oom_score_adj`. On ties (`points >= best`), the later (higher `tgid`) process wins. If no process is eligible, nothing is killed, logged, or counted.
- Swap attribution (`swap_context` built once per selection pass):
  - `SwapContext::Exact` when `pagemap_readable()`: `mm.exact_swap_pages().unwrap_or(0)`.
  - `SwapContext::Zero` when pagemap is unreadable and host `VmSwap == 0`.
  - `SwapContext::Fallback` when pagemap is unreadable and host `VmSwap > 0`: scans every unique live guest `Mm` (including `-1000` protected processes so their swap is not shifted onto eligible ones), caches `mm.swappable_nonresident_pages()`, and attributes `cand` if `total_cand <= host_swap_pages`, else `(cand * host_swap_pages) / total_cand` (`estimate_swap_share`, guaranteed `<= cand`, `<= host_swap_pages`, and sum `<= host_swap_pages`).
- `oom::proc_score` (`/proc/<pid>/oom_score`): returns `0` if `eligible_mm` is `None`; otherwise computes badness against `total = (appine_hal::ram_and_swap() / PAGE_SIZE).max(1)` and scales to `((1000 + points * 1000 / total) * 2 / 3).max(0)`. When pagemap is unreadable, it avoids the whole-kernel `fallback_swap_context` scan whenever `scaled(host_swap) == scaled(0)` or `scaled(mm.swappable_nonresident_pages().min(host_swap)) == scaled(0)`.
- Kill and reap sequence in `out_of_memory`:
  1. Increments `oom` on `domain` (and ancestors) and records `k.oom.victim = Some((downgrade, now))`.
  2. If `victim.exiting.is_some()`, calls `reap` and returns (no `oom_kill` increment, no log, no `memory.oom.group` scan).
  3. Otherwise prints a host stderr line, writes three Linux memcg klog records (`memory: usage … failcnt …` where `failcnt` is `domain`'s hierarchical `mem_events.max`; `oom-kill:constraint=CONSTRAINT_MEMCG,…`; `Memory cgroup out of memory: Killed process …` whose `anon`/`file`/`shmem` split iterates `mm.regions()` using `resident_in` and classifies `(Some(_), _)` as `file`, so memfds/file-backed shmem show as `file`), and records `bug::KillReason::OutOfMemory`.
  4. Calls `kill(k, &victim)` (`signal::group_exit` with `SIGKILL` if `exiting.is_none()`, plus `oom_kill += 1` on `victim.cgroup` and ancestors).
  5. If `cgroup::oom_group(k, victim.cgroup, domain)` finds a topmost cgroup with `memory.oom.group == "1"` between `victim.cgroup` and `domain`: increments `oom_group_kill` on that group (and ancestors), then kills every live member in that group's subtree with `tgid != INIT` and `oom_score_adj != -1000` (including `stuck` victims and `victim` itself, logging `log_killed` and incrementing `oom_kill` for each, so `victim` is logged and counted twice like Linux's `mem_cgroup_scan_tasks`; `bug::record_kill` is skipped for `victim` to avoid duplicate UI reports).
  6. After all kills and RSS logs in the round finish, calls `reap` once per distinct `Mm` across all killed groups: checks `oom::will_free_mem` on the killed group and every other live `ThreadGroup` sharing that `Mm`, samples peak RSS (`sample_rss`) on every sharing group, and calls `Mm::release()` if `mm.try_borrow_mut()` succeeds.

### `process_mrelease` and `Mm::release`

- `process_mrelease(pidfd, flags)`: returns `EINVAL` if `flags != 0`; `EBADF` if `pidfd` is not a `FileKind::Pidfd` (including `/proc/<pid>` directory fds); `ESRCH` if the target process is reaped or has no live thread in `k.tasks`; `EINVAL` unless the target and every other live `ThreadGroup` sharing its `Mm` satisfy `syscall::mem::will_free_mem`.
- `oom::will_free_mem(k, g)` returns `false` if `g.core.is_some()`, and otherwise requires `g.exiting.is_some()` plus one of: `!g.single_exit` (group exit), `g.leader_exit.is_none() && g.threads.len() == 1` (single-thread `exit(2)`), or any thread in `g.threads` having `t.killed.get()`. `syscall::mem::will_free_mem(k, g)` additionally accepts `has_fatal_pending(k, g)` (unless `g` is an unkillable non-root pid-namespace init: a live untraced thread has an unblocked pending signal in `g.pending | t.pending` with `DEFAULT` disposition that is not ignored by default, stop, `SIGCONT`, or coredumping).
- Before releasing, `process_mrelease` calls `group.borrow_mut().sample_rss(&mm.borrow())` (on the target group only) and calls `m.release()` if `mm.try_borrow_mut()` succeeds (silently returning `Ok(0)` if `mm` is currently borrowed elsewhere). Calling it repeatedly succeeds.
- `Mm::release` leaves all VMAs (and `/proc/<pid>/maps`) unchanged and skips executable (`v.prot & prot::EXEC != 0`, including the vDSO) and reserved (`v.reserved()`) VMAs:
  1. For each page in `cow.borrowed` belonging to a non-skipped anonymous VMA, remaps the soft-MMU entry back to the VMA's own host page via `cow_map_shared`. If remapping fails, retains the page in the skip set (`retained`, initialized from `cow.lent`) and keeps the loan.
  2. Removes remapped pages from `cow.borrowed` and calls `self.give_back(returned)` (one `shootdown()`, then returns loans so owners regain write permission).
  3. For each non-skipped VMA with `v.was_written()`:
     - `Backing::Anonymous`: calls `discard_unlent_in(s, v, &retained)`, which skips the entire VMA if `v.region.has_io_write_pins()` is true; otherwise invalidates vmsplice page watches and calls `discard_host` (whole host pages via `MADV_DONTNEED`, partial host-page ends zeroed) on each unretained run.
     - `Backing::PrivateFile` with `!v.cow`: caps length to `page_up(v.contents_len()).unwrap_or(v.len).min(v.len)`; for host file mappings (`v.region.anonymous.is_none()`), calls `discard_whole_host_pages` (reverts whole host pages to the file, leaves partial host pages untouched); for anonymous copies, calls `discard_host` (reverts to zeros); then invalidates vmsplice page watches over the exact discarded byte range.
  4. Still-running threads read whatever remains (zeros or file contents) rather than taking `SIGBUS` (`MMF_UNSTABLE` on Linux).

### Written code-view release

- `oom::unmap_code_views` runs on the timer thread regardless of whether any memory limit is configured, wrapped in `bug::catch`.
- Because Tier 1 maps its code cache twice for W^X, host RSS double-counts pages dirtied through the writable view. When due (`code_views` is `None` or elapsed), if any thread is awake (`!idle`) or a prior run was scheduled (`code_views.is_some()`), it calls `appine_engine::unmap_written_code()`. If all threads are sleeping and `shared.stop_checks(|| all_sleeping(k))` succeeds, it sets `code_views = None` and stops until `resume_checks()` wakes the timer thread; otherwise it schedules `now + 1 s`. Timed teardown avoids per-write page faults during JIT backpatching.

## Dependencies (inbound / outbound)

### Inbound

- `kernel/time` (`machine::timers`): calls `oom::check` and `oom::unmap_code_views` under `bug::catch`, followed by `cgroup::notify_changes`.
- `kernel/boot` (`Machine::start`): initializes `k.oom.limit` from `MachineOptions.memory_limit` or `oom::default_limit()`.
- `kernel/process`: task run loop calls `Shared::resume_checks()`; `ThreadGroup::sample_rss*` calls `Mm::peak_resident_pages` at last-thread exit, execve (`syscall/proc.rs`), and `getrusage` (`syscall/misc.rs`).
- `kernel/procfs`: calls `Mm::{resident_split, resident_pages, resident_in, cow_shared, pagemap, stack_bytes, pgtables_bytes, exact_swap_pages}` and `oom::proc_score`.
- `kernel/cgroup`: reads/writes `k.oom.{measure, limit, peak, recheck}`, calls `oom::{app_memory, out_of_memory, handle_memory_high}`, uses `oom::Events`, `Usage`, and `Mm::pgtables_bytes`.
- `kernel/fileio` (`syscall/fs.rs`): calls `oom.recheck()` and signals `shared.timers` after writes to cgroup `memory.max`.
- `kernel/mm` (`syscall/mem.rs` dispatch): dispatches `process_mrelease` to `syscall::mem::process_mrelease`.

### Outbound

- `kernel/mm`: VMA table, `Vma::{was_written, present_run, host_len, contents_len, reserved, foreign}`, `touched_windows`, `shmem_like`, `regions`, `discard_range` (PTE zapping when pagemap is unreadable).
- `kernel/cow`: `Cow::{lent, borrowed}`, `Loans`, `Loan::users`, `cow_map_shared`, `give_back`, `non_cow_runs`, `discard_unlent_in`.
- `kernel/hostmem`: `HostRegion::{reserved, foreign, has_io_write_pins}`, `UNMAPPING`, `discard_host`, `discard_whole_host_pages`.
- `kernel/mmu`: `Mmu::{table_bytes, table_pages, next_present, invalidate_page_watches}`, `Mm::shootdown`.
- `kernel/cgroup`: `ROOT`, `memory_limits`, `memory_high_limits`, `measure_memory`, `note_memory_high_state`, `memory_event`, `oom_group`, `Cgroups::{path, is_within, get}`.
- `kernel/signal`: `signal::group_exit` (`SIGKILL`), `klog::printk`; `kernel/bug`: `bug::record_kill`, `KillReason::OutOfMemory`; `kernel/procfs`: `pseudo::comm_str`.
- `hal` (`appine_hal`): `memory_usage`, `memory_info`, `ram_and_swap`, `resident_pages`, `page_residency`, `touched_pages`, `swapped_pages`, `pagemap_readable`, `page_size`.
- `engine/dbt` (`appine_engine`): `written_code_bytes`, `unmap_written_code`, `trim_dbt_pool`.

## Invariants and gotchas

- **Two distinct measurement bases:** root OOM checks measure the whole host app process (`RssAnon + RssShmem - written_code_bytes + VmSwap`, excluding host file cache), while child cgroups and victim scores measure guest VMAs (including file pages; shared pages once per cgroup via `Usage`, once per process in victim scores, and with swap attributed per `Mm` in scoring but excluded from child `Usage`).
- **CoW non-double-counting invariant:** `Mm::resident_in` explicitly skips `cow.borrowed` guest pages during the own-host `mincore` scan; `Mm::resident_pages` / `resident_split` / `plan` do not exclude them from the VMA's own host span because unmaterialized borrowed pages have never dirtied the borrower's own host backing.
- **Private anonymous PTE rule:** `touched_windows` and `swappable_nonresident_pages` rely on `kernel/mm`'s invariant that every private anonymous page with content has a soft-MMU entry (or CoW entry); unmapped windows are skipped, and `Vma::present_run` must be shrunk or cleared on any unmap, discard, or split so `swappable_nonresident_pages` never skips unmapped holes.
- **Polling headroom and `UNMAPPING` gate:** because guest faults do not enter the kernel layer, short-lived allocations can escape a poll interval and Tier 1 can dirty gigabytes per second, so limits need headroom. Polling never triggers `memory.max` kills or `memory.high` events while `UNMAPPING != 0` (deferred host unmaps pending after a recent exit), though direct `memory.max` / `memory.high` writes bypass the `UNMAPPING` check.
- **Live code groups are never flushed on pressure:** `reclaim_dbt_memory` trims only pooled instances, spare thread workspaces, template arenas, and writable code-view aliases; flushing active `DbtSpace`s synchronously under the BKL would kick and wait for runners, risking a watchdog stall and forcing retranslation on every 5 ms poll.
- **`failcnt` and `oom_kill` quirks:** `failcnt` in the OOM klog line is `domain`'s hierarchical `mem_events.max` (including descendants); under `memory.oom.group`, the picked victim is logged and counted twice in `oom_kill`, matching Linux's `mem_cgroup_scan_tasks`.
- **I/O write pins block anonymous discard in `Mm::release`:** `discard_unlent_in` returns early for any private anonymous VMA whose `HostRegion` currently has `has_io_write_pins()`, protecting in-flight zero-copy host I/O even during OOM reaping or `process_mrelease`.

## Confirmed bugs / technical debt

- **SELinux-blocked pagemap swap attribution is heuristic:** on Android hosts where SELinux blocks `/proc/self/pagemap` and host `VmSwap > 0`, `mincore` cannot distinguish swapped-out private anonymous pages from never-touched pages inside an eagerly mapped (`<= 1 MiB`) or 2 MiB demand-filled written private anonymous span. OOM scoring mitigates this by bounding and scaling per-`Mm` candidates against host `VmSwap` (returning 0 when `VmSwap == 0` and excluding unmapped, never-written, file, and `MADV_DONTNEED`-zapped pages), while `/proc/<pid>/status` `VmSwap` reports 0 when pagemap is unreadable.
- **Writing `memory.high` does not wake the timer thread immediately:** `cgroup::write` calls `k.oom.recheck()` on `memory.high` (and handles an immediate crossing), but `syscall/fs.rs` only calls `signal_later(&cx.shared.timers)` for `memory.max`. When `k.oom.limit == u64::MAX` and no prior limits existed, periodic 20 ms/5 ms `memory.high` polling does not start immediately and waits up to 1 s (`CODE_VIEW_INTERVAL` from `oom::unmap_code_views`, or another timer wakeup) before the timer thread wakes.
- **`process_mrelease` does not sample peak RSS for other `CLONE_VM` sharers:** unlike `oom::reap` (which samples `sample_rss` on every process sharing the `Mm` before calling `Mm::release`), `process_mrelease` samples `maxrss_kb` only on the target `group`.

## Open questions

None.

## To verify

- Linux 6.1 behavioral comparisons (`mm/oom_kill.c`, `fs/proc/task_mmu.c`, `mem_cgroup_scan_tasks`, `task_will_free_mem`) were checked from kernel knowledge rather than against an upstream Linux 6.1 source tree.
