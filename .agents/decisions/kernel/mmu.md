# Soft-MMU (kernel/mmu) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: kernel/mmu"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12)
>
> Soft-MMU decisions made before kernel/mmu was split from kernel/mm keep their `kernel/mm` tags and live in `.agents/decisions/kernel/mm.md`: D1, D13, D16, D39 and the page-table half of D4.

## Active

### D1: kernel/mmu is split out of kernel/mm
- **What**: kernel/mmu owns `mmu.c` and the `Mmu` wrapper, the software TLB in `cpu.c` and `internal.h`, and in `mm.rs` the Consistency and code section and the `Mm` methods that fill entries (`map_pages`, `fault_in`) and keep threads consistent (users, generations, code spaces, `sync`, `quiesce`, `shootdown`), and the engine map's references to the page table and code groups point here.
- **Why**: The page table, TLB and consistency machinery are a contract with the engine and the run loop whose changes rarely need the VMA syscalls' detail, and the mm map (69.6 KB) could not be read in one Read.
- **Tradeoffs**: Every VMA change records what kernel/mmu acts on, so changes to unmapping or permissions still read both maps.
- **Watch out**: A VMA change that skips recording for `sync` because the rule now lives in this map; the map keeps open the bug of entries dropped when a host allocation fails.
- **Source**: 44a21b7

### D2: Unwatched scalar hits use a fixed-width same-page copy

- **What**: Tier 0 computes the candidate host address alongside the shared tag check and uses a fixed-width copy for an unwatched scalar hit on one page.
- **Why**: Generic copies and slow-path stack setup cost time on ordinary hits.
- **Tradeoffs**: The outlined miss path can repeat the probe, and the probe expression also affects instruction-fetch code generation.
- **Watch out**: Use the candidate only after a hit and handle faults and watchpoints before register or memory writeback.
- **Source**: 16914003

### D3: Tier 1 retains the top byte in data TLB entries

- **What**: Cache the full guest page base in the tag and addend, walk the low 48 bits, and require both read and write permission for a cached write.
- **Why**: Tagged pointers otherwise missed repeatedly, and grouped or atomic operations can use a write entry to read.
- **Tradeoffs**: Aliases with different top bytes compete for a slot, and raw write-only mappings take the Tier 0 store path.
- **Watch out**: Check endpoints and watchpoints before access and use untagged addresses for exclusive monitors and instruction-cache invalidation.
- **Source**: 8bc0e63b

### D4: `CodeGroup` tracks admitted shared memops separately from per-process `Mm::memops`

- **What**: `CodeGroup::memops` tracks admitted shared memops restricted to `group.ranges`, merged during `Mm::sync` and `Mm::code_space`, and marks `memops_dirty` when a sole member leaves a closed group so its `DbtSpace` switches back to `Mm::memops`. Refresh shared translation sources on final membership drop so a departing address space cannot clear another member's registrations.
- **Why**: Pushing one member's `Mm::memops` onto a shared `DbtSpace` overwrote peers' registrations and could expose private mappings across members.
- **Tradeoffs**: Memops in private executable mappings of a `CodeGroup` member are not accelerated until that member leaves the group.
- **Watch out**: Like `CodeGroup::ranges`, `CodeGroup::memops` grows monotonically while shared; members remapping conflicting code at those addresses still leave the group via `check_code_group`.
- **Source**: 9b3b6860, 99b74cef

### D5: Memop entry points register only for executable mappings of the verified bundled libc snapshot

- **What**: `mm.rs` maps the shared in-process SHA-256-verified bundled libc snapshot and registers Tier 1 memop entry points only for executable mappings of that snapshot.
- **Why**: The executable mapping must execute the exact bytes that passed SHA-256 verification rather than a mutable original file.
- **Tradeoffs**: Maintains one shared in-process snapshot of the bundled libc (shared via `FileMaps` per kernel/mm D47).
- **Watch out**: Preserve translation invalidation whenever executable mappings change.
- **Source**: 6b59a1b4

### D6: Soft page tables support 2 MiB L2 superpages with atomic demotion and a 1024-entry `stlb`

- **What**: L2 entries encode 4 KiB-host-aligned 2 MiB superpages via `PTE_SUPER` (bit 3, with `>= 16`-byte aligned tables), `demote_l2` atomically publishes a populated 512-entry L3 table on partial mutation, `store_range` promotes empty/superpage/all-zero-L3 slots while retiring up to 256 empty L3 tables per `appine_mmu` until free, and a 1024-entry `stlb` plus `stlb_used[16]` sits at the tail of `struct appine_cpu`, flushed by `appine_cpu_tlb_flush` and bypassed when `nwatch != 0`. Count real table allocations, including demotion and root tables, and expose their byte total for memory accounting.
- **Why**: 4 KiB-only L3 leaves forced 512 walks per 2 MiB and capped fast-loop spans at 4 KiB across large contiguous model weights and scratch buffers.
- **Tradeoffs**: Adds 16 KiB of virtual `struct appine_cpu` tail per thread (zero RSS unless touched), retains up to 256 retired L3 tables (<= 1 MiB) per address space until teardown, and allocates one L3 table on partial-mutation demotion.
- **Watch out**: Any missed `stlb` flush on demotion/unmap/protect, unaligned table pointer colliding with `PTE_SUPER` or watchpoint entry entering `stlb` can leave stale translations or miss watchpoints.
- **Source**: 42d49fc0, e6e88b55

### D7: Read-only non-FUSE file faults fill 16 MiB soft page-table spans

- **What**: `Mm::fault_in` uses `FILE_FAULT_SPAN` (16 MiB, clipped to VMA and EOF) for read-only non-FUSE `PrivateFile` and `Shared` mappings while keeping `LEAF_SPAN` (2 MiB) for anonymous, writable and FUSE `Fill` mappings.
- **Why**: Filling only one 2 MiB `LEAF_SPAN` per fault serialized multi-threaded scans of 1–4 GiB read-only model mappings on the BKL when `MAP_POPULATE` or `MADV_WILLNEED` was absent, whereas aligned read-only spans now promote to L2 superpages without allocating L3 tables or touching host memory.
- **Tradeoffs**: Unaligned fixed read-only file mappings can allocate up to 8 L3 leaf tables (32 KiB) on first fault, and writable or FUSE file mappings keep 2 MiB demand granularity.
- **Watch out**: Relies on file mappings staying outside `Mm::touched_windows`; any future RSS or fork accounting that scans soft PTEs on read-only file mappings must account for 16 MiB fault and `MADV_WILLNEED` prefill spans.
- **Source**: d0945a96

### D8: Eager page-table fills preserve faults that still need admission

- **What**: Roll back failed fills for a new mapping, retain successful entries in existing VMAs and prefill unwritten anonymous pages read-only until the first admitted write.
- **Why**: Eagerly granting write access loses first-write state.
- **Tradeoffs**: Allocation failure can leave pages to fault in later.
- **Watch out**: Never upgrade a CoW page through the anonymous fast path.
- **Source**: 0e02d7cd, 71790fde, 9d0ad1b6

### D9: Shootdowns and membarriers cover every participating address space

- **What**: Fence quiescence, target global commands by registration, and complete revocation across affected mappings before host truncation or invalidation.
- **Why**: Host-side replay lacks a guest caller that can be excluded.
- **Tradeoffs**: Global commands must visit each live address space.
- **Watch out**: Capture shared-write baselines before granting writable translations.
- **Source**: 47231a42, 523ae8a9, f495f2cc, b421eac0

### D10: Private file-page detachment survives VMA splits

- **What**: Record the detached host page on its first write and share that record across split views.
- **Why**: Fork and remap need to recognize private data after a split.
- **Tradeoffs**: Detached-page bookkeeping follows the backing.
- **Watch out**: Do not infer private status from changed byte values.
- **Source**: 11816722

### D11: Page watches survive permission changes and expire on backing replacement

- **What**: Share weak watches per readable page, invalidate changed ranges on mapping moves, replacement or discard, and pre-demote only partially covered boundary superpages before unmap.
- **Why**: A live pipe reference needs backing identity independent of unrelated TLB generations.
- **Tradeoffs**: Demotion can allocate before an unmap commits.
- **Watch out**: A failed preparation preserves translations and watches.
- **Source**: 6eb822d7, bb31dbda, d7a28ce2, 33e95609

### D12: Allocation-free `unmap_noalloc` widens only partially covered L2 superpages

- **What**: `appine_mmu_unmap_noalloc` (also reused by `appine_mmu_unmap` after `appine_mmu_prepare_unmap`) and `Mmu::unmap_noalloc` clear partially covered L2 `PTE_SUPER` slots whole while clearing exact L3 entries elsewhere, returning the widened cleared range and expiring `PageWatch`es across it without allocating page tables.
- **Why**: Non-rollbackable unmap callers (`clip_file_mappings` and `invalidate_fill`) need an infallible fallback when superpage demotion hits `ENOMEM` without disturbing unrelated mappings sharing an L3 table in the same 2 MiB slot.
- **Tradeoffs**: Adds one C/Rust MMU entry point and slightly widens `PageWatch` expiration and demand re-faulting across a cleared 2 MiB superpage on demotion OOM.
- **Watch out**: Never use `unmap_noalloc` to widen over private anonymous VMAs that rely on `touched_windows` or PTE presence instead of demand re-faulting from file or FUSE backing.
- **Source**: e9761fd4

### D13: Guest entry pairs a single `SeqCst` fence with relaxed generation loads and Tier 1 `InGuest` code-generation polling

- **What**: Full guest entry writes `in_guest` relaxed, issues one `fence(SeqCst)`, loads `(tlb, code, space)` relaxed with `fence(Acquire)` on TLB change, and non-sleeping fast syscalls staying `InGuest` exit guest mode when `dbt.is_some()` and `generations.code` changed to preserve `space -> tlb -> code` ordering.
- **Why**: Issuing three separate `SeqCst` loads after a `SeqCst` store to `in_guest` added hot-path barrier overhead, while staying in guest mode across fast syscalls still needed sound synchronization with `Mm::sync`, `Mm::quiesce`, `Mm::shootdown`, and un-kicked code-only invalidations.
- **Tradeoffs**: Adds one relaxed `generations.code` load per non-sleeping fast syscall in Tier 1.
- **Watch out**: Relies on every `space_generation` and `tlb` generation bump kicking in-guest users and on Tier 0/Tier 1 entry checking `cpu->pending` before executing guest instructions.
- **Source**: da5ba1ef

### D14: `Mm::sync_except` shoots down only in-guest threads and `syscall::dispatch` gates sync on `Mm::needs_sync()`

- **What**: Share `shootdown_users()` (a `SeqCst` generation bump followed by kicking and waiting on only `in_guest` threads with brief `spin_loop` before `yield_now`) between `Mm::sync_except` and `Mm::shootdown`, keep `Mm::quiesce_except` kicking all users for fork and membarrier, and gate post-syscall `Mm::sync` in `syscall::dispatch` on `Mm::needs_sync()`.
- **Why**: Calling `Mm::quiesce_except` from `Mm::sync_except` spuriously sticky-kicked parked futex/epoll threads on `munmap`/`mprotect`, and `syscall::dispatch` unconditionally cloned `Arc<TaskSync>` and mutably borrowed `Mm` even when nothing was dirty.
- **Tradeoffs**: `sync_except` always bumps the generation when dirty or when the graveyard is non-empty so parked threads flush lazily on their next guest entry instead of being kicked.
- **Watch out**: Any future state cleared by `Mm::sync` must be reflected in `Mm::needs_sync()`, and fork and membarrier must continue calling `Mm::quiesce` rather than `shootdown_users`.
- **Source**: ff0a4e4c
