# kernel/vfs Map

> Static understanding snapshot, not a decision history.
> See `.agents/decisions/kernel/vfs.md` for the paired decision history (it still holds the decisions on mounts, inotify, AIO, loop devices, timerfd, overlayfs, locks, pipes, xattrs, open files and file I/O made before they were split out; the last two are listed in `kernel/files`'s and `kernel/fileio`'s maps).
>
> Verified: 2026-11-26 (full refresh; 18 claims verified — 13 confirmed, 5 partial, 0 refuted; all fixes applied)
>
> Maintained: 2026-11-27 (targeted verification only since the full mapping; latest change: in-place condensation with no mapped claim changes)

## Responsibilities

`kernel/vfs` handles path resolution, mount policy, tmpfs accounting, `/dev` population and
metadata/namespace/directory syscalls: `core/kernel/src/vfs/mod.rs` (excluding mount table and
overlay methods), `vfs/dev.rs`, matching sections of `syscall/fs.rs`, and tmpfs options in
`syscall/mount.rs`. Open/ioctl dispatch is `kernel/files`; I/O, size and seek are `kernel/fileio`;
locks, pipes, loops, mounts, overlay and FUSE are separate modules.

## Key types

- `Node::{Store,Pseudo,Overlay,Fuse}` pairs a mount with a store inode, `PseudoNode`, `OvlId` or
  `FuseId`; only `Store` yields `Node::ino`. `Dentry` holds parent/name; `WalkDentry` adds unlink state.
- `FsContext` holds root, cwd, umask and mount namespace. `Vfs` owns the store, mounts/namespaces,
  mount events, executable cache, overlay/FUSE tables, fast resolver, removed names, memfd sizes,
  tmpfs ranges and anonymous attrs.
- `Pseudo` supplies lookup, kind, parent/name, readlink, magic jumps/dentries, search attrs,
  protected-link policy and miss reporting (`View` live, `Offline` boot/host). `Cached` holds
  metadata, store mount points and usability; `Pending` has 64 slots.
- `Tmpfs` holds root, `TmpfsParams`, optional usage, a changed flag and
  `unlinked: HashMap<Ino, UnlinkedTmpfs>` (pages + open host file). `TmpfsFileRanges` tracks
  allocations/data; `TmpfsMapping` pins the object for faults.
- `Attrs::{Store,Overlay,Pts,Anon,Mq,MqDir,Cgroup,Fuse}` selects the metadata backend.
  `AnonTarget` identifies a pipe/socket; `Device` identifies generic devices and loops.

## Public entry points

- Resolution: `Vfs::resolve`, `walk`, `walk_resolve`, `resolve_mountpoint`, `resolve_parent`,
  `resolve_parent_with`, `found_path`, `reach`, `path_of`, `mounts_from`, `Cached::stat`,
  `Cached::readlink`, `Cached::access` (`CachedCred`), `start_dir`, `lookup_at`,
  `create_parent_at`, `may_create`, `display_path`.
- Syscalls: `newfstatat`, `fstat`, `statx`, `statfs`/`fstatfs`, `faccessat`/`faccessat2`,
  chmod/chown variants, `utimensat`, `getdents64`, `readlinkat`, `getcwd`, `chdir`/`fchdir`,
  `chroot`, `umask`, `mkdirat`, `mknodat`, `symlinkat`, `linkat`, `unlinkat`, `renameat2`.
- Internal/boot: `file_stat`, `dev_ino` (fallback without store lookup), `file_inode_flags`,
  `file_accessed`, `readonly`, `new_tmpfs` + tmpfs hooks, `new_fuse`, `add_fuse_mount`,
  `fuse_child`, `fuse_permission`, `open_with_gpu`.

## Data flow / lifecycle

### Path lookup and search

`start_dir` rejects empty input before dirfd (`ENOENT`). Absolute paths use root, relative
paths use cwd for `AT_FDCWD` or the fd's directory (`EBADF` for bad fd, `ENOTDIR` for wrong
type/no node). `lookup_at_or_fd` with empty path and `AT_EMPTY_PATH` takes cwd or fd node
(`EBADF` if absent). Stat and anonymous attrs also have fd-specific routes.

`walk_resolve` runs `walk_in` in one store snapshot via stack-backed `WalkPending` (32 inline borrowed
`&[u8]` slots, lazy overflow/symlink-target buffers, skipping final `Dentry` when `!want_found`). Path
syscalls read guest strings into 256 B stack-inline `PathBytes` (`read_path_buf`, heap fallback). Trailing
`/` forces final-link follow and searchable dir. Each component (incl. `.`/`..`) checks ≤255 B, type,
search: `.` keeps dentry; `..` clears it, stops at process root or climbs stacked attachments, takes parent
(store parent outside bind subtree → `ENOENT`) and enters top mount (as does child lookup). Ordinary walks
touch mounts; `resolve_mountpoint` keeps expiry marks (umount/host stats).

`searches_all` skips store/overlay/synthetic search checks for initial-ns `CAP_DAC_OVERRIDE` or
`CAP_DAC_READ_SEARCH`; else store/overlay check visible dir perms. `View::searched` supplies attrs for
cgroup dirs, mqueue root and live other-group proc `fd`/`fdinfo`/`ns` (own-group works when non-dumpable;
dead proc tasks give none so lookup reports disappearance; procfs also gates lookup/listing/magic links).
Final `faccessat` allows own-group exception for nonwrite masks on `fd`/`fdinfo` and search-only on `ns`.
Chdir/fchdir/chroot check final dir via `searched_attrs` or FUSE `may_chdir`; chroot checks caller-ns
`CAP_SYS_CHROOT` and keeps cwd.

FUSE always checks whether the connection allows the caller. `default_permissions` can
require cached attrs. On a miss, `lookup_walk` batches consecutive ordinary names up to dots,
the trailing-slash marker or an oversized component. Lookup, readlink or attrs can request
`FUSE_RETRY`. The dispatcher services the query and retries, with `EIO` beyond 16,384 retries.
Only component lookup `ENOENT` invokes `Pseudo::missing`, which records selected proc/sysfs
interfaces and names directly in the boot `/dev` root.

### Symlinks and scoped walks

- The 41st followed symlink gives `ELOOP`. For a trailing store link in a store directory
  (including `link/`), `protected_symlinks` gives `EACCES` when the directory is sticky and
  world-writable and neither caller fsuid nor directory owner equals link owner. Next come
  `NO_SYMLINKS`/mount `NOSYMFOLLOW` (`ELOOP`). Proc `follow` can deny access before magic-link
  restrictions. For magic links `NO_MAGICLINKS` gives `ELOOP`, cross-mount `NO_XDEV` and scoped
  jumps give `EXDEV`. Proc fd, ns, cwd, root and exe links are magic. `self`/`thread-self` are ordinary.
- Magic jumps reach process directories, namespace files, store/overlay/FUSE executables or
  retained `OpenExe`. `follow_fd` preserves overlay identity, linked store files/devices, directory,
  path and proc nodes, and a slave PTY's opened device or devpts node. Deleted regular files,
  memfds and many special fds use `OpenFd`. An open walk also selects it for a retained overlay
  regular file whose last upper link is gone. Reopen supports regular backing, pipes, queues,
  FUSE snapshots and host fds. Unsupported kinds can give `ENXIO`. A final jump can retain
  the opened dentry and unlink state.
- Relative symlinks continue from the parent. Absolute targets restart at the supplied root.
  `BENEATH` rejects absolute input/targets and `..` from the start. `IN_ROOT` interprets
  absolute paths from the dirfd. `NO_XDEV` checks child and parent mount transitions. An
  absolute symlink jump under `NO_XDEV` requires an already-taken root (absolute/scoped start,
  earlier `..` or root jump) on the link directory's mount.
- `openat2` checks `open_how` size (under 24 gives `EINVAL`, over 4096 or nonzero extension
  bytes `E2BIG`), flags, mode and open combinations. `BENEATH|IN_ROOT` gives `EINVAL`.
  `RESOLVE_CACHED` rejects create, truncate, tmpfile or `O_DIRECTORY` with `EAGAIN` before
  pathname copy. Other requests use the ordinary walk. Scoped `..` under the BKL has no
  separate rename-race retry.
- `resolve_parent_with` handles slash-containing prefixes with the final separator included
  (checking parent search during the walk), uses the start directly for slash-free basenames, and
  takes a separate shortcut for all-slash input (`EXDEV` under `BENEATH`). It checks basename
  length and parent type, leaving final dots and access to callers. `O_CREAT|O_EXCL` rejects
  existing names. After `ENOENT`, create rejects a trailing slash/final dot with `EISDIR`,
  handles mqueue creation, rejects regular creation in writable cgroups with `EACCES` and
  delegates FUSE creation. Dangling store, overlay and FUSE links lead to their targets with
  a fresh 40-link count and approximate `NO_XDEV` root history.

### Path reconstruction

`reach` climbs parents and attachments toward the supplied root using the recorded dentry for
file mounts. Detached/disconnected roots, parentless pseudo nodes or forgotten FUSE nodes can
end as unreachable. `getcwd` prefixes `(unreachable)`, counts the NUL and gives `ERANGE` for
short buffers. Removed directories give `ENOENT`. `path_of` permits them and appends ` (deleted)`.
Store rows retain parents. `removed_dirs` saves names for cwd, root, directory/path fds and
tracked ancestors. Overlay linkage and FUSE's `unlinked` serve the same purpose. `mounts_from`
returns optional paths. Procfs skips unreachable mounts and shows cwd/root against the reader's
root. Fd links track renamed dentries, with saved paths/descriptions as fallback and deleted
names marked. `display_path` falls back from `found_path` to lexical normalization.
`readlinkat` checks positive size first and returns raw bytes truncated to fit. Empty input
reads the fd without flags (`ENOENT` for node-less fd/nonlink, `EINVAL` for a nonempty path to a nonlink).

### No-BKL stat, readlink and access

`Fast::new` snapshots root in attached `ROOT_MOUNT` (incl. chroot inside it) without `NOSYMFOLLOW`, with
`searches_all` creds, recording effective `eaccess_cred` and real-ID `access_cred = cred.for_access()`.
Absolute paths allow any cwd; relative needs `AT_FDCWD` + cwd in that mount. Needs current epoch, valid
flags/amode/size (`readlinkat` falls back on `size <= 0` or targets filling `PATH_MAX` when `size >
PATH_MAX`), readable nonempty path ≤`PATH_MAX` incl. NUL. Global/syscall tracing, stepping or pending
icache invalidation disables fast dispatch; seccomp may admit cached always-allowed syscall numbers.

`Cached::{stat,readlink,access}` use a store metadata reader (snapshotting `ROOT_MOUNT`
`root_readonly`/`root_noexec`, rebuilt on `ROOT_DEV` `set_sb_flags`). Cached negatives → `ENOENT`;
unavailable/stale reader, uncached entry, host stat failure, >64 pending steps or store mount point (incl.
store root when boot mount isn't topmost) fall back to BKL. Length/type/loop errors can be final. Followed
trailing link not owned by its sticky world-writable dir's owner, or final immutable/append/nodump on
either stat call (not noatime alone), falls back. `Cached::readlink` → `EINVAL` on non-symlinks, truncates
to caller buffer. `Cached::access` needs `CAP_DAC_OVERRIDE`/`CAP_DAC_READ_SEARCH` in `CachedCred`, checks
regular `root_noexec` (`EACCES`), `IMMUTABLE` (`EPERM`), `Cred::permission`-equivalent DAC (fallback for
extended ACLs on non-exec `X_OK` or non-owned group/other checks) and `root_readonly` (`EROFS` for
non-special nodes). Fast statx uses `ROOT_MOUNT` and advertises supported attr mask. `mounts_changed_ns`
always rebuilds cached mount state and increments/wakes once per live nonanonymous affected ns;
mount/fs-context/cred changes call `fast_changed`; `Fast::refresh` rebuilds on epoch change under BKL.

### Mount flags and atime

- `readonly` combines mount `RDONLY` and superblock state shared by device number, including a
  lower-only overlay. Store create checks existence before `EROFS` and write permission after.
  Unlink/rmdir check writability after special-dot handling, rename after mount IDs and dots.
  Attribute changes generally check it before ownership, truncate after type checks.
- On existing store regular files, `O_TRUNC` on a read-only mount or a write open on a
  read-only superblock gives `EROFS` before DAC. On a read-only bind, an ordinary write open of a writable instance checks
  DAC and `O_NOATIME` first. Overlay write opens check read-only early. Memfd open and SETFLAGS
  exempt memfds from their read-only tests. Fd metadata mutation can still consult their mount.
- `NODEV` blocks device opens (`EACCES`), including overlay, FUSE and devpts. FUSE also refuses
  device opens on connections owned by a noninitial user namespace. `mknod` uses separate
  capability/device-filter checks. `NOEXEC` denies exec after execute permission and denies
  `X_OK` on regular files. Memfd descriptor exec omits mount flags and access exempts identified
  memfds from noexec. Executable regular mmap checks the fd node's mount (root for memfd), giving
  `EPERM` now or `EACCES` on a later mprotect upgrade. `NOSUID` suppresses set-id and file caps.
  FUSE also suppresses them outside the connection user namespace and its descendants, or for
  a retained executable snapshot whose connection is absent.
- Regular reads and successful regular mmap installation use `file_accessed`. A nonempty read
  can touch atime at EOF. Store `getdents64` checks atime before buffer validation. Exec header
  reads can call `Vfs::accessed` even for cached store/overlay programs. For fd access
  `O_NOATIME` suppresses the call. Mount/superblock read-only, mount `NOATIME` and inode
  `FS_NOATIME_FL` suppress updates. `NODIRATIME` applies only to directories. Relatime is due
  when atime ≤ mtime or ctime, or is at least 24 hours old. Ordinary access changes only atime.
  Mountinfo reports sync, dirsync, mand and lazytime. Statfs encodes sync and mand.

### Tmpfs creation and remount

`new_tmpfs` allocates a detached store directory and device number (initial usage: 0 pages, 1
inode); binds share the instance and overlay layer pins keep it alive. Boot instances start
uncounted (dropping deltas until first count) with relatime: `/dev` 0755 nosuid, `/dev/shm` 1777
nosuid,nodev, `/run` 0755 nosuid,nodev (20% RAM, `800 << 10` inodes).

- Options: `size=` (bytes rounded up to 4096-byte pages or `%` RAM), `nr_blocks=`, `nr_inodes=`
  (base prefixes, binary K/M/G/T/P/E suffixes), octal `mode=` (masked to 07777), base-detected
  `uid=`/`gid=` mapped in caller's user namespace (`EINVAL` if unmapped or `u32::MAX`),
  `inode32`/`inode64` (reporting only), `huge=` (never/always/within_size/advise/deny/force),
  `mpol=` and recognized generic superblock words ignored; unknown options `EINVAL`.
- Defaults: 1777, mounter fsuid/fsgid, inode64, half of RAM in pages and inodes (0 = unlimited).
  Mountinfo shows nondefault limits/mode, nonzero IDs and inode width. Non-bind remount validates
  options/IDs before `CAP_SYS_ADMIN` and RO writer checks, then evicts and recounts before applying
  limits and inode width (mode/owner persist; unlimited-to-finite or below-use is `EINVAL` with no
  parameter or flag changes).

### Tmpfs accounting and lifetime

- Counting charges 1 inode per root/name (incl. hard links), pages once per distinct regular file/symlink
  (≥128 B symlink = 1 page), plus unlinked records. `statfs` recounts; unlimited resources report 0
  total/free.
- `TmpfsFileRanges::allocated` tracks reservations + instantiated pages; `data` tracks mapped-instantiated
  pages; allocation rounds outward. `tmpfs_missing` merges guest runs with host extents (FIEMAP, seek
  fallback); without guest runs, total uses FIEMAP or rounded host blocks. Tracked stats combine ranges +
  host allocation in 512 B sectors. `SEEK_DATA`/`SEEK_HOLE` merge host results with `data`, so untouched
  logical reservations consume quota without becoming data or extending EOF.
- Creation (incl. AF_UNIX bind) checks room before and charges after success. Overlay copy-up checks
  ancestors/data against tmpfs upper (tracked lower copies mapped data ≤EOF, omitting untouched
  reservations); replacing a whiteout needs no extra inode for that name. Mapping quota follows the
  supplying layer, even for held unlinked objects.
- `tmpfs_write_room` stops before first unfittable page (`ENOSPC` if none fits); final room check, write
  and charge hold BKL. Fallocate needs full missing range; changed usage triggers 1 fresh count before
  refusal/shortening. `tmpfs_resized` charges measured delta and updates unlinked records. Sparse truncate
  growth costs 0 pages; shrink/punch forget whole guest pages. `TmpfsMapping::fault_range` admits in-file
  faults: host data covering faulting page maps ahead to fault window (clamped to EOF/next host hole) at 0
  charge; holes admit only demanded range + contiguous instantiated guest data (no speculative hole
  read-ahead), instantiating pages even on private reads without double-charging reservations, shrinking
  multi-page hole ranges to fitting prefix via `fit_fault_range` (recount once before shortening/`ENOSPC`)
  and reserving host blocks on shared writes (1-page fallback if multi-page `fallocate` fails).
  Read/private faults keep backing mtime/ctime; shared writes publish mtime before granting write.
- `tmpfs_victim` captures pages and last-linked regular object before removal/replacement. `tmpfs_removed`
  refunds name + last-link pages, or moves a regular file found by `file_in_use` to `Tmpfs::unlinked` with
  **1 inode and its pages still charged** (host pin allows stat via last `O_PATH` fd after store reaping).
- `evict_unlinked_tmpfs` scans fd tables (incl. path fds), AF_UNIX inflight files, loop backings, distinct
  address spaces and running executables (borrow conflicts defer). Live pins refresh page counts; dead
  entries drop ranges, pages and inode. Runs at post-syscall collection, thread exit, exec CLOEXEC, tmpfs
  statfs, non-bind remount; also sweeps deferred shared-object releases, linkable tmpfiles and closed memfd
  size records (separate liveness checks). Memfds bypass ordinary tmpfs quota.
- `open_tmpfile` checks dir type/writability/perm; store/tmpfs unnamed files inherit default ACL and
  inheritable flags. Tmpfs immediately charges 1 inode and pins it unlinked. Without `O_EXCL` it is
  linkable: first `Store::link` clears eligibility and `linkat` drops the unlinked record without extra
  charge even at full inode quota; later hard links need fresh room.

### Stat and filesystem identity

`stat_at` resolves and reads pathname metadata in one store snapshot. `AT_EMPTY_PATH` on a
real fd uses `file_stat_attrs` and `OpenFile::mnt_id`. A followed `OpenFd` link uses the target
descriptor. Owner IDs are mapped into the caller's namespace (overflow 65534).

- Store uses mount dev/ino, overlay visible dev/ino (following copy-up); unlinked files/executables stat
  retained host objects or FUSE exec snapshots. Namespace files use nsfs, queues their IPC ns mqueue dev,
  anon pipes/sockets/anon-inodes fixed devs.
- Devpts-pinned masters stat `PtsPtmx` (1024 B blocks); store/overlay ptmx masters report opened node;
  node-less unpinned masters report ino 2, `ROOT_DEV`, 1024 B blocks. Slaves use opened dev ino or terminal
  attrs + devpts instance/index.
- Regular objects/host fds supply host block counts (+ tracked tmpfs adjustments). Default block size 4096
  (procfs/devpts synthetic 1024, FUSE own); missing counts derive from page-rounded size (8 sectors/page).
  Symlinks <60 B on `ROOT_DEV` or <128 B elsewhere use 0 blocks; `ROOT_DEV` dirs report size 4096.
- `newfstatat`/`statx` accept `AT_SYMLINK_NOFOLLOW`, `AT_EMPTY_PATH`, `AT_NO_AUTOMOUNT` and statx sync
  flags (`newfstatat` allows both sync bits; `statx` rejects that combo or reserved mask bits with `EINVAL`
  before path copy). `statx` always reports `STATX_BASIC_STATS | STATX_MNT_ID`; `stx_mnt_id` comes from
  node/fd (empty-path `statx` on node-less fd → `ANON_MOUNT`, matching fdinfo). Store/overlay advertise
  immutable/append/nodump even when clear; `stx_attributes` reports set bits (noatime outside
  `stx_attributes_mask`).
- `statfs_of`: ext4 magic + host object-dir capacity (store), tmpfs limits (tmpfs), overlayfs magic +
  store capacity, FUSE daemon figures (excluded → `FUSE_SUPER_MAGIC`, 0 capacity/`f_flags`), or synthetic
  magic + 0 capacity. `f_flags`: `ST_VALID`, mount flags, sb RO/sync/mand (nsfs: `ST_VALID` only).
  `fstatfs`: pipefs (anon pipes), underlying fs (named FIFOs), mqueue (queues), root figures (other
  node-less fds).

### Access and inode flags

`faccessat` validates mask/flags before pathname copy. Without `AT_EACCESS`, `for_access` uses
real IDs. Unless `SECBIT_NO_SETUID_FIXUP` is set it uses permitted caps for namespace root and
clears effective caps otherwise. FUSE uses its access handler even for `F_OK`, which otherwise
succeeds after lookup. For non-FUSE targets, regular-file noexec (`EACCES`) and immutable for
`W_OK` (`EPERM`) precede final DAC. Store and overlay targets use the backing inode's extended
access ACL, including through proc fd and exe links. Write access to a regular file, directory
or symlink checks read-only (`EROFS`) after permission.

`FS_IOC_GETFLAGS`/`FS_IOC_SETFLAGS` transfer four bytes for store/overlay regular or directory
descriptions, including tmpfs and memfds. GET reads supported flags on the visible inode.
SET reads the argument, checks read-only (except memfd), owner or `CAP_FOWNER`, then initial-user-namespace
`CAP_LINUX_IMMUTABLE` for immutable/append changes. Unsupported bits give `EOPNOTSUPP` before
copy-up. Success persists flags and updates ctime. Legacy rows default to zero. Named children
and tmpfiles inherit only nodump/noatime.

Immutable blocks write open. Append-only requires append on write open and refuses truncate.
Writes recheck current flags and force EOF even for positional writes on append-only files.
`F_SETFL` cannot toggle their `O_APPEND`. Range copies check destination flags. Both protected
flags restrict unlink/replacement, hard-link source, chmod/chown, explicit times and xattrs/ACLs.
Append-only directories can gain names. After fd access and noexec checks, append on a
readable/writable regular fd rejects any `MAP_SHARED` with `EACCES` (even read-only); if that
passes, immutable rejects shared `PROT_WRITE` with `EPERM`. Permitted protected shared mappings
cannot gain write later.

### Mutable metadata

- `Attrs::of` checks writability for store/overlay, devpts slaves, queues, mqueue roots,
  cgroup and FUSE. For named cgroup files `pseudo::gone` matches name **and generation**.
  A stale handle gives `ENODEV` before mutation. FUSE can pass an open `fh` to `SETATTR`
  and delegate permissions. Unsupported pseudo paths give `EROFS` if read-only, else `EACCES`.
- `Attrs::of_fd` selects anonymous objects, queues and node-less devpts slaves directly without
  checking mount writability. Ordinary `O_PATH` and unsupported pseudo fds (including the mqueue
  root) give `EBADF` before mount checks. `Attrs::at` handles `AT_EMPTY_PATH` for `fchownat` /
  `utimensat` and followed proc links to anonymous objects. `fchmodat` takes no flags.

- `chmod` masks to 07777, checks protected flags/ownership, drops setgid outside file's group without
  `CAP_FSETID` over it; `F_SEAL_EXEC` memfd rejects exec-bit changes (`EPERM`) after ownership. `chown`
  maps IDs (`EINVAL` if unmapped, −1 unspecified); without `CAP_CHOWN`, supplied uid needs owner keeping
  it, gid needs owner keeping it or one of their groups. On store/overlay non-dirs, chown clears setuid,
  file caps and setgid (if group-exec or caller lacks group membership and `CAP_FSETID`); clearing set-id
  needs owner/`CAP_FOWNER`; `(-1,-1)` can fail on set-id or clear caps alone; no IDs and nothing to clear
  skips change. Writes/truncates use `kernel/fileio`'s `remove_privs`.
- `utimensat` validates flags and resolves target before reading times (null path uses fd: `EFAULT` on
  `AT_FDCWD`, else flags must be 0). Two `UTIME_OMIT` succeed after target checks; null times or two
  `UTIME_NOW` allow owner/write access (incl. append-only, but immutable blocks both-now too);
  explicit/mixed updates need owner/`CAP_FOWNER` where locally checked. `notify_utimens` runs only for
  store/overlay/cgroup/FUSE (`IN_ACCESS` atime, `IN_MODIFY` mtime, `IN_ATTRIB` both), not PTYs, anon
  objects, queues or queue roots.
- With VFS, `pipe2` sets shared attrs 0600, creator fsuid/fsgid, creation times (no VFS: 0600, root, 0
  times); positive pipe reads touch atime, writes mtime/ctime. Sockets start 0777, creation/accept
  fsuid/fsgid, 0 times; socket chown updates socket + shared attrs if present. `anon_attrs` keeps
  mutations across aliases (pruning dead weak refs at insertion thresholds); named FIFOs use fs inode.

### Creating and removing names

- `resolve_create_parent` rejects final dots or existing synthetic children (`EEXIST`). Store create runs
  `may_create_name` (search, existence, RO) then `may_add_name` (immutable, parent write/search); non-dir
  create with trailing `/` tests existence earlier (`EEXIST`/`ENOENT`). Overlay checks merged existence,
  perms and upper quota before parent copy-up. `create_parent_at` exposes this to AF_UNIX bind. `store_dir`
  rejects `Node::Overlay` (`EXDEV`) and accepts directly addressed `Node::Store` without checking overlay
  backing.
- `new_owner` applies setgid-dir inheritance and skips normal umask if parent has a default ACL.
  `create_in` installs inherited ACLs in creation txn: access ACL intersected with requested mode (stored
  only if extended), new dirs keep full default ACL, symlinks skip inheritance. Overlay create may replace
  whiteout and mark new dir opaque in that txn; upper create events precede overlay events.
- For existing `O_CREAT` targets, `may_create_in_sticky` uses found parent + store/overlay/FUSE attrs
  (`protected_regular`=2, `protected_fifos`=1): in sticky world-writable dirs (also group-writable at 2),
  caller (incl. root) gets `EACCES` unless fsuid or dir owner equals file owner. Protected
  symlinks/hardlinks default on.
- Cgroup mkdir checks parent search, existing name/dots, RO, write/search; FUSE uses daemon. Store/overlay
  `mknodat` resolves parent, rejects dots, validates type (0/reg/chr/blk/FIFO/sock ok, dir `EPERM`, other
  `EINVAL`). Devices need initial-ns `CAP_MKNOD` + device BPF after admission; char whiteout (dev 0) skips
  both and is refused on overlay. `symlinkat` rejects empty target before reading new path.
- `unlinkat` accepts only `AT_REMOVEDIR`, resolving parent once before mqueue/cgroup/overlay/FUSE/store
  dispatch (other synthetic parents → `EROFS`/`EACCES` before dot/victim checks). Store unlink `.`/`..` →
  `EISDIR`, rmdir `.` → `EINVAL`, `..` → `ENOTEMPTY`; else writability precedes lookup. Plain unlink with
  trailing `/` → `ENOENT` (missing), `EISDIR` (dir) or `ENOTDIR` before delete perm. Store delete runs
  `may_delete` (search, victim lookup, immutable parent, write/search, sticky, then parent/victim protected
  flags), mount-point `EBUSY` and tmpfs victim inspection in `Store::remove_checked`'s removal write txn.
- `linkat` validates flags, requiring initial-ns `CAP_DAC_READ_SEARCH` with `AT_EMPTY_PATH` (`ENOENT`
  before path reads), resolving source before dest. Existing pseudo dest → `EEXIST`; overlay pairs and FUSE
  dest use own handlers, other mixed → `EXDEV`. Store checks dots/trailing `/` before parent search,
  `EEXIST`, `EROFS`, cross-mount `EXDEV` (even across binds). Protected hardlinks need owner/cap or rw
  regular source without setuid/group-exec setgid; then dir admission, source flags, tmpfs room,
  `Store::link` (dir `EPERM`, 0 links `ENOENT` except linkable tmpfiles, no `EMLINK`). Deleted regular
  files via proc fd keep opened mount; followed memfd stays synthetic (`EXDEV`), direct empty-path fd has
  store node on root mount.

### Rename and mount points

`renameat2` rejects unknown flags (including `RENAME_WHITEOUT`) and `NOREPLACE|EXCHANGE` before
copying paths. It resolves source then destination parent and checks final store parent search.
Different mount IDs give `EXDEV` before dots. A source dot gives `EBUSY`, a destination dot
`EEXIST` with `NOREPLACE` and `EBUSY` otherwise.

- Store writability precedes source lookup. One transaction looks up both entries, tests
  `NOREPLACE` (`EEXIST`) and missing exchange target (`ENOENT`), then trailing slashes.
  Destination slash requires a directory source for ordinary rename, directory destination for
  exchange. Moving into the source subtree gives `EINVAL`. Replacing an ancestor gives
  `ENOTEMPTY`, or `EINVAL` on exchange. Only then does the same-inode no-op succeed.
- `may_rename` checks source removal then destination removal/addition, including write,
  sticky and inode flags. Nonexchange type mismatch precedes write permission on reparented
  directories (both sides of exchange), then local mount-point checks. Replacement quota
  and open-name maintenance follow transaction success.
- Synthetic rename checks read-only, source lookup and, on procfs, destination lookup errors.
  `NOREPLACE` onto an existing name gives `EEXIST`, then identical nodes succeed. Otherwise
  mqueue/cgroup give `EPERM`. Other pseudo parents check write/search (`EACCES`), then give
  `EPERM`. Same-mount overlay and FUSE rename use their own handlers.
- `not_mount_point` rejects attached mount points in the caller's namespace (`EBUSY`). For
  store files it compares inode and recorded parent/name when available. After removal or
  nonexchange replacement, `detach_points` detaches mounts elsewhere and marks removed names
  used as attached/detached mount roots. Mountinfo can show `//deleted` even when a bound
  file has other hard links. A held last-unlinked store object can be released once no fd,
  mapping, executable, unshare or inotify open hold needs it.
- Moved open names follow rename even without active watches. With watches, move pairs share
  a cookie, followed by self/overwrite events. Exchange also moves the second side. Visible
  overlay events use overlay keys independently of backing-layer events.

### Directory iteration

`getdents64` rejects `O_PATH` with `EBADF`, non-directories with `ENOTDIR`, and removed store
directories with `ENOENT`. The open description caches its listing until a directory seek lands on zero.
Store, pseudo and overlay prepend `.` and `..`. FUSE supplies its own list.
Records align to eight bytes and `d_off` is the next index. Count narrows to 32 bits. While
entries remain, count above `INT_MAX` or room for no complete next entry gives `EINVAL`.
Partial usercopy returns bytes through the last complete record and advances only that far
(`EFAULT` if none). Successful EOF can still report `IN_ACCESS`.

### Boot devices and host configuration

Import runs `ensure_dev_nodes` transactionally (errors propagate); boot logs persistent setup failures.
Creates missing `/proc`, `/sys` (0555), `/run`, `/dev` (0755), populating `/dev` if a dir. `populate` adds
missing root-owned entries to store and boot tmpfs without overwriting existing types/modes: char 0666
(`null`, `zero`, `full`, `random`, `urandom`, `tty`, `ptmx`, `fuse`), `kmsg` 0644, `console` 0600; gid 6
(`disk`) 0660 char `loop-control` (10:237) + block `loop0`–`loop7` (7:0–7); symlinks
`fd`/`stdin`/`stdout`/`stderr` → `/proc/self/fd` (+ `/0`,`/1`,`/2`); dirs `pts` (0755), `shm` (1777),
`mqueue` (0755).

During rootfs-backed boot (`Vfs::open_with_gpu`), if tmpfs creation or base population fails, a
usable store `/dev` serves as fallback; renderer-enabled VFS startup requires the fresh
`DEV_MOUNT` or returns `ENODEV` (a machine without a rootfs skips `Vfs::open_with_gpu` and can
keep a supplied renderer without `/dev`). Only `DEV_MOUNT` receives `populate_gpu`'s root-owned
`dri` (0755) and `dri/renderD128` (char 226:128, 0666) in one transaction (errors propagate).
Char open dispatches by device number regardless of path; `Device::from_rdev` handles major 1
minors 3, 5, 7, 8, 9, 11 (both randoms → `Random`) and loop-control (terminal, render and FUSE
precede it; unknown → `ENXIO`); block opens go to `kernel/loop`.

Host config helpers validate the whole absolute path first (NUL/`..` `EINVAL`, long component
`ENAMETOOLONG`), skip empty/`.` components and walk store names without following symlinks.
`write_file_owned` creates root-owned 0755 parents and applies uid/gid to new final files; with
overwrite off, existing entries stay untouched; overwrite and `read_file` require a regular file
(overwrite keeps owner/mode). Boot seeds missing `/etc/resolv.conf` (`nameserver 127.0.0.53`,
0644) when `/etc` exists.

## Dependencies (inbound / outbound)

### Inbound

- Dispatcher, `kernel/files`, `kernel/fileio`, `kernel/exec`, `kernel/mm` (lookup, metadata,
  mount policy, accounting); `kernel/process` (`Cached`, fs-context invalidation); `kernel/mount`
  (walks, tmpfs); `kernel/overlay` (layer pins, upper charges); `kernel/xattr` (lookup, flags);
  `kernel/unix` (bind); procfs/epoll/locks (identities); boot/import/host wrappers (devices, config,
  offline lookup).

### Outbound

- `kernel/store` (snapshots, readers, metadata, transactions, objects); `kernel/mount` (topology,
  flags); `kernel/overlay`, `kernel/fuse`, `kernel/files` (fd identity/holds); eviction scans
  mappings, executables, sockets, loops and inotify open records.
- `kernel/procfs` (`View`, `kernel/sysfs`, `kernel/sysctl`), `kernel/ipc`, `kernel/tty`,
  `kernel/cgroup` (ops + device BPF), `kernel/cred`, `kernel/xattr` (ACLs), `kernel/inotify`,
  `appine_hal` (host file/fs/memory).

## Invariants and gotchas

- Preserve reached mount for scope, `..`, `EXDEV` and stat; absolute lookup and `..` at process
  root never lift it; store mount-point busy checks compare namespace and entry.
- A `Node` alone pins neither FUSE entries nor overlay orphans (FUSE path fds pin entries; cwds,
  mounts and watches join sweeps). Preserve overlay identity/holds on opens; waits retaining a
  looked-up node stay out of `waits_on_files_only`; end file borrows before waits or BKL release.
- Propagate `FUSE_RETRY` before irreversible state changes and never swallow it as absence. Report
  overlay events by visible key. Check quota before mutation, charge after success and transfer a
  tmpfile's charge on first link.

## Confirmed bugs / technical debt

- `Attrs::of_fd` selects node-less devpts slaves/mqueues before mount RO checks (`fchmod` can pass where
  `chmod` gives `EROFS`).
- `file_in_use`/`live_store_inodes` omit pending AIO polls (`PendingPoll`), and unlink-time `file_in_use`
  skips unborrowable descriptions, so live unlinked tmpfs regular files can lose quota charges early.
- `RESOLVE_CACHED` can do store I/O or FUSE lookup; `statx` lacks birth time and `STATX_DIOALIGN`.
- File-mount traversal matches a node (same-mount hard links enter it), while busy checks can distinguish
  recorded parent/name.
- Host punch `EOPNOTSUPP` can leave zero-filled extents charged after logical pages are forgotten; dynamic
  boot `/dev` loop-node changes bypass quota/changed flags until fresh count.
- Without VFS, anon mode/time mutations lack shared storage (pipe fstat falls back to 0600, root, zero
  times; socket ownership persists on socket).

## Open questions

None.
