# Advisory locks and leases (kernel/locks) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: kernel/locks"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12)
>
> Lock and lease decisions made before kernel/locks was split from kernel/vfs keep their `kernel/vfs` tags and live in `.agents/decisions/kernel/vfs.md`: D12, D43 and the lock-range half of D25.

## Active

### D1: kernel/locks is split out of kernel/vfs
- **What**: kernel/locks owns `locks.rs`, `syscall/lease.rs`, `flock` and the fcntl lock commands (`record_lock`): flock, POSIX and OFD locks, leases and their breaks, and the `/proc/locks` and fdinfo listings.
- **Why**: Locks and leases are their own files and Linux's `fs/locks.c`, with their own listings, and the vfs map could no longer be read in one Read.
- **Tradeoffs**: Close paths and lease breaks are called from kernel/vfs, and lock keys of overlay and FUSE nodes come from there.
- **Watch out**: Bugs open in the map are leases lost across a copy-up, no-op locks on inode-less files and the listing order.
- **Source**: 5f327de

### D2: Overlay leases use the overlay node identity

- **What**: Use the same overlay key across set, get, breaks and copy-up while consulting the backing inode for metadata.
- **Why**: Copy-up replaces the layer inode beneath an existing lease.
- **Tradeoffs**: Lease lookup and attribute lookup use different identities.
- **Watch out**: Open and truncate must break leases before copy-up.
- **Source**: ab694aab

### D3: Lock conflicts and proc listings use stable file identities and ordered waiters

- **What**: Key locks by filesystem or anonymous object, track blocked flock requests and lease acquisition order, and resolve displayed device and inode from the live object.
- **Why**: Aliases, dup and conversion must agree in flock, record locks, fdinfo and proc locks.
- **Tradeoffs**: A conversion drops the previous mode before attempting the new one.
- **Watch out**: Remove wait records on every exit and report the daemon inode for FUSE.
- **Source**: 021c7fc2, d10f2973, ef8afe48, fcf3989d, ab417943

### D4: `Locks::closed` short-circuits when no POSIX record locks exist

- **What**: Return early in `Locks::closed` when `self.by_key.is_empty()` and stop batch close loops as soon as `by_key` becomes empty.
- **Why**: Every `close(fd)` previously built `LockKey::of(...)` (borrowing pipes, sockets, or PTYs) even when zero POSIX locks were held.
- **Tradeoffs**: None.
- **Watch out**: None; an empty `by_key` table guarantees no POSIX record locks exist for any closed descriptor.
- **Source**: b38371ff
