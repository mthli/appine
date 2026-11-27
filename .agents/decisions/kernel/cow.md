# Fork and copy-on-write (kernel/cow) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: kernel/cow"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12)
>
> Decisions on kernel/cow made before it was split from kernel/mm keep their `kernel/mm` tags and live in `.agents/decisions/kernel/mm.md`: D6, D10, D11, D19, D24, D25 and the fork/copy-on-write parts of D15, D16, D18, D20, D22, D31 and D39.

## Active

### D1: Fork and page-granular copy-on-write have a dedicated module

- **What**: kernel/cow owns the `fork` section (`Mm::fork`, `commit_too_big`, `carve`, `spare_below`) and the `Copy-on-write` section (`MmRef`, `Loan`, `PageHasher`, `Loans`, `Runs`, `Borrowed`, `Cow` and CoW `impl Mm` methods except `kernel/mmu`'s `shootdown`) in `core/kernel/src/mm.rs`.
- **Why**: Fork and page-granular copy-on-write (~820 lines across two contiguous sections of `mm.rs`) needed a focused map of their own.
- **Tradeoffs**: Pre-split decisions remain in `.agents/decisions/kernel/mm.md`, and section boundaries inside `mm.rs` live in `.agents/MODULES.md`.
- **Watch out**: Changes to `mprotect`, `munmap`, `mremap`, `MADV_DONTNEED`, `Mm::write`, futex writes or fork carving require reading `kernel/cow` alongside `kernel/mm` or `kernel/hostmem`.
- **Source**: 610d623f

### D2: Copy-on-write lending publishes only completed page-table transitions

- **What**: Prepare parent and child mappings before publishing loans, retain ownership on failure, and use ordered non-CoW runs and shared failpoints to verify rollback.
- **Why**: A borrower must never retain a mapping to memory already returned to its owner.
- **Tradeoffs**: Fork needs staging storage and recall can return ENOMEM.
- **Watch out**: Drop the child MMU before releasing staged region handles.
- **Source**: 2609ab43, 9d9c5c7f, bb31dbda, 33e95609

### D3: Fork retains backing identities while copying private file pages

- **What**: Inherit cached descriptors and executable pins, remap the pinned file, and copy pages marked private through rounded EOF.
- **Why**: Children need private writes and stable executable references even after unlink or rename.
- **Tradeoffs**: Fork inspects page state and copies private data.
- **Watch out**: Same-value writes still count as private.
- **Source**: 68b70897, 93a9b204, 71790fde, 11816722

### D4: Fork preserves sticky private-memory accounting

- **What**: Carry accounted state through fork and VMA edits once a reserving private write mapping acquires it.
- **Why**: Removing write access does not release the original accounting contract.
- **Tradeoffs**: VMA metadata must survive splits and moves.
- **Watch out**: Forced kernel writes must not acquire this accounting bit.
- **Source**: 9d0ad1b6

### D5: Small writable private-file fork views are pooled and `private_pages` sampling is shared

- **What**: Pool one released `ForkFileView` per small (`<= 64 KiB`) writable `PrivateFile` VMA, share one `appine_hal::private_pages` sample between `anon_vma_fork` and `copy_private_file`, and finalize `probe_writes` before fork lending.
- **Why**: Per-VMA host `mmap`, `fstat`, and repeated pagemap sampling on small writable private-file VMAs regressed `spawnbench` `fork`/`exec` latency by 13–20%.
- **Tradeoffs**: Retains at most one idle 64 KiB file mapping per fork family's small writable private-file VMA until the parent VMA drops.
- **Watch out**: Returning a `ForkFileView` without first discarding its prior user's pages via `MADV_DONTNEED` would leak dirty private data to the next forked child.
- **Source**: b38371ff
