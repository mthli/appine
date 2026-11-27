# kernel/mm Map

> Static understanding snapshot, not a decision history.
> See `.agents/decisions/kernel/mm.md` for the paired decision history (it still holds the decisions on the soft-MMU, the OOM killer, fork/copy-on-write and host backing made before they were split out into `kernel/mmu`, `kernel/oom`, `kernel/cow` and `kernel/hostmem`).
>
> Verified: 2026-11-26 (full mapping, 15 claims independently checked across VMA layout, page faults, permission changes, file invalidation and dump selection)
>
> Maintained: 2026-11-27 (targeted verification only since the full mapping; latest change: in-place condensation with no mapped claim changes)

## Responsibilities

`kernel/mm` implements Linux address-space and VMA semantics on top of `kernel/mmu`'s soft-MMU and
`kernel/hostmem`'s host backing: per-process `Mm`/VMA tables, memory syscalls, brk, stack growth,
guest fault policy, `[vvar]`/vDSO mapping, file EOF/truncation/object-move updates, on-demand FUSE
mappings (`Fill`), kernel guest access (`UserSlice`) and `/proc/<pid>/maps`/core-dump views.

Root: `core/kernel/src/mm.rs` (excluding `kernel/mmu`'s entry population/consistency/code groups,
`kernel/oom`'s resident-memory measures/`Mm::release`, `kernel/cow`'s `fork`/`Copy-on-write`, and
`kernel/hostmem`'s `Host backing`), `core/kernel/src/syscall/mem.rs` (excluding `process_mrelease`),
`core/kernel/src/uaccess.rs` (`UserSlice`), the page-fault branch in `task.rs`, and `Cx` stack-growth
helpers in `kernel.rs`.

Outside the module: `kernel/cow`, `kernel/hostmem`, `kernel/mmu`, `kernel/oom`, `engine`,
`engine/dbt`, `kernel/ipc`, `kernel/futex`, `kernel/exec`, `kernel/time`, `kernel/procfs`,
`kernel/cgroup`, `kernel/signal`, `kernel/vdso`, `kernel/fuse`, `kernel/fileio` and `core/hal`.

## Key types

### Address space

- `Mm` — per-process address space (`Rc<RefCell<Mm>>`, BKL-only; built unlocked at execve): owns `mmu`
  (dropped first), `vmas: BTreeMap<start, Vma>`, deferred-release `graveyard`, layout bounds (`brk_start`/
  `brk`/`mmap_top`/`start_stack`/`stack_low`), non-reused `id`, `shared_maps` (fast-futex writable shared
  ranges), FUSE/loader/procfs/vDSO/membarrier/`mlockall` state, and `kernel/{mmu,cow,hostmem}` handles.
- `Vma` — guest mapping: range, `prot`/`may_write`/`may_exec`, `Backing`, `Arc<HostRegion>` + host ptr,
  file/mount metadata (`file_len`, identity/version, fd, `name`/`ino`, `MountPin` `mnt` → `vfs::released`),
  SysV `shm`, `Fill`, Linux flags (locked, sticky `written`, growsdown, dump/fork/noreserve, sticky
  `accounted` charge), `anon_pgoff`, `present_run`, `probe_writes`, `has_anon_vma`, fork `anon`, `ForkFileView` (`fork_view`).
- `FileSource`, `Fill`, `FillPages` — file-mmap backing: store retains a dup of its pinned host fd;
  tmpfs tracks allocation/quota; FUSE supplies an open-specific `Fill` (shared across its VMA pieces) and
  per-inode `FillPages` (sparse memfd, size, filled/previously-filled runs, read-ahead, shared-write
  baselines, writable opens).
- `same_vma` — joins adjacent pieces with matching backing, prot/limits, lock, growsdown, dump/fork,
  noreserve, charge, name, fork identity and `has_anon_vma`, plus contiguous `anon_pgoff` (private anon),
  matching host/guest inodes + contiguous offsets + compatible FUSE open/cache (files), or contiguous
  spans in one host region and SysV attachment (file-less shared).
- `Backing` — `Anonymous` (private anonymous, private `/dev/zero`), `PrivateFile` (private file
  mapping), `Shared` (shared file, shared anonymous, shared `/dev/zero`, GPU or SysV shm; also
  `[vvar]`, whose host memory is shared with forked children).
- `flags` — guest ABI values of the `MAP_*` flags the mm interprets.
- `Refill` / `Reread` — private-file pieces remapped after `MREMAP_DONTUNMAP` or reread after
  `MADV_DONTNEED*`; `AdviseRange` — host-page-aligned `Arc<HostRegion>` slice for unlocked `madvise`;
  `PopulateWalk`/`PopulateStep` — resumable state and unlocked `WILLNEED`/FUSE yields for `MADV_POPULATE_*`.
- `WANTED` (thread-local) — the page of a `Fill` a kernel access found not read, for the dispatcher
  or a local result copy to service (`take_wanted`).
- `UserSlice` — an unchecked guest range passed to kernel-side accessors.

### Introspection

- `Region` — one `/proc/<pid>/maps` / `smaps` line.
- `DumpRegion`, `dump_filter` — core-dump segments and the `coredump_filter` bits (default `0x33`).

## Public entry points

- **Syscalls** (`syscall/mem.rs` → `Mm`): `mmap`, `mmap_fill`, `munmap`, `protect` (mprotect),
  `mremap` (`mremap_dontunmap`), `remap_file_pages`, `msync`, `madvise`, `mlock`/`mlock2`/
  `munlock`, `mlockall`/`munlockall`, `mincore`, `brk`. (`process_madvise` is `ENOSYS`;
  `process_mrelease` is `kernel/oom`'s; `mmap_gpu` backing is `kernel/hostmem`'s.)
- **execve / loader / faults**: `Mm::new`, `share_files`, `into_shared`, `set_layout`,
  `map_anonymous`, `map_vvar`, `find_free`, `set_brk`, `map_stack`, `set_start_stack`, `label`,
  `vdso_syscalls`; `fill_wanted`, `fault_past_eof`, `Cx::grow_stack`/`grow_stack_to`/`grow_for`,
  `is_mapped` (`cow_break` is `kernel/cow`'s; `mmu().translate`/`fault_in` `kernel/mmu`'s).
- **FUSE fills & kernel access**: `FillPages::new`/`size`/`has`/`window`/`window_from`/`store`/
  `notify_store`/`has_tracked`/`fd`, `take_wanted`, `has_fills`, `first_unread`,
  `next_unread_fill`, `invalidate_fill`, `invalidate_fill_code`; `read`, `write` (`kernel/cow` CoW break;
  `for_each_page` coalesces non-CoW/Fill/tmpfs spans and expands lazy anon fills to 2 MiB `LEAF_SPAN`),
  `pin_io` (`PinnedIo` via `HostRegion::io_write_pins`: `fork` copies, teardown defers `discard_unlent`),
  `translate` (no CoW break), `read_cstr`/`read_cstr_into`, `read_force`, `write_kernel`/`write_force`, `populate_mlock_step`, `mapped_above`, `check_mapped`.
- **Files, shm, futex & introspection**: `futex_key`, `shared_maps`, `id`; `file_truncated`,
  `writably_maps`, `maps_file`, `maps_ino`, `mapped_inos`, `set_mnt`, `mapped_mounts`,
  `next_msync`, `residency_chunk`; `attach_shm`/`detach_shm`/`shm_attachments`;
  `regions`, `residency`, `size`, `locked_bytes`/`locked_in`, `dump_regions`, `dump_page`.

## Data flow / lifecycle

### Creation and layout

- execve builds a fresh `Mm` (with a fresh `Loans` table) that shares the kernel-wide `FileMaps`;
  the loader fills it with the BKL released, and after the point of no return it is shared and
  installed.
- `set_layout` sets `mmap_top` from the `RLIMIT_STACK` at execve (capped at 8 MB for a secure
  exec). It adds the guard gap to the limit with saturation, clamps the sum between
  1 GiB + 8 MiB and 5/6 of the address space, then subtracts that gap from `STACK_TOP` and
  page-aligns the result. An unlimited stack still gives a top-down layout with the largest gap.
- `STACK_TOP` (`1 << 47`) caps stack/non-fixed `mmap` inside 48-bit `TASK_SIZE` (`1 << VA_BITS`) for
  47-bit NaN-boxing/tagging; fixed `mmap`/`mremap`/`shmat`, `brk`, in-place `mremap` reach `1 << 48`; PIE loads at `0x5555_5555_0000` (2/3 `STACK_TOP`).
  Non-fixed `mmap` allocates top-down from `mmap_top` (`find_free`), 2 MB-aligning mappings ≥
  `LEAF_SPAN` (2 MB) when VA space permits (falling back to page alignment) to form `kernel/mmu` L2
  superpages, skipping `stack_guard_gap` (256 pages) below grows-down VMAs, floored at
  `vm.mmap_min_addr` (`MMAP_MIN_ADDR`, 32768, ≥1 page; global, writable with `CAP_SYS_RAWIO`).
- `map_stack` maps the arguments and auxv plus 128 KB (capped at the page-aligned limit, never
  below what the arguments use) at the top of one `MAP_NORESERVE` host region of
  `max(min(RLIMIT_STACK, 8 MB), mapped length)`, leaving the rest below as reserved growth room.

### vDSO and `[vvar]`

- Loader maps vDSO (`kernel/vdso`) as kernel-written private anon. For host vDSO, `map_vvar` maps
  `[vvar]` right below at host length/distance: RO `Shared` VMA (`may_write`/`may_exec` false →
  mprotect widening `EACCES`) on a `foreign` `HostRegion` (`kernel/hostmem`) at host vvar addr. Only
  the first `data` clock bytes get PTEs; the rest faults `SIGBUS` like past-EOF (Linux time-ns page
  outside a time ns). `[vvar]` shares across fork, rejects ptrace writes (`write_force` refuses
  non-writable shared), cannot grow (`EFAULT` before space check, `VM_DONTEXPAND`) or dup (`EINVAL`),
  counts in no RSS, dumps nothing, and shows `r--p`.
- `vdso_svc` says whether the vDSO's clock functions are svc stubs: set at execve for the built
  image, and by `vdso_syscalls` once a process using the address space enters a time namespace
  with offsets, which writes the stubs over the host image's clock entries (`write_kernel`) and
  queues the vDSO for code invalidation. It is copied at fork and never cleared.

### mmap and brk

- Checks in order: unknown prot (`PROT_BTI`/`PROT_MTE` → `EINVAL`), unaligned offset (`EINVAL`),
  map type (`MAP_SHARED_VALIDATE` unknown flags → `EOPNOTSUPP`), `MAP_SHARED|MAP_ANONYMOUS|MAP_GROWSDOWN`
  (`EINVAL`), fixed addr < `vm.mmap_min_addr` w/o `CAP_SYS_RAWIO` (`EPERM`; anon needs len > 0; file
  checks `EBADF` + zero-len `EINVAL` first), file rules (FUSE gets `Fill`; store checks access/noexec,
  inode flags — `FS_APPEND_FL` writable fd `MAP_SHARED` → `EACCES`, `FS_IMMUTABLE_FL` `MAP_SHARED|PROT_WRITE`
  → `EPERM`, clearing `may_write` on RO `MAP_SHARED` of protected inodes so later `mprotect(PROT_WRITE)`
  is `EACCES` — and seals before unsharing/pinning), then `MAP_LOCKED` (`EPERM` if `RLIMIT_MEMLOCK == 0`
  w/o `CAP_IPC_LOCK`, `EAGAIN` over limit) or `MCL_FUTURE` (`EAGAIN` over limit). Private anon records
  growsdown/lock at creation; post-map runs `reap_shm` (`MAP_FIXED`), label, write/exec denials, lock,
  populate (`MAP_POPULATE`/`MAP_LOCKED`; FUSE reads pages then).
- Lengths round up to a page; zero or overflow is `EINVAL`.
- `READ_IMPLIES_EXEC` (cleared on execve, arm64 `SET_PERSONALITY`): readable `mmap` gets exec unless
  noexec mount (`do_mmap`), `mprotect` adds exec per `may_exec` VMA (`do_mprotect_pkey`, allowing mixed
  perms), `brk` heap is exec (`VM_DATA_DEFAULT_FLAGS`; perm change splits off a new VMA).
- `MAP_FIXED`/`MAP_FIXED_NOREPLACE`: aligned addr, end ≤ `1 << 48` (`EINVAL`, after `EPERM`);
  `NOREPLACE` occupied → `EEXIST`; syscall validates args/fd before `Mm`; fixed replace unmaps old
  range before backing/insert finishes (later error can leave a hole). Hints round down to page, raise
  to `vm.mmap_min_addr` (`round_hint_to_min`), apply if end ≤ `mmap_top`, free, and clear of upper guard gap.
- Host backing, `FileMaps` sharing, content-version trust, 16 KB alignment and commit accounting
  belong to `kernel/hostmem`. A FUSE file maps its inode's memfd with the permissions of the
  guest open, without a content version or `FileMaps` entry. Its `file_len` stays zero.
- `new_vma` inherits `locked` from `MCL_FUTURE`; `insert_vma` enforces `vm.max_map_count` (default
  65530, procfs-writable) and builds PTEs (`kernel/mmu`). Private anon starts with `anon_pgoff =
  start / PAGE_SIZE` and RO PTEs (0 anon RSS) until first write, except with readable `/proc/self/pagemap`:
  non-growsdown writable eager VMAs (`<= 1 MiB`) inheriting `has_anon_vma` or lacking mergeable neighbours
  start writable (`probe_writes`) and lazily detect stores (zeros included) via pagemap (`was_written`);
  split, move, mprotect, join of untouched neighbours, discard, heap growth or fork calls
  `finish_write_probe` (downgrades unwritten PTEs RO, shoots down, re-samples).
- `brk`: `< brk_start` returns current brk; growth needs free space, extending in-place in heap's host
  region (discarding bytes first) when tail is unnamed anon with matching perms/charge/flags and no
  other VMA on that host range, else reserving 256 MB (`MAP_NORESERVE`) at first heap page for ≤ 256 MB
  growth, else a separate accounted anon VMA. Shrink unmaps. Caps at `1 << VA_BITS`, ignores `mmap_top`/
  guard gap/`mmap_min_addr`/`RLIMIT_DATA`/`RLIMIT_AS`; `MCL_FUTURE` enforces `RLIMIT_MEMLOCK` (old brk
  on failure) and populates unless `MCL_ONFAULT`.

### Mappings read on demand (`Fill`)

- Page-table entries build eagerly for small/executable mappings and lazily otherwise (`kernel/mmu`);
  `PROT_NONE` gets none. FUSE and tmpfs mappings have their own fault paths. Without host pagemap,
  ordinary private file mappings prefill reads and record private host pages on first write.
- Tmpfs files pin their mapped object. Faults (`fault_range` → `FilePages::admit` → `TmpfsMapping::fault_range`)
  admit their span against mount quota (1 page on guest fault; up to 2 MiB leaf on kernel `access`/
  `write_kernel`, shrinking to fitting prefix before `ENOSPC`) and map ahead over already-populated
  contiguous data in the 2 MiB leaf without instantiating holes. Read faults install RO PTEs; first
  shared write updates mtime/ctime; private mappings break host CoW per page independently. `punch_tmpfs`
  and `MADV_REMOVE` revoke aliases before freeing pages.
- `Fill` VMAs keep `file_len` 0, so `map_pages` and `file_len`-sized paths (mprotect, mremap, fork,
  populate, truncate) build no PTEs even when exec; only `fault_in` builds already-read runs in a
  2 MiB leaf. FUSE `INVAL_INODE` expires attrs and (offset ≥ 0) revokes PTEs/exec translations across
  all mapping `Mm`s, marking `FillPages` stale; next fill refreshes EOF (including past old end).
  Shared writable aliases keep baselines for dirty-run writeback.
- Reading (`kernel/fuse::fill`): `Fill::window` picks unread pages around target (32 starting 16
  before; forward sequential doubles last read or prior run, 32–512), clipped to read runs and EOF.
  Daemon I/O runs unlocked; `store` `pwrite`s missing pages to memfd, recording whole window if reply
  hits `Fill::size`, 1 page on 0-byte EOF, else complete pages (`kernel/fuse` retries from `window_from(page)`
  if backward read-around stopped short, and loops partial pages until full page/EOF). Refill merges
  local shared stores with daemon bytes; private host copies keep theirs.
- Kernel accesses (`read`, `write`, `read_cstr`, `write_kernel`) hitting unread `Fill` pages return
  internal `FUSE_RETRY` + `WANTED`; dispatcher fills and retries (`kernel/process`). Socket result
  copies (`recvfrom`/`recvmsg`/`accept4`; per-record `recvmmsg`) fill locally. `read`/`write` walk
  page-by-page (may stop partway); `write_kernel` prechecks the whole range (any prot, `PROT_NONE`
  fills included) before mutating, giving `PROT_NONE` anon prot-0 PTEs. Debugger `read_force` walks
  page-by-page without changing prot: CoW reads owner, untouched anon reads 0, file growth refreshes
  extent, holes/`[vvar]` stop, missing FUSE requests fill.

### Guest page faults (task run loop, under the BKL, in this order)

1. A write fault where `cow_break` (`kernel/cow`) succeeds → re-execute.
2. The page now translates (another thread fixed it while this one waited for the BKL) → re-execute.
3. `try_fault_in` (`kernel/mmu`) succeeds → re-execute. The first private anonymous write
   records an identity for the logical VMA and upgrades present entries of the faulting host
   piece. Anonymous leaf-window fills skip CoW pages. A read or execute fault on a missing CoW
   page installs only its owner-backed entry with write permission stripped. A tmpfs page allocation failure delivers
   `SIGBUS` / `BUS_ADRERR`.
4. A page of a `Fill` mapping not yet read and allowing the access (`fill_wanted`) → read it through
   the daemon (above) and re-execute; a signal arriving before the daemon took the read is
   delivered first and the access faults again; a read error → `SIGBUS` / `BUS_ADRERR`.
5. `fault_past_eof`: checks VMA permissions first (`v.prot & need == 0` falls through to step 7's
   `SIGSEGV` / `SEGV_ACCERR`); otherwise past EOF in an ordinary file mapping whose file has grown →
   map the new part and re-execute (a `Fill` obtains its current EOF from the shared backing); otherwise past EOF
   → `SIGBUS` / `BUS_ADRERR`.
6. Stack growth (`grow_down`) succeeds → re-execute.
7. Otherwise `SIGSEGV`: `SEGV_ACCERR` if the address is mapped, else `SEGV_MAPERR`.

"Re-execute" forces no signal and leaves the PC on the faulting instruction (already-pending
signals may still be delivered). For an unhandled page fault the task also records an arm64
abort syndrome. A non-backing access error with a present leaf gives a level-3 permission fault.
Otherwise the populated page-table levels determine the translation-fault level.

### munmap, mprotect, mremap

- VMAs are only ever split (`split_at` advances file or anonymous page offsets and host pointers), never merged;
  `same_vma` tells which pieces Linux would have as one VMA.
- `munmap`: unaligned addr, 0/overflow len, end > 2^48 → `EINVAL`; 3-way split at `max_map_count` →
  `ENOMEM`. Coalesces spans and pre-demotes partial boundary superpages before recalling/taking CoW
  loans or splitting (demotion `ENOMEM` preserves VMAs/PTEs/regions/loans; CoW recall can `ENOMEM`
  before splits). Splits endpoints, unmaps spans, moves removed regions to `graveyard`, clears `vdso`
  on overlap, sets `dirty`, queues exec `code_ranges`, returns loans (`kernel/cow`), syscall calls `reap_shm`.
- `mprotect` (`protect`, `do_mprotect_pkey`): unknown prot (`PROT_SEM`)/both grows bits/unaligned addr
  → `EINVAL`; len 0 succeeds; overflow → `ENOMEM`. Walks VMAs from first ending above addr; gap (`ENOMEM`)
  or denied `may_write`/`may_exec` (`EACCES`) stops leaving earlier VMAs changed. `PROT_GROWSDOWN`
  extends start to first VMA's Linux-VMA start (must be growsdown → `EINVAL`; `ENOMEM` if start ≥ end);
  `PROT_GROWSUP` → `EINVAL`. `unshare` copies `cow` VMAs (files remap/copy private pages; `Fill` remaps
  privately, copies changed read pages, drops PTEs) before writable; `back_reserved` backs `reserved`
  regions before accessible. Adding private write sets persistent commit charge (unless `MAP_NORESERVE`).
  Tracked file pages (`v.pages`) prepare boundaries and restrict PTEs in place to `new & !prot::WRITE`
  (keeping readable `PageWatch`es, prefilling RO from `PROT_NONE`, letting detached pages regain write on
  demand) instead of unmapping. Downgrades set `dirty`. On private anon with fork CoW pages, installs
  owner-backed PTEs under old non-writable perms before granting access, then grants write only outside
  CoW pages. Failures preserve VMA prot and queue rollback invalidation.
- `mremap`: checks flags (`MREMAP_DONTUNMAP` needs equal lengths) and fixed target < `mmap_min_addr`
  (`EPERM`) first. Linux order: unmapped old addr `EFAULT`; `MREMAP_FIXED` target validated (`EINVAL`)/
  unmapped and shrunk tail unmapped; rest must lie in one Linux VMA (`same_vma`, pre-6.17 → `EFAULT`).
  Non-fixed shrink just unmaps tail. In-place growth needs old end at VMA end, free space clear of upper
  growsdown `stack_guard_gap`, end ≤ `1 << VA_BITS`: reservations grow unbacked; shared anon/SysV extend
  same VMA (contents capped at original backing extent, `SIGBUS` past); backed private anon tails reuse
  exclusively-held spare host bytes (discarding first); others add continuation VMA with next offset/flags
  (file/FUSE maps next slice). Private anon moves keep offsets across faulted logical VMAs, else take
  destination offset (allowing neighbour join); extensions continue from moved tail. Growing/duplicating
  (`old_len == 0`) locked VMAs enforces `RLIMIT_MEMLOCK` (`EAGAIN`, after `MREMAP_FIXED` unmap) and
  populates unless `MCL_ONFAULT`. Shared anon/SysV keep backing across shrink/growth/move (regrown pages
  alias original bytes). Moves settle CoW (`kernel/cow`), re-point host memory, move PTEs, update `v.shm`
  attach bases, and update/clear `vdso` (whole vs partial).
- Moves (`MREMAP_MAYMOVE` without in-place room, `MREMAP_FIXED`, `MREMAP_DONTUNMAP`) return `ENOMEM`
  up front unless > 3 VMAs below `max_map_count` (`move_room`, 6.1 `move_vma`; +2 for target unmap in
  `mremap_to`); moved pieces insert via `place_moved_vma` without rechecking. PTE-move OOM rolls back
  partial moves into retained source tables before withdrawing temp destination ranges and queueing
  exec invalidation there.
- `MREMAP_DONTUNMAP` (6.1 order): unmapped old addr `EFAULT`, target `EINVAL`, margin `ENOMEM`, unmap
  target, one-VMA `EFAULT`; then checks split + remnant room. Empty anon remnants keep `anon_pgoff`,
  unlink `anon_vma`/fork identity only on whole-VMA moves (partial stays joined with siblings; whole
  avoids merging with faulted neighbours until later), and clear `VM_LOCKED` across whole old VMA while
  moved range stays locked. Private file remnants also clear fork `anon` only on whole-VMA moves: FUSE
  remaps memfd keeping fill/`file_len == 0`; ordinary files emit one `Refill` per compatible run of
  retained fds/mount pins/tmpfs/interpreter opens (even after unlink). Shared aliases coalesce across
  contiguous spans in one host region/attachment with compatible pins and accessible prefix. Remnants
  keep perms/charge/non-lock flags. Syscall calls `reap_shm`. `shmat` also refuses non-zero fixed addr
  < `vm.mmap_min_addr` w/o `CAP_SYS_RAWIO` (`EPERM`).
- `mremap` `old_len == 0` duplicates (6.1 shm dup): unmapped `EFAULT`; must be shared (`EINVAL`, after
  `MREMAP_FIXED` validate/unmap); w/o `MREMAP_MAYMOVE` → `ENOMEM`; no margin check. Shared files remap
  from addr's offset for `new_len`; shared anon/SysV share host region (up to end, `SIGBUS` past), SysV
  copies creating a new attach base (`shmdt` detaches it).
- `remap_file_pages`: non-zero prot `EINVAL`; rounds start/size down; needs contiguous shared pieces of
  one host file with matching prot (and FUSE open/access limits; rejects shared anon/SysV → `EINVAL`);
  remaps `MAP_SHARED|MAP_FIXED`, keeping label/FUSE/exec denial/lock; populates unless `MAP_NONBLOCK`.
- `msync`: bad flags/align `EINVAL`, overflow `ENOMEM`, len 0 ok; walks via `next_msync`, skips gaps
  (`ENOMEM` at end), `MS_INVALIDATE` on locked VMA → `EBUSY` (overrides gaps); `MS_SYNC` syncs shared
  host slices unlocked (`Arc<HostRegion>`) and writes dirty shared FUSE pages before data sync. `mincore`:
  align `EINVAL`, overflow/end > `1 << VA_BITS` `ENOMEM`, `vec > 1 << VA_BITS` `EFAULT`; walks contiguous
  VMAs in ≤ `PAGE_SIZE`-page `residency_chunk`s without stack growth, stopping at gap (`ENOMEM`) or write
  fault (`EFAULT`); reserved → 0, borrowed → 1.
- `mlock`/`mlock2`/`munlock` (`do_mlock`/`apply_vma_lock_flags`): wraps `len + (addr & PAGE_MASK)` up to
  page; after `RLIMIT_MEMLOCK` check (`EPERM` if 0, `ENOMEM` over; skipped w/ `CAP_IPC_LOCK`), wrap-past-end
  is `EINVAL`, empty ok. `set_locked` walks contiguous VMAs from `start`, stopping at gap (`ENOMEM`, earlier
  updated, later untouched, no populate). Fully mapped lock w/o `MLOCK_ONFAULT` runs `populate_range`
  (`populate_mlock_step`): fetches FUSE `Fill` unlocked (`ENOMEM` read error, `EINTR` kill), stops at
  `PROT_NONE` (`ENOMEM`, leaving locked), skips RO private anon (0 RSS, CoW intact), settles CoW +
  atomically write-faults writable private anon. `mlockall(MCL_CURRENT)` checks total size (incl. `PROT_NONE`)
  vs limit, locks all VMAs and populates (cold FUSE incl., ignoring errors) unless `MCL_ONFAULT`;
  `MCL_FUTURE` alone checks only `RLIMIT_MEMLOCK != 0` (`EPERM` w/o `CAP_IPC_LOCK`); new mappings (`mmap`/
  `brk`/`shmat`) and locked growth (`mremap`/`grow_down`) enforce `RLIMIT_MEMLOCK`.
- `madvise`: align/len/unknown advice (counted unimplemented) → `EINVAL`; len 0 ok. Walks VMAs (6.1
  `do_madvise`): skips gaps (`ENOMEM` at end), per-VMA errors (`EINVAL` on locked/foreign/GPU for
  `MADV_DONTNEED`/`MADV_FREE`/`MADV_COLD`/`MADV_PAGEOUT`, foreign/GPU for `MADV_DONTNEED_LOCKED`,
  non-private-anon/`[vdso]` for `MADV_FREE`, non-private-anon for `MADV_WIPEONFORK`, `EINVAL`/`EACCES`
  for `MADV_REMOVE`, `EINVAL`/`EFAULT` for `MADV_POPULATE_READ`/`MADV_POPULATE_WRITE`, split `ENOMEM`)
  stop immediately (overriding gaps), leaving earlier slices applied. Flag advice (`DONTDUMP`/`DODUMP`/
  `WIPEONFORK`/`KEEPONFORK`/`DONTFORK`/`DOFORK`) splits at slice ends. `MADV_COLD`/`MADV_PAGEOUT`
  (`check_lru`) and `MADV_FREE` (`check_free`) validate via `check_vmas`. Non-destructive hints (`NORMAL`/
  `RANDOM`/`SEQUENTIAL`/`WILLNEED`) prefill RO non-FUSE file PTEs ≤ 4 GB/call (`prefill_file_ptes`) and
  fetch FUSE `Fill` unlocked (`willneed_fuse` → `next_unread_fill` + `fuse::fill`, advisory regardless of
  prot) for `WILLNEED`, forwarding non-FUSE slices clipped to `file_len` to `appine_hal::advise` unlocked
  via coalesced `AdviseRange`s (skipping `reserved`/foreign/GPU/`Fill`; `NORMAL`/`RANDOM`/`SEQUENTIAL`
  file-only; `WILLNEED` skips untouched anon) before reporting gaps `ENOMEM`.
- `MADV_POPULATE_READ`/`MADV_POPULATE_WRITE` (`madvise_populate` → `populate_step`): checks `PROT_READ`/
  `PROT_WRITE`, refuses `[vvar]`/GPU (`EINVAL`), refreshes stale extents, faults tmpfs (quota + shared
  write times) and tracked private files, reads FUSE `Fill` unlocked (`EINTR` signal, `EFAULT` read error/
  EOF), warms regular slices ≥ 64 KB with unlocked `MADV_WILLNEED`, keeps CoW on `POPULATE_READ` vs
  settles CoW + atomic write-fault (`populate_write_host`) on `POPULATE_WRITE`, installs missing PTEs,
  stops `EFAULT` past EOF.
- `MADV_DONTNEED`/`MADV_DONTNEED_LOCKED` (`discard_range`): `LOCKED` allows `mlock`'d VMAs without
  unlocking; both refuse `[vvar]`/GPU (`EINVAL`). Private anon reads 0 after discard: after freezing
  `probe_writes`, non-vDSO `discard_with_pagemap` keeps PTEs and expires `PageWatch`es if `/proc/self/pagemap`
  is readable; only when unreadable and range has PTEs/CoW loans does it pre-demote partial superpages,
  settle CoW, discard host bytes, clear `present_run`, zap PTEs, set `dirty`. Tracked private file pages
  lose PTEs before clearing records. Written private file pages before EOF `unshare` `cow` first, then
  revert via `discard_file_host` (whole host pages) and `Reread`s (`fill_private`, zeros past EOF/open
  fail; `Fill` partial host pages reread memfd keeping PTEs). Exec ranges queue invalidation; shared/
  unwritten untouched; vDSO refills from kernel image (svc stubs if `vdso_svc`).
- `MADV_REMOVE` (`remove`): per-VMA check (`EINVAL` on locked, private anon or GPU; `EACCES` on
  other private or never-writable, 6.7); shared host ranges use `remove_host`, tmpfs and FUSE
  ranges punch holes through tmpfs / the daemon. Neither path requests code invalidation.

### Stack growth

- `grow_down` refuses pages below `vm.mmap_min_addr` and addresses already mapped. It applies when
  the nearest VMA at or above the address is `growsdown`. It refuses if the single nearest VMA
  below is non-growsdown, accessible and closer than 256 pages (`stack_guard_gap`; a `PROT_NONE` or
  growsdown neighbour is not looked past), if the new stack size exceeds the current
  `RLIMIT_STACK`, or if the growsdown VMA is locked and growth would exceed `RLIMIT_MEMLOCK`.
  Otherwise it extends into the reserved room below, or maps a new chunk in a host
  allocation of `clamp(size, grow, limit − size)` bytes (exactly the growth if that fails) with the
  chunk at its top, so the VMA count grows only logarithmically; the chunk takes the flags of the
  VMA above it and needs room for one more VMA.
- The kernel grows a stack itself at syscall entry when SP is below the main stack (arm64 has no
  red zone), before writing a signal frame, and when a kernel access to unmapped memory fails
  (`grow_for`, as Linux's fault handler does): the shared syscall helpers (`get`/`put`), read,
  write and readv buffers, getdents64, vmsplice, waitid and FUSE's device ioctls and prefault go
  through `Cx::read_user`/`write_user`, and `/proc/<pid>/mem` accesses grow within the accessor's
  `RLIMIT_STACK`. Other direct `Mm::read`/`write` callers do not grow it.
- `MAP_GROWSDOWN` is honoured only for private anonymous mappings (including private `/dev/zero`);
  `MAP_SHARED | MAP_ANONYMOUS` with it is `EINVAL`, and file or shared `/dev/zero` mappings ignore
  it. An mmap'd growsdown VMA has no reserved room, so its first growth maps a new chunk.

### Files: EOF and truncation

- Ordinarily whole pages past EOF have no entries. Ordinary shared and private mappings keep the file open
  across descriptor close, VMA splits, fork and mremap. Private mappings share a retained descriptor
  per host inode across a fork family. A store pin takes precedence over an unpinned descriptor from
  the ELF loader. Guest instruction faults and kernel copies retried through
  `grow_for` check the current extent. If a private mapping has anonymous or shared read-only backing after fork,
  caching or mprotect, only the previously inaccessible tail gets a fresh private host mapping.
- Before a host shrink, truncate, ftruncate and `O_TRUNC` clip the file's accessible extent
  in each address space they can borrow, queue executable invalidation, mark it dirty and sync
  it. Clipping removes post-EOF PTEs (clearing enclosing 2 MiB superpages w/o alloc on demotion OOM) so
  later guest access faults; a later host truncate error can leave logical EOF shorter than host file.
- Private and shared mmap of a named store file first give the inode its own backing object, including
  on read-only opens. The VMA retains a duplicate of its locked open description. Splits, fork
  and mremap keep the pin. Cached host views use a separate open description. Clones and overlay
  copy-up copy bytes when either object is pinned.
  A Files app truncate clips affected address spaces in each running kernel before the host
  shrink; guest faults can then observe EOF or regrowth. Private mappings retain their backing.

### Introspection and teardown

- After guest exec, `exe_open` holds the main program's inotify open record. Fork shares it.
  Non-overlay file-backed interpreter VMAs share their open record across splits and fork.
  Their last unmap releases it. Leaving the address space with no users releases its remaining exec opens.

- `regions` labels each VMA (file name, else `[stack]` for the VMA containing `start_stack` and the
  contiguous same-prot growsdown VMAs below it, else `[vdso]`, else `[vvar]` for the foreign VMA,
  else `[heap]` inside `[brk_start, brk)`) and joins adjacent pieces of one Linux VMA (`same_vma`,
  plus the same label). procfs escapes a newline in a file name as `\012`.
- `dump_regions` includes the VMA starting at the recorded vDSO address before consulting
  dump flags. Other foreign or `MADV_DONTDUMP` pieces have zero payload. `ANON_PRIVATE`
  selects private anon with anon_vma (or `was_written`) and private file pieces written or writable.
  File and ELF-header filters cover further cases. The
  writer leaves holes for absent pages in sparse regions and fetches missing FUSE pages before
  inspecting headers or copying segments.
- `Mm` drop leaves its code group (`kernel/mmu`), releases CoW (`kernel/cow`), defers the page
  table (`kernel/hostmem`), then drops VMAs and graveyard. RSS measures live in `kernel/oom`.

## Dependencies (inbound / outbound)

### Inbound

- `syscall/mem.rs`, `syscall/mod.rs` (stack growth at entry, `take_wanted`), `kernel/fuse` (`Fill`,
  `prefault`, `invalidate_fill`), `kernel/process`/`kernel/exec`/`kernel/ns`/`kernel/signal` (execve, setns
  `vdso_syscalls`, faults, `Cx` stack growth, signal frames), `kernel/ptrace` (`read_force`, `write_force`),
  `kernel/futex` (`futex_key`, `translate`, `id`, `shared_maps`), `kernel/ipc` (`attach/detach_shm`),
  `kernel/files`/`kernel/fileio` (`ETXTBSY`, seals, truncation, clones, unsharing), `kernel/procfs`,
  `kernel/oom`, `kernel/mmu` (VMAs/EOF/sync inputs), `kernel/cow`.

### Outbound

- `kernel/cow` (`cow_break`, `cow_settle`, `cow_unmap`/`give_back`, `cow_pages`/`cow_entries`/
  `write_around`, `discard_unlent`, `return_loans`); `kernel/hostmem` (`HostRegion`, `FileMaps`,
  `content_version`, `WRITABLY_SHARED`, `discard_host`/`discard_file_host`/`remove_host`,
  `mmap_gpu`); `kernel/mmu` (PTE builders/movers, `sync`, `quiesce`, `shootdown`, code groups).
- `appine_hal` (`populate`, `fstat`, `msync`, `advise`, `mincore`, memfds,
  `page_size`); `kernel/vdso` (image/svc image, clocks, host vvar); `kernel/fuse` (`Fill::source`);
  `kernel/vfs`/`kernel/fileio`/`kernel/mount`/`kernel/cred`/`kernel/ipc` (`reap_shm`).

## Invariants and gotchas

- Follow `kernel/mmu` (release host memory only after thread flushes; keep PTEs valid at each step
  since `shootdown` does not pause threads; record `dirty`/`graveyard`/`code_ranges` for `sync`),
  `kernel/cow` (never write lent/borrowed pages in place; break/settle CoW before mutating), and
  `kernel/hostmem` (never dereference `RESERVED_HOST`; never discard/unmap `foreign`/`gpu` regions;
  release via `HostRegion` drop).
- A `Fill` VMA has `file_len` 0 and entries only for valid pages. A previously filled page can
  retain private CoW data after invalidation. Fork and unshare compare all previously filled
  pages within EOF when copying private contents to a new host file mapping.
- Every private anonymous page with content has a PTE (except borrowed pages in `PROT_NONE`, which
  `kernel/cow` adds): kernel writes, mlock and `MAP_POPULATE` fill entries, mremap moves them, and
  `PROT_NONE` keeps prot-0 entries (`kernel/cow` lending and `kernel/oom` RSS rely on this).
- `Mm` (`Rc<RefCell>`) is BKL-only (checking builds assert no borrow across BKL release);
  `shared_maps` is shared across threads for the fast futex path. `install_vma`, `grow_tail` and `move_range`
  publish write-eligible shared ranges in `shared_maps` before installing or moving PTEs,
  including ranges with write permission currently disabled (rolling back if
  `map_pages` fails), and `munmap`, `move_range`, and `deny_write` prune removed/non-writable
  ranges after old PTEs are gone.
- The host's `vm.max_map_count` is shared by the whole app process, so the guest limit is enforced
  per address space.
- VMAs never merge; adjacent anonymous VMAs are separate host mappings and are only joined (as
  `same_vma`) for display, mremap's one-VMA rule, `PROT_GROWSDOWN`, fork's `anon` identities and
  fork's commit estimate. Code creating a VMA that continues another (stack
  chunks, mremap growth) must copy the flags `same_vma` compares.
- Guest pages are always 4 KB (`AT_PAGESZ` 4096) whatever the host page size.
- Host `mincore` reports pages swapped to zram as absent; RSS (`kernel/oom`), mincore(2), pagemap
  and core-dump holes still use mincore.
- `clip_file_mappings`, `punch_tmpfs`, `invalidate_fill` use `unmap_or_clear_superpages`: on partial
  2 MiB demotion OOM, `Mmu::unmap_noalloc` clears only partially covered L2 superpages whole (L3 tables
  stay exact so adjacent anon VMAs in the slot stay untouched) and expires `PageWatch`es over the widened
  range; valid file/FUSE pages refault with intact contents and baselines.

## Confirmed bugs / technical debt

- In-place growth still costs a host piece and a VMA slot for a file mapping or when a backed
  private anonymous tail lacks exclusively held spare backing. A split sibling, loan, active
  host advice or pending graveyard release can keep the region shared. Moving the host allocation
  would invalidate live guest translations or loans. Shared-file DONTUNMAP aliases spanning
  distinct host regions retain one remnant per compatible region.

## Open questions

- None.
