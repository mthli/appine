# Memory accounting and the OOM killer (kernel/oom) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: kernel/oom"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12)
>
> Decisions on memory accounting and the OOM killer made before kernel/oom was split from kernel/mm keep their `kernel/mm` tags and live in `.agents/decisions/kernel/mm.md`: D26, D29, D32, D35 and D44.

## Active

### D1: kernel/oom is split out of kernel/mm
- **What**: kernel/oom owns `oom.rs`, the resident-memory measures and `Usage` in `mm.rs`, `Mm::release`, and process_mrelease in `syscall/mem.rs`: resident memory for procfs, rusage and child cgroups, the app-wide and cgroup limits, victim choice and kills, `oom_score` and the release of written code views.
- **Why**: `oom.rs` is the most independent part of mm (only 9 of its 17 commits touched `mm.rs` too), and its consumers are procfs, cgroups and rusage rather than mmap.
- **Tradeoffs**: Measures depend on mm internals, procfs owns views, kernel/cgroup owns cgroup policy, kernel/files triggers immediate memory.max rechecks, and kernel/time owns periodic checks and the idle handshake called from the process run loop.
- **Watch out**: A new kind of mapping that the measures do not classify, now that the classification lives apart from the mapping code.
- **Source**: 44a21b7; cf029f9a; 8a17e4a9; d053be24; 39e55b4b

### D2: OOM scoring accounts for accessible residents, page tables and swap

- **What**: Bound scans by backed ranges, exclude lent pages from exclusive counts, and include table bytes and attributed swap in victim estimates and proc statistics.
- **Why**: RSS alone misranks address spaces with very different page-table and swap costs.
- **Tradeoffs**: Exact attribution requires reading host page state.
- **Watch out**: Avoid double-counting borrowed or unbacked ranges.
- **Source**: 0e02d7cd, 74584914, e6e88b55

### D3: Reap a dying address space only when all remaining users can release it

- **What**: Check every live group sharing the Mm, distinguish thread-only exits and core dumps, and preserve loaned pages whose remap fails.
- **Why**: A dying task can share live memory with another process.
- **Tradeoffs**: Memory release may wait or reclaim only part of the address space.
- **Watch out**: Invalidate page watches for every discarded span.
- **Source**: 2609ab43, 3a90a0ef, 98225432, 3de64361, bb31dbda

### D4: Root and memory.high pressure share DBT reclaim

- **What**: Trim pooled translations and written code views, then resample usage while preserving active code groups.
- **Why**: Reclaiming unused code may relieve pressure without a kill.
- **Tradeoffs**: A failed resample must fall back to the earlier measurement.
- **Watch out**: Keep translations needed by live group members.
- **Source**: 567ff786, ab417943

### D5: OOM swap walks skip `Vma::present_run`, cache shared `Mm` stats, and bound-check `proc_score`

- **What**: Skip per-page `mmu.next_present` FFI probes across contiguous `Vma::present_run` spans in `swappable_nonresident_pages`, cache shared `Mm` stats and `SwapContext::Fallback` candidates in `select_with`, use `was_written()` for resident accounting, and short-circuit `proc_score` when swap bounds cannot alter the rounded score.
- **Why**: Per-4 KiB FFI probes under the BKL and repeated scoring and fallback-swap scans for shared address spaces made OOM scoring and `/proc/<pid>/oom_score` reads unnecessarily expensive.
- **Tradeoffs**: Maintains a monotonic `Vma::present_run` hint (reset on unmap, discard, or split) and a per-pass cache in `select_with`.
- **Watch out**: Failing to shrink or clear `present_run` when unmapping soft PTEs would cause `swappable_nonresident_pages` to skip over unmapped holes.
- **Source**: b38371ff
