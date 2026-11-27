# CPU engine (engine) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: engine"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12). D1–D18 were seeded by hand from the retired design
> doc §4 and the round records in `docs/rounds/`, so their sources cite rounds; later entries cite
> commits.

## Constraint index (compressed active decisions)

> Full text of D1–D4, D6–D18 and D20–D24: `git show 1ea0c1ef9d686d7126bec2029fc928d4f6fdfc6b:.agents/decisions/engine.md`

- **D1** — The guest ISA is aarch64 (same as the host), enabling verbatim copies and `ic ivau` + `isb` code-invalidation hooks without write-protecting code pages, which requires `CTR_EL0` to report `DIC = IDC = 0` (design review 2026-10-24)
- **D2** — Execution uses two tiers with Tier 0 as the semantic reference and shared whitelist, so every new instruction or extension must be implemented in Tier 0 before Tier 1 translates it (design review 2026-10-24, rounds 01, 02, 09)
- **D3** — Tier 0 uses nested-switch decoding rather than threaded dispatch, accepting slow Tier 0 execution when breakpoints, watchpoints or poisoned instances keep workloads out of Tier 1 (design review 2026-10-24)
- **D4** — Tier 0 runs SIMD/FP, CRC32 and LSE via cached native single-instruction stubs (specializing integer ADD/SUB and logical vector ops by read operands), which must preserve flags, aliases, read destinations and upper-lane clearing and are required before advertising those features (rounds 02, 09; 16914003)
- **D6** — Translated code never touches host `x18` (Android ShadowCallStack, guest `X18` stays in CPU state) and never alters guest NZCV (using `cbnz`/`tbnz`/`eor` instead of compares) (round 09)
- **D7** — The default register set keeps guest `SP` in host `X26` and compiles `bl`/`blr`/`ret x30` to host calls/returns with a shadow return stack reset on instance flush, falling back to the jump cache on mismatches (round 15)
- **D8** — Inline TLB checks index by the access's last byte and compare the tag against the first byte's page so one compare rejects misses and cross-page accesses, depending on `kernel/mmu`'s TLB layout and D3 (rounds 09, 23, 30; 8bc0e63b)
- **D9** — Translated blocks continue past conditional branches (linked as side exits patched only while one thread is attached for CMODX safety) but end at unconditional branches and calls (rounds 09, 31)
- **D10** — Same-base accesses in a block share one leader TLB check (up to 512 bytes; extended in `engine/dbt` D5), falling back to Tier 0 and ungrouping on repeated leader failures, while members after conditional branches may widen the checked range (round 27)
- **D11** — HINT-space PAC and BTI instructions are NOPs in both tiers, PACA/PACG are never advertised, and non-hint PAC instructions raise `SIGILL` (design review 2026-10-24)
- **D12** — Guest CPU features equal `host ∩ implemented ∩ whitelist` driving `AT_HWCAP`, `/proc/cpuinfo` and ID registers, with the whitelist sampled against `llvm-mc` when present on Linux aarch64 (rounds 02, 133–155)
- **D13** — Threads of an address space share one Tier 1 code cache and lock-free translation table (locked install writing only empty slots/tombstones, CMODX `b`→`b` live patching, and full-flush waits until all threads leave translated code) (rounds 24, 72)
- **D14** — Translated blocks are shared through a process-global template arena and dropped instances return to a per-program pool re-checked or parked on reuse, requiring trusted sources to identify content by host object ctime and size (rounds 09, 14)
- **D15** — Processes of the same executable with consistent layouts share one Tier 1 instance (code groups), with shared blocks never linking to private ones and diverging members leaving the group (round 108; see `kernel/mm` D13)
- **D16** — Programs with a Go build-ID note use `X21`–`X25` (+ `X18`) as stolen registers while others keep `X16`, `X17`, `X26`–`X28` (+ `X18`), with per-set routines/templates and `APPINE_DBT_REGS` override (rounds 113, 124)
- **D17** — Code cache and native stubs use W^X double mapping (`mremap` RW + RX, RWX fallback) with RW page-table entries dropped every second, and OOM measurement must subtract not-yet-dropped bytes (rounds 09, 106)
- **D18** — Code invalidation is content-based, retiring changed blocks through forwarding stubs (relied on by uncleared jump-cache entries), unlinking dead page-bucket records on small ranges, and keeping parked blocks revivable until a full flush (rounds 11, 24, 35; 16914003)
- **D20** — Engine Rust files and `tests/lockstep` read in execution order (callers before helpers, `Cpu::reset` after `tlb_flush`), so appending new code at file ends drifts from the layout (91fabe2)
- **D21** — `appine_guard_fail` unwinds through an armed fault guard as `SIGABRT` (releasing registered locks) so engine check failures end only the guest process in release builds, aborting only when unguarded or in debug builds (e5922b1, b43ff37)
- **D22** — Locks taken under the fault guard must be registered (`appine_guard_hold_fn` for lock sets such as `template_clear`'s 64 shards), counting nesting depth past four while fault recovery releases only the four outermost entries (b43ff37)
- **D23** — `emit_lookup` routes `pc == 0` to the miss path with a `cbz` so unordered `ldp` cannot pair newly installed code with an empty slot's key 0 (b43ff37)
- **D24** — `CNTVCTSS_EL0` is read like `CNTVCT_EL0` in both tiers and `APPINE_HWCAP2_ECV` is advertised when the host has ECV so host-patched vDSOs run in guests, though the path remains untested on ECV hardware (16364c0)

## Active (full detail)

### D5: Exclusives are emulated as value compare-and-swap in both tiers
- **What**: LDXR records address, size and value; STXR does a compare-and-swap against the recorded value on the host address. Clear the local exclusive reservation at guest exceptions, system calls and asynchronous kick exits.
- **Why**: Extra memory accesses (TLB lookups) between `ldxr` and `stxr` can clear the host's exclusive monitor and livelock, and recognizing whole LL/SC sequences is unnecessary; musl hard-codes `ldaxr`/`stlxr`, so LL/SC must work even when LSE is advertised.
- **Tradeoffs**: ABA is allowed (as in QEMU), harmless for correct LL/SC use.
- **Watch out**: Tier 1's `ldaxr`/`stlxr` loop must keep only register operations between the two instructions.
- **Source**: rounds 01 (`01-m2`), 02 (`02-m1a`), 09 (`09-m7`); risk R4, d1fc9715

### D19: The engine module covers Tier 0 execution, CPU state, features and the fault guard
- **What**: engine covers the Tier 0 interpreter, native stubs, CPU state contract, CPU features, the host fault guard, shared C/Rust bindings and `tests/lockstep`, while page tables and TLB fill/flush belong to `kernel/mmu` and Tier 1 translation to `engine/dbt`.
- **Why**: The combined engine map reached 37,942 B (82.3% of one read) and kept growing with Tier 1 optimizations while mixing Tier 0 interpretation with the Tier 1 DBT.
- **Tradeoffs**: Changes to `struct appine_cpu` layout, shared instruction whitelists or fault-guard lock registration touch both `engine` and `engine/dbt` maps, pre-split Tier 1 decisions remain here, and host-vDSO CPU-feature dependencies belong to `kernel/vdso`.
- **Watch out**: Both tiers must keep identical instruction whitelists and immediate-offset fields before the TLB in `struct appine_cpu`, and bugs still open in the map include Tier 0 losing pending `ic ivau` on guest page 0 and unallocated ID registers reading 0 where Linux delivers `SIGILL`.
- **Source**: f9edf81 (bug list since updated by e5922b1, 6d1d9c0 and b43ff37); 44a21b (MMU split); 36083476; 20137e4c; d053be24; 60790c14; 39e55b4b; 8bc0e63b; e4d7bbe5

### D25: Translated FPCR and FPSR reads use different sources

- **What**: Tier 1 reads the saved guest FPCR and the live FPSR through register remapping, and keeps FPCR writes in the block when the masked value is unchanged. Tier 0, Tier 1, sigreturn and ptrace share the engine's FPCR write mask.
- **Why**: FPCR stays constant during a translated run, while instructions in that run can raise FPSR flags before the saved copy is refreshed.
- **Tradeoffs**: A changed FPCR or any FPSR write still exits to Tier 0 for masking.
- **Watch out**: Keep both FPCR masks identical and preserve NZCV, newly raised FPSR flags and reads targeting a stolen register.
- **Source**: 16914003, 26ceb37c, 8bc0e63b, 9163f493

### D26: Tier 0 reuses fetch translations and skips work with no architectural effect

- **What**: Within a run, fetches on the same executable page reuse its offset, arithmetic skips unused NZCV calculations and contiguous vector transfers copy by register after probing the full range.
- **Why**: Repeated page probes, flag calculations and per-lane copies slowed common interpreter loops.
- **Tradeoffs**: Two arithmetic branches preserve Clang performance, and interleaved vector transfers still use the general loop.
- **Watch out**: Check kicks at every instruction, discard the offset on exit, and preserve breakpoint priority, SP/ZR semantics and faults before writeback.
- **Source**: 7bc780ca

### D27: Translated barriers finish pending code invalidation before the next lookup

- **What**: Tier 1 executes DSB and ISB in the block and sends pending invalidation to the dispatcher before looking up the following PC.
- **Why**: Interpreting barriers after guest code changes repeatedly interrupted translated execution.
- **Tradeoffs**: A pending page still requires a state transfer and a page check.
- **Watch out**: Page zero needs its own path because zero is the empty pending-page sentinel.
- **Source**: 8bc0e63b

### D28: Tier 1 accelerates six length-gated libc memops through an atomic table and install-time check

- **What**: Tier 1 emits an inline NZCV-preserving `x2 >= 128` gate (`ubfx + cbnz`) for registered `memcpy`, `memset`, `memmove`, `memcmp`, `memchr` and `strncmp` entry PCs via an atomically published sorted `memop_table`, rechecks the observed kind under the DBT lock before installing untagged blocks, bypasses global templates, retires affected PCs on update and before `DbtSpace` pooling, ignores private tagged code-group blocks, untags TBI pointers with NULL checks in `exec_memop`, pops matching shadow return-stack entries on return and falls back to guest code on watchpoints or missing permissions.
- **Why**: Tight libc memory loops paid per-iteration soft-TLB checks in Tier 1, while earlier unconditional string hooks regressed short-string workloads 2.9x and unsynchronized mutable/templated registrations caused stale-block and cross-process races.
- **Tradeoffs**: Unbounded `strlen`, `strchr` and `strcmp` stay in guest code, small calls (< 128 B) pay two entry instructions, memop entry blocks (~6–12 per process) skip global templates, and each untagged block install performs one table lookup.
- **Watch out**: Keep the install-time kind check before code publication, note that a concurrent `munmap` mid-`memmove` falls back after partial writes (matching a hardware race), and a full code cache during `set_memops` triggers a full flush.
- **Source**: aad49bdd, dc43dc57, 9b3b6860, 39db4760

### D29: Concurrent memop-registration races are tested in Cargo integration tests

- **What**: `memops_concurrent_set_memops_keeps_translations_consistent` in `core/engine/tests/dbt.rs` replaces the standalone `core/engine/tests/memop_update.c` by racing worker translations against `set_memops` and `flush` across 64 rounds and 16 PCs and asserting post-race cache consistency.
- **Why**: The standalone C test included `../src/c/dbt.c` directly and was neither compiled nor executed by `cargo test` or CI, inviting silent bit-rot.
- **Tradeoffs**: Trades deterministic white-box single-step interleaving for a stochastic multi-threaded test that runs on every `cargo test` and CI build.
- **Watch out**: Very rare interleavings may not fire on every single run despite repeated concurrent rounds.
- **Source**: c8f01dbf

### D30: `struct appine_cpu::dbt_groups` holds eight spill slots before `tlb`

- **What**: Both C `struct appine_cpu::dbt_groups` and Rust `CpuState::dbt_groups` expand from 4 to 8 `uint64_t` slots, shifting `TLB_OFF` from 448 to 480 bytes within the 504-byte `ldp` immediate limit.
- **Why**: Widening Tier 1 concurrent access grouping from 4 to 8 groups (engine/dbt D5) required four more host-base spill slots before `tlb` without adding extra address arithmetic.
- **Tradeoffs**: Consumes 32 of the remaining 40 bytes of `ldp` immediate headroom before `struct appine_cpu::tlb`, leaving only 8 bytes before `TLB_OFF` hits the 496/504 limit.
- **Watch out**: Any future field placed before `tlb` in `struct appine_cpu` will trip the `TLB_OFF + 16 <= 504` static assertion and require relocating cold fields or changing TLB addressing while keeping C and Rust synced via `layout_matches_c`.
- **Source**: 011b7445

### D31: Tier 0 and guest-entry boundaries apply arm64 TBI0 (`BranchAddr`) to instruction pointers

- **What**: `untag_pc` clears bits 63:56 only when bit 55 is 0 on `BR`/`BLR`/`RET` in `interp.c` and at `cpu_run`/`appine_interp_step` entry in `cpu.c`, with unified fault-table tests in `tests/interp.rs`.
- **Why**: Linux 6.1 enables `TCR_EL1.TBI0` (`TBID0 = 0`, `TBI1 = 0`), and preserving top-byte tags on TTBR0 instruction pointers previously faulted on valid tagged targets and diverged from ARMv8-A `BranchAddr`.
- **Tradeoffs**: Normalizing `cpu->pc` at engine entry handles signals, `rt_sigreturn`, `ptrace` and direct engine callers uniformly without modifying kernel register setters.
- **Watch out**: Inspecting `cpu->pc` after a `Kick` on first entry observes the post-`ERET` untagged TTBR0 PC rather than the pre-`ERET` raw value, matching hardware and `engine/dbt`.
- **Source**: 6a979929, 8a839900

### D32: Normal-context fault-guard lookups cache the per-thread guard in `_Thread_local`

- **What**: `guard.c` caches the per-thread `struct guard *` in `_Thread_local` for normal-context calls (released by the `pthread_key_t` destructor), keeps `handler()` reading `pthread_getspecific`, fences before arming `g->armed`, and skips `write_fpcr` in `recover()` when unchanged while keeping `read_fpcr()` on entry.
- **Why**: Every guarded run and lock registration previously paid an atomic acquire load of `installed` and a `pthread_getspecific` call.
- **Tradeoffs**: Retains one non-serializing `mrs fpcr` per guarded run plus `pthread_key_t` registration for signal delivery and thread-exit cleanup.
- **Watch out**: Keep `handler()` off `_Thread_local` so signals on unrelated threads in `dlopen`'d libraries never trigger lazy dynamic-TLS allocation.
- **Source**: d2eb8398

## Superseded

- ~~Every FPCR write exits to Tier 0 in 16914003~~ → narrowed by **D25** in 8bc0e63b (2026-11-17).
- ~~Unconditional `strlen`/`strchr`/`strcmp` dispatch and unsynchronized `d->memops` storage in aad49bdd and dc43dc57~~ → replaced by **D28** in 9b3b6860 (2026-11-21).
- ~~Standalone C test `core/engine/tests/memop_update.c` in 39db4760~~ → replaced by **D29** in c8f01dbf (2026-11-21, implicit refinement).
- ~~Combined Tier 0 and Tier 1 ownership in engine (D19, f9edf81)~~ → replaced by **D19** and engine/dbt **D1** in e4d7bbe5 (2026-11-22).
