# Knowledge loop (knowledge-loop) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: knowledge-loop"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12)

## Active

### D1: Changes are recorded in commits and module maps; `docs/rounds/` is frozen after round 208
- **What**: Each change is recorded in its commit (Decision blocks; measurements, survey results and testing in the body) and in the module maps, with deferred work and unfixed problems in a map's "Confirmed bugs / technical debt" or "Open questions" and pitfalls in its "Invariants and gotchas"; the old rounds get no new rounds, index rows or "→", an old deferred item moves into its map only when it comes up again, `docs/milestones.md` keeps the cross-change reference, and work with no diff gets a short doc only when worth keeping.
- **Why**: Each change was written down three times (a round, a Decision block, a map refresh), and the round was the only copy that went stale and needed "→" marks added back into old files.
- **Tradeoffs**: The records are in English instead of Chinese; commit bodies cannot be amended, so the maps carry the live state; a change spanning many commits has no single narrative; open items in old rounds that were never moved can be forgotten.
- **Watch out**: Every Decision block created by commit-context must carry MODULE, WHY, ALTERNATIVES, CHOSEN, TRADEOFFS and RISKS, with SUPERSEDES for replacements; old deferred items are found only by grepping; map debt sections grow if done entries are not removed.
- **Source**: 511866b (known flaky tests applied in 093cb96); 36083476; 82dba168; d053be24; 39e55b4b

### D2: Distills turn migration scopes and implicit supersessions into entries by recorded judgment
- **What**: Each migration commit's module-scope Decision becomes one scope entry, a block replacing part of an entry is merged into the chain's latest entry, supersessions without a SUPERSEDES line or recorded under another module are listed under Superseded and flagged at review, hand-seeded entries naming removed code get minimal edits with the new hashes in Source, and "Last distilled" says which entries were seeded by hand; a fix spanning modules gets one entry per module that refer to each other, blocks sharing one approach form one cluster entry, entries a later block refines are edited with its hash added to Source, scope entries' Watch out names only bugs still open in the map, and a revert without a Decision and replaced parts of entries or clusters become Superseded lines; distills read develop only, a block that only changes a map's bug records is folded into the scope entry's Watch out and Source, and a commit recording a deviation joins the cluster of the commit that fixes it; a snapshot past about 30 full entries gets a constraint index of one-line bullets whose full text a named commit holds, while entries edited or added in a batch stay in full, an indexed entry a batch edits moves back to full detail and leaves its pinned range, and entries found stale outside the batch are corrected against the code and maps before writing; a block covering several topics is split by topic into the entries it extends, a module split out of another gets a snapshot whose D1 is its scope entry and whose preamble lists the parent's IDs, and earlier distills updated child maps' See lines, a historical practice prohibited by the current snapshot-only skill.
- **Why**: Five snapshots had Decision blocks committed after their hand seeding and knowledge-loop had none, distilling from commits for the first time raised cases the snapshots had no rule for, and the first distill of every module added cross-module fixes, test-only blocks and refinements of existing entries; the fourth distill (89 blocks from 44 commits) met commits only on explore/hybrid, map-only bug records and recording commits followed by their fixes, and the fifth (130 blocks from 51 commits) edited indexed entries and found 12 stale entries outside the batch, and the sixth (41 blocks from 25 commits) created snapshots for 14 modules split out that day and met blocks covering several topics.
- **Tradeoffs**: Scope entries repeat part of `MODULES.md`, one-sentence fields and cluster entries drop details that stay in the source commits, a cross-module fix is spread over several snapshots, implicit and cross-module supersessions rest on judgment rather than SUPERSEDES lines, the explore/hybrid commits' Decisions are in no snapshot, and earlier See-line edits changed maps outside map-module; current distills report those follow-ups without editing maps.
- **Watch out**: Edited hand-seeded entries keep their IDs, which later distills must preserve, and possible merges left separate (android/setup D13 and D5, kernel/netns D10 and D4) may want merging later, as may android/terminal D22 into D18, D14 with D15 and android/setup D15 into D12, and the further pairs c87fd21 left separate; the 2026-11-08 distill gave kernel/vfs, kernel/mm, kernel/process, android/terminal and harness constraint indexes, and the first 2026-11-10 distill (c87fd21) indexed kernel/procfs and a second kernel/process batch, both pinned to 260512d; the c1a37da distill (5d18308) indexed a second android/terminal batch pinned to c1a37da, the 2026-11-12 distill (67d40c70) a third kernel/process batch pinned to 0553229, and kernel/signal and kernel/net, at 27 full entries each, are next; the c1a37da distill's implicit supersessions (android/service D5, android/terminal D36, D40, D47 and D50) rest on judgment, and the children's lists of inherited IDs must follow later distills of their parents.
- **Source**: 8349911, 4b0adb9, e868881, bfb1e44, 260512d, a536142, c87fd21, 599804a, 5d18308; 67d40c70; 20137e4c; d053be24; 39e55b4b

### D3: A map's Maintained line is one sentence
- **What**: A map's Maintained line names in one sentence the areas changed since its full mapping, the latest change last, and says what the full mapping predates; map-module asks for this on every targeted refresh, while full refreshes reset the Verified baseline after fresh source research and independent claim verification for maps at or near the post-baseline drift threshold.
- **Why**: The terminal map's line had grown to 2626 characters over 22 targeted refreshes, a changelog repeating commit subjects and naming features later removed, and heavily drifted baselines need periodic full re-verification against current source.
- **Tradeoffs**: Maps no longer list every targeted refresh (git log holds them), and source-only full refreshes still leave device-dependent behavior and exhaustive runtime event ordering to runtime checks.
- **Watch out**: None outstanding for the Maintained-line format (every map now uses the current wording), while implementation bugs surfaced during full refreshes stay recorded in each map until fixed in follow-up commits.
- **Source**: a75981b (applied to the service map in c2f4912 and to five more maps in 3f3db11), f7f79a2a, 48f338d6, 1dbca89e

### D4: Files with no module are registered when found
- **What**: A kernel file without a module gets a registry line in the module that owns its behaviour (`syscall/posix_timer.rs`, `syscall/mod.rs`, `bug.rs` and `syscall/misc.rs` outside kernel/signal's handlers in kernel/process, `errno.rs` and `debug.rs` in kernel/signal, native AIO in kernel/vfs (since kernel/aio), `oom.rs` (since kernel/oom) and `uaccess.rs` in kernel/mm, SysV IPC and message queues in a new kernel/ipc), its module doc comment names that map, and maps written before later splits are repointed at the current owners.
- **Why**: `MODULES.md` named a `posix_timer.rs` that never existed, about 2300 lines of IPC plus AIO, the OOM killer and the dispatcher's, bug handling's, errno's and debug log's files resolved to no module, so their behaviour and defects were recorded nowhere, and the registry, maps and doc comments named different owners for some.
- **Tradeoffs**: Message queues and cgroup memory enforcement are spread over several maps, and `syscall/misc.rs`'s owner is a default whose membarrier, umask and capability and seccomp prctls other maps describe.
- **Watch out**: A new kernel file needs a registry line or no map covers it, and owners split inside one file (`syscall/misc.rs`, `syscall/mod.rs`, `vfs/mod.rs`, `syscall/mount.rs`) exist only in the registry's wording.
- **Source**: 4140c75, 3f3db11, d2b1dc3 (owners since moved by cc160c1 and 44a21b7; corrected in 5d18308)

### D5: Survey conclusions without a diff go into one doc that points at the maps
- **What**: The two Starnix surveys live in one English `docs/starnix.md` whose ranked ideas point at the deviations recorded in the maps, with a "Taken so far" table updated with the fixing commits and no milestones index row, since rounds are frozen. The differential selection links from the survey overview, with port order and repeated measurements recorded in milestones.
- **Why**: The first survey's conclusions lived only outside the repository, and the second survey's ideas had no code change to carry them.
- **Tradeoffs**: The ranked ideas and run counts are a snapshot at 8dc166b, and ideas that become work must move into maps or commits.
- **Watch out**: The doc and the maps drift unless "Taken so far" is updated each time an idea is taken, and that update relies on exact matches of map lines.
- **Source**: 612d238, 2d78930, 231480cb

### D6: Prose names the system Appine; the frozen rounds keep the lowercase name
- **What**: Every sentence or title naming the system in `docs/design`, `docs/milestones.md`, `docs/starnix.md`, `android/README.md`, the maps, the decisions, source comments, crate descriptions and `appine-run`'s help says Appine, identifiers and labels stay lowercase (harness D23), and `docs/rounds` stays as written.
- **Why**: The app had been renamed Appine with developer docs and comments left for a separate pass, so the README said Appine while the design doc, maps and decisions still said appine.
- **Tradeoffs**: The frozen rounds and old commit messages keep the lowercase name, so a search for the product name has to be case-insensitive.
- **Watch out**: New prose, maps and Decision blocks must say Appine, and a distill copying old commit text into `.agents/decisions` can bring the lowercase name back.
- **Source**: 248d79f

### D7: Maps that outgrow one Read are split along subsystems with files of their own
- **What**: Oversized maps are split along clean file or contiguous-section boundaries—or condensed in place when a file is already heavily partitioned (`android/vtcore`)—to stay below 80% of the 46,080 B single-read budget (adding `android/settings`, `android/reports`, `engine/dbt`, `kernel/bug`, `kernel/cow`, `kernel/hostmem`, `android/vtgestures` [registered in `02dfe2ec` without a `knowledge-loop` tag], `kernel/rawicmp`, `kernel/route`, `kernel/sysctl` and `kernel/sysfs` after the earlier splits), while pre-split decisions keep their parent tags and live in the parent snapshot referenced by ID on child `See` lines and snapshot preambles. The fileio split leaves historical IDs with the parent, while registry boundaries and limitations follow the implemented owners.
- **Why**: Maps near or past 46,080 B truncate on a single read (especially with line numbers), and moving pre-split decisions into child snapshots would not survive the next parent distill.
- **Tradeoffs**: The registry has grown to 68 structural modules (plus `knowledge-loop`), boundaries run inside shared files (`vfs/mod.rs`, `syscall/fs.rs`, `syscall/mount.rs`, `syscall/proc.rs`, `syscall/misc.rs`, `syscall/fast.rs`, `syscall/mod.rs`, `syscall/net.rs`, `kernel.rs`, `machine.rs`, `mm.rs`, `pseudo.rs`, `engine.h`, `core/engine/src/lib.rs`, `VtView.kt`, `vt.c`), and cross-boundary edits consult multiple maps and parent/child snapshots.
- **Watch out**: Inherited decision ID lists on child `See` lines and cross-module pointers need coordinated updates during future distills and refreshes, and a split preserves the parent `Verified` baseline rather than performing an independent full audit.
- **Source**: c008749, d2b1dc3, b320f8e; 5d18308 (made the general rule); 67d40c70 (netlink split); ce193253 (open-file split); 20137e4c; 81f60bef; 9c6c51a7; 9015b244, cf029f9a, 8a17e4a9; d053be24; 60790c14; 39e55b4b; e4d7bbe5; 610d623f; e0719858, 4fcd3e13, 68b70897, f495f2cc

### D8: Agent guidance and module knowledge use shared repository paths

- **What**: AGENTS.md and `.agents/MODULES.md`, maps and decisions replace agent-specific entry points, with README as the development entry point and skills/templates using the same paths, while pinned Git paths, frozen rounds and ~/.codex session storage remain intact.
- **Why**: Repository knowledge needs to work across agents without Claude- or Codex-specific storage assumptions.
- **Tradeoffs**: Tools must honor explicit repository paths even when their defaults name .claude or .codex.
- **Watch out**: Keep historical Git paths and frozen records intact when updating live links, keep new components registered and mapped (including the website and its Android policy link), and check imported skills for obsolete project .codex paths.
- **Source**: 36083476; 20137e4c; 20177d4d; bd580227, 8e211142, c7309c0c, 165b5f99; d053be24; 39e55b4b; 5b213f09

### D9: Shared skills use neutral interfaces and explicit commit authorization

- **What**: Knowledge skills omit token/cost accounting, manage only root AGENTS.md, describe editing and delegation by role, require independent multi-agent full mapping, keep distill-module explicitly invoked and let ordinary commit requests authorize commit-context.
- **Why**: Shared workflows should work across agents without host-specific syntax or coupling selection to permission.
- **Tradeoffs**: Other instruction entry points require manual integration, hosts without subagents cannot complete full mapping, and unavailable map-module leaves maps reported stale.
- **Watch out**: Skill selection alone never authorizes a commit; OpenAI metadata and cross-skill dependencies remain, and reviewing or editing a skill grants no commit permission.
- **Source**: 83a79ec0, fee21cbd, 82dba168, 5aa15a10; d053be24; 39e55b4b

### D10: The reasoning skill retains guidance without its optional evaluation bundle

- **What**: fable-mind keeps its instructions, references and metadata while the optional evaluation definitions, fixtures and grader are removed.
- **Why**: The user wanted the reasoning guidance without maintaining evaluation projects.
- **Tradeoffs**: Future evaluation runs require restoring the bundle from Git.
- **Watch out**: The removed tools are unavailable in the working tree; remaining skill files have no references to them.
- **Source**: 51cc5778; d053be24; 39e55b4b

### D11: Retired performance surveys remain recoverable with their measurement conditions

- **What**: Recover the Tier 0 tables with `git show 7bc780ca:docs/tier0-performance-survey.md` and Tier 1 tables with `git show 8bc0e63b:docs/tier1-fallback.md`, and read the definitions of translation fallbacks and interpreter exits beside --stats in the profiling instructions.
- **Why**: The surveyed implementation changes and their measurements are already recorded in Git.
- **Tradeoffs**: Per-workload comparisons require opening the historical survey files.
- **Watch out**: Use the original compiler, workload and run conditions when comparing measurements.
- **Source**: 426949bb

### D12: Mobile GPU design evidence and hardware acceptance criteria live in `docs/mobile-gpu.md`

- **What**: Record the candidate Venus architecture, distribution package evidence, hardware validation plan and device survey results in standalone English `docs/mobile-gpu.md`.
- **Why**: Implementation and cross-device GPU evaluation need package evidence and acceptance criteria preserved together in the repository rather than chat.
- **Tradeoffs**: Package and source references describe the specific versions examined.
- **Watch out**: Android driver memory mapping and vendor shader defects can limit device coverage and must stay tied to tested hardware.
- **Source**: ab1c4a7e

### D13: Mobile GPU sources belong to existing module owners rather than a dedicated GPU module

- **What**: Assign mobile GPU implementation, build and test sources across the existing kernel, Android and harness modules in `.agents/MODULES.md` and record measured hardware limits in their maps.
- **Why**: GPU support integrates into boot, open files, mm, poll, procfs, vfs, native, build, tests and harness rather than forming an isolated subsystem.
- **Tradeoffs**: Changing the device contract can touch several module maps.
- **Watch out**: Hardware capability claims in maps must track only the devices actually tested.
- **Source**: 87c7d9db

## Superseded

- ~~`errno.rs`, `uaccess.rs`, `debug.rs`, `bug.rs` and `syscall/mod.rs` have no module (D4's watch-out, 4140c75)~~ → replaced by **D4** in d2b1dc3 (2026-11-10)
- ~~Tool-specific /commit-context usage as the required recording mechanism in D1~~ → replaced by **D1 and D8** in 36083476 (2026-11-13, implicit refinement)
- ~~D1 agent-independent requirement that every commit carry structured Decision fields~~ → replaced by **D1 and D9** in 82dba168 (2026-11-15)
- ~~AGENTS.override.md selection retained in 83a79ec0~~ → replaced by **D9** in fee21cbd (2026-11-15)
- ~~Host-selection wording, fixed explorer/followup_task assumptions and the direct-verification fallback in fee21cbd/36083476~~ → replaced by **D9** in 82dba168 (2026-11-15)
- ~~Explicit-name-only invocation for commit-context in 82dba168~~ → replaced by **D9** in 5aa15a10 (2026-11-15)
- ~~Aiming at ~45 KB per map in D7~~ → refined to below 80% of the 46,080 B single-read budget by **D7** in e4d7bbe5, 610d623f and e0719858 (2026-11-22, implicit refinement)
