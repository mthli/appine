# Tier 1 dynamic binary translator (engine/dbt) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: engine/dbt"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12)
>
> Decisions on engine/dbt made before it was split from engine keep their `engine` tags and live in `.agents/decisions/engine.md`: D6–D10, D13–D16, D18, D23, D25, D27–D29, and the Tier 1 halves of D2, D5, D17, D20, D22 and D24.

## Active

### D1: Tier 1 dynamic binary translation has a dedicated module

- **What**: engine/dbt owns `dbt.c`, `emit.h`, the Tier 1 sections of `engine.h` and `core/engine/src/lib.rs`, and `core/engine/tests/dbt.rs`, while Tier 0 execution, CPU state and the fault guard stay in `engine` and the soft-MMU in `kernel/mmu`. Kick and SVC exits clear the local exclusive reservation, interpreter faults inherit Tier 0 clearing, and code-invalidation exits preserve it.
- **Why**: The 5,250-line Tier 1 translator needed its own map with headroom for ongoing optimization work.
- **Tradeoffs**: Pre-split decisions remain in `.agents/decisions/engine.md`, and shared header and Rust binding files span `engine`, `kernel/mmu` and `engine/dbt`.
- **Watch out**: Keep `struct appine_cpu` layout, TLB entry format and guarded-run lock registration synchronized with `engine` and `kernel/mmu`.
- **Source**: e4d7bbe5, d1fc9715

### D2: Tier 1 memop extension directions are tracked in a standalone handoff document

- **What**: `docs/dbt-memop-extensions.md` records the source-verified Tier 1 memop baseline, three prioritized extension directions, non-goal boundaries and a profiling-first verification checklist.
- **Why**: Multi-step memop optimization work needed a persisted design baseline and ABI pitfall reference outside conversation history and the module map.
- **Tradeoffs**: Adds a planning document outside `.agents/maps/` that must be updated or retired as directions land.
- **Watch out**: Re-verify the documented baseline against `.agents/maps/engine/dbt.md` and `loader.rs` before implementing deferred directions.
- **Source**: 30e42030

### D3: Shared in-cache routines accelerate same-page memops and `exec_memop` scans up to 64 MiB

- **What**: `emit_routines` emits shared in-cache same-page helpers for `memset`, overlap-safe `memcpy`/`memmove` (`n >= 32`), 8-byte `memcmp` (`n >= 8`) and first-page SWAR `memchr` (`n >= 1`, including `SIZE_MAX`) through a predicted-return tail with 4-bit routine relocations, while `exec_memop` scans `memchr`/`strncmp` up to a 64 MiB budget instead of rejecting large `n` upfront and warms the data TLB for untagged addresses on completion. Even identical memop source and destination ranges require valid read and write access before the no-copy shortcut.
- **Why**: Same-page 128 B–4 KiB musl memops paid full `V0`–`V31` exit/enter costs, 32–127 B calls and bytewise `memcmp` went unaccelerated, and `strnlen(..., SIZE_MAX)` triggered excessive `R_INTERP` exits.
- **Tradeoffs**: Adds ~0.9 KB of per-instance startup routines, borrows `cpu->x[3..9]` scratch slots inside helpers, and remains scoped to registered bundled-musl symbols.
- **Watch out**: Keep `emit_routines` well under `MAX_BLOCK_WORDS` and synchronize 4-bit `TO_*` tags and `X0`–`X9` scratch assumptions with `struct appine_cpu` across both Default and Go register sets.
- **Source**: 7438389e, 849bf98a

### D4: `llama.cpp` CPU optimizations follow a profiling-driven roadmap in `docs/llama-cpp-cpu-perf.md`

- **What**: `docs/llama-cpp-cpu-perf.md` records native-versus-Appine prefill/decode profiling, hot-loop disassemblies and A/B regression plans, reprioritizing verbatim `YIELD` and grouping first, strip-mined fast loops second (split into `flash_attn` Phase 2B-1 and `tinyBLAS::gemm_bloc` Phase 2B-2), and elevating 2 MiB superpages/STLB (Direction 3A) ahead of or alongside Phase 2B-2.
- **Why**: Real profiling showed 92.8–95.2% of decode time in translated blocks and 75–80% of post-2B-1 Q4_0 prefill overhead in 4 KiB fast-loop page crossings and `emit_refill`, contradicting the initial unprofiled order.
- **Tradeoffs**: Maintains a standalone roadmap outside the module maps and defers either F16 prefill or Q4_0 page-crossing wins depending on which step lands first.
- **Watch out**: Profiling used Qwen2.5-0.5B on an Apple Silicon Linux VM, so larger models and narrower mobile cores can shift the balance between `emit_refill` and in-block checks and should be re-profiled against current maps before further changes.
- **Source**: 9542b3db, f1142ce8, 3098de19, 681b4572

### D5: Tier 1 emits `YIELD` verbatim and raises block and grouping limits

- **What**: Tier 1 emits `YIELD` verbatim, raises `MAX_BLOCK_INSNS` to 256 (with 32 B bitfield-packed blocks, lazy guest snapshotting and unchanged 4096-word / 96 MiB limits), widens concurrent grouping to 8 slots and 32 members via bitmasks, tracks depth-1 block-local affine aliases, and allows stolen-register bases, matching `M_REG` accesses and aligned SP-led groups.
- **Why**: Unrolled GEMM/GEMV loops hit the 64-instruction cap, 4-slot grouping ceiling, affine/stolen-base/`M_REG` gaps and NOP-dropped `YIELD` hints without wanting extra translation cost on cold compiler blocks.
- **Tradeoffs**: Uses ~36 KiB of per-thread scratch workspace, translates ~1.3% more fall-through instructions in branchy code, and adds complexity to `plan_group` and `affine_update`.
- **Watch out**: Any missed register-kill, SP-alignment, 24-bit offset or 32-bit `UXTW` wrap guard can misgroup accesses or leave stale bases across Default and Go register sets.
- **Source**: 011b7445

### D6: Strided self-loops compile strip-mined native fast loops ahead of checked bodies

- **What**: Single-block constant-stride self-loops—including cross-4 KiB-code-page fast loops with single-page slow fallbacks and exact page-1 invalidation—emit an NZCV-free strip-mined native fast loop supporting up to 7 root pointers, 16 access streams (`M_REG`, derived shifted indices, register strides and 16-byte-aligned zero-stride SP frames), loop-local write-before-read NZCV, and up to 6 pinned stolen GPRs via `T0`/`T1` and borrowed unused host registers, bounded by `nwatch == 0` and `KICK_BUDGET` (12,288 guest instructions) and counted in `DbtStats::fast_loops`.
- **Why**: Hot ggml and `tinyBLAS::gemm_bloc` inner loops still paid per-iteration inline soft-TLB checks, group spills, stolen-register reloads and kick checks after block grouping.
- **Tradeoffs**: Adds ~100–400 host words per matched loop (up to 128 bail slots), always runs at least one final or boundary iteration in the checked loop, skips parking cross-page blocks on recheck, and raises the worst-case fast-loop kick budget to 12,288 guest instructions.
- **Watch out**: Missed pointer-opacity or read-before-write alias checks, NZCV or bound-overflow bugs, borrowed host-register clobbering, SP alignment/contiguity gaps or missed page-1 invalidation across `install`, `template_fetch` and `invalidate_locked` can corrupt guest state or run stale code, and unkept `appine_dbt_free` flushes must clear `nogroup_count` and `strikes`.
- **Source**: 49585f4e, 669f3edb, 2ce97315

### D7: Tier 1 leverages 2 MiB `stlb` runways, sequential TLB prefill and 2 MiB `exec_memop` spans

- **What**: Leaving the ordinary 4 KiB inline TLB hit sequence unchanged, `emit_fill` stops at L2 `PTE_SUPER` to populate `stlb` and batch-prefill up to 8 contiguous 4 KiB TLB slots on sequential misses, `emit_fast_loop` probes `stlb` before `tlb` for a 2 MiB runway with 34-bit reciprocal division and a `MUL` register-stride fast path, and `memop_check_range`/`exec_memop` translate up to 2 MiB per step.
- **Why**: 4 KiB page-crossing exits in fast loops and `emit_refill` misses accounted for most of the remaining Q4_0 prefill and decode overhead, while non-ML workloads could not regress on inline hits.
- **Tradeoffs**: Enlarges fast-loop prologues (~42 words/stream budget) and `emit_refill` stubs, and writes up to 7 extra TLB slots on sequential misses.
- **Watch out**: Register-stride `MUL` overflow or reciprocal-division rounding errors over 2 MiB spans could overestimate `safe_iters` and fault late, requiring coverage for boundary crossings, non-power-of-2 strides, TBI, watchpoints and superpage demotion across Default and Go register sets.
- **Source**: 42d49fc0

### D8: Tier 1 C sources are split into flat sibling `dbt*.{c,h}` translation units

- **What**: `core/engine/src/c/dbt.c` is split into flat sibling files (`dbt_internal.h`, `dbt_translate.c`, `dbt_group.c`, `dbt_loop.c`, `dbt_routines.c`, `dbt_cache.c` and `dbt.c`) using `dbt_`-prefixed cross-unit symbols, `static inline` hot emitter, table-lookup, catch-up, whitelist and atomic helpers in `dbt_internal.h`, and `#ifdef DBT_SUPPORTED` guards on helper units.
- **Why**: Recent Tier 1 optimizations grew `dbt.c` to 8,207 lines, making navigation and maintenance disproportionate to sibling files in `core/engine/src/`.
- **Tradeoffs**: Cross-unit calls are no longer eligible for single-TU static inlining without LTO, and `dbt_internal.h` must stay synchronized across all six C files.
- **Watch out**: Keep cross-TU helpers `dbt_`-prefixed and `#ifdef DBT_SUPPORTED`-guarded so unprefixed symbols do not leak and non-ARM64 host builds keep compiling under `-Wall -Wextra -Werror`.
- **Source**: cc72b093, b38371ff

### D9: Conditional branch relinking batches per-thread patches under the DBT lock while entry stubs use lock-free CAS

- **What**: `patch_stub` patches `LINK_AT_ENTRY` unconditional stubs lock-free via CAS, while conditional-branch relinks queue up to 29 per thread (`pending_relinks`) and drain under `d->lock` with the next block install's `dbt_sync_all` after verifying `retire_gen` and ownership (`d->nthreads == 1` for shared blocks, atomic `t->sole_tag` for private tagged blocks).
- **Why**: Synchronous per-link `d->lock` and full icache sync regressed cold startups (`cold_node` +14%, `cold_java` +12%), while lock-free conditional patches remain unsafe under ARM64 CMODX.
- **Tradeoffs**: Adds small per-thread `pending_relinks` queue state and delays conditional-branch shortcuts until the next block install or queue flush.
- **Watch out**: Never lazily publish a conditional branch patch, and bump `retire_gen` under `d->lock` on every path that rewrites `code[ENTRY_CHECK_WORDS]` or recycles blocks so queued relinks cannot patch a retired block.
- **Source**: 849bf98a, 99b74cef, b38371ff

### D10: A revived DBT block cannot replace a newer translation of the same key

- **What**: Retire matching parked blocks on insertion and check for a current table entry before reviving a block.
- **Why**: A stale parked translation can otherwise outlive its replacement.
- **Tradeoffs**: Revival consults the active table.
- **Watch out**: Keep fallback statistics on the instance so independent translators cannot race.
- **Source**: 849bf98a

### D11: Repeated grouping failures are tracked by leader

- **What**: Search the 64 strike slots by leader on the cold ungroup path, reusing vacant slots before FIFO eviction and exercising alternating leaders in one instance.
- **Why**: Direct slot collisions lost repeated failures.
- **Tradeoffs**: Cold-path lookup can scan the table.
- **Watch out**: Run tests against the same live instance across strikes.
- **Source**: 9163f493, 9951951a

### D12: Memory pressure drains pooled instances and reclaims template storage

- **What**: Detach free instances and thread instances under the pool lock, release them outside it, then trim templates and unused code views.
- **Why**: Idle DBT memory can otherwise survive pressure.
- **Tradeoffs**: Reclaimed translations may need rebuilding.
- **Watch out**: Acquire template shards in ascending order before the arena lock.
- **Source**: 567ff786, ab417943

### D13: Tier 1 applies arm64 TBI0 in `d->lookup`, `exec_memop` returns and `dbt_run` entry

- **What**: `emit_untag_pc` (`tbnz #55` + `and`) runs at `d->lookup` entry rather than on inline `BR`/`BLR`/`RET` jump-cache probes or `d->rs_miss`, while `dbt.c` applies `untag_pc` at `dbt_run` entry and out-of-line `exec_memop` returns, checked against Tier 0 with a shared per-register-set `Guest` helper.
- **Why**: Inline `emit_untag_pc` on every indirect branch slowed untagged indirect jumps, while moving TBI0 normalization to `d->lookup` still preserves Linux 6.1 `BranchAddr` semantics without clobbering guest NZCV or slowing predicted `RET X30` hits.
- **Tradeoffs**: Tagged TTBR0 indirect targets miss the inline jump cache (which stores aligned 48-bit PCs) and normalize in `d->lookup` with zero extra instructions on untagged jump-cache hits.
- **Watch out**: Tagged `X30` returns miss both the shadow return stack and inline jump cache while preserving `X30`, and `exec_memop` must pop matching shadow-stack entries using `untag_pc(c->x[30])`.
- **Source**: 6a979929, 8a839900, b38371ff

### D14: `emit_fill` probes `cpu->stlb` before walking the 4-level radix page table

- **What**: `emit_fill` probes 2 MiB `cpu->stlb` (`tag_write` on write refills; `tag_read` plus branchless `EON`/`CLZ`/`SBFX` `w_mask` derivation on read refills) before walking L0–L2, branching directly into the superpage 4 KiB fill and 8-slot sequential prefill path while leaving ordinary 4 KiB inline hits untouched.
- **Why**: 4 KiB TLB misses inside already-cached 2 MiB superpages previously walked L0–L2 on every refill even though `cpu->stlb` already held the translation.
- **Tradeoffs**: Adds 57 host words across the six `emit_fill` expansions and a 6-instruction STLB probe before the radix walk on cold non-superpage misses.
- **Watch out**: Changes to `stlb` tag layout, `w_mask` derivation or superpage demotion/flush ordering could grant write access on a read-only superpage or serve stale translations.
- **Source**: a1fe8a19

### D15: Tier 1 skips unchanged `msr fpcr` transitions and write-only stolen `Rd` loads

- **What**: Shared routines compare host FPCR against `cpu->fpcr` via NZCV-free `eor` + `cbz` to skip unchanged `msr fpcr` transitions, while `struct operands::reads_rd` and `static inline simd_dp_operands` elide `emit_load_guest` for write-only stolen `Rd` (keeping `emit_operands` and `emit_fast_operands` separate and preserving source-aliasing loads and `x->t2_group = -1` on `nmap == 3`).
- **Why**: Unconditional pipeline-serializing `msr fpcr` on `enter`/`exit` and loading write-only stolen `Rd` registers wasted cycles on >99.9% of transitions and common ALU/SIMD instructions.
- **Tradeoffs**: Adds a few shared-routine words, retains separate `emit_operands`/`emit_fast_operands` loops to avoid hot-path indirection, and relies on translated code never mutating host FPCR.
- **Watch out**: Any newly whitelisted DP or SIMD instruction that reads `Rd` must set `reads_rd = true`, and `simd_dp_operands` must stay aligned with `struct native_desc`.
- **Source**: d2eb8398, e6ba682e

### D16: Tier 1 activates FP/SIMD state lazily per block and probes `t->jc` in `dbt_run`

- **What**: Blocks touching V/FPCR/FPSR emit a 2-word check at `code[ENTRY_CHECK_WORDS]` calling shared `d->load_fp` (with `d->exit` flushing FP state only when `dbt_fp_loaded` is set and `guard.c` clearing it on host-fault recovery), `dbt_fp_loaded` + `nwatch` reuse the unused `dbt_reason` slot before `tlb` (`TLB_OFF = 480`), and `dbt_run` probes thread-local `t->jc` before `dbt_table_lookup` when `patch_site == NULL`.
- **Why**: Syscall-heavy and integer-only runs previously paid 512 B `V0`–`V31` save/restore, `d8`–`d15` spills, FPCR/FPSR moves, and `SEQ_CST` `dbt_lookup_seq` + shared table probes on every transition.
- **Tradeoffs**: Adds 2 inline words plus 2 stub words to FP/SIMD blocks and one branch in `d->exit`, and cross-page fast loops with FP only on page 1 re-prepare once with `uses_fp` forced.
- **Watch out**: Every new Tier 1 instruction touching host V/FPCR/FPSR must be recognized by `insn_uses_fp`, and every exit path bypassing `d->exit` must clear `dbt_fp_loaded`.
- **Source**: 051ce718

### D17: Tier 1 eagerly links inline stubs, pre-translates multithreaded branches, shares `d->ret_miss`, widens grouping and inlines common system ops

- **What**: Tier 1 eagerly links inline `R_CHAIN`/`R_CHAIN_FWD` stubs at install time, pre-translates up to 2 same-page conditional branch targets before publication when `nthreads > 1`, routes `RET X30` shadow-stack misses through shared `d->ret_miss`, widens `GROUP_SPAN` to 2048 B while continuing past out-of-span members, `MRS TPIDR_EL0` and `LDAR`/`STLR`/`LDAPR`, inlines common system registers (`DIT`, ID/CTR regs, masked `MSR FPSR`) and 64/128-bit `LDXP`/`STXP` exclusives, and fast-paths base ARMv8.0 encodings in `dbt_tier0_base_defined`.
- **Why**: Cold inline `R_CHAIN` exits, unlinked multithreaded conditional branches under CMODX, bloated `RET` epilogues, premature group termination and Tier 0 fallbacks on common sysregs and pair exclusives slowed translated execution.
- **Tradeoffs**: Wider 2048 B groups can incur one extra ungroup on unmapped guard pages, and pre-translation is bounded by cache/table occupancy and stack snapshot size.
- **Watch out**: Keep `dbt_tier0_base_defined` in exact parity with `interp.c`, and ensure `appine_dbt_run` continues calling `appine_sync_dit` before Tier 0 steps and on exit so inline `MSR DIT` stays synchronized.
- **Source**: d0a0c71c

### D18: Strip-mined fast loops cover 32-bit `M_REG` induction, backward strides, shifted-`ADD` pointers, bounded LUTs, early exits and `while` loops

- **What**: `dbt_loop.c` adds NZCV-free 32-bit `M_REG` wrap clamps, backward constant-stride `avail = page_off` checks, derived shifted-`ADD` (`sym_pidx`) pointers, intra-iteration `u_bits`-bounded LUT streams validated as 0-stride 4 KiB ranges, shared NZCV-free epilogues for up to 4 early exits, header-tested `while` loops and `ADDS`/`TBZ`/`TBNZ` closers, `ADR`/`ADRP`/single-page `M_LITERAL` support, and a 4096-iteration `KICK_BUDGET` cap within 12,288 guest instructions.
- **Why**: Hot crypto, compression, Go SSA and NEON loops (such as `crc32` LUTs, `sha256` shifted-`ADD` indexing, reverse scans and early-exit or `while` loops) previously missed fast-loop matching or paid excess prologue overhead under the 256-iteration cap.
- **Tradeoffs**: Raises `dbt_loop.c` complexity and per-loop budget (`FAST_LOOP_MAX_WORDS` 1500→1600, `bails[192]`), while cross-page `M_LITERAL`s and side exits with mismatched mid-iteration pointer aliases stay on the checked slow loop.
- **Watch out**: Any missed alias-set equality check at a side exit, premature `ctrl_idx` `CMP` omission, 32-bit index wrap or backward upper-page straddle can corrupt guest state.
- **Source**: b19afd83

### D19: Shared inline helpers deduplicate Tier 1 conditional `MSR`, jump-cache probe, atomic and grouping boilerplate

- **What**: Core DBT units extract zero-overhead `static inline` helpers (`emit_msr_if_changed`, plain-indexed `emit_jc_probe`, `dbt_is_ldar_stlr`, `dbt_is_ldapr`, `dbt_is_nogpr_ctrl`) in `dbt_internal.h` alongside local grouping/translation/test helpers, without altering emitted instructions, struct layouts or `dbt_loop.c` cross-slice contracts.
- **Why**: Recent Tier 1 performance commits (`051ce718`, `d0a0c71c`, `a332f651`) duplicated conditional `msr` skipping, the 5-instruction jump-cache probe, raw atomic/control bitmasks and exclusive/test setup across multiple files.
- **Tradeoffs**: Keeps `dbt_loop.c` outside this slice's helper extraction so cross-slice contracts remain explicit.
- **Watch out**: Any behavioral drift in `emit_msr_if_changed` or `update_memop_aliases` could break lazy FP state or affine group tracking across `Default` and `Go` register sets.
- **Source**: 9cb9d455

### D20: In-cache memops fall back to 2 MiB `stlb` with split caps and accelerate `strlen`, `strcmp` and `strncmp`

- **What**: All in-cache memop helpers add strictly NZCV-free and FP-free 2 MiB `stlb` fallbacks capped at 4 KiB (`MEMOP_COPY_SUPER_MAX_SHIFT = 12`) for GPR-only `memset`/`memcpy`/`memmove` and 64 KiB (`MEMOP_SUPER_MAX_SHIFT = 16`) for 8-byte `memcmp` and SWAR `memchr`/`strlen`/`strcmp`/`strncmp`, paired with `exec_memop` support for `strlen`/`strcmp` and `memop_warm_tlb` on `strncmp`.
- **Why**: Cross-4 KiB superpage spans triggered `R_INTERP` exits on 16.5%–24% of 128 B–4 KiB memops, `strncmp` lacked an in-cache helper, and musl's `strlen`/`strcmp` paid per-access inline TLB checks while >4 KiB GPR-only copies trailed `exec_memop`'s host NEON/`DC ZVA`.
- **Tradeoffs**: Adds ~900 words of shared routines (`d->routine_words = 1795 / 4096`), consumes 3 more 4-bit `TO_*` slots (`TO_COUNT = 14`), and adds ~0.86 ns on 1-byte `strcmp` for helper register save/restore.
- **Watch out**: Keep `MEMOP_COPY_SUPER_MAX_SHIFT` synchronized with `emit_memop_memmove`'s overlap check and SWAR `(diff | nul)` boundary clamping, and monitor `TO_COUNT <= 16` and `MAX_BLOCK_WORDS` headroom before adding routines.
- **Source**: fc34ad74

## Superseded

- ~~Initial unprofiled execution order in `docs/llama-cpp-cpu-perf.md` (9542b3db)~~ → replaced by **D4** in f1142ce8 (2026-11-22)
- ~~64-instruction block cap, 4-slot/16-member non-stolen immediate-only grouping and NOP-dropped `YIELD` in `engine` D10 (round 27)~~ → replaced by **D5** in 011b7445 (2026-11-22, cross-module predecessor in `.agents/decisions/engine.md`)
- ~~Keeping 2 MiB superpages deferred to Step 3 behind Phase 2B in 3098de19~~ → replaced by **D4** in 681b4572 (2026-11-23)
- ~~Per-link synchronous conditional-branch relinking under `d->lock` (849bf98a, 99b74cef)~~ → replaced by **D9** in b38371ff (2026-11-27)
- ~~Inline `emit_untag_pc` on `BR`/`BLR`/`RET` jump-cache probes and `d->rs_miss` (6a979929)~~ → replaced by **D13** in b38371ff (2026-11-27)
- ~~XOR-folded `t->jc` indexing (`pc ^ (pc >> 12)`) in d0a0c71c~~ → reverted to plain `ubfx` indexing by untagged revert a332f651 (2026-11-27; no `MODULE:` block, reflected in **D19** via `emit_jc_probe` in 9cb9d455)
