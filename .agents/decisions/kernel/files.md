# Open files (kernel/files) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: kernel/files"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12)
>
> Decisions on open files made before kernel/files was split from kernel/vfs keep their `kernel/vfs` tags and live in `.agents/decisions/kernel/vfs.md`: D5, D11, D17, D22, D24, D26, D27, D29, D33, D34, D41, D44, D45, the owner half of D25, the `open_pseudo` half of D19 and the file I/O halves of D36 and D37; the pre-split fd reservation decision is D55.

## Active

### D1: kernel/files owns fd tables, open descriptions, opening and control

- **What**: kernel/files covers `gpu.rs`, `syscall/gpu.rs`, `fd.rs` except eventfd/timerfd, the Opening, closing, duplicating section of `syscall/fs.rs` (excluding `write_at`, `clone_range`, clone/dedupe ioctl arms, overlay, lock and pipe code) and shared helpers `executing` and `open_for_write`, after splitting data I/O, size changes, syncing and object sharing into `kernel/fileio`.
- **Why**: `kernel/files.md` reached 79.9% of a single read while combining open-file lifecycle and control with file data I/O, size changes, syncing and object sharing.
- **Tradeoffs**: Changes spanning `OpenFile` fields (`pos_busy`, `cow`, `retired`), `O_TRUNC` or `FICLONE` ioctl dispatch now read both `kernel/files` and `kernel/fileio`, and pre-split decisions remain in `.agents/decisions/kernel/files.md` and `.agents/decisions/kernel/vfs.md`.
- **Watch out**: Open-file changes must stay aligned with `kernel/fileio` (`OpenFile` borrow-across-BKL-release rules, `O_TRUNC` unsharing/truncation hooks, clone ioctl dispatch), `kernel/vfs` (resolution, mount flags, tmpfs accounting), `kernel/cgroup` (device checks and observation) and `kernel/procfs` (synthetic opens).
- **Source**: ce193253; 20137e4c; cf029f9a; d053be24; 39e55b4b; 4fcd3e13

### D2: Reopens retain their mount and normal open checks

- **What**: `StoreOpen` routes reopens through open checks on their original mount with the path and IN_OPEN event, keeps reopened memfds as writers, and lets truncate through fd links use the open handle for deleted files and memfds. Path descriptions also retain mqueue and FUSE pins, PTY backing identity, FIFO watches and the regular backing of deleted proc-fd targets through stat and reopen. Executable write-denial also counts writable opens held alive by FUSE mappings.
- **Why**: Procfs reopens bypassed mount and open restrictions, while truncating deleted files or memfds through fd links did nothing.
- **Tradeoffs**: Reopens now expose normal EROFS, O_NOATIME, ETXTBSY and lease errors, and writable memfd reopens block execution.
- **Watch out**: Container runtimes reopening through `/proc/self/fd` can now receive errors they previously bypassed.
- **Source**: 01bfd882; 20137e4c; d053be24; 39e55b4b, d10f2973, f495f2cc, 515ecd7f, 11816722, 30e5002e, f985c1f8, b421eac0

### D3: File I/O preserves position ownership and partial progress

- **What**: Writes acquire `pos_busy` before checks and apply RLIMIT_FSIZE once per call, rollbacks are bounded by actual movement, copy_data returns progress, ftruncate waits without a held borrow, and make_private rehomes only held or COW objects.
- **Why**: Chunked writes, device reads and truncation could miscount progress, signal spuriously, deadlock or leak host fds.
- **Tradeoffs**: The existing lseek race outside `pos_busy` remains.
- **Watch out**: New I/O paths must preserve the same position, borrow and partial-progress rules.
- **Source**: 01bfd882; 20137e4c; d053be24; 39e55b4b

### D4: sendfile shares directional splice checks

- **What**: sendfile requires a seekable, splice-readable input and a splice-writable output unless the output is a pipe, using kernel/pipe’s predicates.
- **Why**: A separate permissive kind list returned the wrong errors for devices and unsupported outputs.
- **Tradeoffs**: Unsupported ends fail before reading, and FUSE inputs use the chunked fallback.
- **Watch out**: Programs expecting ENOSPC from sendfile into `/dev/full` now receive EINVAL.
- **Source**: 01bfd882; 20137e4c; d053be24; 39e55b4b

### D5: Replacing an fd closes it in the target process context

- **What**: The shared `close_replaced` helper releases locks, mqueue notifications, FUSE flush ownership and Unix references using the target table and tgid, including seccomp ADDFD.
- **Why**: ADDFD previously closed the target’s old fd as the supervisor.
- **Tradeoffs**: The replacement paths share an additional helper.
- **Watch out**: Future fd replacement paths must use the same target-context close.
- **Source**: 01bfd882; 20137e4c; d053be24; 39e55b4b

### D6: Render-node open descriptions carry per-open virtio-gpu context state

- **What**: `gpu.rs` and `syscall/gpu.rs` provide the virtio-gpu render ioctl subset with per-open context state, guest resource handles and capability reporting on the open file description.
- **Why**: Packaged Mesa Venus expects standard virtio-gpu render ioctls without modifying the guest driver.
- **Tradeoffs**: PRIME and kernel syncobj ioctls stay outside the implemented subset.
- **Watch out**: Future Mesa versions may issue additional render ioctls.
- **Source**: 87c7d9db

### D7: `open_pseudo` pins weak `Mm` on `pagemap` and passes `ThreadGroup` to `mqueue_closed`

- **What**: `open_pseudo` pins a weak `Mm` on `FileKind::Proc::mm` for both `Entry::Mem` and `Entry::Pagemap` and resolves targets via `group_of_id`, while `close`, `close_range`, `dup3` and `close_replaced` pass `&Rc<RefCell<ThreadGroup>>` to `mqueue_closed`.
- **Why**: `Entry::Pagemap` previously stayed unpinned across `execve` and PID reuse, and `open_pseudo`, `reopen` and `mqueue_closed` callers relied on bare PIDs.
- **Tradeoffs**: Open `pagemap` descriptors retain a weak `Mm` reference until closed.
- **Watch out**: `Entry::Pagemap` shares weak `Mm` lifetime semantics with `Entry::Mem`, returning EOF once the target address space is released.
- **Source**: b51818cf

### D8: `OpenFile::drop` releases node mounts and anonymous `open_tree` namespaces, and mqueue opens record their mount

- **What**: `OpenFile::drop` releases `node.mnt()` when distinct from `self.mnt` and `ANY_MOUNT` when `self.tree` is set, and `open_path_with` sets `file.mnt` for `PseudoNode::Mq` opens.
- **Why**: `O_PATH`, synthetic, overlay-directory, `open_tree` and mqueue fds previously kept `ROOT_MOUNT` and failed to trigger detached or anonymous mount collection on close.
- **Tradeoffs**: Dropping an `open_tree` fd with `tree` set always triggers a `collect_released` pass at syscall end even if the clone was already attached.
- **Watch out**: Future pseudo nodes backed by user-visible mounts that should block `umount2` need matching `file.mnt` assignment or `try_in_use` handling.
- **Source**: 0e02d7cd

### D9: `F_GETFL` preserves open-time status flags and `CLOSE_RANGE_UNSHARE` enforces `nr_open`

- **What**: `kept(flags)` retains `O_ASYNC | O_DIRECT | O_NOATIME | O_DIRECTORY | O_NOFOLLOW` (and `O_PATH | O_DIRECTORY | O_NOFOLLOW` for `O_PATH`), `F_SETFL` registers fasync whenever `O_ASYNC` is set, and `CLOSE_RANGE_UNSHARE` enforces `nr_open` (`EMFILE`) before cloning a shared `FdTable`.
- **Why**: `F_GETFL` previously stripped open-time flags that Linux retains in `do_dentry_open`, and `CLOSE_RANGE_UNSHARE` skipped `nr_open` checks.
- **Tradeoffs**: `F_GETFL` now exposes open-time flags such as `O_DIRECTORY` and `O_NOFOLLOW`.
- **Watch out**: Internal callers checking `f.flags` must continue masking the specific bits they care about rather than comparing the full flags word for equality.
- **Source**: ab694aab

### D10: `open_path_with` shares `resolve_create_parent` for `O_CREAT` parent resolution

- **What**: `open_path_with` routes `O_CREAT` parent-directory resolution and trailing-slash/overlay setup through the shared `resolve_create_parent` helper in `syscall/fs.rs`. For a read-only superblock, store opens check filesystem writability before DAC, while a read-only bind over a writable instance retains its distinct check order.
- **Why**: Inline creation-parent resolution duplicated logic already shared with namespace-creation syscalls.
- **Tradeoffs**: `open_path_with` creation setup is coupled to `resolve_create_parent`'s error ordering.
- **Watch out**: Changes to `resolve_create_parent` error ordering affect `openat`/`openat2` `O_CREAT` paths alongside `mkdirat`, `mknodat` and `symlinkat`.
- **Source**: 3a90a0ef, e6495409

### D11: Ordinary tmpfs files report F_SEAL_SEAL

- **What**: Look up recorded memfd seals first and otherwise return F_SEAL_SEAL for regular tmpfs files.
- **Why**: Linux treats these files as permanently closed to new seals.
- **Tradeoffs**: The query needs to distinguish tmpfs from other regular files.
- **Watch out**: Attempts to add valid seals return EPERM.
- **Source**: 48f1c17e, f6a8240a

### D12: Store and tmpfs implement unnamed file creation

- **What**: Open O_TMPFILE with normal parent permission, owner and ACL checks, track its unlinked quota, and permit later linking only when the file was created linkable.
- **Why**: Applications need a file that can be populated before publication.
- **Tradeoffs**: Overlay and FUSE directories return EOPNOTSUPP.
- **Watch out**: Retain the deleted proc-fd display name and release unused objects and quota on last close.
- **Source**: 68b70897, 65e10e36, ab417943

### D13: Fd-table growth and reservations respect the active limits

- **What**: Bound installation, duplication and reservations by the owning process and nr_open, remember allocated capacity on close, and size copied tables from surviving slots.
- **Why**: A shared table can serve callers with different limits and procfs exposes its capacity.
- **Tradeoffs**: Allocated capacity rounds to fd-table granularity.
- **Watch out**: Accept and SCM_RIGHTS reservations must use the same cap as ordinary installs.
- **Source**: 4309ef58, 576501d3, e6495409, 661d5f01

### D14: Final descriptor processing uses table order

- **What**: Visit files in descriptor order and clone only handles retained across exit flushing.
- **Why**: Exit needs ordered close semantics without copying the whole table.
- **Tradeoffs**: The borrowed iterator cannot outlive the table borrow.
- **Watch out**: Duplicated descriptions still receive per-descriptor close processing.
- **Source**: 865d24c1

### D15: open_by_handle_at verifies superblock identity

- **What**: Compare the mount's store root with the handle inode's tree root and reject stale or unlinked targets.
- **Why**: A valid inode number can belong to a different filesystem instance.
- **Tradeoffs**: Resolution requires a root lookup.
- **Watch out**: Mismatches return ESTALE.
- **Source**: 6fcaff37

### D16: A thread group can reopen its own proc fd directories for reading

- **What**: Use the shared live-group permission helper for nonwrite opens of fd and fdinfo.
- **Why**: A nondumpable task must retain access to its own descriptors.
- **Tradeoffs**: The open mask distinguishes reading these directories from searching ns.
- **Watch out**: If a future non-path open uses only MAY_EXEC, revisit the namespace-directory exemption.
- **Source**: 36005e07, 3ad68857

### D17: `open_pseudo` permits zombie-leader memory opens and `EventFd` tracks reusable per-instance IDs

- **What**: Gate ptrace-checked procfs opens on `pseudo::pid_exists_in` while allowing `Mem`, `Pagemap`, and `Environ` to open on unreaped zombie leaders with `mm = None` (EOF on I/O), and allocate lowest-free `EventFd::id` values from `Kernel::eventfd_ids` released in `Drop`.
- **Why**: Linux 6.1 allows opening zombie-leader `mem`/`pagemap` descriptors as EOF while rejecting exited non-leader threads, and procfs `fdinfo` requires reusable per-instance `eventfd-id` numbers.
- **Tradeoffs**: Implementing `Drop` on `EventFd` prevents struct update syntax in unit tests, which construct defaults through `EventFd::default()`.
- **Watch out**: Dropping an `EventFd` after `Kernel` teardown skips ID release safely via `Weak::upgrade`.
- **Source**: b9497c7e

### D18: Open-file helpers share FUSE special-file opens and `FileKind::FuseSnapshot` handling

- **What**: Share `open_char_device` and `open_fifo` across store/overlay and FUSE opens, resolve FUSE keys to `Node::Fuse` in `OpenFile::node()`, pin non-handle FUSE opens via `OpenFile::pin_fuse_path()`, and pass `FuseSnapshot` through `open_fuse_snapshot` and `encode_fuse_snapshot_stat` to serve deleted FUSE `/proc/<pid>/exe` files across I/O, stat, mmap, poll and inotify.
- **Why**: Opening FIFOs and devices on FUSE and reopening deleted FUSE executables require unified open-file dispatch and FUSE node-key identity without duplicating device or stat plumbing.
- **Tradeoffs**: `OpenFile::node()` checks `fuse::id_of(ino)` across pipe, device, PTY, render and `FuseDev` kinds, and `open_fuse_snapshot` callers construct `FuseSnapshot` before permission checks.
- **Watch out**: Store inode numbers must stay below `2^62` so they never collide with `fuse::key(id)`.
- **Source**: 7b505898, 263e23bc

### D19: Plain `openat(O_CREAT)` resolves parents in one walk with deferred `display_path` and stack path reads

- **What**: Resolve the parent first in `open_path_with` for plain final components when `O_CREAT` is set with no `RESOLVE_*` flags (falling back to a full walk for non-`O_EXCL` symlinks), defer `display_path` until open succeeds with a `dir == root` shortcut, and read guest paths into a 256-byte stack-inline `PathBytes` buffer via `Mm::read_cstr_into`.
- **Why**: Creating files walked the full path twice, `display_path` climbed to root even on failed or magic-link opens, and `read_path` heap-allocated a `Vec<u8>` on every path syscall.
- **Tradeoffs**: `openat2` with non-zero `RESOLVE_*` flags stays on `walk_resolve` to preserve `BENEATH`/`NO_XDEV` root history, and `PathBytes` uses 264 bytes of stack space.
- **Watch out**: Any new `open_path_with` branch overriding `display_path` must set `display_set = true`, and `Vfs::resolve_parent_with` error-order changes now affect the `O_CREAT` fast path.
- **Source**: 99a9b186

## Superseded

- ~~`kernel/files` combined open descriptions, opening, data I/O, lseek, syncing, truncation, fallocate and shared helpers (D1, ce193253)~~ → replaced by **D1** in 4fcd3e13 (2026-11-23)
- ~~Reporting EINVAL when querying seals on an ordinary tmpfs file~~ → replaced by **D11** in f6a8240a (2026-11-24)
