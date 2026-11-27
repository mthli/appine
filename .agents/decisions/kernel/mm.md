# Guest memory (kernel/mm) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: kernel/mm"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12). D1–D17 were seeded by hand from the retired design
> doc §5 and the round records in `docs/rounds/`, so their sources cite rounds; later entries cite
> commits.

## Constraint index (compressed active decisions)

> Full text of D1, D2, D4, D6–D9, D11–D17, D19–D21, D23–D26: `git show 248d79f637c8f05dadc6ef9fdb0733c568c6a468:.claude/decisions/kernel/mm.md`
> Full text of D3, D5, D10, D28–D33, D36, D38–D39, D41–D44: `git show 597290948791d48aa8074f338e2dba5f0fc6cfab:.agents/decisions/kernel/mm.md`

- **D1** — Guest memory uses a software radix page table per address space and a software TLB per thread, never emulated ARM translation hardware, and the TLB layout is a contract with the Tier 1 translator, which inlines the check (design review 2026-10-24, round 23)
- **D2** — Unmapped host memory is released only after the other threads are quiesced (the graveyard) and then after the BKL drops (a thread-local list), so any new path freeing host memory under the BKL must use the same deferral, and the OOM killer skips a round while releases are pending (rounds 04, 29, 36, 109)
- **D3** — Host memory is requested as accounted or `MAP_NORESERVE` from the guest's prot and flags (including shared anonymous `MAP_NORESERVE`) rather than the always-read-write host mapping, so host and guest commit views can differ (rounds 10, 179, 199; 5d23297)
- **D4** — New `PROT_NONE` mappings get no page-table entries, so mprotect away from `PROT_NONE` must rebuild them, and `MADV_DONTNEED` hands whole host pages to the host's madvise while zeroing partial ones on 16 KB hosts (round 12)
- **D5** — truncate, ftruncate and `O_TRUNC` remove PTEs past the new EOF and sync every affected address space (caller included) before shrinking the host file so stale translations cannot raise host `SIGBUS`, though a failed host shrink leaves mappings clipped until remapped (round 12; 400817d)
- **D6** — fork finds the pages the host touched through `/proc/self/pagemap`, never `mincore` (which reports zram-swapped pages as absent), and lends every page of mappings of at most 16 pages or when pagemap is unreadable (rounds 13, 26, 179)
- **D7** — VMAs are never merged and each keeps its own host memory, so `same_vma` treats adjacent pieces as one Linux VMA (D22) and must compare every flag shown in maps and smaps, and the guest's `max_map_count` is enforced per address space (rounds 17, 18; e03c0cc)
- **D8** — The first brk growth reserves 256 MB of `MAP_NORESERVE` host address space that later growth lengthens as one VMA and fork recreates, with growth beyond it falling back to separate mappings (round 17)
- **D9** — execve reserves host memory below the main stack down to `RLIMIT_STACK` (at most 8 MB), and the stack grows into it and then into chunks about as large again, but only at syscall entry, for signal frames and on failed accesses through the user-copy helpers and `/proc/<pid>/mem`, never through direct `Mm::read`/`write` (round 22; e03c0cc)
- **D10** — fork lends written private anonymous pages read-only in soft page tables (borrowers copy on write, owners recall before writing), and `translate` does not break CoW so kernel write paths must break CoW or fault the page in first (round 26; e4d3386)
- **D11** — When the last borrower returns a lent page, the owner's entries get the VMA permission back at once instead of waiting for a write fault (round 26)
- **D12** — Read-only private mappings of files with a trusted content version share one kernel-wide read-only host mapping, so a VMA must copy before it becomes writable, and a missed copy faults instead of corrupting other processes' code (round 28)
- **D13** — Address spaces of the same executable with consistent executable layouts share one Tier 1 translation space, a diverging member leaves for good with a shootdown, and the last member to leave must not leave other members' translations behind (rounds 108, 181)
- **D14** — Private anonymous host ranges are discarded with `MADV_DONTNEED` and reused for the next request of the same size and accounting class (up to 32 ranges per size, 256 MB in all), and must be discarded before reuse so guests never see stale data (round 109)
- **D15** — fork carves the child's small mappings, heap and stack from a pool of pre-mapped host memory refilled after the BKL drops, a carved region is returned only when all its pieces are unmapped, and pool misses and mappings over 1 MB still mmap under the BKL (round 109)
- **D16** — Mappings over 1 MB (except executable ones) get page-table entries on first access in 2 MB leaf tables, so every private anonymous page with content must have an entry and any kernel path writing guest memory directly must fill it, since fork and RSS look only where entries exist (round 179)
- **D17** — A private anonymous mapping that has been `PROT_NONE` since creation holds no host memory until it becomes accessible or the kernel writes into it, and its fake host base must never be dereferenced (round 199)
- **D19** — `MADV_WIPEONFORK` and `MADV_DONTFORK` are VMA flags that fork honours (dontfork VMAs are skipped, in the commit check too, and wipeonfork ones are never lent, so the child gets zeroes), and every path that copies a VMA must carry them along with dontdump (e020977)
- **D20** — mm.rs, syscall/mem.rs and oom.rs read in the order their code runs (mm.rs in the sections Address space, Consistency and code, Copy-on-write, Introspection types and Host backing), so new code goes where it runs rather than at the end of `impl Mm` or of the file (80d41b5)
- **D21** — mremap with an old length of 0 duplicates shared mappings in 6.1's error order, mapping shared files again and cloning shared anonymous and SysV VMAs onto the same host memory, so a copy's `file_len` is capped at its host region's end and need not equal its length, and SysV copies count as attachments per kernel/ipc D2 (5dcf7db)
- **D23** — mlock, mlock2 and munlock validate and page-align their range in the syscall with `do_mlock`'s arithmetic before calling `Mm::set_locked` (09188ec)
- **D24** — `vm.overcommit_memory=1` makes fork skip `commit_too_big` (kernel/procfs D16), while mode 2's strict accounting is not emulated and behaves as 0 (42d2ffe)
- **D25** — `Mm::fork` copies the membarrier registrations, as Linux's `dup_mm` copies `membarrier_state` (cbf7267)
- **D26** — `resident_split` sorts whole VMAs into anonymous, file and shared pages for status's Rss lines (kernel/procfs D20), counting a written private file mapping as anonymous, and shares `shmem_like` with core dumps (566da95)
- **D28** — `Mm::mapped_above` checks whether the last VMA ends above an address (like Linux's `find_vma`) to choose `SEGV_ACCERR` vs `SEGV_MAPERR` for forced signal-frame `SIGSEGV`s (kernel/signal D18) (41bd6ec)
- **D29** — OOM checks, code-view release and load-average sampling stop only via `Shared::stop_checks`, using SeqCst stores/loads on `checks_idle` and every task's `sleeping` flag so a fast-path waking thread never leaves checks stuck off (5d23297)
- **D30** — File mapping identities use host `st_dev` and `st_ino` as `u64` without casts on the remaining aarch64 Linux/Android hosts (hal D1), which would require re-casting on a 32-bit target (7c5e636)
- **D31** — Private futexes key on non-reused `Mm::id` and user address (no mapping or CoW break needed), while `shared_maps` tracks all writable shared mappings so shared-anon futexes take the BKL path, leaving a brief window in mmap/shmat before recording (kernel/process D32) (f6a82cc, 5d7b936)
- **D32** — `Oom::stuck` skips victims that outlive a second until their last thread ends, `badness` scores unclamped as `oom_badness` (skipping vfork children whose `Task::vfork` must clear in `release_mm`, omitting swap/page tables), and `proc_score` scales against host RAM plus swap for `oom_score` (kernel/procfs D30) (b43ff37, 39ea702)
- **D33** — `MADV_REMOVE` forwards whole host pages to host `MADV_REMOVE` and zeroes edges or refused ranges (tmpfs accounting updates only on recount), and `MADV_DONTNEED` refills the vDSO from its image (dropping debugger breakpoints) (5d7b936)
- **D36** — madvise counts every advice value refused up front (KSM, THP, hwpoison, plus invalid test advice) as an unimplemented path (kernel/signal D24) (99b4216)
- **D38** — `Mm::map_vvar` maps host live vvar pages below the vDSO as a read-only foreign Shared VMA (`[vvar]`, never written/unmapped/discarded, omitted from RSS/smaps/core dumps), and `Mm::vdso_syscalls` patches svc clock stubs on entering an offset time namespace (restored across discard) (kernel/signal D4, hal D18) (16364c0)
- **D39** — Shootdowns kick threads in guest code without holding later entrants out (only `quiesce` does), so `mprotect` updates per VMA without relying on a prior shootdown: drop write, install RO CoW entries, then restore write in runs around them (f51f688, 04163e7)
- **D41** — `Mm::code` and `Mm::data` record main-program `start_code`/`end_code`/`start_data`/`end_data` from the loader (copied at fork) alongside `brk_start` and `start_stack` for `/proc/<pid>/stat` (kernel/procfs D34) (6d9a9e8)
- **D42** — `mremap` moves (`MREMAP_MAYMOVE`, `MREMAP_FIXED`, `MREMAP_DONTUNMAP`) check `move_room` before mutating and re-insert via `place_vma`, so any new `move_range` caller must check `move_room` first (a host PTE allocation failure in `move_entries` still leaves a partial move) (004f5be)
- **D43** — `READ_IMPLIES_EXEC` (cleared on every execve) adds `PROT_EXEC` to readable mmaps (except noexec mounts), per-VMA `Mm::protect` where `may_exec`, and `Mm::brk`'s heap (splitting differing permissions into a new VMA) (5760479)
- **D44** — Cgroup `memory.current` uses `mm::Usage` via `Mm::plan` to merge overlapping host spans and measure each shared host page once in one mincore pass (lending across cgroups still charges both, and distinct host addresses per process would double-count) (kernel/procfs D5) (1d3d559)

## Active (full detail)

### D18: kernel/mm covers VMA semantics and memory syscalls, with soft-MMU, OOM, CoW and host backing split out
- **What**: kernel/mm covers address-space VMA semantics, layout, `[vvar]`/vDSO mapping, memory syscalls (`syscall/mem.rs` except `process_mrelease`), FUSE `Fill`, fault order, `uaccess.rs`, file EOF/truncation/moves and maps/core-dump views (keeping `.agents/maps/kernel/mm.md` condensed in place rather than splitting a fifth child), while the soft-MMU belongs to `kernel/mmu`, memory accounting and OOM to `kernel/oom`, fork/copy-on-write to `kernel/cow` and host backing to `kernel/hostmem`.
- **Why**: Splitting four child modules brought `kernel/mm.md` under the single-read budget from 48,272 B (104.8%), and subsequent growth past 80% was handled by condensing in place to 32,660 B (70.9%) and repointing object-unsharing/seals references to `kernel/fileio`.
- **Tradeoffs**: VMA mutations that settle CoW or allocate/discard host regions cross into `kernel/cow` and `kernel/hostmem`, section boundaries inside `mm.rs` live in `.agents/MODULES.md`, pre-split decisions stay here, and condensing in place leaves less growth headroom than another split.
- **Watch out**: VMA paths must preserve `kernel/cow`'s no-in-place-write rules, `kernel/hostmem`'s `RESERVED_HOST`, foreign/GPU and deferred-unmap contracts and `kernel/mmu`'s `sync` tracking, large memory-syscall additions may push `kernel/mm.md` back toward the 80% read budget, and open map bugs remain (shared-anon `mremap` regrowth, approximate `madvise`/VMA boundaries, whole-range gap failures and FUSE non-page-cache semantics).
- **Source**: cd53f35, 44a21b7 (scope since extended by 3f3db11 and d2b1dc3; bug list since updated by 3f3db11, 1459292, 4e64e74, 400817d, 5d23297, d9e1f0c and 5760479); 36083476; 20137e4c; cf029f9a; 8a17e4a9; d053be24; 60790c14; 39e55b4b; 610d623f; 4fcd3e13

### D22: Linux VMA boundaries are computed over unmerged pieces
- **What**: `same_vma` decides which adjacent pieces form one Linux VMA (contents source, backing, protection, flags, grows-down and an `anon` identity that fork renews per parent VMA) for maps, mremap's one-VMA rule and `PROT_GROWSDOWN`; mprotect follows `do_mprotect_pkey`; a guard gap below grows-down VMAs and `MMAP_MIN_ADDR` (32768) bound placement; and failed kernel accesses grow stacks. Joining also checks anonymous page offsets, lazy anon_vma state, permitted execution, noreserve and sticky accounting, preserving faulted offsets across moves.
- **Why**: With VMAs never merged (D7), maps, mremap, `PROT_GROWSDOWN`, merging after fork, stack growth on kernel accesses, partial mprotect and mmap_min_addr all differed from Linux, as Starnix's mmap tests showed.
- **Tradeoffs**: fork reads pagemap once per written private file mapping's host region (hal D12), `anon_vma` is still approximated, and direct `Mm::read`/`write` callers do not grow stacks.
- **Watch out**: mremap now refuses ranges spanning differently flagged pieces with `EFAULT` (as Linux before 6.17), partial mprotect leaves earlier VMAs changed on error, and the fork pagemap read may show in fork-heavy benchmarks.
- **Source**: 1459292, e03c0cc, 9d0ad1b6, ab417943

### D27: `MADV_DONTNEED` reverts written private file mappings to the file
- **What**: For written private file VMAs, `discard` gives the whole host pages of a host private file mapping back with the host's `MADV_DONTNEED` (`discard_file_host`, shared with the anonymous path) and returns `Reread`s for partial host pages (16 KB hosts) and VMAs copied into anonymous memory by `unshare`, which madvise reads from the store object (zeros past EOF or when it cannot be opened) and stores with `Mm::fill_private`, queueing executable ranges for invalidation.
- **Why**: `discard` only gave private anonymous memory back to the host, so a program resetting a private file mapping with `MADV_DONTNEED` kept its own changes where Linux reads the file again.
- **Tradeoffs**: Re-read pieces are private copies of the file at that moment, so later writes to the file do not show in them as they would on Linux.
- **Watch out**: The 16 KB partial-page path runs only on 16 KB devices and no differential test covers it, and an inode reused after its object was reclaimed would be read in place of the old file.
- **Source**: 8127cfe

### D34: Unmapping paths reap destroyed shm segments, and `Mm::can_write` is removed
- **What**: `munmap`, `mremap` and `MAP_FIXED` call `reap_shm` for destroyed SysV shm segments (kernel/ipc D7), while the unused `Mm::can_write` VMA-only writability pre-check has been removed in favor of actual `UserSlice` writes.
- **Why**: Destroyed shm segments outlived their last mapping when removed outside `shmdt`, and removing the `recvmsg` pre-check in `kernel/unix` left `Mm::can_write` with no callers.
- **Tradeoffs**: Any future caller needing a VMA-only writability pre-check would have to re-add it, though copy-before-consume is stronger against partial faults and races.
- **Watch out**: Callers must rely on actual `UserSlice` writes rather than VMA pre-checks when partial faults matter.
- **Source**: 5d7b936, 15347732

### D35: process_mrelease releases a dying process's memory; process_madvise stays `ENOSYS`
- **What**: `Mm::release` re-points borrowed pages at the VMA's own page without copying, leaves lent pages, discards the other written private anonymous pages and the written pages of private file mappings, and leaves VMAs, executable mappings and reservations alone; process_mrelease calls it after 6.1's flag and pidfd checks once every process sharing the address space is exiting without a core dump, while process_madvise stays `ENOSYS`.
- **Why**: While process_mrelease was `ENOSYS`, earlyoom warned after each kill and Starnix's test failed, whereas process_madvise carries only hints Appine ignores or refuses, has no Linux userspace caller, and is `ENOSYS` in Starnix and `EPERM` under docker's default seccomp.
- **Tradeoffs**: "Exiting" is `ThreadGroup::exiting`, so a release right after a fatal signal other than SIGKILL is `EINVAL` until a thread takes it; executable private memory is never released, and the OOM killer does not release its own victims' memory.
- **Watch out**: A dying thread still running guest code reads zeros where Linux would fault, and the release must not reuse `discard`, which makes borrowers copy lent pages and settles borrowed ones by copying.
- **Source**: b141001, 39ea702, 983fc3d

### D40: FUSE mappings share per-inode sparse backing and honor range invalidation

- **What**: Retain the source per mapping, share filled pages through an inode memfd, and revoke translations and stale fills across mappings when a range changes.
- **Why**: Per-mmap storage could diverge from another mapping or a descriptor write.
- **Tradeoffs**: Fills and dirty baselines require range tracking.
- **Watch out**: An outstanding read must not restore an invalidated page.
- **Source**: c008749, 3957a03, b421eac0, 576501d3

### D45: GPU mappings retain coherent renderer storage in shared VMAs
- **What**: Guest GPU mappings keep the coherent renderer backing in a shared VMA across fork, VMA splits and TLB invalidation, so closing a GEM handle leaves live guest mappings intact.
- **Why**: Packaged Vulkan drivers expect mapped GPU buffers to outlive closing their GEM handles without copying guest buffers.
- **Tradeoffs**: GPU mappings require alignment and cannot grow or be executable.
- **Watch out**: Real 16 KiB-page hardware still needs GPU mapping coverage.
- **Source**: 87c7d9db

### D46: `Mm::memops` tracks read-only executable file mappings and prunes centrally in `Mm::sync`
- **What**: `Mm` stores discovered `DbtMemop` entries across fork, scans only read-only, non-FUSE, versioned `Backing::PrivateFile` executable mappings in `Mm::map` (later narrowed to the verified bundled musl snapshot by kernel/exec D2 and kernel/mmu D5), marks `memops_dirty`, and prunes `Mm::memops` centrally in `Mm::sync` whenever executable ranges are invalidated or lose read-only exec status (including `write_force`).
- **Why**: Runtime-mapped shared libraries need memop registration in `Mm::map`, while direct unpruned pushes left stale memop entries after `munmap`, `mprotect`, `mremap`, `write_force` breakpoints or `ic ivau`.
- **Tradeoffs**: Newly mapped memops activate at `Mm::sync` (syscall exit) rather than mid-syscall.
- **Watch out**: Internal callers of `Mm::map` that skip `Mm::sync` do not publish new memops until the next `sync` or `code_space` creation.
- **Source**: f2690b02, 9b3b6860

### D47: Verified bundled musl snapshots share host mappings through `FileMaps`
- **What**: `BundledLibc` records its memfd snapshot's `(st_dev, st_ino)` at creation and `Mm::map` keys `FileMaps` by `[snap_dev, snap_ino, offset, len, 0, size]` so dynamic Alpine processes share host mappings of the verified snapshot.
- **Why**: Bypassing `FileMaps` forced every dynamic Alpine process to create and unmap its own host `mmap`, fault host page tables separately and lose single-counted shared host-address accounting.
- **Tradeoffs**: Adds one `fstat` at one-time snapshot creation and holds one `FileMaps` slot per distinct mapped slice until memory trim.
- **Watch out**: Relies on the global `OnceLock<Arc<BundledLibc>>` keeping the snapshot memfd open so its `(st_dev, st_ino)` cannot be reused.
- **Source**: c8f01dbf

### D48: `Mm::find_free` prefers 2 MiB alignment for large mappings with page-aligned fallback

- **What**: When `len >= LEAF_SPAN` (2 MiB), `Mm::find_free` searches top-down for a 2 MiB-aligned gap first and falls back to `PAGE_SIZE` alignment if none fits.
- **Why**: Page-only top-down placement shifted large buffers like ggml's `work_data` off 2 MiB boundaries, forcing L3 leaf tables and preventing `stlb` superpage hits.
- **Tradeoffs**: Can leave up to 2 MiB of virtual address space unused above aligned large mappings without consuming extra physical memory.
- **Watch out**: Preserve the explicit fallback to `PAGE_SIZE` alignment so fragmented or tightly constrained address spaces still succeed.
- **Source**: 42d49fc0

### D49: Non-destructive `madvise` hints forward unlocked with retained host regions and prefill file PTEs

- **What**: `madvise` collects coalesced host-page-aligned `AdviseRange` slices holding `Arc<HostRegion>` (skipping reserved, foreign, GPU, FUSE `Fill` and untouched anonymous VMAs, clipping to `file_len`, and restricting `MADV_NORMAL`/`RANDOM`/`SEQUENTIAL` to file-backed host mappings), prefills read-only non-FUSE file soft PTEs up to 4 GiB per call, and forwards hints via `appine_hal::advise` inside `Cx::unlocked`. MADV_POPULATE_READ uses an explicit population path, and mlock and WILLNEED can fetch cold FUSE pages while the kernel lock is released.
- **Why**: Ignoring `MADV_WILLNEED`, `MADV_SEQUENTIAL`, `MADV_RANDOM` and `MADV_POPULATE_READ` blocked host page-cache readahead on large model files and left soft page tables empty until per-fault BKL entries.
- **Tradeoffs**: Anonymous VMAs do not receive host `MADV_SEQUENTIAL`/`MADV_RANDOM` flags so recycled regions are not split.
- **Watch out**: If another thread unmaps the last VMA while `AdviseRange` is held across `Cx::unlocked`, `HostRegion::drop` runs outside the BKL, so any future `HostRegion` kind requiring BKL-only drop must be excluded or handled.
- **Source**: d0945a96, 20631a25, b3b7ee74

### D50: VMA mount pins, `mremap`/`munmap` shm and vDSO updates, and short-reply FUSE `Fill` tracking

- **What**: File-backed VMAs hold a `MountPin(MountId)` releasing their mount on drop (`Mm::set_mnt`), `move_range`/`munmap` keep moved SysV `v.shm` bases and `Mm::vdso` synchronized, `MREMAP_DONTUNMAP` preserves `VmaFlags`/`mnt` on remnants and calls `reap_shm`, and `Fill::store` records only complete pages on short FUSE replies unless at recorded or confirmed EOF.
- **Why**: `mremap`/`munmap` left SysV attach bases and `Mm::vdso` stale, `MREMAP_DONTUNMAP` dropped non-lock VMA flags and skipped `reap_shm`, VMAs did not keep detached mounts busy for `umount2`, and `Fill::store` marked unread tail pages filled on short replies.
- **Tradeoffs**: Every file-backed VMA clone or drop runs `MountPin`'s drop hook (`vfs::released`), and moving only part of the vDSO clears `Mm::vdso` to 0 rather than tracking split vDSO pieces.
- **Watch out**: New paths creating file-backed VMAs must call `Mm::set_mnt` so detached mounts stay pinned while mapped.
- **Source**: 0e02d7cd

### D51: Past-EOF permission precedence, `populate_mlock` isolation and `cow` private-file `MADV_DONTNEED`

- **What**: `Mm::fault_past_eof` checks VMA permissions before delivering `SIGBUS`, `populate_mlock` stops at `PROT_NONE` (`ENOMEM`), skips read-only private anonymous VMAs and settles CoW plus atomically write-faults (`AtomicU8::fetch_or(0)`) writable ones, `resident_in` skips never-written anonymous VMAs and `cow.borrowed` pages on the VMA's host scan, and `Mm::discard` unshares written `cow` private file VMAs before reverting them.
- **Why**: Past-EOF faults masked permission failures behind `SIGBUS`, `mlock`/`mlock2` ignored `PROT_NONE` and inflated RSS on read-only anonymous and post-fork CoW pages, and `MADV_DONTNEED` skipped written `cow` private file VMAs.
- **Tradeoffs**: Atomic `fetch_or(0)` per host page avoids racing concurrent guest stores while allocating real private host pages, and unsharing a `cow` private file VMA on `MADV_DONTNEED` copies it before reverting the discarded slice so fork peers remain isolated.
- **Watch out**: `mlockall` still ignores `PROT_NONE` (`fail_on_none = false`) as on Linux, `MADV_DONTNEED` on a large `cow` private file VMA unshares the whole VMA first, and the `resident_in` change belongs to `kernel/oom` despite being tagged `kernel/mm` in `2609ab43`.
- **Source**: 2609ab43

### D52: `Mm::discard` branches explicitly on mutually exclusive VMA `Backing` kinds

- **What**: `Mm::discard` replaces its 8-element VMA tuple destructuring with mutually exclusive `if` / `else if` branches on `Backing::Anonymous` and `Backing::PrivateFile`, re-borrowing the VMA only after an optional `self.unshare(s)?`.
- **Why**: The 8-tuple destructuring re-indexed `self.vmas[&s]` unnecessarily across mutually exclusive backing kinds and obscured positional fields.
- **Tradeoffs**: Slightly more explicit control flow around `unshare` borrow lifetimes in exchange for removing tuple positional indexing.
- **Watch out**: Any future `Backing` variant requiring `MADV_DONTNEED` handling must be added as an explicit branch in `Mm::discard`.
- **Source**: 3a90a0ef

### D53: Shared anonymous regrowth retains the original mapping extent

- **What**: Reuse the region on growth or duplication, bound access by its backing length and preserve or update the SysV attachment base according to the move.
- **Why**: Shrinking and growing a mapping must preserve the original backing relationship.
- **Tradeoffs**: The VMA can extend past the accessible region.
- **Watch out**: RSS and pagemap walks must obey the same extent.
- **Source**: 54ce6b50, 74584914

### D37: Store mappings retain the backing object they expose
- **What**: Unshare the backed file before writable shared admission, pin private file backing as required, and use the pinned read descriptor to refill the correct object after extent changes.
- **Why**: Shared mappings made through read-only descriptions kept showing the shared object after the file got its own, silently (LMDB's `MDB_RDONLY` readers, read-only SQLite with mmap).
- **Tradeoffs**: An existing mapping pin can force eager copying when a clone would otherwise share storage.
- **Watch out**: Synchronize all affected address spaces before shrinking backing and preserve the guest mapping when a provider writes.
- **Source**: df6ee70, 523ae8a9, 568b6a48, f495f2cc, 68130f74

### D54: File mappings retain the descriptor, mount and permissions used at admission

- **What**: Share pinned read descriptors across a fork family, retain executing image references, and derive prefill rights and refreshed tail extents from the actual VMA.
- **Why**: Rename, growth or backing replacement can change what later path lookup sees.
- **Tradeoffs**: Pins outlive the original file descriptor.
- **Watch out**: Hole removal must invalidate aliases.
- **Source**: 68b70897, 93a9b204, 71790fde, 11816722

### D55: Kernel copies resolve recorded faults and validate backed extents

- **What**: Retry the affected copy on the missing FUSE page and verify the complete forced-write range against contents_len before CoW or modifying storage.
- **Why**: A remote or syscall destination can cross cold pages or EOF.
- **Tradeoffs**: Servicing a cold range can require multiple retries.
- **Watch out**: Never mutate the valid prefix of an invalid forced-write range.
- **Source**: 4309ef58, 30e5002e, f985c1f8, 865d24c1, 6084b8af

### D56: Page walks distinguish reservations and file extents

- **What**: Read reserved pages as zero and re-evaluate file extents after growth.
- **Why**: Reservations need not have a physical page yet.
- **Tradeoffs**: The walk must proceed per page.
- **Watch out**: A cached previous EOF cannot hide newly accessible bytes.
- **Source**: 25b0f2a0

### D57: VMA walks and shared-map indexing follow committed ranges

- **What**: Publish or roll back shared-map membership around page-table changes and use one next-VMA walk for range operations.
- **Why**: Walks that skip a hole or retain stale indices give incorrect partial results.
- **Tradeoffs**: Moves and removals update a secondary index.
- **Watch out**: Preserve Linux's per-operation treatment of holes and earlier progress.
- **Source**: c3514220, 3de64361

### D58: Future locked mappings respect the caller's memlock allowance

- **What**: Check the limit before MAP_LOCKED, future mlock and growth, returning the Linux error appropriate to the operation.
- **Why**: A future-lock policy can otherwise exceed RLIMIT_MEMLOCK.
- **Tradeoffs**: Admission is repeated for later growth.
- **Watch out**: Distinguish a zero unprivileged allowance from exhausted capacity.
- **Source**: 99b74cef

### D59: Unmap prepares fallible demotion before changing CoW or host state

- **What**: Prepare all mapped spans before two-phase teardown, use one unmap for single-step paths, and route truncation clipping and FUSE invalidation through `unmap_or_clear_superpages` to fall back to `Mmu::unmap_noalloc` on superpage-demotion OOM.
- **Why**: A boundary-superpage allocation failure could otherwise leave half a teardown committed or leave live PTEs past truncated EOF (risking host `SIGBUS`) or for invalidated FUSE pages.
- **Tradeoffs**: Preparation adds a boundary walk for two-phase callers, and demotion OOM on file or FUSE unmaps incurs a demand fault and expires `PageWatch`es across the cleared 2 MiB superpage.
- **Watch out**: An ordinary failed `munmap` must preserve valid watches, and any future VMA kind placed inside a file or FUSE 2 MiB superpage must remain demand-refaultable from its backing without losing state.
- **Source**: d7a28ce2, f2d15cfd, 33e95609, e9761fd4

### D60: `MREMAP_DONTUNMAP` remnants preserve `anon_pgoff` and unlink `anon_vma` only on whole-VMA moves

- **What**: After fixed-target unmapping, `Mm::mremap_dontunmap` computes the logical `[vma_start, vma_end)` via `vma_start`/`vma_end`, retains `anon_pgoff` on anonymous remnants, resets `anon` and `has_anon_vma` once only when the whole logical VMA moved, clears `locked` across `[vma_start, vma_end)` in one scan after `move_range`, and guards `Mm::dump_page` against `v.reserved()`.
- **Why**: Unconditionally resetting anonymous remnants and unlocking only the moved slice split partial `MREMAP_DONTUNMAP` VMAs, dropped non-linear page offsets, and duplicated per-backing reset logic.
- **Tradeoffs**: Preserving `anon_pgoff` on whole-VMA moves matches Linux 6.1 rather than newer kernels that reset unfaulted `vm_pgoff`, and the single unlock scan relies on `[dst, dst + len)` staying disjoint from `[vma_start, vma_end)`.
- **Watch out**: Consumers of `has_anon_vma` must still check `written` and `reserved()` because partial remnants can retain `has_anon_vma` without host backing, and `vma_start`/`vma_end` must stay computed after fixed-target `munmap`.
- **Source**: d747205b, 8a839900

### D61: `brk`, `map_anonymous`, and in-place `mremap` growth are bounded by `1 << VA_BITS` inside a 47-bit default map window

- **What**: Keep `STACK_TOP = 1 << 47` as the default map window (`mmap_top`), reject `brk_limited` and `map_anonymous` ranges ending past `1 << VA_BITS`, and share `Mm::range_fits(start, len, limit)` across `hint_fits` (`mmap_top`) and `mremap_limited` in-place growth (`1 << VA_BITS`, after checking `offset == tail.len`) for overflow, free-space, and `STACK_GUARD_GAP` clearance.
- **Why**: `brk_limited` could insert lazy VMAs past the 48-bit `TASK_SIZE` while `mremap_limited` rejected valid in-place growth of `MAP_FIXED` mappings in `[mmap_top, 1 << 48)` and duplicated `hint_fits`'s clearance checks.
- **Tradeoffs**: Non-fixed `mmap` hints above `mmap_top` still fall back below it while mappings right below `mmap_top` can now grow in place above it when unblocked (matching Linux 6.1's `vma_expandable`), and `Mm` carries one private clearance helper.
- **Watch out**: Every future VMA insertion path must enforce `end <= 1 << VA_BITS` so no untranslatable VMA enters the 48-bit soft-MMU.
- **Source**: 7e2143cf, ee941aed

### D62: Batched `tmpfs` faults, in-place `FilePages` `mprotect`, and pagemap-backed `probe_writes`/`MADV_DONTNEED` fast paths

- **What**: Batch `tmpfs` faults via `Mm::fault_range` and `FilePages::admit`, restrict tracked-file `mprotect` PTEs in place without setting `dirty` on upgrades, start eager writable private anonymous VMAs writable via `Vma::probe_writes`/`finish_write_probe`/`was_written` and keep soft PTEs intact (expiring `PageWatch`es) in `discard_with_pagemap` on pagemap-readable hosts, and skip `reconcile_mapping` when `MappingObject` indicates it is unnecessary.
- **Why**: Single-page `tmpfs` faults, unconditional lazy `anon_vma` first-write traps, whole-VMA `FilePages` unmaps on `mprotect`, and unconditional private anonymous `MADV_DONTNEED` soft-PTE zapping and shootdowns regressed memory-heavy and multi-threaded workloads.
- **Tradeoffs**: Splits behavior across pagemap-readable (Linux) and pagemap-denied (Android) hosts and requires resolving `probe_writes` before every VMA split, merge, fork, remap, `mprotect`, or discard.
- **Watch out**: Any new VMA mutation that reads `written` directly instead of `was_written()` or skips `finish_write_probe` can misclassify an eagerly written anonymous VMA.
- **Source**: b38371ff

## Superseded

- ~~Adjacent VMAs joined only for display in maps and in fork's commit estimate (D7 in part, rounds 17, 18)~~ → replaced by **D22** in e03c0cc (2026-11-07)
- ~~The kernel grows the stack only at syscall entry and for signal frames (D9 in part, round 22)~~ → replaced by **D22** in e03c0cc (2026-11-07)
- ~~process_mrelease stays `ENOSYS` until earlyoom and oom_score make it worth doing (b141001 in part)~~ → replaced by **D35** in 39ea702 (2026-11-08)
- ~~FUSE mappings copy the whole range into a memfd at mmap (c008749)~~ → replaced by **D40** in 3957a03 (2026-11-09)
- ~~Guest memory, with the page table, software TLB and OOM killer, is one kernel/mm module (D18, cd53f35)~~ → replaced by **D18** in 44a21b7 (2026-11-11)
- ~~Direct unpruned `Mm::map` memop registration in f2690b02~~ → replaced by **D46** in 9b3b6860 (2026-11-21).
- ~~Bypassing `FileMaps` for `bundled_libc` mappings in 6b59a1b4~~ → replaced by **D47** in c8f01dbf (2026-11-21, cross-module predecessor tagged under kernel/mmu and kernel/exec).
- ~~D18 ownership of fork/copy-on-write and host backing in kernel/mm (cd53f35, 44a21b7)~~ → replaced by **D18**, kernel/cow **D1** and kernel/hostmem **D1** in 610d623f (2026-11-22).
- ~~`Mm::can_write` VMA writability pre-check before kernel writes (D34 in part, 5d7b936)~~ → replaced by **D34** in 15347732 (2026-11-23).
- ~~Excluding Files app writes from the shared mapping coherence policy~~ → replaced by **D37** in 523ae8a9 (2026-11-24)
- ~~Deferring restoration of shrunken shared anonymous mappings~~ → replaced by **D53** in 74584914 (2026-11-24)
- ~~Moving a checked set of existing mappings as the normal way to admit a shared store mapping~~ → replaced by **D37** in f495f2cc (2026-11-24)
- ~~Giving each FUSE mmap a separate memfd and refusing writable sharing~~ → replaced by **D40** in b421eac0 (2026-11-24)
- ~~Allowing a private store mapping to rely on stale backing after a split~~ → replaced by **D37** in 68130f74 (2026-11-24)
- ~~Implementing MADV_POPULATE_READ as an advisory WILLNEED request~~ → replaced by **D49** in 20631a25 (2026-11-25)
- ~~Approximating anonymous VMA joining without anonymous page offsets~~ → replaced by **D22** in 9d0ad1b6 (2026-11-26)
- ~~Expiring page watches alone when truncation or FUSE invalidation unmap fails (D59 in part, f2d15cfd)~~ → replaced by **D59** in e9761fd4 (2026-11-26)
- ~~Always creating a fresh anonymous remnant on `MREMAP_DONTUNMAP` (D22 in part, 9d0ad1b6)~~ → replaced by **D60** in d747205b (2026-11-26)
- ~~Unconditional write-trapping for lazy `anon_vma`, single-page `tmpfs` faults, whole-VMA `FilePages` `mprotect` unmaps, and unconditional `MADV_DONTNEED` soft-PTE zapping (D22 and D54 in part, plus cross-module `kernel/mmu` D8 and `kernel/oom` D2; 9d0ad1b6, 11816722, 71790fde, e6e88b55)~~ → replaced by **D62** in b38371ff (2026-11-27)
