# Futexes (kernel/futex) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: kernel/futex"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12)
>
> Futex decisions made before kernel/futex was split from kernel/process keep their `kernel/process` tags and live in `.agents/decisions/kernel/process.md`: D26, D44 and the futex halves of D32, D33 and D37.

## Active

### D1: kernel/futex is split out of kernel/process
- **What**: kernel/futex owns `futex.rs`, the futex and robust-list handlers in `syscall/misc.rs` and the futex call of `syscall/fast.rs`: keys, the queue the BKL and fast paths share, the commands with `FUTEX_WAKE_OP` and `futex_waitv`, PI locks and their hand-over at exit, and robust lists, every kernel write to a futex word going through `writable_word`.
- **Why**: The process map was still 52.4 KB after exec and ns left, and futexes are self-contained, as Linux's `kernel/futex` is.
- **Tradeoffs**: Fast-path waits and deadline handover stay in kernel/process, while Expiry and clock jumps belong to kernel/time, so timed futex changes read both maps.
- **Watch out**: Low risk beyond the small map going stale.
- **Source**: b320f8e; 8a17e4a9; d053be24; 39e55b4b

### D2: Exec releases PI ownership under the retiring task identity

- **What**: Pass the released tid to the PI-owner exit scan during exec takeover.
- **Why**: The task can still be present when its old ownership ends.
- **Tradeoffs**: The exit scan accepts an explicit retiring owner.
- **Watch out**: Owner-death handoff must still cover absent tasks and zero owners.
- **Source**: 15347732

### D3: Fast-path `FUTEX_PRIVATE_FLAG` operations skip `shared_maps` lookup

- **What**: `Fast::dispatch` short-circuits `!self.in_shared_map(a[0])` whenever `FUTEX_PRIVATE_FLAG` is set, avoiding `shared_maps.read()` on private futex calls while retaining the shared-mapping check for non-private calls.
- **Why**: Checking `in_shared_map` unconditionally acquired `shared_maps.read()` on private futex operations even though `Mm::futex_key` always maps them to `Key::Private`.
- **Tradeoffs**: Relies on `Mm::futex_key` continuing to map `FUTEX_PRIVATE_FLAG` operations in shared mappings to `Key::Private { mm, addr, shared: false }`.
- **Watch out**: Keep `syscall::fast` synchronized if `Mm::futex_key` ever changes how `FUTEX_PRIVATE_FLAG` keys shared mappings.
- **Source**: a1fe8a19

### D4: Futex waiters are sharded across 256 buckets with per-waiter woken tracking and a lock-free PI skip

- **What**: `Futexes` shards waiters across 256 cache-line-aligned `Mutex<Vec<Entry>>` buckets locked in ascending index order, tracks each waiter's woken state and current bucket in an `Arc<WaiterSlot>` so `Enqueued::leave` skips locking once woken, and uses an `AtomicUsize` `pi_count` to skip locking on exit when no PI waiters exist.
- **Why**: A single global `Mutex<Futexes>` over a flat waiter vector serialized unrelated futex traffic and forced woken threads and exiting threads with no PI waiters to re-acquire the lock.
- **Tradeoffs**: Each kernel allocates a 16 KiB bucket array and each blocking wait allocates one `Arc<WaiterSlot>` when parking.
- **Watch out**: Every multi-bucket operation must acquire bucket locks in strictly ascending bucket-index order and update `WaiterSlot::bucket` while holding both locks across moves.
- **Source**: 8c591916
