# Pipes and splicing (kernel/pipe) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: kernel/pipe"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12)
>
> Pipe and splice decisions made before kernel/pipe was split from kernel/vfs keep their `kernel/vfs` tags and live in `.agents/decisions/kernel/vfs.md`: D31 and D54.

## Active

### D1: kernel/pipe is split out of kernel/vfs
- **What**: kernel/pipe owns `pipe.rs`, `pipe2`, `open_fifo`, the pipe-size fcntl commands, `pipe_read` and `pipe_write`, and the splice block of `syscall/fs.rs` from `splice` to `wait_splice_done`, with the pipe and splice rules and the two splice invariants. An anonymous pipe is identified by its pipe inode and PIPE_DEV. Pipe creation uses the bounded fd installer and rolls back the first end if installing the second fails.
- **Why**: Pipes and splice are Linux's `fs/pipe.c` and `fs/splice.c` and are changed on their own, and the vfs map was still 54.3 KB after overlay and locks left.
- **Tradeoffs**: A splice into or out of a file or socket uses kernel/files's read and write paths, so such changes read both maps.
- **Watch out**: Code that takes data out of a pipe without waiting for `wait_splice_done`, now that the rule lives here.
- **Source**: 5f327de; ce193253 (module boundary); 20137e4c; d053be24; 39e55b4b, 021c7fc2, 576501d3

### D2: splice and sendfile share directional support predicates

- **What**: `splice_reads` and `splice_writes` distinguish device directions, exclude `/dev/kmsg` and `/dev/loop-control` entirely, and serve both splice and sendfile. Validate offset pointers and splice direction in Linux order, reserve output capacity before reading, and consume only bytes successfully copied to userspace.
- **Why**: One kind list incorrectly permitted reading `/dev/null` and writing `/dev/full` through splice.
- **Tradeoffs**: Procfs and sysfs remain broadly treated as splice-capable, including per-process files Linux rejects.
- **Watch out**: The `/dev/zero` and `/dev/full` read behavior follows native 6.8 and may differ from the 6.1 contract.
- **Source**: 01bfd882; 20137e4c; d053be24; 39e55b4b, ab694aab, 3a90a0ef, 30e5002e, d96f5d63, e6495409

### D3: Pipe capacity and writer wakeups follow buffer slots

- **What**: Track occupied and reserved slots alongside byte counts, preserving slot boundaries through splice and tee.
- **Why**: Free bytes do not guarantee room for an additional pipe buffer.
- **Tradeoffs**: An unlocked splice must reserve a slot across the read.
- **Watch out**: Wake writers when a slot becomes usable.
- **Source**: 25b0f2a0

### D4: vmsplice observes live source bytes while the page watch remains valid

- **What**: Retain a per-page watch and a snapshot, read live bytes only while the page remains readable, and fall back to the snapshot after replacement or reclaim.
- **Why**: Unrelated mappings must not freeze a live vmsplice source or expose a reused page.
- **Tradeoffs**: Each retained source has a snapshot and weak watch.
- **Watch out**: Reclaim that discards in-place data must expire the watch.
- **Source**: 4905fcba, ab417943, 6eb822d7, bb31dbda

### D5: Pipe writes fast-path small buffers, batch arrival notifications, and initialize transfer scratch lazily

- **What**: Use a 256-byte stack buffer in `pipe_write_user` for writes `<= 256` bytes, flush `pipe_arrivals` once per contiguous write burst before blocking or returning, and wrap `Pipe::transfer`'s 4 KiB scratch buffer in `LazyCell`.
- **Why**: `pipe_write_user` previously zeroed 4 KiB on every write and updated timestamps and wakeups on every chunk, while `Pipe::transfer` zeroed 4 KiB even without `vmsplice` slots.
- **Tradeoffs**: Adds slight branching between small (`<= 256` B) and page-sized pipe writes.
- **Watch out**: Any early return from `pipe_write_user` after writing chunks must still flush pending `pipe_arrivals` so timestamps and edge wakeups are not lost.
- **Source**: b38371ff

## Superseded

- ~~Using a global TLB generation and host address as vmsplice source identity~~ → replaced by **D4** in 6eb822d7 (2026-11-26)
