# Overlay filesystem (kernel/overlay) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: kernel/overlay"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12)
>
> Overlay decisions made before kernel/overlay was split from kernel/vfs keep their `kernel/vfs` tags and live in `.agents/decisions/kernel/vfs.md`: D39, D40 and D49; those tagged `kernel/process` live in `.agents/decisions/kernel/process.md`: D45 and D46.

## Active

### D1: kernel/overlay is split out of kernel/vfs
- **What**: kernel/overlay owns `vfs/overlay.rs`, the overlay methods of `Vfs`, `open_overlay`, `follow_copy_up`, `copy_up`, `unlink_overlay`, `link_overlay` and `rename_overlay`, and the overlay options and node sweep in `syscall/mount.rs`: mounting and layers, lookup and node lifetime, merged readdir and stat, copy-up, namespace changes and opening. The reported redirect_always_follow parameter is N, matching lookup behavior.
- **Why**: overlayfs is the largest part of the VFS with its own file and Linux counterpart (`fs/overlayfs`), like kernel/fuse, and the vfs map could no longer be read in one Read.
- **Tradeoffs**: The invariants on `Node::ino` and node holds bind code in kernel/vfs too, which keeps only a pointer to them, and every overlay commit so far also changed the VFS core, so overlay changes read two maps.
- **Watch out**: A new file operation that skips the overlay rules because they now live in another map.
- **Source**: 5f327de; 01bfd882; 20137e4c; d053be24; 39e55b4b, 6fcaff37

### D2: Copy-up isolates lower files that can still change

- **What**: Every file copy-up uses `syscall::fs::copy_up`, copying data immediately under a live host writer or writable mapping and otherwise sharing while rehoming non-COW lower writers. Probe both backing objects and copy pinned lower files, checking write denial on the overlay and its backing even on procfs reopens.
- **Why**: Unconditional sharing let later lower-file writes change the copied-up upper file.
- **Tradeoffs**: A live writer or writable mapping forces a full copy.
- **Watch out**: The writable-mapping check scans tasks; the superseded pre-split sharing rule is kernel/vfs D40, with related clone rules in D45.
- **Source**: df6ee70, 01bfd882; 20137e4c; d053be24; 39e55b4b, f495f2cc, 68130f74, f985c1f8

### D3: Overlay upper tmpfs accounts for copy-up and namespace changes

- **What**: Compute the upper device and reserve its pages and inodes before creation or copy-up, charging copied lower pages.
- **Why**: An overlay can exhaust its tmpfs upper even when its lower layer has space.
- **Tradeoffs**: A copy-up may consume the full data charge.
- **Watch out**: Check permissions and room before creating upper parents.
- **Source**: ab694aab, 3a90a0ef, 71790fde

### D4: Overlay identity survives node eviction and cold lookup

- **What**: Evict unheld leaves with headroom, persist validated origin and impure markers during upper changes and detach mount points when their overlay target is removed or replaced.
- **Why**: A cold directory lookup or rename must preserve visible inode identity and mount lifetime.
- **Tradeoffs**: Origin metadata and temporary holds consume storage.
- **Watch out**: Validate recorded origins against the mounted lower roots and pin raw node IDs across waits.
- **Source**: ec233137, 4e279fa9, eea43393, ab417943

### D5: Overlay instances cache origin, impure and opaque upper xattrs with rollback-safe updates

- **What**: Cache three-state `origins` (`Absent`/`Invalid`/`Valid`), `impure`, and `opaque` private xattrs per overlay `Instance`, evicting `impure` inside external transactions (`mark_impure`), publishing `true` only after commit, and clearing entries via `forget_upper` on upper removals or overwrites.
- **Why**: Positive-only `origins` caching and uncached `impure`/`opaque` queries ran SQLite `get_xattr` on every upper lookup and cold `readdir` entry.
- **Tradeoffs**: Each mounted overlay instance retains modest per-mount cache memory proportional to accessed upper inodes until unmount or remount.
- **Watch out**: Any new direct upper-layer xattr mutation bypassing `overlay.rs` helpers must invalidate the matching `Instance` cache entry.
- **Source**: b38371ff

## Superseded
- ~~Always sharing the lower regular-file object on copy-up (df6ee70; kernel/vfs D40)~~ → replaced by **D2** in 01bfd882 (2026-11-12)
- ~~Positive-only `origins` caching and uncached `impure`/`opaque` SQLite lookups (D4 in part, eea43393)~~ → replaced by **D5** in b38371ff (2026-11-27)
