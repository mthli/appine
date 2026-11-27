# Socket layer (kernel/net) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: kernel/net"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12). D1–D8 were seeded by hand from the retired design doc
> §10 and the round records in `docs/rounds/`, so their sources cite rounds; later entries cite
> commits.

## Constraint index (compressed active decisions)

> Full text of D1–D4, D6–D8, D10, D12–D13, D16–D17, D19, D21 and D23–D26: `git show 1ea0c1ef9d686d7126bec2029fc928d4f6fdfc6b:.agents/decisions/kernel/net.md`

- **D1** — Guest TCP/UDP sockets pass through to nonblocking host sockets with kernel-emulated interruptible blocking and timeouts (no tun device), so host privilege limits apply and every guest-facing address path must undo namespace, port, NAT and DNS-stub rewrites (design review 2026-10-24, rounds 06, 180)
- **D2** — Raw ICMP/ICMPv6 is emulated on unprivileged host ping sockets with a per-namespace aggregation point that rebuilds IPv4 headers, echo IDs and ICMP errors for echo requests only, and the timer thread must keep polling those taps so raw sockets become readable (rounds 125, 128)
- **D3** — Netlink is answered in process (`NETLINK_ROUTE`, `NETLINK_GENERIC` `nlctrl`, `NETLINK_KOBJECT_UEVENT`, `NETLINK_SOCK_DIAG`), unimplemented protocols return `EPROTONOSUPPORT`, host interfaces/routes stay unchangeable, and single non-dump `RTM_GETLINK`/`RTM_GETROUTE` requests must be answered (rounds 06, 83, 91, 101; 39ea702, 7a13dd2)
- **D4** — Main and local routing tables are synthesized like Linux from `Machine::set_routes` (Android `LinkProperties`) or host `/proc/net/route` with `ip route get` using host source selection, and an empty route list empties the main table rather than falling back to synthesis (round 98)
- **D6** — Binding a port below 1024 tries the real port first, then fixed offsets (+10000, +20000, +30000, ephemeral), hiding the remap from the guest, notifying the app on the first non-loopback listen, and keeping a released port's host mapping for 120 s so `TIME_WAIT` surfaces as `EADDRINUSE` (rounds 156, 208)
- **D7** — Guest socket payload bytes and packets are counted per interface and protocol for `/proc/net/dev`, sysfs `statistics/`, netlink stats and `/proc/net/snmp` on both send and receive paths, skipping `MSG_PEEK` and the error queue (round 90)
- **D8** — `SO_ORIGINAL_DST` is answered for TCP (no TPROXY) from records keyed by the host 4-tuple registered before releasing the BKL so the peer cannot accept first, while `SO_MARK` is stored only for nftables (round 180)
- **D10** — `SO_RCVTIMEO` and `SO_SNDTIMEO` values of at least `KTIME_MAX` mean no timeout and read back as 0 to avoid deadline overflow, shared with `kernel/unix` (96cd8bd)
- **D12** — Socket Rust files read in a socket's life order (callers before helpers, `IPPROTO_*` above their first user, `/proc/net` last), so appending new code at the end of a file drifts from the layout (412ffba)
- **D13** — `net::rw` executes socket read/readv as one receive and write/writev as one message send or successive 1 MiB stream sends, suppressing `SIGPIPE` on later pieces and returning partial progress on subsequent `EPIPE` (b295644; 211a5867; 20137e4c; d053be24; 39e55b4b)
- **D16** — Blocking `connect` joins the wait on host `EALREADY`, honours `SO_SNDTIMEO` (returning `EINPROGRESS`/`EALREADY` on timeout), and returns `ERESTARTSYS` without a timeout or `EINTR` with one (5296a0a)
- **D17** — `PortMap` marks a mapped listen as reported only after confirming room in the 32-event queue so dropped events can be reported on a later listen (6adbd6c)
- **D19** — Link, address and route dumps pack into 3776-byte datagrams (one family per run, `NLMSG_DONE` alone), and `SHUT_RD`/`SHUT_RDWR` sets guest-side `InetSocket::rd_shut` (even on host `ENOTCONN`) so blocking datagram receives return 0 when empty (b3e134a)
- **D21** — After a refused blocking connect fails, the failure branch connects the host socket once more (ignoring `ECONNABORTED`) so the guest's next connect sends a fresh SYN (f6a82cc)
- **D23** — `SockOpts`, netlink options/autobind/pktinfo/creds, `SO_*TIMEO_NEW`, `getpeername`, `socketpair` errnos and `CAP_NET_ADMIN` checks on rtnetlink changes follow Linux rules, rejecting unknown netlink levels with `ENOPROTOOPT` (5d7b936, 7a13dd2)
- **D24** — `NETLINK_SOCK_DIAG` opens and answers like a 6.1 kernel without diag modules (`EOPNOTSUPP` for `TCPDIAG`/`DCCPDIAG`, `EINVAL` or `ENOENT` for `SOCK_DIAG_BY_FAMILY`/`SOCK_DESTROY`), honoring `NETLINK_CAP_ACK` and `NLM_F_ACK` (39ea702)
- **D25** — Unhandled `interface_ioctl` and `netns_ioctl` requests are counted in `kernel/signal`'s unimplemented-path table, while netlink `FIONREAD` stays uncounted because its `ENOTTY` matches Linux (99b4216)
- **D26** — Rtnetlink link, address and route changes notify subscribed groups (1, 5, 7, 9, 11) via pre/post namespace snapshot diffs (quadratic on large batches, deterministic dump encoding required, 1024-datagram `ENOBUFS` cap unless `NETLINK_NO_ENOBUFS`) on weakly registered `Kernel::rtnl_sockets` (7a13dd2)

## Active (full detail)

### D5: DNS goes through a stub at 127.0.0.53 answered by the host resolver
- **What**: The initial namespace intercepts only UDP DNS at 127.0.0.53, resolves through the host on query threads, connects or autobinds the host socket to expose a real local endpoint, caps in-flight queries at 128 and drops queued answers once SO_RCVBUF has been exceeded.
- **Why**: Going through the system resolver makes Android's Private DNS, VPNs and per-network routing apply to the guest; failing fast with SERVFAIL stops resolvers from waiting for their timeouts.
- **Tradeoffs**: Each admitted query starts a host thread, answers can arrive out of order, overload drops queries for resolver timeout, payload lengths approximate skb accounting, and TCP/container DNS is not intercepted.
- **Watch out**: Preserve edited resolv.conf files, keep worker threads independent of kernel lifetime, and leave raw/ping traffic to the host rather than the DNS stub.
- **Source**: rounds 06 (`06-m4`), 07 (`07-m5`); fe4bb2b; 73127176; 20137e4c; d053be24; 39e55b4b

### D9: kernel/net covers internet sockets and socket syscalls; netlink, raw ICMP and routes are split out
- **What**: kernel/net covers `net.rs` (excluding netlink), `syscall/net.rs` (excluding netlink and `raw_recv`/`take_errors`), `portmap.rs`, `traffic.rs` and `origdst.rs` (keeping portmap and legacy route ioctls), while netlink sockets belong to `kernel/netlink`, raw ICMP to `kernel/rawicmp` and routing tables to `kernel/route`.
- **Why**: `kernel/net.md` reached 38,523 B (83.6% of one read) while mixing TCP/UDP socket passthrough with standalone raw ICMP emulation and routing-table synthesis.
- **Tradeoffs**: Changes spanning socket dispatch and netlink, raw ICMP or route lookups consult two maps, pre-split child decisions remain here (referenced by `kernel/netlink`, `kernel/rawicmp` and `kernel/route`), and nftables rules/evaluation stay in `kernel/nft`.
- **Watch out**: Socket option, send/recv, readiness and traffic default-route hooks must stay aligned with `kernel/rawicmp` and `kernel/route`, changing socket ioctls must run between `kernel/netlink`'s `rtnl_watched` and `rtnl_notify`, and open socket bugs remain in the map (`MSG_WAITALL` post-partial error loss, host option semantics, unchecked odd `sendto` destinations and initial-namespace loopback counters).
- **Source**: c3df5d3 (bug list since updated by 58ce3df, 4e64e74, ce8a9ce, 5d23297, 5d7b936 and 7a13dd2); 0c1b416 (full refresh and the netlink split); 52dcc177; 211a5867; 20137e4c; d053be24; 60790c14; 39e55b4b; e0719858

### D11: Noninitial namespace binding policy lives with the namespace

- **What**: Keep low-port translation for the initial namespace and delegate guest overlap, v6-only and reuse policy to namespace bindings with the socket creator's euid.
- **Why**: Host port assignment alone cannot enforce guest bind semantics.
- **Tradeoffs**: Binding state is split between initial and virtual namespaces.
- **Watch out**: Both TCP listen and bind must consult the same guest identity.
- **Source**: ea08993, 3a90a0ef, 7fad509d

### D14: MSG_WAITALL waits for the whole length on TCP
- **What**: recv keeps reading and waiting between pieces until it has the length or meets nonblocking, a timeout, a signal, end of file or a host error, a peek re-reads from the head with the host's `SO_RCVLOWAT` raised for the wait, and recvfrom and recvmsg continue past 1 MiB piece by piece; MSG_TRUNC keeps the received length without copying payload, including continued MSG_WAITALL reads. After partial MSG_WAITALL progress, retain a consumed socket error for the next observation.
- **Why**: Fixed-size readers using MSG_WAITALL got short reads, and `MSG_PEEK|MSG_WAITALL` returned corrupted data, once data arrived in more than one piece.
- **Tradeoffs**: An error after a partial read is lost (the host already took it), a peek against a buffer that can never hold the length returns the part where Linux blocks, and AF_UNIX is not covered.
- **Watch out**: Another thread polling the socket sees the raised `SO_RCVLOWAT` while a peek waits, and a fault on a later piece loses that piece's data.
- **Source**: 58ce3df; 211a5867; 20137e4c; d053be24; 39e55b4b, a1fd0beb

### D15: recvmsg and sendmsg check iovecs in `import_iovec`'s order
- **What**: A negative length is `EINVAL`, an iovec reaching past 2^48 (arm64's `TASK_SIZE_MAX`) is `EFAULT`, and the total is cut to `MAX_RW_COUNT` by shortening the iovec that reaches it. Pin the original description across waits and batched receives, retry faulting copyouts for that socket and return the count of completed records on interruption.
- **Why**: recvmsg summed the guest's iovec lengths unchecked and accepted negative lengths.
- **Tradeoffs**: `TASK_SIZE_MAX` is fixed at 48-bit addresses, where a 52-bit Linux accepts up to 2^52.
- **Watch out**: sendmsg shares `read_msghdr` and returns the same new errors.
- **Source**: 09188ec, 849bf98a, 4309ef58, 11816722

### D18: Inet binds check their family before the host sees it
- **What**: `inet_bind_addr` checks every inet bind first: a sockaddr shorter than the family's is `EINVAL`, IPv4 takes `AF_INET`, or `AF_UNSPEC` with `INADDR_ANY` rewritten to `AF_INET`, and otherwise `EAFNOSUPPORT`, raw IPv4 ignores the family, and IPv6 takes only `AF_INET6` (`EINVAL` for raw, `EAFNOSUPPORT` otherwise), so the host only ever sees `AF_INET` or `AF_INET6`. Validate sendto's guest destination before routing or forwarding to the host, and retain the selected bound address for wildcard connections.
- **Why**: An `AF_UNSPEC` bind passed to the host oopsed the development VM's kernel and hung Appine, and other bogus families got the host's (AppArmor's) error instead of Linux 6.1's.
- **Tradeoffs**: Rebinding a raw socket to another address, which `raw_bind` allows, is still refused by the host ping socket underneath (D22), and sendto's destination is still not checked by the layer.
- **Watch out**: Programs relying on the host accepting an odd bind family now get Linux's error, and differential cases that bind with `AF_UNSPEC` need an unconfined native side, since a confined one can still hit the AppArmor oops.
- **Source**: 0b2c3c8, a1fd0beb

### D20: accept4 installs only a complete socket, and socket timeouts are parsed strictly
- **What**: accept4 checks fd and flags, reserves an fd before resolving and waiting on the listener, writes the address before installation, releases reservations on errors, inherits timeouts and v6only, and returns EINTR for interrupted timed accepts while retaining strict timeout parsing. Reserve a bounded slot before accepting, honoring both RLIMIT_NOFILE and nr_open.
- **Why**: accept4 installed the fd before writing the address and took a connection before finding no free fd, accepted sockets lost the listener's timeouts, and the lenient parser let a negative timeout block forever.
- **Tradeoffs**: A supplied address with a NULL length is EFAULT, including recvfrom after consuming data, and a kernel panic between reserve and fill can leave a reservation until the process ends.
- **Watch out**: Other wait-then-install paths still choose slots without reserving them; preserve the existing short-timeout EINVAL, invalid-microseconds EDOM and negative-seconds-as-zero rules.
- **Source**: 5d23297; 4e01f78b; 20137e4c; d053be24; 39e55b4b, 576501d3, 661d5f01

### D22: Raw ICMP rebinds preserve socket options and connected-state checks

- **What**: Route each send attempt through the raw helper and replace the host ping fd when an eligible guest rebind changes the address.
- **Why**: A host ping socket cannot directly reproduce repeated guest raw binds.
- **Tradeoffs**: Rebinding may create and configure a replacement descriptor.
- **Watch out**: Check connected state before translation and restore option ordering.
- **Source**: dcacfb6, 515ecd7f, 11cb85a0

### D27: Socket filters retain Linux checks even when the host refuses TCP attachment
- **What**: Filter attachment follows Linux 6.1 capability, optlen, lock and program checks; a host-refused TCP program is validated on a scratch UDP socket and retained for count-based SO_GET_FILTER, detach and accept inheritance, while normal host programs still use `hal::net::get_filter`. Validate guest BPF options and scratch-host filter installation, defer rejecting TCP listeners, and give DNAT its own accept queue until a pass filter or detach activates the listener.
- **Why**: SO_GET_FILTER’s instruction count must not be treated as bytes, and newer hosts’ TCP attachment refusal otherwise breaks Docker’s published-port setup.
- **Tradeoffs**: Only the supported fallback listener behavior is emulated, and validation can require a scratch host socket, and netlink/AF_UNIX filter behavior remains limited.
- **Watch out**: SO_LOCK_FILTER relies on host state, count-based copies remain bounded to 4096 instructions, and test hook 14 is needed to force host refusal on accepting hosts.
- **Source**: c65da9d (recorded in 0c1b416); 52dcc177; 20137e4c; d053be24; 39e55b4b, a1fd0beb, f7ddba97

### D28: Legacy route ioctls translate into namespace route requests

- **What**: SIOCADDRT and SIOCDELRT decode arm64 IPv4/IPv6 structures, check namespace CAP_NET_ADMIN, translate Linux’s masks, metrics, labels and gateway rules, and reuse `netdev::request` with rtnetlink notifications.
- **Why**: BusyBox and net-tools route use these ioctls rather than rtnetlink.
- **Tradeoffs**: Only virtual-device routes change, unreachable and IPv6 source routes are refused, and MTU/window/IRTT flags are ignored.
- **Watch out**: Structure offsets are arm64-specific, and IPv4 unicast classification approximates the local table from interface addresses.
- **Source**: 29e59fd3; 20137e4c; d053be24; 39e55b4b

### D29: Device binding is resolved in the socket's network namespace

- **What**: Store the bound interface index on the socket and accepted children and forward only eligible real host-device bindings.
- **Why**: Virtual and host interface indices need not identify the same device.
- **Tradeoffs**: The guest maintains its own bound-device state.
- **Watch out**: Resolve names and indices against the socket's namespace.
- **Source**: 76da8eff

### D30: MPTCP sockets are counted with TCP in `/proc/net/snmp` and `/proc/net/sockstat`

- **What**: `Traffic::account`, `proc_snmp`, `sockstat`, `connect` and `accept_host` match both `IPPROTO_TCP` and `IPPROTO_MPTCP` while keeping `InetSocket::proto == IPPROTO_MPTCP` intact for `SO_PROTOCOL` and socket options.
- **Why**: Strict `IPPROTO_TCP` checks omitted MPTCP sockets (used by default by Go 1.24+ listeners) from SNMP and sockstat counters, disagreeing with Linux 6.1 and `/proc/net/tcp`.
- **Tradeoffs**: Each TCP-MIB site must accept MPTCP explicitly, and multi-subflow MPTCP connections are approximated as one TCP connection per guest socket.
- **Watch out**: `tcp_state()` relies on host `IPPROTO_TCP` `TCP_INFO` support on MPTCP sockets, and hosts without `IPPROTO_MPTCP` skip the live guest test path.
- **Source**: 0bc5207a

### D31: Connected-UDP sends evaluate `world()` lazily and fast-path `update_udp_peer` via cached `peer_map`

- **What**: Evaluate `cx.kr().world()` lazily in `syscall::net::send`, skip `implicit_bind`/`ensure_bound` when already bound, and update `vconn` in place in `update_udp_peer` without a host `getpeername` when `peer_map` matches, `!ttl_set`, and `unique_udp_host_ip` holds (clearing `peer_map` on DNS stub connect or reconnect failure).
- **Why**: Address-less sends on connected UDP sockets targeting a non-initial network namespace allocated `Vec<Rc<NetNs>>` via `world()` and executed a host `getpeername` syscall on every datagram.
- **Tradeoffs**: Mixed-IP shared UDP ports and `ttl_set` (traceroute) sockets still take the host `getpeername` fallback.
- **Watch out**: Any path rebinding a UDP host socket outside `connect` or `update_udp_peer` must clear `peer_map` to avoid keeping a stale cached peer endpoint.
- **Source**: b38371ff

### D32: Socket I/O reuses per-thread `NetScratch` with `RecvSink::Scatter` and direct hot-page `SOCK_STREAM` iovecs

- **What**: Reuse a per-thread 256 KiB-capped `NetScratch` RAII buffer across `recv`, `sendto`, `sendmsg`, and `rw`, scatter inet receives directly into guest iovecs via `RecvSink::Scatter`, and bypass scratch copies on hot-page `SOCK_STREAM` transfers via `translate_iovecs` and `hal::net::{sendmsg,recvmsg}_iov` with no `RefCell` borrows held across waits.
- **Why**: `syscall/net.rs` previously allocated and zero-filled up to 1 MiB per socket I/O call (even on nonblocking `EAGAIN`) and allocated a second `Message::data` buffer on every receive.
- **Tradeoffs**: Retains up to 256 KiB per thread doing socket I/O, requires callers to read only overwritten `[..got]` bytes, and falls back to `NetScratch` for cold, CoW, FUSE, or >64-span stream transfers.
- **Watch out**: Never cache translated host page pointers across `wait_host` or omit `need_addr` for `vconn`, UDP `seen`, or `Traffic::account` paths.
- **Source**: 4dd64f02

## Superseded

- ~~DNS stub queries are resolved synchronously in the sending thread (rounds 06, 07)~~ → replaced by **D5** in fe4bb2b (2026-11-05)
- ~~Internet and netlink sockets are one kernel/net module (D9, c3df5d3)~~ → replaced by **D9** and kernel/netlink **D1** in 0c1b416 (2026-11-11)
- ~~D9 combined ownership of raw ICMP and routing tables in kernel/net (0c1b416)~~ → replaced by **D9**, kernel/rawicmp **D1** and kernel/route **D1** in e0719858 (2026-11-22)
- ~~Retaining a host-refused filter solely for readback~~ → replaced by **D27** in f7ddba97 (2026-11-24)
