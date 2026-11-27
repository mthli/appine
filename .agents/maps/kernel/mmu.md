# kernel/mmu Map

> Static understanding snapshot, not a decision history.
> See `.agents/decisions/kernel/mmu.md` for the paired decision history (decisions from before the split are D1, D13, D16, D39 and the page-table half of D4 in `.agents/decisions/kernel/mm.md`).
>
> Verified: 2026-11-27 (full mapping: 4 research concerns, 14 adversarial claims checked — 13 confirmed, 1 partial patched, 0 refuted, 0 confirmed bugs; 1 external Linux 6.1 arm64 residual under To verify)

## Responsibilities

`kernel/mmu` is the soft-MMU under `kernel/mm`'s address spaces: translating guest virtual addresses to host pointers and keeping every thread of an address space consistent with page-table and executable-code changes. It does not emulate ARM hardware translation (no TTBR, architectural descriptors, TLBI/ASID, exception levels, or guest physical address layer):

- the software radix page table (`appine_mmu` / `Mmu`), 2 MiB L2 superpages, atomic demotion, and page watches;
- populating entries eagerly (`map_pages`, `prefill_pages`, `prefill_file_ptes`) or on demand (`fault_in`, `try_fault_in`, `fault_range`, `ensure_anon_present`);
- the per-thread software TLB (`tlb`, `stlb`, `itlb`) and Tier 0 lookup/refill;
- multi-thread consistency (`sync`, `sync_except`, `quiesce`, `shootdown`, `shootdown_users`, and the generation counters consumed by the run loop and `membarrier`);
- the code-change log (`CodeLog`), memop publication, and code groups (`CodeGroup` / `CodeGroups`) sharing Tier 1 translations across processes.

Root:

- `core/engine/src/c/mmu.c`, the MMU section of `core/engine/include/appine/engine.h`, the `Page table` section (`Mmu`, `PageWatch`, `MapError`) of `core/engine/src/lib.rs`, and `core/engine/tests/mmu.rs` — radix page table, watches, and unit tests.
- `core/engine/src/c/cpu.c`, `core/engine/src/c/internal.h`, and the Tier 0 scalar fast path in `core/engine/src/c/interp.c` — software TLB lifecycle, flush, lookup, and Tier 0 refill.
- In `core/kernel/src/mm.rs`: the `Consistency and code` section and the `Mm` methods that populate entries (`map_pages`, `prefill_pages`, `fault_in`, `try_fault_in`, `fault_range`, `prepare_anon_write`, `finish_write_probe`, `fill_anon_non_cow`, `ensure_anon_present`, `prefill_file_ptes`, `unmap_or_clear_superpages`) and synchronize threads (`add_user`, `remove_user`, `generations`, `code_space`, `invalidate_code`, `prune_memops`, `sync_memops`, `needs_sync`, `sync`, `sync_except`, `check_code_group`, `quiesce`, `quiesce_except`, `shootdown_users`, `shootdown`).

Outside the module: VMAs, memory syscalls, and fault/EOF policy (`kernel/mm`); `fork` and copy-on-write (`kernel/cow`); host backing and deferred unmapping (`kernel/hostmem`); run loop and syscall dispatch (`kernel/process`); Tier 1 translation, inline TLB/STLB fast paths, and code cache (`engine/dbt`).

## Key types

### Page table and watches

- `struct appine_mmu` / `Mmu` — 4-level radix tree (`MMU_LEVELS = 4`, `MMU_LEVEL_BITS = 9`, 512 eight-byte entries = 4 KiB per table) covering 48-bit guest addresses (`APPINE_VA_BITS = 48`). Intermediate pointers are >= 16-byte aligned (`calloc` with `posix_memalign(16)` fallback), keeping bits 3..0 zero. Each L2 slot spans `LEAF_SPAN = MMU_SUPER_SIZE = 2 MiB` and holds 0 (absent), an L3 table pointer, or a superpage leaf (`4 KiB-aligned host_2m_base | prot | PTE_SUPER`, bit 3; `PTE_PROT_MASK = 0x7`). An L3 leaf holds `4 KiB-aligned host_page | prot` (0 = absent; nonzero host + prot 0 = present, inaccessible). `tables` monotonically counts allocated 4 KiB tables (root, intermediate, active L3, and up to `MAX_RETIRED_L3 = 256` retired empty L3 tables kept alive until `appine_mmu_free`). `Mmu` is `!Send` and `!Sync`; mutators (`map`, `prepare_unmap`, `unmap`, `unmap_noalloc`, `protect`, `move_entries`) take `&mut`, while `fill_shared`, `map_shared`, `protect_shared`, `next_present`, `translate`, `table_bytes`, `table_pages`, `watch_readable_page`, and `invalidate_page_watches` take `&self` for cross-`Mm` CoW, demand faults, and accounting under the BKL.
- `PageWatch` / `WatchedPages` — weak-token (`Weak<()>` / `BTreeMap<u64, Arc<()>>` in `RefCell`) page-identity tracker on `Mmu` used by `kernel/pipe` for `vmsplice`. Watches on one page share a token; new registrations sweep dead weak tokens when `pages.len() >= next_sweep` (doubling with a floor of 16).
- `MapError` — `InvalidArgument` (unaligned `gva`/`host`/`len`, outside 48-bit VA, invalid prot bits, or overlapping `move_entries` ranges) or `OutOfMemory`.

### Software TLB

- `struct appine_tlb_entry` — 32-byte `{ tag_read, addend_read, tag_write, addend_write }` storing inverted page numbers (`~vpn` or `~svpn`) and `addend = host_base - guest_base`. `TLB_INVALID = 0`, so zeroed anonymous `mmap` pages start empty with zero RSS.
- `struct appine_itlb_entry` — 16-byte `{ tag, addend }` instruction-fetch TLB slot, Tier 0 only.
- `struct appine_cpu` TLB fields — `tlb[4096]` (128 KiB, direct-mapped 4 KiB) with `tlb_used[64]`; `itlb[512]` (8 KiB); `stlb[1024]` (32 KiB, `_Alignas(4096)` at struct tail, direct-mapped 2 MiB covering 2 GiB) with `stlb_used[16]`. `nwatch` sits before `tlb` for 1-instruction Tier 1 checks.

### Consistency and code

- `TaskSync` — cache-line aligned (`#[repr(align(64))]`) per-thread handle shared without the BKL: `in_guest: AtomicBool`, sticky `kicker: Kicker`, and `entry: KernelEntry`.
- `Generations` — `Arc`-cloned `tlb`, `code`, and `space` (`AtomicU64`) plus `log: Arc<Mutex<CodeLog>>`, read by the run loop without the BKL.
- `CodeLog` — `VecDeque<(u64, u64, u64)>` of `(generation, start, len)` invalidations retaining the last `GENERATIONS = 4096` code generations; binary-searched on `since(seen, now)`.
- `CodeSpace` — `{ space: Arc<DbtSpace>, tag: u16, seen: Mutex<u64> }` binding an `Mm` to its Tier 1 space (`tag == 0` outside a `CodeGroup`) and recording the last applied code generation.
- `CodeGroup` / `CodeGroups` — `Rc`/`RefCell` BKL-only group of address spaces sharing one `DbtSpace` for the same executable inode (`key`). Tracks `members`, monotonically growing trusted `ranges` (at most `MAX_RANGES = 256`, including vDSO sentinel `VDSO_ID = [u64::MAX; 5]`), per-member private executable `claims` `(tag, start, end)`, admitted shared `memops`, `closed`, and `shared`. `CodeGroups` is `RefCell<Vec<Weak<CodeGroup>>>` on `Kernel`, pruned when a group is founded.
- `Mm` consistency fields — `dirty`, `graveyard` (`Vec<Arc<HostRegion>>`), `code_ranges`, `exec_changed`, `memops`, `memops_dirty`, `users`, `code_space`, `code_group`, `code_tag`, `unshareable`, `membarrier`, and per-VMA `present_run` / `probe_writes` / `pages` (`FilePages`).

## Public entry points

### Page table and population

- `Mmu`: `new`, `map`, `prepare_unmap`, `unmap`, `unmap_noalloc`, `protect`, `move_entries`, `fill_shared`, `map_shared`, `protect_shared`, `next_present`, `translate`, `table_bytes`, `table_pages`, `watch_readable_page`, `invalidate_page_watches`.
- `Mm`: `mmu`, `fault_in`, `try_fault_in`, `translate`, `prefill_file_ptes`, plus internal helpers `map_pages`, `prefill_pages`, `fault_range`, `prepare_anon_write`, `finish_write_probe`, `fill_anon_non_cow`, `ensure_anon_present`, `unmap_or_clear_superpages`.

### Software TLB and Tier 0

- `Cpu`: `tlb_flush`, `translate`, `kicker`, `reset`; `CpuState::set_hw_debug`.
- C internals: `appine_tlb_fill`, `appine_cpu_tlb_flush`, `appine_mmu_lookup_super`, `appine_mmu_lookup_pte`, `tlb_probe`, `tlb_lookup`, `mem_read`, `mem_write`.

### Consistency, shootdowns, and code groups

- Threads and run loop: `Mm::add_user`, `Mm::remove_user`, `Mm::generations`, `Mm::code_space`, `Mm::invalidate_code`, `Mm::shared_with_others`.
- Synchronization: `Mm::needs_sync`, `Mm::sync`, `Mm::sync_except`, `Mm::quiesce`, `Mm::shootdown`, and `membarrier` (`syscall/misc.rs`).

## Data flow / lifecycle

### Page-table operations, superpages, and watches

- `store_range` (`map`/`fill`) promotes 2 MiB-aligned full leaf spans to L2 `PTE_SUPER` entries when the L2 slot is empty, already a superpage (on `map`), or an all-zero L3 table (`l3_is_empty`) that `retire_l3` stores in `mmu->retired`. If the 256-table retirement cap is reached or `realloc` fails, it fills the existing L3 table instead. With `only_empty` (`fill`), an existing `PTE_SUPER` skips the leaf without demoting even on partial spans.
- Partial edits to an L2 superpage call `demote_l2`, allocating a 512-entry L3 table, populating identical 4 KiB entries, and publishing via release store so concurrent walkers see either the superpage or the populated L3 table. `prepare_unmap` demotes only partially covered boundary superpages; `unmap` calls `prepare_unmap` then `unmap_noalloc`, preserving all translations and watches on `OutOfMemory`.
- `unmap_noalloc` never allocates or returns `OutOfMemory`: partially or fully covered L2 `PTE_SUPER` slots are cleared whole (widening the returned range to 2 MiB boundaries), while L3 tables are cleared strictly over `[gva, gva + len)`. `unmap_or_clear_superpages` tries `unmap` and falls back to `unmap_noalloc` on demotion OOM for non-rollbackable revocations (`clip_file_mappings`/`file_truncated`, `invalidate_fill`, `punch_tmpfs`).
- `protect` / `protect_shared` updates full 2 MiB superpages in place (including to prot 0, keeping `PTE_SUPER` and host base) and demotes partial superpages, leaving absent slots untouched. `move_entries` rejects overlapping ranges (`InvalidArgument`), fast-paths 2 MiB-aligned full superpages (at 2 MiB-aligned destinations) to empty or superpage L2 destination slots (otherwise demoting and moving 4 KiB entries via `ensure_l3`), and may leave partial moves on `OutOfMemory` (which `mm.rs` reverses back into the already-allocated source tables). `next_present` skips absent subtrees whole and counts prot-0 entries (including prot-0 superpages); `translate` requires nonzero permissions matching all bits of `required_prot`.
- `watch_readable_page` requires `translate(addr, prot::READ)`. `unmap` expires watches over `[gva, gva + len)` only on `Ok`; `unmap_noalloc` expires watches over its returned widened range on `Ok`; `map`, `map_shared`, and `move_entries` expire requested ranges (both `from` and `to` on move) on any result other than `InvalidArgument`, including `OutOfMemory`. `fill_shared`, `prepare_unmap`, and `protect`/`protect_shared` preserve watches. `invalidate_page_watches` explicitly expires watches on in-place discard while PTEs remain (`discard_range` when not zapping, `discard_unlent_in`, `Mm::release` for private files). Consumers re-check read permissions before reading.

### Populating page-table entries

- `fill_eager` builds entries via `fill_shared` when `mmu_prot(prot) != 0` and either `len <= EAGER_MAX` (1 MiB) or the mapping is executable and not private anonymous (`file && prot & EXEC != 0`: file mappings, shared anonymous, SysV shm). `Vma::eager_len()` is 0 for tmpfs (which waits for per-page quota admission) and `file_len` otherwise; `PROT_NONE` gets none. `map_pages` (new VMAs, growth) unmaps `[start, start + len)` on failure; `prefill_pages` (existing VMAs: `mprotect` leaving `PROT_NONE`, `place_moved_vma`, `unshare`) is best-effort and keeps existing entries intact on failure.
- `Vma::prefill_prot()` strips `PROT_WRITE` for software-tracked file mappings (`v.pages.is_some()`: tmpfs or private files without readable `/proc/self/pagemap`) and for unwritten private anonymous pieces with `!probe_writes`. Small (`<= EAGER_MAX`) writable non-`MAP_GROWSDOWN` anonymous VMAs on hosts with readable pagemap that inherit `has_anon_vma` or have no mergeable neighbours set `probe_writes = true` and start writable eagerly; `finish_write_probe` write-protects, calls `shootdown()`, and re-samples pagemap before `mprotect`, `discard_range`, or VMA merge.
- `fault_in` (`try_fault_in` -> `fault_range(addr, need, demand)`) returns `false` if no VMA covers the page, the page is at or past `contents_len().min(v.len)`, or `mmu_prot(v.prot)` lacks `need` (`WRITE` implies `READ`; `EXEC`-only does not imply `READ`, so data loads and kernel reads fault while instruction fetch works):
  - `v.pages` (tmpfs on any access; software-tracked private files on write): calls `FilePages::admit` (tmpfs charges `demand` up to the 2 MiB `LEAF_SPAN` boundary and extends over already-charged pages, or 1 page if private; ordinary private files break host CoW via atomic `fetch_or(0)` and record the host-page-aligned offset in shared `FilePages::copied` so detachment survives VMA splits), then calls `fill_shared` and (on writes to existing/multi-page ranges) `protect_shared`.
  - Anonymous CoW (`borrowed` / `lent`): an absent read installs one 4 KiB read-only entry (`cow_map_shared`); writes or present pages return `false` so the caller breaks CoW.
  - Anonymous non-CoW writes: returns `false` if already present, `!probe_writes`, and writable; otherwise `prepare_anon_write` sets `written = true`, clears `probe_writes`, propagates `has_anon_vma` across the logical VMA, and upgrades existing PTEs via `protect_shared` (no TLB flush needed), retrying single-page `protect_shared` if partial superpage demotion failed.
  - Absent non-CoW pages: spans `FILE_FAULT_SPAN` (16 MiB = 8 leaf spans) for read-only non-FUSE file mappings (`file_id.is_some() && fill.is_none() && prot & WRITE == 0`), or `LEAF_SPAN` (2 MiB) otherwise, clipped to `[s, end)`. Anonymous calls `fill_anon_non_cow` (skipping CoW pages and updating `present_run`); non-FUSE files call `fill_shared` with `prefill_prot()`; FUSE `Fill` returns `false` if the faulting page is unread, else fills already-read `fill.runs` (tracking shared-write baselines first via `fill.track`).
- `mprotect` to `PROT_NONE` keeps prot-0 entries (retaining host contents); leaving `PROT_NONE` installs CoW entries under old permissions, rewrites kept entries (keeping write stripped on unwritten anonymous pieces and `v.pages`), and best-effort `prefill_pages` the rest. For `v.pages`, `mprotect` pre-demotes boundaries with `prepare_unmap` before `protect` so demotion OOM leaves permissions intact.
- `MADV_WILLNEED` calls `prefill_file_ptes` for read-only (`READ` and not `WRITE`), non-tmpfs, non-FUSE, non-foreign, non-GPU file mappings up to `FILE_PREFILL_MAX` (4 GiB/call) via `fill_shared` without touching host pages. `MADV_POPULATE_READ`/`MADV_POPULATE_WRITE` (`populate_step`) faults tmpfs and write-tracked files per page via `try_fault_in`, populates host pages, and installs soft PTEs (preserving CoW on read via `fill_anon_non_cow`, settling CoW on write). Kernel uaccess (`for_each_page`, `pin_io`) fast-paths single-page `mmu.translate` hits, passes multi-page `demand` (up to `LEAF_SPAN`) into `fault_range` for `Fill`/`pages`, and on anonymous writes calls `prepare_anon_write` plus `ensure_anon_present` (2 MiB `LEAF_SPAN` windows via `fill_anon_non_cow`, guarded by `v.present_run`).

### Software TLB and Tier 0 refill

- `appine_cpu_new` allocates `struct appine_cpu` with anonymous `mmap` (zero RSS for untouched TLB/STLB pages) and recycles up to `SPARE_CPUS = 8` freed structs in a mutex-protected pool. `appine_cpu_tlb_flush` clears only indices marked in `tlb_used` and `stlb_used`, zeroes both bitmaps, and `memset`s the entire `itlb` (8 KiB). `appine_cpu_init` zeroes state before `tlb`, zeroes `v`, clears `nbrk`/`nwatch`/`dit`, binds `mmu`, and flushes. `CpuState::set_hw_debug` flushes only when `nwatch` or active `watch[..nwatch]` entries change.
- `tlb_probe` computes `*host = va + addend` alongside the tag check (`tag == ~vpn`) so the host can load tag and addend together; only a hit makes `*host` valid. In `interp.c`, `ldst_access` fast-paths unwatched single-page scalar loads and stores using `tlb_probe` and fixed-width copies without a stack frame; misses, cross-page accesses, and watched runs call `noinline` `ldst_slow` -> `mem_read`/`mem_write`. `mem_read`/`mem_write` (up to 16 bytes) strip the TBI0 top byte (`untag`), translate both pages before copying on cross-page accesses (reporting `va + first` on a second-page fault), and check `watch_hit` after translation and before copying (translation faults take priority over watchpoints). `cpu_run` caches the last fetched instruction page's `addend` across sequential instructions on one page, clearing that cache on every kick/re-entry.
- `appine_tlb_fill` walks via `appine_mmu_lookup_super` and returns `NULL` if absent or `!(prot & need)`. `APPINE_ACCESS_EXEC` fills only `itlb`. Data accesses fill both halves of `tlb[idx]` and mark `tlb_used`: the read half is valid iff `PROT_READ` is set and the page has no `APPINE_WATCH_LOAD`; the write half is valid iff `PROT_READ | PROT_WRITE` are both set (raw write-only pages stay uncached so Tier 1 grouped/atomic/exclusive paths cannot read through a write entry) and the page has no watchpoint of any kind. When `is_super && !cpu->nwatch`, `appine_tlb_fill` also populates `stlb[sidx]` and `stlb_used` with the same read / read+write rule.
- Tier 1's inline refill probes `stlb` before walking the 4-level radix tree with address-dependency-ordered loads, populates `stlb` on L2 `PTE_SUPER` hits, batch-prefills up to 8 sequential 4 KiB slots in the same `tlb_used` word, and consumes `stlb` in strip-mined fast loops and 2 MiB STLB memop fast paths. Tier 1 defers to Tier 0 whenever `nwatch != 0` and retains the guest pointer's top byte in cached `tlb`/`stlb` tags and addends (so Tier 0's untagged `tlb_probe` misses and refills the slot).

### Multi-thread consistency: shootdowns, quiescing, and generations

- Under the BKL, mutators record `dirty` (unmap, mremap moves, permission downgrades, fork lending, `unshare`, truncation, FUSE invalidation, tmpfs hole punch, leaving a code group, failed eager `install_vma`, non-pagemap `discard_range` zaps), `graveyard` (unmapped `HostRegion`s held until shootdown), `code_ranges` (executable unmaps, moves, growths, re-protects including failed rollbacks, discards, truncations, FUSE/tmpfs revocations, `ic ivau`, `write_force`), `exec_changed` (executable inserts, unmaps, re-protects, growths, moves, rollbacks, `write_kernel`), and `memops_dirty`. `Mm::needs_sync()` returns true iff any of these five is pending.
- `Mm::sync` / `Mm::sync_except` runs at:
  - `syscall::dispatch`: post-syscall gated on `needs_sync()` (skipped on `exit`, `exit_group`, `rt_sigreturn`, ptrace/seccomp-skipped `NO_SYSCALL`, ptrace entry kill, seccomp `Exit`; no-BKL fast syscalls never mutate `Mm`), and unconditionally before `exec_done` on successful `execve`/`execveat` to drain image-building state.
  - `task::handle` (`invalidate_code`): on `Exit::InvalidateCode` (`ic ivau`) and any exit leaving `cpu.state().ivau_page != 0` (invalidating both pages when distinct).
  - `Mm::write_force` (`PTRACE_POKETEXT`, `/proc/<pid>/mem`).
  - Cross-`Mm` file/FUSE/host changes: `syscall::fs::change_file_maps`, `vfs::fuse::for_each_mm`, `syscall::fuse::sweep` (before flushing `unmapped_fills`), and `machine::truncate_host_object` (`sync_except(None)` when the Files app truncates a mapped object with no current guest thread).
- `Mm::sync_except(current)` executes in order:
  1. Captures `exec_changed` and runs `check_code_group()` (which may set `memops_dirty`, or drop `code_space`, bump `space_generation` with `SeqCst`, and set `dirty = true`).
  2. Takes `code_ranges`; if `!memops.is_empty() && (!code.is_empty() || exec_changed)`, runs `prune_memops(&code)`.
  3. Calls `sync_memops()`.
  4. Takes `dirty`; returns early if `!dirty && code.is_empty() && graveyard.is_empty()`.
  5. If `!code.is_empty()`, locks `code_log`, appends `(code_generation + 1, &code)`, and stores the new `code_generation` (`SeqCst`).
  6. If `!dirty && graveyard.is_empty()`, returns without kicking other threads (they see code-only invalidations on next guest entry or via `membarrier(PRIVATE_EXPEDITED_SYNC_CORE)`).
  7. Increments `generation` (`SeqCst`), runs `shootdown_users(current)`, and clears `graveyard`.
- `shootdown_users(current)` kicks in its first pass only other users with `in_guest.load(SeqCst) == true` (sparing parked futex/epoll/BKL threads from spurious sticky kicks; they flush lazily on next guest entry). Its wait loop then polls each other user while `in_guest.load(SeqCst)` is true, spinning with `spin_loop()` up to `SHOOTDOWN_SPINS = 64` before `yield_now()`, and kicking on `spins == 0` and `spins >= 64` so threads entering guest mode after the first pass are still kicked. `Mm::shootdown()` (no-op if `users.is_empty()`) bumps `generation` (`SeqCst`) and calls `shootdown_users(None)` for immediate PTE re-pointing or rollback (CoW `materialize` and `give_back`, `finish_write_probe`, failed `install_vma` `shared_maps` rollback, failed `move_entries` rollback, failed growth rollback). `shootdown` does not hold threads out of guest mode after returning.
- `Mm::quiesce` (`quiesce_except`) does not bump `generation`; it unconditionally sticky-kicks every other user regardless of `in_guest` and yields until all have `!in_guest.load(SeqCst)`. Because sticky kicks force returning threads onto the BKL held by the caller, `quiesce` holds all other threads out of guest code until BKL release (required by `fork` and `membarrier`). `membarrier` checks `flags == 0` (`EINVAL`), advertises 7 supported commands on `QUERY`, requires matching registration for private commands (`EPERM`), and brackets `quiesce` with `fence(SeqCst)` before and after (`GLOBAL` visits each unique live `Mm` once; `GLOBAL_EXPEDITED` visits only registered `Mm`s, even if the caller is unregistered).
- In the run loop (`task.rs`), full guest entry (`!in_guest`) writes `in_guest = true` (`Relaxed`), issues `fence(SeqCst)`, and loads `(tlb, code, space)` (`Relaxed`), pairing with the mutator's `SeqCst` bump-then-read under fence-atomic coherence:
  1. `space` changed: clears `in_guest` (`Release`), drops old `Dbt` (if attached), takes BKL for `code_space` and re-attaches, updates `seen.2`, and `continue`s to re-run the full header.
  2. `tlb` changed: issues `fence(Acquire)` and calls `cpu.tlb_flush()`.
  3. `code` changed (with Tier 1 attached): locks `space.seen`; if `*done < now.1`, calls `CodeLog::since(*done, now.1)` and runs `d.invalidate(cpu, start, len)` per range or `d.flush()` on `None`, then sets `*done = now.1`.
  4. Stores `seen = now`.
  Non-sleeping fast syscalls (`FastOutcome::InGuest`) stay in guest mode, relying on engine-entry `cpu->pending` kicks for TLB/space changes while polling `dbt.is_some() && generations.code.load(Relaxed) != seen.1` to clear `in_guest` (`Release`) and run the full `space -> tlb -> code` header. Sleeping/yielding fast syscalls (`FastOutcome::LeftGuest`) clear `in_guest` themselves.
- `Mm::remove_user` unregisters a thread's `TaskSync` under the BKL before its `Cpu` is destroyed; when `users` becomes empty and `exe_open.take()` was `Some`, it also clears `interp_open` on all VMAs under the BKL.

### Code-change log, memops, and code groups

- `CodeLog` keeps up to `GENERATIONS = 4096` code generations (`push` evicts entries with `e.0 + 4096 <= generation`). `since(seen, now)` returns `None` when empty or `front().0 > seen + 1` (triggering full `Dbt::flush`); otherwise it binary-searches with `partition_point(|e| e.0 <= seen)` and returns `(start, len)` pairs up to `now`.
- `exec_split` / `exec_source` partitions executable VMAs into trusted shared sources — an unmodified vDSO (`start == self.vdso && backing == Anonymous`, verified byte-for-byte against `crate::vdso::image()` via `self.read`, sentinel `VDSO_ID = [u64::MAX; 5]`) and never-written `PrivateFile` executable VMAs with `file_id` and `content` (`[dev, ino, offset, ctime, size]`) — and contiguous merged `private` `(start, end)` intervals (JIT output, written code).
- On first Tier 1 use (and after `execve` or leaving a group), `code_space` tries `join_group` for `Mm::exe` unless `unshareable`:
  - `join_group` scans open (`!closed`) matching-key groups, allocates a tag via `space.new_tag()` (1..=65535; closes the group when exhausted; failed admission does not return the tag), and calls `group.admit(tag, &layout, &private)`.
  - `admit` succeeds iff (1) `private` overlaps no registered `group.ranges`, (2) `layout` overlaps no other member's `claims`, and (3) `group.extend(layout)` finds every overlapping registered range is `same_file` (same `dev, ino, ctime, size` and `start.wrapping_sub(offset)`) with total `ranges.len() <= MAX_RANGES (256)`. On admission it replaces this tag's `claims`, calls `space.share(&ranges)` if `ranges` grew, increments `members`, and on the first transition to multi-member (`!group.shared.replace(true)`) calls `space.retire_outside(&ranges)` once to drop pooled blocks outside the registered layout.
  - Otherwise `code_space` creates `DbtSpace::new(exe.unwrap_or(0), dbt_regs)`, calls `space.trust(&self.code_sources())` (file-backed only, capped at `DbtSpace::SOURCES = 64`), and runs `found_group`: if `exe` is `Some`, `!unshareable`, `layout.len() <= 256`, and `new_tag()` succeeds, it calls `space.share(&layout)` and registers a new `CodeGroup` (pruning dead weaks from `CodeGroups`); else it sets `unshareable = true` (`code_tag = 0`).
  - `CodeSpace::seen` initializes to `code_generation.load(SeqCst)`, and `code_space` sets `memops_dirty = true` and calls `sync_memops()`.
- On `exec_changed` during `sync_except`, `check_code_group` re-runs `group.admit`. On conflict, it drops the member's `claims`, sets `unshareable = true` (sticky), and clears `code_group` and `code_tag`. A sole member (`members == 1`) closes the group, keeps `code_space` (with its existing `CodeSpace::tag`), calls `space.retire_outside(&group.consistent(&layout))`, and sets `memops_dirty = true` (no shootdown or `space_generation` bump). Otherwise it decrements `members`, clears `code_space = None`, bumps `space_generation` (`SeqCst`), and sets `dirty = true` so `sync_except` shoots down threads to re-attach.
- `Mm::memops` tracks discovered entry PCs (up to `APPINE_DBT_MEMOPS = 32`) in executable mappings of the verified bundled libc snapshot. `prune_memops` drops entries inside invalidated `code_ranges` or no longer in an unmodified (`!written`), read-only (`!WRITE`), non-`Shared`, executable VMA. In a `CodeGroup`, `sync_memops` merges only PCs inside `group.ranges` into monotonic `group.memops` and calls `group.space.set_memops` on growth (private-mapping memops are withheld while grouped); outside a group, it publishes `self.memops` directly.
- A forked child starts outside any group with its parent's `exe` and may join an open group on first `code_space`. On `Mm::drop`, a grouped member decrements `group.members` and drops its `claims`; only when `remaining == 0` does it call `group.space.set_sources(&group.file_sources())` (excluding `VDSO_ID`, capped at `DbtSpace::SOURCES = 64`). A non-group `CodeSpace` calls `set_sources(&self.code_sources())`. `Mmu` destruction is deferred to `UNMAPPED.mmus` (`kernel/hostmem`) when dropped under the BKL.

## Dependencies (inbound / outbound)

### Inbound

- `kernel/mm`: VMA mutations recording `dirty`, `graveyard`, `code_ranges`, `exec_changed`, `memops_dirty`; entry fill (`map_pages`, `prefill_pages`, `fault_in`, `try_fault_in`, `prefill_file_ptes`, `ensure_anon_present`); `unmap_or_clear_superpages`; `Mm::write_force`; `Mm::drop`.
- `kernel/cow`: `shootdown`, `fill_shared`, `map_shared`, `protect_shared`, `fill_anon_non_cow`.
- `kernel/hostmem`: deferred `Mmu` destruction (`Unmapped::mmus`).
- `kernel/oom`: `Mmu::table_bytes` / `table_pages`, `next_present`, `invalidate_page_watches` in `Mm::release`.
- `kernel/process`: run loop (`TaskSync::in_guest`, `Generations`, `Cpu::tlb_flush`, `CodeLog::since`, `CodeSpace`, `ic ivau` / `ivau_page`), `syscall::dispatch` (`needs_sync`, `sync`), `membarrier` (`quiesce`), `fork` (`quiesce`), thread start/exit (`add_user`, `remove_user`), no-BKL fast path (`Cpu::translate`).
- `kernel/vfs` / `kernel/fileio` / `kernel/fuse` / `kernel/boot`: cross-`Mm` `sync` / `sync_except(None)` on file truncation, hole punch, FUSE invalidation/writeback, and host-side truncation (`machine::truncate_host_object`).
- `kernel/pipe`: `Mmu::watch_readable_page` and `PageWatch::is_current` for `vmsplice`.
- `kernel/vdso`: `Mmu::map` on its standalone probe table.

### Outbound

- `engine`: `Cpu`, `CpuState`, `Kicker`, `appine_cpu_tlb_flush`, `appine_cpu_kick`, `appine_cpu_init`.
- `engine/dbt`: `DbtSpace` / `Dbt` (`new`, `trust`, `new_tag`, `share`, `retire_outside`, `set_sources`, `set_memops`, `attach`, `invalidate`, `flush`); Tier 1 also depends on radix geometry, `PTE_SUPER` (bit 3) and `PTE_PROT_MASK` bit positions, `appine_tlb_entry` layout, `tlb`/`stlb` offsets and bitmaps (`tlb_used`, `stlb_used`), and `nwatch`.
- `kernel/vdso`: `vdso::image()` for byte-for-byte vDSO verification in `exec_source`.
- `hal`: `appine_hal::page_size()`, `pagemap_readable()` (`probe_writes` and `FilePages` tracking).

## Invariants and gotchas

- Only the kernel layer mutates page tables, serialized by the BKL, while other threads walk them concurrently (Tier 0 acquire loads; Tier 1 address-dependency-ordered loads). Tables start zeroed, intermediate pointers are >= 16-byte aligned (never colliding with `PTE_SUPER` or `PTE_PROT_MASK`), entries are published with release stores, `demote_l2` fills all 512 L3 entries before publishing, and no allocated or retired L3 table is freed before `appine_mmu_free` (deferred until BKL release).
- Unmaps, permission downgrades, and entry re-pointing require a TLB shootdown (`sync` or `shootdown`) before old host memory is freed or reused. Adding permissions or filling absent entries never requires a flush (a stale invalid half only misses).
- Only `quiesce` (unconditional sticky kicks to every user) holds other threads out of guest code until the BKL is released. `shootdown` and `sync_except` bump `generation` (`SeqCst`) and kick only `in_guest` threads; a thread entering guest mode afterward flushes its TLB and may run concurrently while the BKL is still held, so every intermediate page-table state must stay valid. Any future state cleared by `Mm::sync` must be reflected in `Mm::needs_sync()`.
- `quiesce` and `shootdown_users` wait while holding the BKL, so every user observed `in_guest` must be kicked (including threads entering guest mode after the initial pass, e.g., a thread spinning on a BKL waiter as in LTP `shmctl05`). Kicking is safe only because `remove_user` runs under the BKL before `Cpu` is destroyed.
- Never use `unmap_noalloc` to widen over private anonymous VMAs: clearing an enclosing 2 MiB superpage whole breaks `touched_windows` and non-pagemap swap attribution, which rely on PTE presence.
- Watched pages must never enter `stlb` (`!cpu->nwatch`), and `tlb` write halves require both `PROT_READ` and `PROT_WRITE` with zero watchpoints on that page. Any missed `stlb` flush on demotion/unmap/protect or stale watchpoint entry can cause silent corruption or missed SIGTRAPs.
- Page-table memory (`table_bytes`) grows monotonically until `Mmu` destruction; `MAX_RETIRED_L3 = 256` caps retired empty L3 tables at 1 MiB per address space.
- `CodeGroup` and `CodeGroups` use `Rc`/`RefCell` and are valid only under the BKL; `TaskSync`, `Generations`, `CodeLog`, and `CodeSpace` are thread-shared. `CodeGroup::ranges` and `CodeGroup::memops` grow monotonically while shared.

## Open questions

- None.

## To verify

- Linux 6.1 arm64 semantics for `PROT_EXEC`-only mappings (execute-only with Enhanced PAN vs exec implying read): needs the Linux 6.1 source to confirm whether Appine's execute-only behavior matches.
