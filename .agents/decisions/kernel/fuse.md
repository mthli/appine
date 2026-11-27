# FUSE (kernel/fuse) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: kernel/fuse"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12)
>
> The VFS side of FUSE (the `Node::Fuse` kind, `/dev/fuse` as a file kind, key bits and the sweep's view of mounts) is kernel/vfs's D47 and D51; FUSE becoming a module of its own is knowledge-loop D7.

## Active

### D1: FUSE follows Linux 6.1's `fs/fuse`, with walks reading a node cache that a syscall retry fills

- **What**: Connections in `vfs/fuse.rs` follow Linux's queue, interrupt and abort rules; walks read names, attributes and links from a node cache (`Fuses`) and return `FUSE_RETRY` when they need the daemon, after which `serve()` answers one query and the syscall runs again with a per-syscall token keeping what the retries fetched; file syscalls in `syscall/fuse.rs` send change requests with the BKL released after all of a handler's walks; programs are read into memfds; INIT offers ATOMIC_O_TRUNC, EXPORT_SUPPORT, BIG_WRITES, DONT_MASK, NO_OPEN(DIR)_SUPPORT, PARALLEL_DIROPS, HANDLE_KILLPRIV and MAX_PAGES but not ASYNC_READ; FLUSH is forced; one `O_APPEND` write per node runs at a time; and parents are kept free of loops (`ELOOP`). Convert daemon metadata through the synthetic Stat defaults.
- **Why**: sshfs, rclone mount and other FUSE daemons could not run, and Starnix's FUSE depends on Zircon, so Linux 6.1's `fs/fuse` is the model.
- **Tradeoffs**: Locks stay local, and fuseblk, fusectl, readdirplus and writeback cache remain outside the negotiated interface.
- **Watch out**: Retryable walks must precede state changes, and directory operations that release the BKL must use the directory and rename guards.
- **Source**: c008749, 68b70897, b421eac0, 9e45d0f3, b3b7ee74

### D2: Mapped pages are read on demand, and unused nodes are forgotten by a sweep between syscalls

- **What**: mmap of a FUSE file makes a `Fill` (kernel/mm D40) that reads pages through the mapped file's handle as they are touched, before read and write syscalls for their buffers (`prefault`) and in full with `MAP_POPULATE` (shared mappings of `FOPEN_DIRECT_IO` files are `ENODEV`); nodes count lookups and connections count open handles per node, and when the adaptive node threshold is crossed or on `drop_caches` 2 or 3 a sweep at a syscall's end keeps every node that tasks' roots and cwds, fds (`O_PATH` too), running programs, mounts, inotify watches, open handles and requests in flight use, with their directories, and forgets the oldest leaves (down to 12288, or all with `drop_caches`) through `FUSE_FORGET` or `FUSE_BATCH_FORGET` interleaved with requests as 6.1 does; nodes `drop_caches` finds kept only by a `RELEASE` or `RELEASEDIR` in flight lose their names and wait in `Conn::doomed`, and the sweep after that release's answer forgets those nothing keeps any more that are leaves. Path descriptions pin nodes until last close and cache sweeps use an adaptive live-node threshold with explicit handling for unlinked files.
- **Why**: Without forgetting, the node cache and the daemon's node table grew with every name looked up until unmount, a mapping read its whole range at once, and a closed file's node kept at `drop_caches` only by its release was forgotten late or never, where Linux evicts it once the release lets go.
- **Tradeoffs**: Each bulk sweep scans holders, mappings retain source handles until unmap, and doomed nodes can wait for outstanding releases or children.
- **Watch out**: Register every new persistent or in-flight node holder with the pin or in-use mechanism before it can cross a syscall boundary.
- **Source**: 3957a03, dbfce1d, 515ecd7f, 00550dc9

### D3: A lazily unmounted connection aborts when the VFS collects its last mount

- **What**: The instance aborts in `Vfs::collect` once no attached or detached mount of it remains, which now also happens when the last open file, cwd or root of a lazily unmounted mount goes (kernel/vfs D48), rather than at `MNT_DETACH` or when the last FUSE handle closes. Connection abort wakes both groups of waiters and collection retains a fallback when the connection is already borrowed.
- **Why**: `fusermount -uz` and `umount -l` of a busy sshfs or rclone mount are common, and the daemon kept running until reboot or an unrelated umount.
- **Tradeoffs**: A running program's executable on the mount does not keep it, where Linux's does.
- **Watch out**: The detached mounts kernel/mount's map lists as outliving their last use (never-attached open_tree clones, mounts whose mount point was removed, holders that release the root mount) keep their daemon running until an unrelated collection.
- **Source**: 922e015, 1dab8c16

### D4: Releases that find the connection borrowed are queued per thread until the syscall ends

- **What**: An open file's last reference sends its `Released` record (connection, node, release) at once, or, when the connection is borrowed, queues it on the thread for `flush_released` at the end of every syscall and at thread exit, and a queue left when the thread ends is leaked rather than dropped without the BKL.
- **Why**: A release dropped while the connection was borrowed was lost, leaking the daemon's handle and the node's open count.
- **Tradeoffs**: A deferred release reaches the daemon at the end of the syscall rather than at the drop.
- **Watch out**: Drops on threads that never end a syscall or exit through the guest path (none known) would keep their releases queued.
- **Source**: 6d9a9e8

### D5: FUSE `mknod` restricts the whiteout exemption to char `0:0` and enforces the cgroup device BPF filter

- **What**: `syscall::fuse::mknod` exempts only character device `0:0` (`S_IFCHR && dev == 0`) from `CAP_MKNOD` and calls `bpf::check_device(..., ACC_MKNOD)` after `CAP_MKNOD`.
- **Why**: FUSE `mknod` previously exempted block `0:0` from `CAP_MKNOD` and bypassed the cgroup device BPF filter.
- **Tradeoffs**: `CAP_MKNOD` and cgroup device checks remain duplicated between store/overlay `mknodat` and `syscall::fuse::mknod`.
- **Watch out**: Future changes to `mknod` device policy must keep both `syscall/fs.rs` and `syscall/fuse.rs` in sync.
- **Source**: b51818cf

### D6: FUSE mapping `fill` falls back from short read-around and loops until a full page or EOF

- **What**: `syscall::fuse::fill` stores any complete pages from read-around, switches to `fill.window_from(page)` if the faulted page was not reached, and loops reading the first page's remainder until `PAGE_SIZE`, `fill.size()`, or a 0-byte EOF reply. Bounded page fills also serve socket data and control buffers and forced remote I/O, skipping a device served only by the dying process.
- **Why**: Short `FUSE_READ` replies during read-around could leave the faulted page unmarked and cause an infinite guest fault loop.
- **Tradeoffs**: Daemons returning sub-page chunks incur multiple `FUSE_READ` round trips to satisfy one faulted page.
- **Watch out**: Even tiny daemon replies make bounded forward progress (`PAGE_SIZE` iterations per page) and remain interruptible by signals between reads.
- **Source**: 0e02d7cd, 4309ef58, 865d24c1

### D7: FUSE emits syscall-layer inotify events, syncs `O_SYNC` writes via `FUSE_FSYNC`, and uses RAII `PosGuard`

- **What**: FUSE operations emit syscall-layer inotify events (`Attrs::Fuse`, `inotify::unlinked`, and `make_node` deduplicating MKNOD-fallback `IN_CREATE`), synchronous writes call `fuse::fsync` via `write_sync`, and `fuse::read`, `fuse::write` and `fuse::lseek` guard `pos_busy` with `PosGuard`. Report the pending executable open before reading the FUSE image snapshot.
- **Why**: FUSE metadata/truncation/xattr/`O_TRUNC` changes missed inotify hooks, MKNOD fallback emitted `IN_CREATE` twice, `IN_EXCL_UNLINK` broke on FUSE directories, `O_SYNC` writes did not flush, and FUSE `lseek`/I/O lacked uniform RAII position locking.
- **Tradeoffs**: Synchronous FUSE writes (`O_SYNC`/`O_DSYNC`/`RWF_SYNC`/`RWF_DSYNC`) send a `FUSE_FSYNC` round trip to the daemon, while `PosGuard::lock_if` eliminates manual error-path unlock calls.
- **Watch out**: Genuine `FUSE_FSYNC` errors now surface to synchronous writers (`ENOSYS` is cached in `no`), and `PosGuard` must be dropped before any blocking wait outside the FUSE I/O body.
- **Source**: ab694aab, 3a90a0ef, 93a9b204

### D8: Shared FUSE mappings acknowledge dirty byte ranges and invalidate stale fills

- **What**: Share backing across mappings, send contiguous dirty runs, acknowledge only accepted bytes and discard fills invalidated by notifications or open replies.
- **Why**: Descriptor and mapped I/O must agree on contents.
- **Tradeoffs**: Writeback tracks dirty bytes and can require multiple requests.
- **Watch out**: A delayed reply must never restore invalidated data.
- **Source**: b421eac0, c008749d, 3957a033

### D9: FUSE namespace changes serialize per directory and pin socket nodes

- **What**: Hold killable directory and rename guards across changes, pin locked or bound nodes, and batch consecutive plain lookup components.
- **Why**: RPC waits otherwise allow conflicting directory changes or premature eviction.
- **Tradeoffs**: Lock contention can suspend a request.
- **Watch out**: Release guards on interruption and preserve socket-node pins while bound.
- **Source**: 9e45d0f3, b3b7ee74, c008749d

### D10: FUSE stat and directory seek use the open handle's actual capabilities

- **What**: Pass a regular FUSE file handle to GETATTR when eligible and support directory seek from the daemon size, clearing cached entries on rewind.
- **Why**: Open handles can carry attributes and seek behavior that pathname lookup misses.
- **Tradeoffs**: Fetching directory size can involve an RPC.
- **Watch out**: Honor NONSEEKABLE and STREAM and exclude O_PATH and directory handles from regular-file GETATTR.
- **Source**: 9e45d0f3, c008749d

### D11: FUSE executable mappings retain node keys and display daemon inode numbers

- **What**: Pin program and interpreter node keys in the in-use set and resolve those keys when rendering proc maps.
- **Why**: Store inode lookup cannot describe a FUSE image.
- **Tradeoffs**: Formatting a mapping may consult the FUSE node cache.
- **Watch out**: FUSE paths must bypass store deletion marking.
- **Source**: 4905fcba, ab417943

### D12: FUSE honors exec privileges, pinned `/proc/<pid>/exe`, and in-process FIFO/device opens

- **What**: Apply `mnt_may_suid`, `Query::FileCaps` (`FUSE_GETXATTR` mapped through `conn.user_ns()`), and `!HANDLE_KILLPRIV` stripping (shared via `truncate_with_killpriv`), pin `FuseExe` on `ExeFile` so linked `/proc/<pid>/exe` follows `Node::Fuse` while unlinked executables reopen via `FileKind::FuseSnapshot`, and dispatch `S_IFIFO`/`S_IFCHR`/`S_IFBLK` in-process with `MS_NODEV`/`SB_I_NODEV` and cgroup BPF checks without sending `FUSE_OPEN`.
- **Why**: FUSE executables previously acted as `nosuid` and lost `/proc/<pid>/exe` identity across rename or unlink, while special files on FUSE failed with `ENXIO` instead of matching Linux `init_special_inode`.
- **Tradeoffs**: Non-nosuid FUSE execs perform one extra `FUSE_GETXATTR` retry when xattrs are supported, unlinked exe reopens serve the exec-time memfd snapshot instead of re-opening on the daemon, and `syscall/fuse.rs` keeps small shared truncation/pinning and unit-test harness helpers.
- **Watch out**: Future `FileKind` or `OpenFile::node()` consumers must handle `fuse::id_of` keys and `FileKind::FuseSnapshot`.
- **Source**: 7b505898, 263e23bc

### D13: `/dev/fuse` reads and splices commit queue state only after delivery succeeds

- **What**: Stage deliverable messages via `Conn::peek_forget`, `Conn::stage_read`, `Conn::commit_read`, and `dev_read_with` so scalar `read`, vectored `readv`, and `/dev/fuse`-to-pipe `splice` deliver the full message with `Conn` unborrowed under the BKL and commit queue, `ReqState::Sent`, `next_unique`, and `forget_batch` state only on `Ok`.
- **Why**: `dev_read` previously marked requests `Sent` and queued interrupts before copying to daemon memory or checking pipe capacity, hanging foreground callers on `EFAULT` or `EIO`.
- **Tradeoffs**: The delivery callback must hold the BKL without yielding so no concurrent reader can stage the same message, and a partial `EFAULT` still leaves the copied prefix in daemon memory.
- **Watch out**: Any future `dev_read_with` caller releasing the BKL inside its delivery callback could duplicate a staged message to another reader.
- **Source**: 0160070e

## Superseded

- ~~Mappings are copied whole into memfds, so mmap reads the whole mapped range at once, and nodes are never forgotten (D1's tradeoffs, c008749)~~ → replaced by **D2** in 3957a03 (2026-11-09, no SUPERSEDES declared)
- ~~`drop_caches` leaves nodes kept only by requests in flight to a later sweep (D2 in part, 3957a03)~~ → replaced by **D2** in dbfce1d (2026-11-11)
- ~~Leaving O_PATH descriptions in transit out of FUSE node retention~~ → replaced by **D2** in 515ecd7f (2026-11-24)
- ~~Isolating each mapped backing and refusing shared writable FUSE mappings~~ → replaced by **D8** in b421eac0 (2026-11-24)
- ~~Rescanning every new cache entry above the threshold and deferring forget after last unlink~~ → replaced by **D2** in 00550dc9 (2026-11-25)
- ~~Letting conflicting FUSE namespace changes proceed concurrently within a directory~~ → replaced by **D9** and **D10** in 9e45d0f3 (2026-11-25)
- ~~Regular FUSE programs ignore set-ID bits (D1's tradeoffs, c008749)~~ → replaced by **D12** in 7b505898 (2026-11-26, no SUPERSEDES declared)
