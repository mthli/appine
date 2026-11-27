# File data I/O (kernel/fileio) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: kernel/fileio"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12)
>
> Decisions on file data I/O made before kernel/fileio was split from kernel/files (and before kernel/files was split from kernel/vfs) keep their parent tags and live in `.agents/decisions/kernel/files.md` (D3, D4 and the truncate-through-fd-link half of D2) and `.agents/decisions/kernel/vfs.md` (D11, D22, D24, D29, D33, D45, the `write_sync` half of D17, the write-path half of D41 and the file I/O halves of D36 and D37).

## Active

### D1: kernel/fileio owns file data I/O, size changes, syncing and object sharing

- **What**: `kernel/fileio` covers `write_at`, `clone_range`, the `FICLONE`/`FICLONERANGE`/`FIDEDUPERANGE` ioctl arms, Reading and writing (excluding overlay copy-up, pipe reads/writes and splice), `lseek`, Cache hints and syncing, truncate and fallocate, and shared helpers `check_seals`, `seals_permit`, `remove_privs` and `truncating` in `syscall/fs.rs`.
- **Why**: Separating file data I/O, seeking, size changes, cache hints, syncing and object sharing/unsharing from `kernel/files` keeps both maps well within the single-read budget.
- **Tradeoffs**: Pre-split decision history remains in `.agents/decisions/kernel/files.md` and `.agents/decisions/kernel/vfs.md`.
- **Watch out**: Changes touching `OpenFile` position locking (`pos_busy`), `cow`/`retired` handles, `O_TRUNC` or clone ioctl dispatch require reading `kernel/fileio` together with `kernel/files`.
- **Source**: 4fcd3e13

### D2: Pagemap reads use the pinned `Mm` and offset-0 procfs reads check for gone processes

- **What**: `IoPath::Pagemap` carries `Option<Rc<RefCell<Mm>>>` upgraded from `FileKind::Proc::mm` (returning EOF once released), and `refresh_pseudo` skips its `fresh` fast-path when `pseudo::gone` is true so `pseudo::content` returns `ESRCH`.
- **Why**: `IoPath::Pagemap` previously looked up the live task by `Tid` on every read, and `refresh_pseudo` could serve stale open-time content at offset 0 for an already-reaped process.
- **Tradeoffs**: The first read at offset 0 of a process procfs file performs one `pseudo::gone` check under the BKL.
- **Watch out**: Non-zero offset reads still serve the existing snapshot buffer per Linux `seq_file` behavior, while only offset-0 reads regenerate and report `ESRCH`.
- **Source**: b51818cf

### D3: Per-request `IoFlags` thread `RWF_NOWAIT` and `RWF_APPEND` through AIO and `preadv2`/`pwritev2`

- **What**: `IoFlags { nowait, append }` and `read_file_with`/`write_file_with`/`vectored_with` thread per-request `RWF_NOWAIT` and `RWF_APPEND` through regular-file and FUSE I/O for native AIO and `preadv2`/`pwritev2` (`pvectored`) without mutating `OpenFile::flags`.
- **Why**: Native AIO needed per-request `RWF_NOWAIT` and `RWF_APPEND` without mutating the shared open file description, and `preadv2`/`pwritev2` previously rejected both flags.
- **Tradeoffs**: Internal helper signatures in `syscall/fs.rs` and `syscall/fuse.rs` are slightly wider while existing callers stay on `IoFlags::default()`.
- **Watch out**: Future per-request I/O flags should extend `IoFlags` rather than mutating shared `OpenFile::flags`.
- **Source**: 15347732, ab694aab

### D4: RAII `PosGuard` and `TwoPosGuard` serialize `f_pos_lock` updates across file I/O

- **What**: `PosGuard` (`lock_if`, `lock_if_with`, `lock_positioned`) and pointer-ordered `TwoPosGuard` serialize `pos_busy` across `lseek`, `read_file`, `write_file`, `writev`, `sendfile`, `copy_file_range`, `copy_data` and `splice`, keeping `take_pos`/`release_pos` private (alongside `open_tmpfs_size` for overlay-aware tmpfs size queries). Ordinary proc mem transfers and lseek also take the open description's position lock.
- **Why**: `lseek` and other position-moving paths raced with unlocked I/O, and regular-file and FUSE paths previously duplicated raw acquire/release logic.
- **Tradeoffs**: Holding `pos_busy` across multi-piece calls serializes concurrent position-based operations on one description, and external callers (`kernel/fuse`) acquire `f_pos_lock` exclusively through RAII `PosGuard`.
- **Watch out**: Helpers called while a `PosGuard` is held must receive `pos_held = true` to avoid self-deadlock, and any future non-RAII unlock pattern would need an explicit `PosGuard` method.
- **Source**: ab694aab, 3a90a0ef, 865d24c1

### D5: Synchronous `writev` and kernel copies flush once via `write_sync` and propagate errors

- **What**: `vectored_inner` invokes `write_sync` once after the iovec loop and returns sync errors cleanly, and `sendfile`, `copy_file_range` and `splice` call `write_sync` for `O_SYNC`/`O_DSYNC` destinations.
- **Why**: Synchronous `writev` previously synced per iovec while masking sync errors, and kernel copy paths did not sync `O_SYNC`/`O_DSYNC` outputs.
- **Tradeoffs**: Multi-iovec synchronous writes defer syncing until after all iovecs rather than flushing per iovec.
- **Watch out**: Any new write or copy path targeting store or FUSE files must call `write_sync` and propagate its error.
- **Source**: ab694aab

### D6: `remove_privs` aligns non-exec `S_ISGID` clearing on writes with Linux 6.2+ `chown`

- **What**: `remove_privs` checks `CAP_FSETID` with `capable_wrt` and clears non-exec `S_ISGID` on writes when the caller is neither in the file's group nor holds `CAP_FSETID`.
- **Why**: Write-path `remove_privs` previously cleared `S_ISGID` only when the group execute bit was set, diverging from `chown` and Linux 6.2+.
- **Tradeoffs**: Setgid clearing relies on `Store::remove_privs` accepting `keep_sgid` atomically alongside mode and capability removal.
- **Watch out**: Privilege-removal rules must stay aligned between `syscall/fs.rs` and `Store::remove_privs` across writes, truncation and `chown`.
- **Source**: ab694aab

### D7: Memfds can represent a logical size beyond the host filesystem limit

- **What**: Keep a logical length only after host set_len returns EFBIG and consult it for stat, sparse reads, offsets, seals and copy operations.
- **Why**: Linux accepts sparse memfds larger than the backing filesystem's maximum file.
- **Tradeoffs**: Writes and mappings beyond that host limit still depend on real storage.
- **Watch out**: Discard the override on ordinary truncation or final cleanup.
- **Source**: 48f1c17e, 4afada09, ab417943

### D8: Unsharing coordinates open descriptions and live file mappings

- **What**: Prepare replacement handles before publication, reconcile descriptions against the pinned object, and synchronize affected address spaces before replacing their host regions.
- **Why**: Provider writes must stay visible to existing mappings without modifying an independent clone.
- **Tradeoffs**: Busy descriptions can defer unsharing.
- **Watch out**: A mapping can appear before the provider notification arrives.
- **Source**: 523ae8a9, 568b6a48, f495f2cc

### D9: Empty reads still validate their descriptor and record format

- **What**: Check access and positioning, keep fixed-record size errors, and return immediately on an empty pipe request.
- **Why**: A zero-byte operation can still target an invalid description.
- **Tradeoffs**: Validation depends on the file kind.
- **Watch out**: A zero-length pipe read must never wait.
- **Source**: 68b70897

### D10: FUSE truncation revokes access before shrinking backing

- **What**: Remove access to the old mapped tail before changing backing size.
- **Why**: A concurrent access could otherwise reach truncated storage.
- **Tradeoffs**: The address spaces must be coordinated.
- **Watch out**: Regrowth must not expose the previous tail.
- **Source**: b421eac0

### D11: sendfile uses pipe slot reservations

- **What**: Reserve through splice and carry the initial tail merge across write chunks.
- **Why**: Byte capacity alone cannot determine whether another pipe buffer fits.
- **Tradeoffs**: A partial transfer can consume the last available slot.
- **Watch out**: Preserve an existing mergeable tail across chunks.
- **Source**: 25b0f2a0

### D12: Eligible tmpfs fallocate reservations are sparse and quota-backed

- **What**: Serialize quota validation and charging through the write, reuse reservations, and extend the sparse backing only as needed.
- **Why**: Guest allocation guarantees and host physical allocation have different costs.
- **Tradeoffs**: Successful reservation need not allocate all host blocks.
- **Watch out**: Truncation and later writes must reuse or release the same reservation.
- **Source**: 71790fde, 7e10a55b

### D13: Scalar and vectored sysctl writes validate the complete user buffer

- **What**: Use one input path, retain the parser's bounded prefix, and check even the discarded suffix before committing.
- **Why**: A fault late in an iovec must leave the setting and position unchanged.
- **Tradeoffs**: Long writes still read data beyond the parse limit.
- **Watch out**: Apply this check to all sysctl writes.
- **Source**: 68130f74, 6fcaff37

### D14: File reads update relatime once per syscall

- **What**: Route data reads and in-kernel copies through a shared access hook and touch atime once after qualifying progress.
- **Why**: Repeated timestamp work inflated the cost of warm reads.
- **Tradeoffs**: A syscall can span multiple internal reads.
- **Watch out**: Preserve zero-length behavior and error order.
- **Source**: 30e5002e, 7a039c93

### D15: Host file I/O distinguishes regular descriptors and caps each syscall

- **What**: Use positional I/O for regular host files, poll nonregular outputs in PIPE_BUF-sized chunks and cap scalar, vectored and kernel copies at 0x7ffff000 bytes.
- **Why**: Large operations need Linux transfer bounds without blocking the kernel on host pipes.
- **Tradeoffs**: Nonregular outputs may poll once per 4 KiB.
- **Watch out**: Another writer can fill a host pipe after readiness.
- **Source**: d96f5d63

### D16: File range operations enforce Linux validation order

- **What**: Check positional offsets before fd lookup, validate fallocate in generic then filesystem stages, and forward applicable loop-device sync ranges.
- **Why**: Several combinations of invalid arguments expose errno precedence.
- **Tradeoffs**: Filesystem-specific checks run after write preparation.
- **Watch out**: A range beyond the loop device can succeed without host I/O.
- **Source**: e6495409

### D17: `write_proc` wraps `disable_ipv6` sysctl writes with rtnetlink notifications

- **What**: Snapshot and emit rtnetlink diffs around `pseudo::write` in `write_proc` for `PseudoNode::NetConf(true, _, b"disable_ipv6", _)` nodes, mirroring the existing `memory.max` hook.
- **Why**: `write_proc` is the single syscall boundary holding `Cx` for `/proc/sys` writes, keeping `pseudo::write` decoupled from netlink sockets.
- **Tradeoffs**: Adds a targeted match arm in `write_proc` alongside `memory.max`.
- **Watch out**: Any future direct call to `netdev::conf_write` outside `write_proc` would bypass rtnetlink notifications.
- **Source**: 8bd3fa4c

### D18: Mapping reconciliation filters open files lazily and single-chunk writes check inode flags once

- **What**: Route `opens_of` through `Kernel::open_files_matching` (skipping `reconcile_mapping` when `MappingObject::reconcile` is false) and deduplicate `file_inode_flags` lookups across write layers via `checked_write_flags` and `prechecked_flags.take()`.
- **Why**: `reconcile_mapping` scanned every open fd on each regular-file `mmap`, and single-chunk regular-file writes looked up `file_inode_flags` three times.
- **Tradeoffs**: Internal write helpers thread an extra `Option<u32>` prechecked-flags parameter.
- **Watch out**: Consume `prechecked_flags` with `.take()` for the first chunk only so later chunks after a BKL release still observe concurrent `IMMUTABLE` or `APPEND` changes.
- **Source**: b38371ff

### D19: Hot file, copy, splice, and pipe I/O paths reuse a per-thread `IoScratch` buffer

- **What**: Reuse a zero-`unsafe` thread-local `IO_SCRATCH: Cell<Vec<u8>>` through an RAII `IoScratch` guard (retaining up to 64 KiB across syscalls while allocating transiently for larger or re-entrant requests) across the 8 hot read, write, copy, and pipe paths in `syscall/fs.rs`.
- **Why**: Hot I/O syscalls previously allocated and zero-filled a fresh `Vec<u8>` up to `IO_CHUNK` (64 KiB) on every call.
- **Tradeoffs**: Each active guest thread retains up to 64 KiB of unzeroed scratch capacity between syscalls.
- **Watch out**: Every caller must slice strictly to the bytes overwritten in the current operation so prior scratch contents can never leak on short reads or faults.
- **Source**: a1fe8a19

### D20: Regular-file I/O uses coalesced `PinnedIo` zero-copy spans and hoisted per-chunk checks

- **What**: Coalesce `Mm::for_each_page` over contiguous non-CoW/non-Fill/non-tmpfs VMA spans, perform regular store file reads and writes directly against pinned spans via `PinnedIo` (guarding against concurrent `Mm::fork` lending via `HostRegion::io_write_pins`), and hoist per-chunk write checks with a fast `store.attrs` + `has_file_caps` `remove_privs` skip.
- **Why**: Regular-file I/O previously copied every 64 KiB chunk through `IoScratch` with per-page `mmu.translate` calls and repeated per-chunk flags, seals, unshare, privilege, and notification checks.
- **Tradeoffs**: Concurrent forks during an unlocked zero-copy read copy the pinned VMA eagerly instead of CoW-lending, and partial-valid write buffers now write page-granularly rather than in all-or-nothing 64 KiB chunks.
- **Watch out**: Any new VMA kind needing per-page fault admission or non-contiguous backing must stay excluded from `Mm::for_each_page` coalescing and `Mm::pin_io`, and fork sharing paths must honor `HostRegion::has_io_write_pins()`.
- **Source**: 7a7cf94e

## Superseded

- ~~Replacing only open descriptors when the provider unshares a mapped file (kernel/store D17)~~ → replaced by **D8** in 523ae8a9 (2026-11-24)
- ~~Copying first and deciding whether live mappings can move afterward~~ → replaced by **D8** in f495f2cc (2026-11-24)
- ~~Physically allocating all backing for eligible tmpfs fallocate reservations~~ → replaced by **D12** in 7e10a55b (2026-11-24)
