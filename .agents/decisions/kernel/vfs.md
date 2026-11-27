# VFS (kernel/vfs) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: kernel/vfs"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12). D1–D14 were seeded by hand from the retired design
> doc §8.2–§8.3 and §11.1 and the round records in `docs/rounds/`, so their sources cite rounds;
> later entries cite commits.

## Constraint index (compressed active decisions)

> Full text of D1, D4, D5, D7, D9–D11, D14, D16–D21, D23, D24 and D26–D29: `git show 248d79f637c8f05dadc6ef9fdb0733c568c6a468:.claude/decisions/kernel/vfs.md`
>
> Full text of D25, D30, D31, D33–D35, D37–D38 and D41: `git show 260512d530d136e70f10564e248f7db876f07062:.claude/decisions/kernel/vfs.md`

- **D1** — Nodes carry the mount they were reached through (`Node::Store(mount, ino)` and `Node::Pseudo(mount, node)`, Linux's `struct path`), so one directory mounted twice is two nodes, `..`, getcwd and `/proc/<pid>/{cwd,root,fd}` climb back through their own mount points, and mount ids are never reused (rounds 105, 116)
- **D4** — `/dev` (tmpfs with standard nodes), `/dev/pts`, `/dev/shm` (executable and unlimited for JIT double mappings), `/dev/mqueue` and `/run` are mounted at boot with systemd's options so OpenRC leaves them alone, `/tmp` stays a store directory, and the store's own `/dev` keeps the same nodes underneath (rounds 05, 184)
- **D5** — Opening any character-device node selects its in-process implementation by device number wherever the node is (unknown numbers are `ENXIO`), and nodev mounts and the cgroup BPF device filter must be checked before dispatch (rounds 04, 05, 184; a26c8b4)
- **D7** — Of the new mount API only open_tree, move_mount and mount_setattr exist, following 6.1's `fs/namespace.c` because crun sends every bind through them, while the fsopen family stays `ENOSYS` (callers fall back to mount(2)), idmapped mounts are `EINVAL` and an unattached clone's anonymous namespace is torn down only at the next collection (round 168)
- **D9** — openat2's `RESOLVE_*` flags follow 6.1's `fs/namei.c` inside Appine's own walker because Android's app seccomp blocks the host's openat2, `RESOLVE_CACHED` only rejects create, truncate and tmpfile opens, and `exe` counts as a magic link here although it is followed by content elsewhere (round 144)
- **D10** — Mounting over `/proc/<pid>/fd` entries is `ENOENT` as in Linux 6.12, one of the deliberate exceptions to the 6.1 contract to count when deciding on a contract upgrade (round 105)
- **D11** — Regular-file reads and writes release the BKL for host `pread`/`pwrite` behind an `f_pos_lock` emulation (one position-based operation per open file, released between 64 KiB chunks), `O_APPEND` writes stay under the BKL, and position-lock waiters listen on the lock wake channel only (rounds 24, 53, 89)
- **D14** — `protected_symlinks` and `protected_hardlinks` default to 1 and are enforced as 6.1's `may_follow_link` and `may_linkat` do (`protected_regular` and `protected_fifos` are not implemented), and the credential-less no-BKL `Cached` walk must keep handing such links to the normal walk (round 185; 12c4afa)
- **D16** — timerfd parses timespecs through the shared `timespec()` helper (kernel/process D15) and the lease-break deadline clamps at KTIME_MAX, so neither can overflow `Instant` (96cd8bd)
- **D17** — Open keeps `O_DSYNC` and `__O_SYNC` (`KEPT_AT_OPEN`), so `F_GETFL` shows them, `F_SETFL` cannot clear them and `write_sync` syncs after each write (sendfile, copy_file_range and splice still do not), and `CLOSE_RANGE_UNSHARE` copies the fd table only above two references, like `unshare(CLONE_FILES)` (68cafce)
- **D18** — A chown that would clear the set-id bits of a store non-directory needs the owner or `CAP_FOWNER` (`EPERM` otherwise), following 6.2's `setattr_should_drop_sgid` as an exception to the 6.1 contract, while writes still clear setgid only with group exec as 6.1 does (ce7d0d8)
- **D19** — `exe_file` and `open_pseudo` find a pid node's process with `pseudo::group_of`, so `/proc/<tid>` works like its thread group's, and code treating a pid node's id as a thread-group id misses threads (kernel/procfs D12) (173da76, 45be9fae) (20137e4c; d053be24, 39e55b4b)
- **D20** — The VFS's Rust files read in the order their code runs (syscall/fs.rs from opening to its helpers, `impl Vfs` top-down), and code appended at the end of a file drifts from that order (866b1cf)
- **D21** — Loop devices in `Kernel::loops` forward I/O through the backing open file at the device offset with 6.1's loop and block ioctl errors (no page cache, partitions, discard, mmap or mounting), differential cases must wait for native kernels after 6.1 to unbind an autoclear device, and a loop over a loop keeps the lower device open until the upper one is unbound (a26c8b4)
- **D23** — A timerfd keeps an `Expiry` on its clock (kernel/process D19), and with `TFD_TIMER_CANCEL_ON_SET` a later realtime clock jump counts as one expiration and makes the next read return `ECANCELED` once while the timer stays armed (63b9e59)
- **D24** — read_file and readv copy out through `Cx::write_user_partial`, growing a stack below the buffer first, and return the bytes copied (rewinding a regular file's position past the uncopied part) instead of `EFAULT`, though pipe and terminal data past the fault is still consumed (e03c0cc)
- **D25** — F_SETOWN follows Linux's `f_setown` on global numbers (`INT_MIN` `EINVAL`, an unknown number `ESRCH`, 0 removes the owner, the setter's uids kept), owners are translated once by the dispatcher's `pid_args`, and lock ranges use an inclusive last byte in `flock64_to_posix_lock`'s order; syscalls listed in `pid_args` must not translate again (09188ec, 5656ada, f6a82cc)
- **D26** — The vfs ioctl dispatch passes `file.readable()` to `tty::ioctl` so TIOCSCTTY can require read access (kernel/tty D14) (838b5b4)
- **D27** — memfd_create passes the caller's fsuid and fsgid (kernel ids) to the store's `create_anonymous` (kernel/store D14) (907b82b)
- **D28** — linkat follows 6.1: `AT_EMPTY_PATH` without `CAP_DAC_READ_SEARCH` is `ENOENT` (6.10's exception for the opener is not followed), a deleted file reached through `/proc/<pid>/fd/N` links as its store node (memfds stay `EXDEV`), `protected_hardlinks` reads the database row, and the store refuses a new name for an nlink-0 inode (kernel/store D15) (c108be0)
- **D29** — sendfile returns `EINVAL` for an `O_APPEND` output that is not a pipe, after the input checks, as Linux's `do_splice_direct` does (298cadb)
- **D30** — `fs.mount-max` (`Kernel::mount_max`) refuses with `ENOSPC` a mount, a bind with its copies or an attach that would pass it, and whole-table walks index the table once per operation; a loop looking each mount up in the list is quadratic again, and `reach` holds a shared borrow of the table while it calls the store and `Pseudo` (0a4712e)
- **D31** — splice, tee and vmsplice follow 6.1's argument order and nonblocking rules (`SPLICE_F_NONBLOCK` or either end's `O_NONBLOCK`), and a splice into a pipe waits for whole free pages and pushes what it read whole, so a pipe with less than a page free is full to them (5530624)
- **D33** — truncate, ftruncate and `O_TRUNC` unmap a shrinking file's dropped tail in every address space (`Mm::file_truncated`, kernel/mm D5) before the host file shrinks; if the host truncation then fails, accesses past the requested length get `SIGBUS` until remapped (400817d)
- **D34** — Open modes follow `build_open_how` and `OPEN_FMODE`: an `O_PATH` fd keeps only `O_PATH`, access mode 3 neither reads nor writes (a FIFO `EINVAL`), `O_TRUNC` on a directory is `EISDIR` and on an `O_RDONLY` regular file truncates, and a synthetic file opened with `O_TRUNC` needs write permission (5d23297, f6a82cc)
- **D35** — A read-only remount or mount_setattr is `EBUSY` while regular or synthetic files on that mount (without `MS_BIND`, on any mount of the same device) are open for writing or back a loop device; files held only by mappings or in-flight `SCM_RIGHTS` messages do not count (b43ff37)
- **D37** — fallocate, `SEEK_DATA`/`SEEK_HOLE` and `st_blocks` use the host object's holes and allocation (kernel/store D16), falling back to writing zeros where the host lacks a mode, and mode 0 really reserves storage (5d7b936)
- **D38** — `security.capability` changes need `CAP_SETFCAP` over the file's mapped owner and group, setxattr stores the value converted for the writer's namespace and getxattr returns the reader's view (kernel/cred D12); an owner unmapped in the caller's namespace refuses changes with `EPERM` (2aca260)
- **D41** — timerfds and signalfds open `O_RDWR` as `anon_inode_getfd` does, and writes to them are kept out by the write path's `EINVAL`, not by `writable()` (e6bfdf8)

> Full text of D44, D46, D54, D55, D57, D58: `git show 3ad68857ee83ca9bb227d4d55db8611416ae226b:.agents/decisions/kernel/vfs.md`

- **D44** — FIONREAD checks stat on regular file kinds before ioctl dispatch (including procfs and queues), host fds delegate, unhandled paths are counted, and FIOQSIZE remains unsupported (99b4216)
- **D46** — Use overlay keys for overlay notifications and visible fdinfo identity, with upper creation events and a known extra IN_DELETE_SELF on last unlink after an overlay hard link (d7076fc)
- **D54** — Every pipe consumer must await a splice-out that can release the BKL, including nonblocking readers, and a FUSE daemon consuming that same source can deadlock (9b99931)
- **D55** — Fd reservations hide uninstalled slots, make explicit replacement return EBUSY via install_at, disappear on table copy and can be filled after a limit is lowered, so musl dup2 can keep retrying (now kernel/files) (4e01f78b; 20137e4c; d053be24; 39e55b4b)
- **D57** — Create mode-0666 character node /dev/dri/renderD128 (226:128) only at GPU-enabled boot and keep its number aligned with kernel/procfs D42 (87c7d9db)
- **D58** — write_file_owned sets new-file uid/gid while write_file passes 0/0, and callers creating user-owned files must provide existing parents or newly created directories will belong to root (0755) (125f3d00)

> Full text of D2, D6, D12, D13, D22, D42, D43, D47, D49, D51, D53 and D56: `git show 1ea0c1ef9d686d7126bec2029fc928d4f6fdfc6b:.agents/decisions/kernel/vfs.md`

- **D2** — Mount records live in one global table keyed by id with a task's mount namespace in `FsContext`, unused namespaces and detached mounts are collected by scanning tasks (D48), and mount operations must verify the mount belongs to the caller's namespace (`check_mnt`) (round 118; 922e015)
- **D6** — Mount propagation follows `fs/pnode.c` peer/master/unbindable groups with `plan_attach` and locked-mount checks; bulk mount operations must use `Index` and `GroupAlloc`, and a mount placed on another mount's root has its `at` in the parent mount (970cf6a, 66b765c)
- **D12** — Advisory locks and leases are in-process (nothing reaches host locks), keyed by store inode or by overlay/FUSE node (`OpenFile::lock_ino`) for locks and store inode for leases, and closing a file with locks or leases must wake the dedicated lock channel (rounds 08, 19, 53; 2a244d2, c008749)
- **D13** — inotify runs entirely in the kernel after store mutations in Linux's fsnotify hook order (plus Files app replay under the BKL and cgroup2 watches, D46/D47/D51), keeping the no-watch cost to one emptiness check per event (rounds 11, 19, 201; d7076fc, affb1e2)
- **D22** — Vectored I/O matches Linux's `read_iter`/`write_iter` split (sockets via kernel/net `rw`, pipes/PTYs/eventfds and non-regular host stdio in 64 KiB stream operations, positional/regular host files per-iovec), and open-file borrows must end before any blocking socket or pipe wait (b295644, a36860b)
- **D42** — Pending `IOCB_CMD_POLL`s complete in io_getevents, io_cancel and the timer thread (`Kernel::aio_polls`) with `io_submit` matching 6.1 checks, `append_offset`/`write_sync` on writes and `has_iter`/`has_fsync` tables that must track new file kinds; queuing an event must wake the context wait object (fffe1c1)
- **D43** — `/proc/locks` and fdinfo `lock:` lines are formatted from the lock tables in `locks.rs` using `FlockHolder`/`Lease` tgids, fstat dev/ino and inclusive ranges, scanning fd tables for POSIX locks (39ea702)
- **D47** — FUSE mounts use a fourth node kind (`Node::Fuse`/`Fs::Fuse`), syscall `FUSE_RETRY` walks, whole-message `/dev/fuse` I/O, top-two-bit lock/inotify keys and `Vfs::mount_nodes`/`Registry::watched` sweeps; handlers must not mutate state or swallow errors before a walk, and new long-lived `Node` holders must join the sweep (c008749, 3957a03)
- **D49** — Removed overlay nodes become timestamped orphans swept in batches once unreferenced by roots, cwds, mounts, watches, `Hold`s or pre-removal syscalls (except `waits_on_files_only`); every new overlay-node holder must take a `Hold` or join the sweep to prevent use-after-sweep panics (0d82807, 9590431)
- **D51** — inotify keys reserve top bits per node kind (store `< 2^62`, cgroup2 `01`, overlay `10`, FUSE `11`), every `of_key` check must test both top bits, cgroup2 nodes are never treated as unlinked, and `mounts_changed` bumps and wakes mount pollers (affb1e2)
- **D53** — `Mounts::sb_flags` tracks per-instance superblock flags (`ro`, `sync`, `dirsync`, `mand`, `lazytime`) by device number; every new filesystem instance must call `new_superblock`, and write checks must consult `Vfs::readonly` rather than a single mount's flags (f52bde6, 9b99931)
- **D56** — `dotdot` returns `ENOENT` outside a bind root by walking the store's parent chain, and tmpfs mount/remount maps `uid`/`gid` options through the caller's user namespace, rejecting unmapped IDs with `EINVAL` (01bfd882; 20137e4c; d053be24; 39e55b4b)

## Active (full detail)

### D3: tmpfs is a detached tree in the store, accounted by the pages its objects take
- **What**: Each tmpfs mount is a new instance backed by a store directory outside the root tree, with its own device number; usage is counted per instance in inodes and in the pages its host objects take, a write or reservation checks room only for the pages it would newly allocate, recounting the instance once first when its usage changed since the last count (`Tmpfs::changed`, `Vfs::tmpfs_recount`), and space is freed at the last unlink. Keep unlinked tmpfs inode charges and track reserved versus instantiated ranges until the last guest reference disappears.
- **Why**: bubblewrap builds its new root on a tmpfs. A separate in-memory filesystem would need every path and fd syscall, mmap, file locks, inotify and AF_UNIX paths to handle another node kind, and its contents would consume the app's memory (the reason `/tmp` was put in the store); counting file sizes as if files had no holes made sparse files reach `ENOSPC` early, and charges for writes to deleted open files, never given back, made an instance refuse writes it had room for.
- **Tradeoffs**: tmpfs is as slow as the store and writes flash; room checks near a full instance cost host lseeks and count reserved-page slack per file rather than per range; concurrent unlocked writes, writes through shared mappings and truncating a deleted open file can miscount until the next recount, the last letting writes pass the limit.
- **Watch out**: statfs, remount and a refusal after any charge recount the whole tree, which also corrects drift from shared mappings, so a full tmpfs walks the instance once after each successful charge; a host whose `SEEK_DATA` treats reserved space differently could let a write past a full tmpfs or refuse one.
- **Source**: rounds 117 (`117-tmpfs`), 131 (`131-tmpfs-usage`); 5d7b936, 9b99931, 0e02d7cd, 71790fde

### D8: Mount flags and cross-mount rules follow Linux 6.1's check order
- **What**: ro is checked per mount and per filesystem instance (`Vfs::readonly`, D53), and noexec, nosuid, nodev and nosymfollow per mount, at the same points and in the same order as Linux; rename and link across mounts are `EXDEV` even between binds of one filesystem; removing or renaming a mount point is `EBUSY` only in the caller's namespace, and mounts on it elsewhere are detached. Apply current mount atime flags to guest store access, including directory reads and O_NOATIME.
- **Why**: Sandboxes (bubblewrap's `--ro-bind`, `--remount-ro`) and LTP's read-only filesystem tests depend on exact errors and ordering (for example `mkdir -p` must still see `EEXIST` on a read-only mount). Treating mount points in other namespaces as busy broke `podman run --rm`.
- **Tradeoffs**: Guest atime policy must be evaluated using the mount through which the file was accessed.
- **Watch out**: New syscalls that create or modify files must call the read-only check (`Vfs::readonly`, not the mount's own flags) at the matching point.
- **Source**: rounds 116 (`116-store-binds`), 168 (`168-open-tree`); f52bde6, 9b99931, 30e5002e

### D15: kernel/vfs owns resolution, metadata and tmpfs; open files belong to kernel/files
- **What**: kernel/vfs keeps path resolution, mount-flag checks, tmpfs instances and accounting, metadata and namespace calls, getdents64 and device nodes, while kernel/files owns fd tables, opening, data I/O, truncation and syncing alongside the already separate filesystem modules.
- **Why**: The fully refreshed VFS map reached 61.5 KB, beyond one read, and open files form a coherent subsystem apart from resolution and metadata.
- **Tradeoffs**: Boundaries run inside shared files, opens and writes need both maps for mount flags and tmpfs duties, and pre-split decisions retain their original tags and IDs here; device checks and cgroup mkdir/rmdir policy belong to kernel/cgroup while synthetic node adapters stay in kernel/procfs.
- **Watch out**: The confirmed reopen and file-position bugs moved to kernel/files, where 01bfd882 fixes reopens and write-position checks but leaves the lseek race; tmpfs accounting and remaining VFS fidelity gaps stay in this module’s map.
- **Source**: c5236a1, a536142, cc160c1, 5f327de (scope since extended by 3f3db11 and 2a244d2 and narrowed by d2b1dc3; bug list since updated by 3f3db11, 4e64e74, 70c1bc6, ce8a9ce, 2aca260, d9e1f0c, 9b99931 and a36860b); ce193253; 01bfd882; 20137e4c; cf029f9a; d053be24; 39e55b4b

### D32: Inherited mounts are locked, and proc, sysfs and cgroup2 check their namespace's owner
- **What**: Copying a mount namespace into one owned by another user namespace locks every copy (`Mount::locks`: the mount itself and its ro, nodev, nosuid, noexec and atime flags), so umount2, moves and pivot_root refuse a locked mount (`EINVAL`), remount and mount_setattr refuse to clear a locked flag (`EPERM`), and a non-recursive bind or clone of a tree with locked mounts below is `EINVAL`; lazy unmounts and namespace collection keep a locked mount on a parent detached with it, as 6.1's `disconnect_mount` does; proc, sysfs and cgroup2 need `CAP_SYS_ADMIN` over the owner of their pid, network or cgroup namespace, and outside the initial user namespace proc and sysfs also need a fully visible instance, whose locked flags the new mount takes over.
- **Why**: Root of a nested user namespace inside a sandbox could unmount the mounts hiding paths, clear ro/nosuid/nodev/noexec with a bind remount, bind a tree without its submounts or mount a fresh unmasked proc or sysfs, and once mounts were locked a lazy unmount still dropped them from their parent, so an fd kept in the detached tree reached the covered directories.
- **Tradeoffs**: Mounts detached because their mount point was deleted in another namespace still disconnect unlocked children (Linux keeps them connected), every lookup also scans the detached list, and `/sys/fs/cgroup` is the only directory counted as an empty mount point.
- **Watch out**: A detached mount may still have a mount point; rootless container setups that mount sysfs depend on `/sys/fs/cgroup` counting as empty, and `unshare -Urm mount -t proc` without a new pid namespace is `EPERM`, as on Linux.
- **Source**: 6d05aa9, 135cb1e

### D39: overlayfs is a third node kind with instances of its own
- **What**: `Node::Overlay(MountId, OvlId)` and `Fs::Overlay` in `vfs/overlay.rs` keep overlay instances and one node per directory and name (Linux's overlay dentry); reads go to the topmost store inode, open files record their overlay node in `OpenFile::ovl` so `node()`, fd-based changes and `/proc/<pid>/fd` never reach a layer's inode, and an overlay without an upper layer is read-only as a superblock (`sb_readonly`).
- **Why**: Container engines on Appine ran on the vfs driver and native snapshotter, which copy every layer and every container's tree, while on Linux they use overlayfs, whose base is read-only multi-layer mounts.
- **Tradeoffs**: Every exhaustive match on `Node` handles a third variant (a fourth since D47), and overlays cannot be layers.
- **Watch out**: `let Node::Store(..) = .. else` sites treat overlay nodes like synthetic ones and may return another errno than Linux; overlay lock keys set the top bit and must never collide with store inode numbers; code that turns an open file's store inode back into a `Node::Store` writes into a layer behind the overlay's back.
- **Source**: 2a244d2 (tradeoffs since changed by d7076fc, 0d82807 and 9590431)

### D40: Overlay copy-up runs in one store transaction per node
- **What**: A change copies its node up in one store transaction (`Overlays::upper_of`), parents first, keeping mode, owner, attributes, times and holes; creations replace a whiteout in the same transaction and make a directory there opaque; `unlink_overlay` and `rename_overlay` follow Linux's check order (merged-directory renames are `EXDEV`); permission is checked on what the overlay shows before copying up; `store_dir` refuses overlay directories so no store path can change a layer; read-only fds move to the upper object on their next read; an instance pins the tmpfs instances its layers are in; origins are kept in memory only.
- **Why**: Container engines need writable overlays (copy-up on change, whiteouts on removal, opaque directories, `EXDEV` for merged-directory renames), and podman's probe (a premade whiteout, then `rm -rf` through the overlay) must pass.
- **Tradeoffs**: The work directory is created but never used; a copied-up file shares the lower one's object only when no host writer or writable mapping requires an immediate copy (kernel/overlay D2); copied-up files change inode number at the next mount, and links made through the overlay show the upper inode's number; writes through an overlay are not counted against a tmpfs upper layer's limits; parents are copied up before a creation's permission check.
- **Watch out**: Code that builds a `Node::Store` from an overlay file's store inode or calls store mutations on a layer bypasses copy-up and whiteouts.
- **Source**: 4b4af75 (since changed by df6ee70 and 0d82807); 01bfd882 (kernel/overlay D2); 20137e4c; d053be24; 39e55b4b

### D45: Clones share store objects until a file is written
- **What**: FICLONE, FICLONERANGE and whole-file copy_file_range share the store's object through `try_share` (copying instead for memfds, overlay and tmpfs files, deleted sources, sources with a writer not marked `cow` or a writable shared mapping, mapped, sealed or unsharing destinations, and objects out for host writing by the Files app); every change calls `prepare_write` or `make_private` first, copying with the BKL released, and `rehome` moves every description to the new object, also when a Files app open makes a held file private (`host_unshared`).
- **Why**: The guest's clone calls must share, every change must unshare first, every description must follow its file, and `try_share` had checked only the kernel's own writers.
- **Tradeoffs**: Private mappings, and after a Files app unshare shared mappings too, stay on the old object; FICLONE succeeds on a filesystem reporting ext4's magic, FIDEDUPERANGE stays `EOPNOTSUPP`, copy_file_range can return more than `MAX_RW_COUNT`, and a source with a writer is copied.
- **Watch out**: Any new path that changes a regular file must call `prepare_write` first with no borrow of the file held, and a description borrowed during `rehome` keeps its old handle (logged).
- **Source**: df6ee70, 1f99a1c

### D48: Detached mounts go when their last use does
- **What**: Each thread records the mounts of the open files, cwds and roots it lets go of (`vfs::released`, up to 16 and then "any", from an open file's drop and `FsContext`'s `set_cwd`, `set_root` and drop), and the dispatcher's `collect_released` and thread exit's `collect_unused` collect when one of them is detached, scanning in-use mounts with `try_borrow` and retrying later.
- **Why**: Detached mounts were collected only by `umount2` and dying namespaces, so a lazily unmounted FUSE mount kept its daemon, and unused open_tree clones and tmpfs trees lingered.
- **Tradeoffs**: With no reference counts each collection scans every task's directories and fd table, a syscall closing a file in a still-used detached mount pays that scan, and releases on non-guest threads wait for another collection.
- **Watch out**: A new holder counted by `in_use` that does not report its release leaves its mount behind until the next collection, as holders that release `ROOT_MOUNT` instead of their node's mount, never-attached open_tree clones and mounts detached by removing their mount point already do (kernel/mount's map), and the per-syscall check must stay a thread-local test when nothing was released.
- **Source**: 922e015 (Watch out since corrected after 49bb4f6's kernel/mount Decision)

### D50: `system.*` xattrs follow Linux's POSIX ACL rules
- **What**: Setting `system.posix_acl_*` follows `posix_acl_xattr_set` and `set_posix_acl` (other `system.*` names `EOPNOTSUPP`, owner or `CAP_FOWNER`, validated, default ACLs only on directories), a base-entries access ACL becomes a chmod, other ACLs are stored with kernel IDs and shown in the reader's namespace, and the walker asks a synthetic view for its search check (`Pseudo::searched`).
- **Why**: `system.*` was stored raw with no permission check, so anyone who could resolve a file could set or remove its ACLs or invent other `system.*` names.
- **Tradeoffs**: Access and default ACLs have different effects at creation, and the enforcement and migration details are recorded in kernel/xattr.
- **Watch out**: Preserve named entries when recovering class permissions from older stores and use the same ACL rules for overlay creation.
- **Source**: f5c09f7, 68b70897

### D52: Exec seals, pivot_root lookups and deleted mount roots follow Linux
- **What**: chmod refuses a change of execute bits on a memfd sealed with `F_SEAL_EXEC` as 6.3 does (memfd_create refuses the MFD exec flags, and the exec seal on an executable memfd adds the write seals), pivot_root looks each path up as a directory in turn, and removing the name a mount's root is marks the mount (`roots_removed`) so mountinfo shows that root with `//deleted`.
- **Why**: A sealed memfd could be made executable again, pivot_root answered `ENOENT` for a file new_root with a missing put_old, and mountinfo showed `/` as the root of a bind whose source was removed.
- **Tradeoffs**: The exec seal stays a 6.3 feature on a 6.1 contract, a deleted root's path comes from its former parent, and a root reached without a recorded name is marked only once it has no links left.
- **Watch out**: A new way of removing a name that skips `detach_points` leaves its mounts' roots looking alive in mountinfo.
- **Source**: 5760479

### D59: Timestamp updates share ownership checks and notification handling

- **What**: Treat two OMIT values as a no-op, allow write permission for NULL or two NOW values, and require ownership or equivalent capability for mixed updates.
- **Why**: Linux distinguishes setting arbitrary times from touching a writable file.
- **Tradeoffs**: The exact pair of timestamp selectors changes authorization.
- **Watch out**: Issue the appropriate event only after a successful change.
- **Source**: 3a90a0ef, 515ecd7f

### D60: Creation applies inherited ACLs atomically with the requested mode

- **What**: Intersect the parent's default ACL with the creation mode and preserve the unmodified default on new directories.
- **Why**: Children need correct immediate permissions and correct inheritance for later descendants.
- **Tradeoffs**: Access and default ACLs can differ.
- **Watch out**: Use the same creation helper for overlay-backed store paths.
- **Source**: 68b70897

### D61: File metadata reports the guest mount and filesystem identity

- **What**: Resolve PTY and overlay device/inode values, derive the store filesystem root per instance and fill statx mount ID from the same source as fdinfo.
- **Why**: Host backing and guest mount identity can differ.
- **Tradeoffs**: Anonymous and reopened fds require explicit mount identity.
- **Watch out**: Do not substitute the current mount reached by walking a renamed path.
- **Source**: d10f2973, f985c1f8, 6fcaff37, 65e10e36

### D36: File syscalls follow 6.1's argument checks and error order
- **What**: getdents64 checks `O_PATH`, removed directories and an int count and copies out entry by entry; copy_file_range follows `generic_copy_file_checks` and `rw_verify_area` (`EXDEV` between devices); fcntl and flock refuse `O_PATH`; truncate checks the type before the mount and permission, and synthetic regular files succeed unchanged; `O_DIRECT` is `EINVAL` except on store regular files outside tmpfs and on loop devices; openat validates `O_TMPFILE`; alarm-clock timerfds need `CAP_WAKE_ALARM`; AIO maps restart codes to `EINTR`; renameat2 `NOREPLACE` onto `.` or `..` is `EEXIST` and synthetic filesystems follow `vfs_rename`'s order; directory `SEEK_END`, `SEEK_DATA` and `SEEK_HOLE` answer per filesystem (the store as ext4's hashed directories, procfs, sysfs and cgroup2 at 0, others `EINVAL`); utimensat reports an event for one time alone; procfs and devpts report an `st_blksize` of 1024. Share validation of known flags while keeping statx-specific combined-sync and reserved-mask checks.
- **Why**: These calls answered with other errors or in another order than 6.1, which Starnix's, gVisor's and LTP's tests check.
- **Tradeoffs**: `O_DIRECT` is checked before the file's own open, where Linux opens first (a FIFO waits for its peer before `EINVAL`), and tmpfs refuses it as 6.1 does although 6.6 and later accept it.
- **Watch out**: Opens of character devices or tmpfs files with `O_DIRECT` now fail, and copies between tmpfs and the store are `EXDEV`, which callers handle by falling back.
- **Source**: dcacfb6, 5d7b936, 37051c60

### D62: Cgroup metadata changes validate the node generation

- **What**: Route chmod, chown and timestamp updates through shared attributes and reject stale controller generations.
- **Why**: An old dentry can name a file recreated by enabling a controller.
- **Tradeoffs**: Generation checks accompany mutable metadata.
- **Watch out**: A stale handle must not update the replacement file.
- **Source**: 865d24c1

### D63: Create and link check parent search, existence and writability in Linux order

- **What**: Share parent admission helpers, distinguish read-only superblocks from bind flags and enforce protected_regular and protected_fifos on existing sticky-directory targets.
- **Why**: Overlapping failures expose different errors depending on their order.
- **Tradeoffs**: Several creation paths share the ordering contract.
- **Watch out**: Check existing names before returning a read-only or write-permission error where required.
- **Source**: d96f5d63, e6495409, ab417943

### D64: Inode flags and anonymous object attributes survive descriptor aliases

- **What**: Persist immutable and append flags, enforce them across mutation, xattrs and writable shared mmap, and preserve socket and pipe ownership in shared attributes.
- **Why**: Separate opens or aliases must observe the same restrictions and owners.
- **Tradeoffs**: Older stores need a flags-column migration.
- **Watch out**: Initialize both socket owner IDs and perform flag checks before overlay mutation.
- **Source**: ba194fea, 977989d5

### D65: Synthetic search checks cover restricted proc and mqueue directories

- **What**: Use shared live-group rules in faccessat and path traversal, allowing nonwrite access to own fd directories and search-only access to own ns.
- **Why**: A nondumpable task must traverse its own directories while other callers face the actual mode.
- **Tradeoffs**: W_OK on the read-only own fd directory remains denied.
- **Watch out**: Add new mutable or non-world-searchable synthetic directories to the policy.
- **Source**: 36005e07, 3ad68857

### D66: Non-FUSE `faccessat` checks full backing ACLs and GPU fence fds support anonymous stat

- **What**: Resolve full backing inode attributes (including extended access ACLs, immutable flags, and unlinked `O_PATH` tmpfs fallbacks) through `access_attrs` in `faccessat`, and include `FileKind::GpuFence(_)` in `file_stat_attrs`'s `ANON_DEV` arm.
- **Why**: Non-FUSE `faccessat` rebuilt a bare `Stat` without access ACLs and disagreed with `open()`, while `fstat` and empty-path `statx` on GPU fence fds returned `EBADF`.
- **Tradeoffs**: `access_attrs` branches on store, overlay, proc-fd, and proc-exe targets separately from `node_stat` instead of extending the ABI stat buffer.
- **Watch out**: Any new `PseudoNode` magic-link variant resolving to a store or overlay inode must be handled in `access_attrs` to preserve ACL fidelity.
- **Source**: 0160070e

### D67: Tmpfs faults batch across extents and `unlink` runs from one parent lookup

- **What**: Batch tmpfs mapping faults with `TmpfsMapping::fault_range`/`fit_fault_range` across populated `SEEK_HOLE` extents and quota-bounded hole allocations, and dispatch `unlink` from one `resolve_parent` using `store.attrs` in `may_delete`, `Store::remove_checked`, `removed_dir` in `detach_points`, and `LazyCell` `store.attrs` loading in `roots_removed`.
- **Why**: Per-page tmpfs faults ran multiple host syscalls under the BKL, and `unlink` repeated `resolve_parent` five times alongside redundant host `fstatat` and `store.attrs` calls.
- **Tradeoffs**: `fit_fault_range` falls back to single-page allocation when a multi-page `fallocate` hits quota or host-space exhaustion.
- **Watch out**: Future changes to `unlink` error precedence must keep the single-walk `remove_checked` closure aligned with Linux 6.1 checks.
- **Source**: b38371ff

### D68: Path walks use a stack-backed queue and `Cached` serves no-BKL `readlink` and `access`

- **What**: Replace per-component heap allocations in `Vfs::walk_in` with a stack-backed `WalkPending` borrowing slices from the input path and indexed symlink buffers, and add no-BKL `Cached::readlink` and `Cached::access` using `CachedCred` and snapshotted `ROOT_MOUNT` flags.
- **Why**: Ordinary BKL path walks allocated a `VecDeque` and a `Vec<u8>` per component, and `readlinkat`/`faccessat` lacked no-BKL fast paths.
- **Tradeoffs**: `WalkPending` reserves a 32-slot inline array on `walk_in`'s stack, and `Cached::access` falls back to the BKL for non-root group or ACL checks.
- **Watch out**: Symlink target storage still grows up to `MAX_SYMLINKS` per walk, and any new `ROOT_MOUNT`/`ROOT_DEV` flag mutation must keep `rebuild_cached` in sync.
- **Source**: cb0f3059

## Superseded

- ~~There are no block devices or loop devices (D5's tradeoff, rounds 04, 05, 184)~~ → replaced by **D21** in a26c8b4 (2026-11-06)
- ~~F_SETOWN/F_GETOWN owners translated by the owner helpers (09188ec)~~ → replaced by **D25** in 5656ada (2026-11-07)
- ~~An unknown F_SETOWN pid is accepted where Linux returns `ESRCH` (D25's tradeoff, 09188ec, 5656ada)~~ → replaced by **D25** in f6a82cc (2026-11-08)
- ~~tmpfs counts usage by file size, treating files as having no holes (D3's What and tradeoff, rounds 117, 131)~~ → replaced by **D3** in 5d7b936 (2026-11-08)
- ~~Mount propagation types are validated and then ignored, so every mount behaves as private (no `shared:N`, `MOVE_MOUNT_SET_GROUP` is `EINVAL`), and remount changes only the one mount's flags except tmpfs size options (D6, rounds 105, 116, 168)~~ → replaced by **D6** in 970cf6a and 66b765c (2026-11-09); the remount clause by **D53** in f52bde6 (2026-11-10, no SUPERSEDES declared)
- ~~Propagation types are recorded but mount events do not propagate (970cf6a)~~ → replaced by **D6** in 66b765c (2026-11-09)
- ~~Unused namespaces and detached mounts are collected only at `umount2` and task exit (D2's tradeoff, round 118)~~ → replaced by **D48** in 922e015 (2026-11-09, no SUPERSEDES declared)
- ~~Synthetic filesystems never report inotify events (D13's tradeoff, rounds 11, 19, 201)~~ → narrowed by **D51** in affb1e2 (2026-11-09, no SUPERSEDES declared): cgroup2 files report
- ~~Overlay nodes are kept until the instance's last mount is collected (D39's tradeoff, 2a244d2), and memory grows with the overlay nodes looked up while mounted (D40's watch-out, 4b4af75)~~ → replaced by **D49** in 0d82807 (2026-11-09)
- ~~inotify on overlay nodes never reports (D39's tradeoff, 2a244d2)~~ → replaced by **D46** in d7076fc (2026-11-09, no SUPERSEDES declared)
- ~~`/proc/<pid>/exe` of a program in an overlay is followed by path with no `ETXTBSY` for its writers (D39's tradeoff, 2a244d2)~~ → changed by 9590431's kernel/procfs and kernel/process Decisions (2026-11-09, no SUPERSEDES declared)
- ~~Copy-up copies data with the BKL held, so large copy-ups stall every process (D40, 4b4af75)~~ → replaced by **D45** in df6ee70 (2026-11-08, no SUPERSEDES declared)
- ~~Mounts, fds, file syscalls, pipes, locks, devices and inotify are one kernel/vfs module (D15, c5236a1)~~ → replaced by **D15** in d2b1dc3 (2026-11-10)
- ~~Host stdio fds stay per-iovec (D22's tradeoff, b295644)~~ → replaced by **D22** in a36860b (2026-11-10, no SUPERSEDES declared)
- ~~kernel/vfs also covers inotify, native AIO, loop devices and timerfd (D15, c5236a1, a536142)~~ → replaced by **D15** in cc160c1 (2026-11-11)
- ~~kernel/vfs also covers overlayfs, locks and leases, pipes and xattrs, overlayfs having stayed when inotify, AIO and loop devices left (D15; cc160c1)~~ → replaced by **D15** in 5f327de (2026-11-11)
- ~~D15’s ownership of fd tables, open files and file I/O~~ → replaced by **D15** in ce193253 (2026-11-12)
- ~~D40’s unconditional sharing of a regular file’s lower object after copy-up (df6ee70)~~ → replaced by **kernel/overlay D2** in 01bfd882 (2026-11-12, cross-module SUPERSEDES)
- ~~Reporting mount atime flags without applying them~~ → replaced by **D8** in 30e5002e (2026-11-24)
