# inotify (kernel/inotify) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: kernel/inotify"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12)
>
> inotify decisions made before kernel/inotify was split from kernel/vfs keep their `kernel/vfs` tags and live in `.agents/decisions/kernel/vfs.md`: D13, D46 and D51.

## Active

### D1: kernel/inotify is split out of kernel/vfs
- **What**: kernel/inotify owns `syscall/inotify.rs`: instances, watches and per-user limits, the registry keyed by store inode or by overlay, FUSE or cgroup2 node key, the hooks at fsnotify's call sites, the `Opened` record and the replay of the Files app's and cgroup2 files' changes; the file's doc comment now says cgroup2 files do get events (through `kernfs_modified`). New inotify instances install their descriptor under the caller's limit.
- **Why**: inotify is the largest of the parts moved out of vfs, with its own registry, limits and replay of outside changes, and the vfs map (99 KB) could not be read in one Read.
- **Tradeoffs**: Hooks span vfs and fuse, cgroup change producers belong to kernel/cgroup, and procfs still owns limits and fdinfo, so producer changes consult both maps.
- **Watch out**: A filesystem change added without its hook, now that the hook list lives in another map.
- **Source**: cc160c1; cf029f9a; d053be24; 39e55b4b, 576501d3

### D2: A file or parent watch keeps a cgroup event observation alive

- **What**: When an open fd no longer holds the observation counter, probe the exact cgroup file key and its parent directory key. Use the cgroup node key for child events delivered to a parent directory watch.
- **Why**: Copying unrelated watch keys made checkpoint work grow with the entire registry.
- **Tradeoffs**: A dirty observation without an open fd costs up to two watch lookups.
- **Watch out**: File-only and directory-only watches must each preserve the observation.
- **Source**: 0f5c5132, 865d24c1

### D3: Inotify follows the open dentry through rename, unlink and last close

- **What**: Weakly index open records by filesystem identity, retain unlink state for IN_EXCL_UNLINK and replay unsharing to descriptions independently of successful open events.
- **Why**: Scanning installed fds misses queued references and reopened or transferred files.
- **Tradeoffs**: O_PATH descriptions carry identity with a zero close mask.
- **Watch out**: Named pipes, memfds and proc reopens must retain the correct node identity and last-close behavior.
- **Source**: ab694aab, 523ae8a9, 45035024, f495f2cc, 93a9b204, 11816722, 30e5002e, d96f5d63

### D4: nsfs inotify watches follow stashed-inode pin lifetime

- **What**: Keep a weak `NsPinInner` per namespace ID with a monotonic `ns_instance` in `Registry::ns_stashed` held strongly by `OpenFile::ns_pin` and `Mount::ns_pin`, comparing `(ns_id, sdev, ns_instance)` for `PseudoNode::NsFile` watches.
- **Why**: Matching nsfs watches by namespace ID alone reused one watch descriptor across unpinned `/proc/self/ns/*` calls, diverging from Linux `fs/nsfs.c`.
- **Tradeoffs**: Adds an `Option<NsPin>` field to `OpenFile` and `Mount` (propagated across mount copies and clones) rather than confining all state to `inotify.rs`.
- **Watch out**: Every future path that opens an nsfs file description or creates an nsfs mount must call `pin_ns` or clone an existing `Mount::ns_pin`.
- **Source**: b98eb140

### D5: Open records use a recycled slab with intrusive per-inode lists and idle-registry fast paths

- **What**: Store `OpenRecord` entries in a freelist-recycled `Vec` slab linked by intrusive per-inode doubly-linked lists in an `InodeHasher` `HashMap`, represent `Opened` as `(Weak<RefCell<Registry>>, u32)`, skip borrowing `Registry` in `file_io_event` when idle, and deduplicate watch cleanup in `Watches::retain_watchers`.
- **Why**: Allocating `Rc<OpenRecord>` and mutating `Registry::opened: BTreeMap` on every `open`/`close` slowed `openbench` by 6–27% even with zero watches.
- **Tradeoffs**: Replaces `Rc` and `BTreeMap` with manual slab indices and doubly-linked list pointers.
- **Watch out**: Slot-index or prev/next pointer bugs during clone, drop, or unlink could corrupt the freelist or drop late `IN_CLOSE_*` events.
- **Source**: b38371ff

## Superseded

- ~~Relying on successful open replay to move shared mappings~~ → replaced by **D3** in f495f2cc (2026-11-24)
- ~~Finding unlink and rename holders by scanning installed open files~~ → replaced by **D3** in 93a9b204 (2026-11-24)
- ~~Per-open `Rc<OpenRecord>` and `BTreeMap` open-record tracking (D3 in part, 93a9b204)~~ → replaced by **D5** in b38371ff (2026-11-27)
