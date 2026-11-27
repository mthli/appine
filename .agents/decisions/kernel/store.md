# Inode store (kernel/store) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: kernel/store"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12). D1–D9 were seeded by hand from the retired design doc
> §8.1 and the round records in `docs/rounds/`, so their sources cite rounds; later entries cite
> commits.

## Active

### D1: The rootfs is an inode store — SQLite for the namespace, one host file per regular file
- **What**: Directories, names, inodes, symlinks, device nodes, xattrs and hardlinks live in SQLite; each regular file's data is `objects/<ino>`, opened by number relative to the objects directory.
- **Why**: A real directory tree plus a metadata database (iSH fakefs style) was rejected: Android cannot create hardlinks, and keeping the tree and the database consistent across rename and unlink is hard. SQLite gives atomic metadata operations; it is bundled as the amalgamation (rusqlite `bundled`) because the NDK exposes no system SQLite.
- **Tradeoffs**: Every metadata operation goes through SQLite; host tools cannot browse the rootfs as plain files.
- **Watch out**: Objects must always be addressed by inode number, never by guest path.
- **Source**: design review 2026-10-24 (§8.1, §15)

### D2: Guest atime is stored separately from host object size and modification times

- **What**: Keep guest atime in SQLite, seed it from legacy storage, and cache verified relatime freshness while using object metadata for regular-file size and modification times.
- **Why**: Host reads and independent writers must not corrupt guest-visible access time.
- **Tradeoffs**: Freshness caching requires a per-store epoch and writer tracking.
- **Watch out**: Exclude writable shared mappings and memfds from the relatime cache, and touch the host object when a metadata update must advance regular-file ctime.
- **Source**: design review 2026-10-24 (§8.1), round 73 (`73-timestamps`), aeaffd4b, 30e5002e, 7a039c93, df6ee707, 1f99a1c4

### D3: Writes are deferred up to 5 ms in one transaction with a savepoint per operation
- **What**: A write transaction stays open up to 5 ms; each operation is a savepoint that alone is rolled back on error or panic.
- **Why**: A commit costs far more than the operation itself (package installs were dominated by per-operation commits); if the app is killed only the last few milliseconds are lost and the store stays at a consistent commit.
- **Tradeoffs**: A failed commit loses every deferred operation in it; guest fsync, host sync and sharing with another connection force immediate commits.
- **Watch out**: The timer thread must commit overdue transactions even when no further operation arrives.
- **Source**: rounds 06 (`06-m4`, risk R5), 32 (`32-workloads-file-ops`)

### D4: A metadata cache, invalidated across connections by a shared commit counter
- **What**: Dentries (including negative ones), rows, symlink targets and listings are cached in memory; own writes invalidate precisely, and a foreign change of the counter in the `lock` file clears everything.
- **Why**: Path lookup and stat dominated file-heavy workloads; the counter keeps several users of one store from reading stale data.
- **Tradeoffs**: No eviction (cleared wholesale after a fill budget); readers without the BKL must fall back on any miss.
- **Watch out**: The counter must be bumped only after `COMMIT`, so nothing older can be filled under a newer label.
- **Source**: rounds 13 (`13-profiling`), 25 (`25-stat-without-bkl`)

### D5: WAL checkpoints run on a background thread
- **What**: A reaper thread with its own connection runs PASSIVE checkpoints; the committing thread only checkpoints when the WAL grows very large.
- **Why**: SQLite's automatic checkpoint (two fsyncs) ran in the committing connection, usually under the BKL; on phone UFS/f2fs an fsync often takes milliseconds to tens of milliseconds, stalling every guest thread entering the kernel.
- **Tradeoffs**: An extra thread and connection per store connection.
- **Watch out**: Keep the inline fallback so the WAL cannot grow without bound.
- **Source**: round 111 (`111-background-checkpoint`)

### D6: Power-loss ordering mimics ext4 `data=ordered` with `auto_da_alloc`
- **What**: Commits do not sync; fsync/sync really reach disk; files renamed over others are flushed before the rename commits; objects are deleted only after the unlinking commit is durable.
- **Why**: Android kills apps routinely and phones lose power; a corrupted store (a `/bin/sh` that was not an ELF was once seen) would make the whole environment unusable, and programs like apk and editors rely on ext4's rename-over behaviour without calling fsync.
- **Tradeoffs**: Rename-over commits flush data while holding the BKL.
- **Watch out**: The crash test (`tests/crash`) only covers ext4 hosts.
- **Source**: round 198 (`198-power-loss`)

### D7: Several connections share one store through the `lock` file
- **What**: Shared `flock`s, a shared-mapped header (commit counter, users, uncommitted inode range), `BEGIN IMMEDIATE`, no deferral while another connection is open, boot cleanup only with an exclusive lock.
- **Why**: The app's DocumentsProvider must read and write the same store as the running kernel without stale or lost metadata.
- **Tradeoffs**: Commits become immediate while the provider has a connection open.
- **Watch out**: A crashed connection leaves `users` high, disabling deferral until the next exclusive open.
- **Source**: rounds 32 (`32-workloads-file-ops`), 196 (`196-documents-read`), 197 (`197-documents-write`), 201 (`201-documents-notify`)

### D8: tmpfs trees live in the store, not in memory
- **What**: A tmpfs mount is a detached directory tree in the store whose files are ordinary objects, removed at the next boot if left over.
- **Why**: bubblewrap builds its new root on a tmpfs, and keeping tmpfs data in host files avoids consuming the app's memory.
- **Tradeoffs**: tmpfs is as fast as the store, not as RAM.
- **Watch out**: Detached trees must be cleaned up when their last mount goes away and at boot.
- **Source**: round 117 (`117-tmpfs`)

### D9: Provider mutation validates the destination at publication

- **What**: Decode only hexadecimal escapes, check the permitted home inside the mutation transaction, sync the staged file and publish without replacing a competing entry.
- **Why**: A path or home can change during a slow copy.
- **Tradeoffs**: Guest permission bits do not restrict the provider, but custom homes must belong to a validated interactive account outside protected roots.
- **Watch out**: Notify guest watchers only after commit.
- **Source**: rounds 196 (`196-documents-read`), 197 (`197-documents-write`), 201 (`201-documents-notify`); d5dbe29, 6e9bd7a7, f6a9510b, 4afada09

### D10: The inode store is a module of its own, below the VFS
- **What**: kernel/store covers `vfs/store.rs`, `vfs/import.rs`, `vfs/browse.rs` and the host wrappers in `lib.rs` (the namespace and objects, deferred commits and power-loss ordering, the reaper and checkpoints, boot cleanup, the metadata cache, import and the browse API), with the VFS layer above it in kernel/vfs.
- **Why**: The retired design doc's §8.1 described the store in prose that had drifted from the code.
- **Tradeoffs**: The rationale lives only in this decisions file, and rounds keep § references to the design doc of their time.
- **Watch out**: Current object-pinning, relatime and last-reference cleanup contracts are recorded in D2 and D17, and a clone can still require a whole-file copy.
- **Source**: c5236a1 (bug list since updated by 907b82b, c108be0, df6ee70 and 1f99a1c), 68130f74, 30e5002e, d96f5d63

### D11: Exclusive boot cleanup makes metadata durable before unlinking objects

- **What**: Lock before create or recovery, clean in one transaction, clear the boot token, commit and sync the WAL unconditionally before unlinking orphans, then join before downgrading.
- **Why**: A crash or failed sync can leave recoverable SQLite references to objects queued for deletion.
- **Tradeoffs**: Exclusive opens incur a sync even when the unlink set is empty and the reaper may back off.
- **Watch out**: Use independent mapping and store lock descriptors, retain doomed objects on sync failure and restore recovery state on error.
- **Source**: b8bf44c, 68b70897, 11c74fa5, 5772f924, be442caa, 2173fed5, 66bcd1b0, e3e6cb18

### D12: chown's setgid clearing is decided by the syscall layer
- **What**: `set_owner` takes `keep_sgid` from its caller and clears setgid when it is false as well as when the file is group-executable, while setuid is still cleared for every caller. Writes and chown clear setgid according to group execute and the caller's authority to retain it.
- **Why**: The store alone could not know the caller's group membership.
- **Tradeoffs**: None.
- **Watch out**: None known; the only other caller is a unit test.
- **Source**: ce7d0d8, ab694aab

### D13: Store files read in the order their code runs
- **What**: store.rs runs constants and `SCHEMA`, then `struct Store` with `impl Store` right under it in sections (opening and boot cleanup, reads, changes, transactions and deferred commits, metadata cache, objects, connection) and its Drop, then operations, shared state, cache entries, the lock file's counters, reaper and checkpoints, errors and names; browse.rs ends with path resolution, import.rs starts with `import_tar_gz`, and lib.rs's host wrappers follow their JNI functions.
- **Why**: The user wanted each Rust file to read in the order its code runs, as the Kotlin code does since a546fea; store.rs had no sections and kept `impl Store` hundreds of lines below its struct.
- **Tradeoffs**: git blame on the moved lines points at this commit unless read with -M or an ignore-revs file; `remove_lost_objects` stays in boot cleanup although commit calls it too.
- **Watch out**: Code appended at the end of `impl Store` or of a file drifts from this order.
- **Source**: 27365c6

### D14: memfds belong to their creator's filesystem ids
- **What**: `create_anonymous` takes a uid and gid and writes them into the new row in the same transaction, and memfd_create passes the caller's fsuid and fsgid (kernel/vfs D27).
- **Why**: Linux's memfd inode is owned by its creator, and root-owned memfds broke fchmod and ownership checks for every other user.
- **Tradeoffs**: None known.
- **Watch out**: None known.
- **Source**: 907b82b

### D15: Only a linkable unnamed inode can acquire its first name after creation
- **What**: `Store::link` returns `ENOENT` for an ordinary row with link count 0, after the directory check, as Linux's `vfs_link` does (kernel/vfs D28). O_TMPFILE creates a transactional linkable exception for an unnamed inode unless O_EXCL was requested, with bookkeeping rolled back alongside SQLite.
- **Why**: An nlink-0 inode's object is deleted after the next commit, so a new name would dangle and boot cleanup would never remove it.
- **Tradeoffs**: The store tracks linkable unnamed files in memory and rolls that state back with failed transactions.
- **Watch out**: None known.
- **Source**: c108be0, 65e10e36

### D16: st_blocks comes from the host object
- **What**: `Stat::blocks` is an `Option<u64>` filled from the host object's st_blocks by `fill_from_host` and `fill_from_object` and `None` elsewhere, where the size stands in, and st_blocks and tmpfs accounting follow it (kernel/vfs D37).
- **Why**: Now that holes are real holes in the host object, st_blocks and tmpfs accounting must follow the storage a file takes, which only the host object knows.
- **Tradeoffs**: st_blocks varies with the host filesystem (APFS on a macOS development host frees zeroed ranges; f2fs keeps inline data).
- **Watch out**: Hosts that report delayed allocation late would undercount tmpfs writes until the instance is recounted.
- **Source**: 5d7b936

### D17: Clone sharing and mappings use transactional object pins

- **What**: Select and lock the backing in one write transaction, probe both clone endpoints, split before private mmap or provider writes and release unused held objects after the last reference.
- **Why**: Mapped files and external writers must stay coherent without changing independent clones.
- **Tradeoffs**: A pin can force a whole-file copy, a missing provider-close report can keep a writer registered, and older builds cannot interpret shared objects.
- **Watch out**: The live scan must include mappings, open descriptions, unsharing and inotify holders.
- **Source**: df6ee70, 1f99a1c, 523ae8a9, f495f2cc, 68130f74, 11816722, d96f5d63

### D18: `appine_kernel::write_file` accepts explicit new-file `uid` and `gid`
- **What**: `appine_kernel::write_file` takes `uid: u32` and `gid: u32` parameters and delegates to `vfs::write_file_owned`.
- **Why**: The host-facing store wrapper must expose new-file ownership to JNI callers seeding regular guest accounts.
- **Tradeoffs**: Changes the Rust crate's `write_file` signature (currently used only by `hosts/android`).
- **Watch out**: Any future host caller outside `hosts/android` must pass explicit ownership IDs.
- **Source**: 125f3d00

### D19: Savepoint rollback removes newly created host objects

- **What**: Track new object numbers during writes and make import and device-node setup one transaction.
- **Why**: Rolling back SQLite alone leaves object files behind.
- **Tradeoffs**: A transaction temporarily tracks new objects.
- **Watch out**: Clean both creation and replacement bookkeeping on rollback.
- **Source**: 0e02d7cd

### D20: Store handle lookup identifies the inode's filesystem root

- **What**: Follow the inode or dentry parent chain to the fixed-point root and reject an unlinked inode.
- **Why**: A handle opened against a different tmpfs instance must be stale.
- **Tradeoffs**: Determining the root can require several SQLite lookups.
- **Watch out**: The walk relies on acyclic parent links.
- **Source**: 6fcaff37

### D21: Rootfs import validates PAX extended attributes

- **What**: Import supported security, trusted, user and POSIX ACL attributes after checking the file kind and encoded value.
- **Why**: Tarballs carry permissions and capabilities outside ordinary mode bits.
- **Tradeoffs**: Unsupported or malformed attributes are skipped.
- **Watch out**: Validate capability versions and ACL encodings before storing them.
- **Source**: 6fcaff37

### D22: Removing a published object pins its host identity across unlink

- **What**: Open the object without following symlinks, obtain device and inode from the pin and forget writable-sharing state only after successful unlink.
- **Why**: The host can recycle an inode number immediately after its last reference closes.
- **Tradeoffs**: Deletion of a published object takes an extra descriptor.
- **Watch out**: Hold it until the content registry update completes.
- **Source**: 9163f493, 4768d932, 9951951a

### D23: Sole-user `mapping_object`, `remove_object`, and unlink/atime updates take cached fast paths

- **What**: Serve sole-joined-user `mapping_object` from `Store::Cache` with post-flock `Commits::users_state` revalidation and `changed_backings`-gated reconciliation, gate `remove_object`'s `O_PATH` pin on `mm::try_enter_fast_unlink()`, update queued `Fill::Row` atime in place while skipping no-op `UPDATE`s, consolidate `remove_link` with `remove_checked` and `UPDATE ... RETURNING nlink`, and drop the duplicate `lookup` in `add_dentry`.
- **Why**: Every regular-file `mmap`, `unlink`, `new_object`, and reaped deletion previously paid avoidable SQLite transactions, multi-table JOINs, cache evictions, or host pin syscalls.
- **Tradeoffs**: Tracking `changed_backings` degrades to full reconciliation on rollback or cap, and sole-user `mmap` still keeps an `openat`+`flock` pin for cross-connection provider safety.
- **Watch out**: Missing a shared-backing mutation in `changed_backings` or skipping invalidation when `!defers()` could serve a stale object after a DocumentsProvider edit.
- **Source**: b38371ff

### D24: Store caches regular-file host stats and rotates two-generation metadata tables

- **What**: Cache regular-file host stats in `Cache` (with relatime-matching invalidation and `held_sizes` for immutable held files), drop `Meta::lock.read()` before uncached `fstatat` in `Reader::stat`, and split `Cache` into two 65,536-entry positive generations plus two 8,192-entry negative-dentry generations whose evicted maps drop outside `Meta::lock.write()`.
- **Why**: Regular-file stats called host `fstatat` under `Meta::lock.read()` on every call, stalling BKL writers, and exceeding 131,072 entries wiped the entire cache under `Meta::lock.write()`.
- **Tradeoffs**: Lookups probe up to two maps (hot then cold), and `Reader::stat` misses after `object_modified` do not repopulate `Cache` until a BKL `Store::stat` runs.
- **Watch out**: Any new host-object mutation bypassing `object_modified` or `touch_ino` will serve stale cached size, mtime, or ctime until invalidated.
- **Source**: cb0f3059

### D25: Short-lived host store wrappers skip the reaper thread and support batched sessions

- **What**: Add `Store::open_short` (reusing inline reaping and SQLite auto-checkpointing without spawning `objects-reaper`), switch `write_file`/`read_file`/`browse`/`browse_changing` to it, and add `with_store`, `write_file_in` (per-write `store.commit()` without extra WAL `fdatasync`s), and `read_file_in`.
- **Why**: Each short-lived host open previously spawned and joined a reaper OS thread, and batched startup writes needed one exclusive open and WAL sync with per-step failure isolation.
- **Tradeoffs**: Short-lived host connections that delete objects reap them inline after WAL sync and rely on SQLite's 1,000-page auto-checkpoint if their WAL grows.
- **Watch out**: Exclusive recovery still runs `sync_metadata()` once per `with_store` session per D11, and callers must drop the store before `Native.start` so the kernel obtains an exclusive boot open.
- **Source**: 641769ab

## Superseded

- ~~Leaving mapped readers on the old backing object after a provider write~~ → replaced by **D17** in 523ae8a9 (2026-11-24)
- ~~Selecting mapped backing separately from opening and locking it~~ → replaced by **D17** in f495f2cc (2026-11-24)
- ~~Exposing host atime directly and skipping guest atime updates on held files~~ → replaced by **D2** and **D17** in 30e5002e (2026-11-24)
- ~~Unlinking boot-cleanup objects inside the transaction after syncing only the preceding WAL~~ → replaced by **D11** in be442caa (2026-11-26)
- ~~Taking the shared lock first and syncing the new WAL only when objects await deletion~~ → replaced by **D11** in 2173fed5 (2026-11-26)
- ~~Mapping the commit header through the live store lock descriptor and locking only after connecting during create~~ → replaced by **D11** in 66bcd1b0 (2026-11-26)
- ~~Unconditional transactional `mapping_object` (D17 in part, f495f2cc) and always-pinned `remove_object` (D22 in part, 9163f493, 4768d932)~~ → replaced by **D23** in b38371ff (2026-11-27)
