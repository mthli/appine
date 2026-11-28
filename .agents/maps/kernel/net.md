# kernel/net Map

> Static understanding snapshot, not a decision history.
> See `.agents/decisions/kernel/net.md` for the paired decision history; it still holds the decisions on netlink sockets (`kernel/netlink`), raw ICMP (`kernel/rawicmp`) and the routing table (`kernel/route`) from before their splits.
>
> Verified: 2026-11-26 (full refresh: 5 research concerns; 18 claims checked by an independent verifier — 18 confirmed, 0 partial, 0 refuted, plus 6 map-audit refinements and 4 additional confirmed bugs folded in)
>
> Maintained: 2026-11-28 (targeted verification only since the full mapping; latest change: bind address and interface in listen events)

## Responsibilities

Internet sockets use nonblocking host fds. The socket layer handles the guest ABI, blocking, selected
options, DNS interception, endpoint translation, forwarder registration, traffic accounting and proc
output.

Under `core/kernel/src` this covers `portmap.rs`, `traffic.rs`, `origdst.rs`, non-netlink `net.rs` and
`syscall/net.rs` including legacy route ioctls. The latter excludes `kernel/netlink`’s `netlink_*`
helpers, netlink section and `Socket::Netlink` arms, and `kernel/rawicmp`’s `raw_recv` and `take_errors`.

## Key types

- `Socket::{Inet,Netlink,Unix}` in `FileKind::Socket` as `SocketRef` (`Rc<RefCell<Socket>>`); socket,
  open file, fd entry hold inode uid/gid, O_NONBLOCK, CLOEXEC respectively.
- `InetSocket`: host fd/type, family, guest proto, raw flag, endpoint state (`net == None` = initial ns;
  `listener` = binding id incl. accepted children; `peer` = dgram peer, `guest_peer` = guest connect addr).
- `Routed`: host mapping, verdict, origdsts; optional `Track`: NAT, verdict, peer, mark. `DeferredListen`:
  backlog, guest reuse, private forwarding listener; `fwd`: forwarder registration.
- `SockOpts` (Unix/netlink opts); `MsgHdr` (imported iovecs/ptrs); `Message` (copied bytes vs reported len,
  source, controls, flags, pending Unix rights). `NetScratch`: per-thread RAII guard (`Cell<Vec<u8>>`, 4
  KiB min, 256 KiB retention cap, transient fallback >256 KiB or re-entrancy) reused by `recv`/`recv_with`,
  `sendto`, `sendmsg`, `rw`; `RecvSink::Scatter` scatters inet payloads from `NetScratch` directly into
  guest iovecs (no `Message::data` copy).
- `PortMap` (live/released `Lease`, queued `ListenEvent`); `Traffic` (`IfStats`, addr cache, `Protocols`);
  `origdst::Table` (ns destinations keyed by host tuple).

## Public entry points

- `socket`, `socketpair`, `bind`, `listen`, `connect`, `accept4` (also accept), `shutdown`, name/option
  calls, sendto/sendmsg/sendmmsg and recvfrom/recvmsg/recvmmsg. File operations use
  `syscall::net::{rw,send,recv,ioctl}`. Poll uses `readiness`, `wake_objects`, `arrivals`.
- `deliver_dns`, `Machine::take_listens`, `Traffic::{account,get}`, `traffic::outgoing_source`,
  `net::{proc_listing,sockstat,proc_snmp,proc_netstat,PROC_ARP}`.

## Data flow / lifecycle

### Creation and descriptors

Creation flags outside the type nibble, NONBLOCK and CLOEXEC give `EINVAL`. HAL sockets are nonblocking
and close-on-exec. Stream/datagram protocol zero defaults to TCP/UDP. Explicit MPTCP remains 262. Raw
protocols 1–255 require family-matching ICMP/ICMPv6 and `CAP_NET_RAW` over the netns owner, else `EPERM`.
Other raw numbers go to the host for validation. Raw ICMP uses a ping datagram, requests host options,
syncs receive budget, registers the namespace tap and signals the timer.

AF_UNIX sets the namespace datagram queue limit. AF_NETLINK accepts raw/datagram for ROUTE, GENERIC,
KOBJECT_UEVENT, NETFILTER and SOCK_DIAG (bad protocol `EPROTONOSUPPORT`, bad type `ESOCKTNOSUPPORT`).
Other families give `EAFNOSUPPORT`. Inodes take creator fsuid/fsgid. Inet retains euid for reuse-port.

Only Unix creates a pair. Inet `socketpair` checks types locally (≥11 `EINVAL`, other unsupported
`ESOCKTNOSUPPORT`). Streams admit 0/TCP/MPTCP, datagrams 0/UDP/ICMP/UDP-Lite, raw any nonzero protocol.
Mismatch gives `EPROTONOSUPPORT`, then raw checks namespace CAP_NET_RAW (`EPERM`), then inet gives
`EOPNOTSUPP`. Netlink gives `EOPNOTSUPP` directly. Unix second-install failure closes the first fd, pair
copyout failure closes both.

`socket_of` pins the open file across waits and rejects O_PATH (`EBADF`) or a nonsocket (`ENOTSOCK`). New
files use O_RDWR plus requested NONBLOCK. Install/reserve chooses the lowest free slot below soft
`RLIMIT_NOFILE`. `nr_open` constrains table expansion. Already allocated capacity survives a lowered
limit.

### Bind

Sockaddrs above 128 bytes give `EINVAL`. `inet_bind_addr` requires 16 bytes for IPv4, 24 for IPv6. IPv4
accepts AF_INET, AF_UNSPEC wildcard or any raw family and writes AF_INET. IPv6 requires AF_INET6. Short
gives `EINVAL`, wrong family `EAFNOSUPPORT` (raw IPv6 `EINVAL`).

Connected raw bind gives `EINVAL` before translation. Otherwise clear the port and translate. For a
changed host IP, `bind_raw` configures/binds a replacement ping fd, drains the old source, replaces the
tap source, swaps fds, resyncs receive budget and closes the old fd. Same-address rebind reuses it. Option
copying compensates for buffer doubling, restores priority after TOS and filter lock last.

Other binds translate before checking nonzero ports below namespace `ip_unprivileged_port_start` (default
1024). These need `CAP_NET_BIND_SERVICE` over the owner (`EACCES`). In a new namespace a wildcard
port-zero datagram other than ICMPv6 binds the host wildcard (`wide`). A v6-only mapped bind or rebind of
an already bound nonraw socket with a port gives `EINVAL` there.

For a new-namespace explicit TCP/UDP port, `bind_conflict` checks guest address/port, protocol, v6-only,
reuse, creator euid and device before host bind (`EADDRINUSE`). `shared_host_port` selects a compatible
port or the host allocates one. `add_binding` records the endpoint, immediately listening for UDP.
`claim_ephemeral` accepts the assigned host port if free in the guest table, else searches the namespace
ephemeral range and then outside it. Initial-namespace low ports use `PortMap`. Other binds use the
translated host address.

### Listen and deferred queues

`listen` tries namespace implicit bind, then `listen_conflict` before a bound TCP socket first enters
listen (`EADDRINUSE`). Host listen or `defer_listen` success sets listening state, marks the namespace
binding, updates forwarding and checks the host address for a listen event.

Retained RET 0 reserves the port with host SO_REUSEADDR off. Unbound IP_BIND_ADDRESS_NO_PORT gives
`EOPNOTSUPP`. Repeated listen updates any private listener and backlog. Held accept waits on the object or
gives `EAGAIN`. Poll returns (0, no fd). SO_ACCEPTCONN, TCP_INFO and proc report listen.

Detach or a supported pass replacement enables host reuse for handover, shuts the private queue and
listens with the saved backlog. Failure tries to restore private listen, disables host reuse and keeps RET
0. Success restores guest reuse best-effort, clears the hold, refreshes forwarding and wakes waiters.

### Connect and names

Retained RET 0 rejects connect (`EOPNOTSUPP`); stub connect returns early. `route` runs OUTPUT NAT, host
translation, applicable bridge/endpoint PREROUTING and TCP/MPTCP or UDP path filters (NAT block ends hook
eval; port-only bridge DNAT keeps L2 path; flow carries guest src port, caller euid/egid, mark, origdsts,
seen-peer UDP reply state; IPv4 path filter clears mark across namespaces).

Isolated streams → `ECONNREFUSED`, isolated UDP records silently blocked peer and disconnects host (trying
to keep local port), other isolated protos → `ENETUNREACH`. Stream reject returns its errno; drop redirects
to full-backlog kernel blackhole listener (`ETIMEDOUT` if unavailable), skipping normal src bind, dest port
translation, counters and origdst registration (shared success/`EINPROGRESS` path can still claim eligible
new-ns ephemeral src port). New-ns stream targets without matching listener → `ECONNREFUSED`.

`ensure_bound` picks ns src or allows host autobind; self-directed UDP src can register return port. Dest
uses target ns binding or initial port map. Wide dgram connecting to mapped ns peer leaves host
disconnected and saves `vconn` (AF_UNSPEC undoes it). UDP may keep verdict + weak target for later sends.

Host success/`EINPROGRESS` may claim ephemeral port and register origdst before wait. Nonblocking returns
host result; blocking `EINPROGRESS` and repeated `EALREADY` wait on POLLOUT (SO_SNDTIMEO expiry returns
original progress errno while connect runs; signal → `ERESTARTSYS` without timeout so SA_RESTART can
re-enter, `EINTR` with one). On ready, kernel reads SO_ERROR and re-calls host connect on either outcome to
consume host connecting state, returning saved result.

`connected_peer` maps IPv4 on IPv6, resolves IPv4 wildcard to bound IPv4/loopback and IPv6 wildcard to ::1
or mapped local counterpart, clears IPv6 flow info and keeps scope only for link-local. Name queries handle
stub, tapped/blocked UDP and virtual peers. `inet_name` restores local ports/ns addrs, uses cached
guest/outside peer after successful host peer query and can pick virtual local endpoint toward peer; raw
sockets report protocol as local port.

### Accept, shutdown and release

`accept4` checks fd existence, validates NONBLOCK/CLOEXEC, reserves a slot, then resolves the listener.
Missing fd `EBADF` precedes bad flags `EINVAL`, and `EMFILE` can precede `ENOTSOCK`. Listener nonblocking
and receive timeout govern the wait. The call's flags govern the child. On `EAGAIN`, wait on POLLIN and
the socket, or only the socket for deferred listen. Expiry gives `EAGAIN`, interruption `ERESTARTSYS`
without a timeout or `EINTR` with one.

`accept_host` snapshots family/type/protocol, both timeouts, namespace, local map and virtual address,
mark, v6-only, creator euid and device before waiting. It clones the retained filter after host accept.
The child takes inode fsuid/fsgid from the accepting task, the listener's local port mapping without a
lease, and any original destination claimed in its namespace. A translated outside peer can be cached. A
mappable new-namespace child registers its own accepted, non-listening binding to preserve bind conflicts
after listener close. Passive opens count before copyout for initial TCP/MPTCP.

Accept/recvfrom ignore addrlen for null addr. Otherwise invalid addrlen gives `EFAULT`, negative room
`EINVAL`. Copy the fitting prefix and return full length. FUSE fill retains connection and slot. Success
installs the child, error releases both.

Deferred SHUT_WR preserves the listener. SHUT_RD/RDWR restores reuse, drops private forwarding, clears
listening, sets `rd_shut` and wakes waiters. Otherwise inet calls host shutdown. Read shutdown records the
flag, clears listening and wakes even on host `ENOTCONN`, returning the host result. Final drop closes the
host fd, removes its self-UDP port when loopback can be borrowed and releases its namespace binding, lease
and forwarding handle.

### Send and receive

`read_msghdr` imports 56 B (>1024 iovecs → `EMSGSIZE`), rejecting negative lengths (`EINVAL`) during
import, checking each original extent <2^48 (`EFAULT`), clamping total to `MAX_RW_COUNT` (0x7fff_f000).
`sendto`/`sendmsg` gather ≤1 MiB (`MSG_MAX`); ancillary caps at 4096 B each way (0 for null recv ctrl ptr).

After Unix/netlink dispatch, `inet_send_addr` validates explicit nonstream/FASTOPEN dests before
DNS/routing (ordinary streams ignore dest). IPv4 needs 16 B, accepts AF_INET, rewrites AF_UNSPEC for
dgrams except nonraw ICMP (short → `EINVAL`, wrong family → `EAFNOSUPPORT`). IPv6 nonraw dgrams except
ICMPv6 accept AF_INET6 ≥24 B, AF_INET ≥16 B unless v6-only (`ENETUNREACH`), AF_UNSPEC as no dest (other
invalid → `EINVAL`); remaining IPv6 sends need 24 B + AF_INET6 (raw rewrites AF_UNSPEC). FASTOPEN under
retained RET 0 → `EOPNOTSUPP`.

Address-less UDP honors saved verdict and re-resolves ns target (missing listener drops with success).
`update_udp_peer` updates `vconn` in place, or on host-connected sockets skips peer verification when
`peer_map` matches, `!ttl_set` and target ns reports `unique_udp_host_ip`, else verifies host peer and
reconnects on mismatch (clearing `peer_map`, returning reconnect error). Addressed dgrams: blocked OUTPUT →
`EPERM`, later drops/rejects return byte count; isolated UDP and missing ns listeners drop silently.
Reverse DNAT history holds 16 pairs; wide/initial wildcard srcs may append IP_PKTINFO/IPV6_PKTINFO unless
supplied. Raw/tapped UDP call `before_io`, drain tap errors and may retry host ICMP failure once; raw uses
`RawIcmp::send` per attempt.

Host send strips guest DONTWAIT/NOSIGNAL (HAL adds MSG_NOSIGNAL). For hot-page SOCK_STREAM without
MSG_PEEK/MSG_ERRQUEUE/MSG_TRUNC/MSG_WAITALL/MSG_FASTOPEN, `try_direct_send_inet`/`try_direct_recv_inet`
translate ≤64 coalesced mapped soft-MMU spans (`translate_iovecs`) under BKL and call
`hal::net::{sendmsg,recvmsg}_iov`, falling back to `NetScratch` on cold/CoW/FUSE/unmapped pages. Ordinary
inet recv uses `hal::net::recvmsg_opt` (skipping unused addr/ctrl allocs) and scatters from `NetScratch`
via `RecvSink::Scatter` (separating socket errors from scatter faults so `recv_rest` never saves `EFAULT`
in `pending_err`). Blocking stream sends loop; on host `EAGAIN`, `wait_host` checks nonblocking, deadline,
signal (`EAGAIN`, `EAGAIN`, `ERESTARTSYS`) before POLLOUT+socket wait. Errors after progress return partial
count; initial host `EPIPE` raises guest SIGPIPE unless suppressed; successful dgram send may claim
autobound port.

Unix/netlink/raw ICMP have family-specific recv paths. Ordinary inet checks stub FIFO, saved error
(non-ERRQUEUE), then UDP tap errors; ERRQUEUE implies nonblocking; host recv strips DONTWAIT/WAITALL.
`vconn` discards foreign dgrams even on peek. Src translation undoes port/ns mapping and DNAT; ≤16 observed
dgram peers aid reply classification; host controls/flags pass through. On host `EAGAIN`, recheck stub
answers; with `rd_shut` + empty dgram queue, blocking recv returns 0 before timeout/signal while
nonblocking returns `EAGAIN`; other waits use POLLIN with send's ordering.

Inet stream MSG_WAITALL accumulates until full, EOF, error or failed wait (partial progress survives
interruption even under SA_RESTART). Consumed host errors after progress go to `pending_err` (consumed once
by ordinary recv, stream send or SO_ERROR; poll reports POLLIN|POLLERR + host fd without clearing). Stream
MSG_TRUNC returns len without payload copy; dgram MSG_TRUNC returns backend len (may exceed copied prefix).
MSG_PEEK|MSG_WAITALL rereads queue head: `wait_peek` temporarily sets host SO_RCVLOWAT to requested len and
tries to restore it; no growth after ready poll returns prefix. Non-peeking stream WAITALL >1 MiB uses
`recv_rest` to scatter at successive offsets until short piece without further controls (fresh timeout per
recv; later hard recv error saved).

### File I/O, batches and copy faults

`rw` reads once across iovecs without flags, address or control. Empty reads consume nothing.
Datagram/seqpacket writes gather one message up to 1 MiB. An empty scalar write can send an empty
datagram. All-zero writev returns in file I/O. Stream writes continue in 1 MiB pieces, sending an already
gathered prefix on a later gather fault. A record gather fault fails the message. After a successful
piece, later sends use NOSIGNAL and errors return the existing count. File byte helpers and splice paths
use `send`/`recv` with flags zero.

Both batch calls handle at most 1024 records of 64 bytes. `sendmmsg` calls sendmsg per numeric fd and
returns completed records on a later send error. Its plain msg_len store propagates faults. `recvmmsg`
reads the deadline, handles zero elements, then pins the file and socket. It rereads nonblocking status on
each record attempt. WAITFORONE adds DONTWAIT after the first success. The batch timeout is checked after
each completed record. Later failures return the fully reported record count.

After consumption, `scatter_received`/`copy_result` retain data across FUSE page fills for individual
iovecs, address bytes/lengths, controls and flags. Recvmmsg retries a pre-consumption input fault within
the current record, and retries its final msg_len separately. Unix copies payload before consumption
(non-peeking datagram/seqpacket survives FUSE retry, while a hard scatter error still dequeues it). A
failed ancillary copy drops pending rights and reports MSG_CTRUNC and zero control length. SCM_RIGHTS fds
install only after output header writes succeed, honoring MSG_CMSG_CLOEXEC.

### Socket options

Setters resolve fd and copy ≤256 B; getters reject negative room (`EINVAL`) and cap ordinary host reads
at 256 B. SO_RCVTIMEO/SO_SNDTIMEO and *_NEW share two signed 64-bit fields across families: short timeval
→ `EINVAL`, usec outside 0–999999 → `EDOM` before sec check, negative sec → immediate expiry, 0 or
≥KTIME_MAX → unlimited (both read back 0).

Unix/netlink `SockOpts` defaults both buffers to 212992: plain sets compare unsigned, cap there and double
(floors 4608 send / 2304 recv); FORCE needs initial-userns CAP_NET_ADMIN (`EPERM`), clamps 0..INT_MAX/2
then doubles with same floors. DEBUG enable needs that cap (`EACCES`); priority outside 0–6 needs
CAP_NET_RAW/NET_ADMIN over netns owner. Generic setters need int (linger 8 B, keeping sec on disable); recv
low-water <0 → INT_MAX, 0 → 1; read-only type/error/domain/proto → `ENOPROTOOPT` after size check; unknown
generic SOL_SOCKET sets are no-ops. Netlink enforces recv capacity; Unix enforces send capacity and stream
recv low-water (only records recv capacity).

Inet SO_SNDBUFFORCE/SO_RCVBUFFORCE need initial-userns CAP_NET_ADMIN, then use ordinary host opts. Raw ICMP
opts and UDP TTL/RECVERR tap handling precede BPF; successful host recv-buffer update resyncs raw recv
accounting.

- SO_ATTACH_BPF/SO_ATTACH_REUSEPORT_EBPF: optlen <4 (`EINVAL`), then plain TCP ATTACH_BPF checks
  netns-owner CAP_NET_ADMIN (`EPERM`), exact size 4 (`EINVAL`); invalid guest fd → `EBADF`, valid →
  `EINVAL` (no guest fd reaches host).
- SO_ATTACH_FILTER/SO_ATTACH_REUSEPORT_CBPF: ≥4 B, plain TCP ATTACH_FILTER cap, exact 16 B fprog, host
  filter lock (`EPERM`; unreadable = unlocked), nonnull prog and 1–4096 insns (`EINVAL`), copying 8 B/insn
  with host-local fprog ptr. Plain TCP = stream proto 6 (excl. MPTCP).
- Plain TCP ATTACH_FILTER: host `EPERM`, test hook 14 or deferred listen validates on scratch UDP fd;
  fallback accepts only exact RET 0 (host TCP_CLOSE, SO_REUSEPORT off) or RET UINT_MAX after only
  immediate/length loads (other valid progs → `EOPNOTSUPP`). Existing host filter blocks fallback without
  retained prog; failure keeps old filter. REUSEPORT_CBPF stays on host path.
- SO_GET_FILTER takes/returns **instruction counts** (0 queries count; short nonzero room → `EINVAL`;
  retained answers locally, else HAL ≤4096 insns). SO_LOCK_FILTER uses host; SO_DETACH_FILTER on retained
  prog needs ≥4 B and unlocked host filter. Deferred listen SO_REUSEADDR uses saved guest value; enabling
  reuse-port under retained RET 0 or rebinding device with private queue → `EOPNOTSUPP`.
- SO_MARK needs 4 B + netns-owner CAP_NET_RAW/NET_ADMIN; feeds nftables and accepted children without
  setting host mark. New-ns AF_INET6 tracks IPV6_V6ONLY locally (`EINVAL` to change once bound and
  registered).
- SO_BINDTODEVICE truncates to 15 B or NUL; SO_BINDTOIFINDEX needs nonnegative int (empty/0 clears). Ns
  lookup (`ENODEV`) precedes CAP_NET_RAW on rebind/clear (first bind needs no guest cap). Real initial
  ifaces pass name to host; index is saved and ns binding updated. Unbound name readback len 0; else room
  <16 → `EINVAL`, deleted dev → `ENODEV`, else NUL-terminated name; index readback returns saved index.
- SO_ERROR drains relevant ICMP tap, consumes `icmp_err` before `pending_err`, then uses raw/UDP handlers
  or host (returned 4 B ECONNREFUSED on counted opening increments AttemptFails). SO_TYPE reconstructs
  SOCK_RAW for ping-backed raw sockets. Remaining inet opts (detach without retained prog, priority,
  IP_TRANSPARENT, SO_PEERCRED, ordinary TCP opts) use host semantics.

### Socket ioctls

Common file ioctls run first. For netlink’s 15 `CHANGING_IOCTLS` and watched namespaces, take snapshots,
run the ioctl, diff, notify subscribers and return the original result.

Inet FIONREAD/SIOCINQ returns front stub-answer length or host input count. Inet SIOCATMARK queries host
before copyout. Unix FIONREAD uses unread count (`EINVAL` if unavailable), SIOCOUTQ outgoing bytes.
Netlink FIONREAD gives uncounted `ENOTTY`. Interface ioctls try virtual devices first, then namespace
loopback or initial host interfaces. Host SIOCGIF* handles name, flags, address, destination, broadcast,
mask, MTU, HWADDR, index and tx queue length. Zero HWADDR family is reconstructed from loopback flags.
Recognized host setters read 40 bytes before `EPERM`. Unix/netlink borrow a temporary host datagram fd.

SIOCGIFCONF reports size for a null buffer. Initial-namespace output appends whole 40-byte virtual entries
after host entries (64 KiB room cap). A new namespace supplies loopback plus virtual entries. Unhandled
requests including inet SIOCOUTQ and timestamp queries reach `stub::ioctl` and return `ENOTTY`. Expected
terminal probes are filtered from counts.

Inet SIOCADDRT/SIOCDELRT read arm64 rtentry (IPv4, 120 bytes) or in6_rtmsg (IPv6, 80) before
namespace-owner CAP_NET_ADMIN (`EPERM`). IPv4 checks families (`EAFNOSUPPORT`), contiguous mask/alignment
(`EINVAL`), or uses /32 for RTF_HOST. Device labels resolve index/preferred source (`ENODEV`). Nonzero metric sets RTA_PRIORITY to metric minus
one. RTF_GATEWAY adds require a gateway, with global scope for unicast. IPv6
retains prefix, optional gateway/index and metric, refuses source prefixes (`EOPNOTSUPP`). Adds use
RTPROT_BOOT. RTM_NEWROUTE/DELROUTE goes to `netdev::request`. Only virtual routes change. Unreachable adds
give `EOPNOTSUPP`. MTU/window/initial RTT are ignored.

### DNS stub

VFS setup's `ensure_resolv_conf` writes `nameserver 127.0.0.53\n` (0644) if `/etc` exists without
`resolv.conf` (failure logs only); Android Debian import writes its own entry.

`stub_capable` needs initial-ns SOCK_DGRAM UDP without raw flag or ICMP state; `is_stub` matches
127.0.0.53:53 and IPv4-mapped form. Connect sets `stub_peer`, clears `peer_map` and tries host connect for
local addr/port. Sends to it (or address-less on stub peer) try wildcard autobind if needed, bypassing host
payload I/O, nft and traffic counters.

At 128 kernel-wide queries (`DNS_MAX_INFLIGHT`), send drops query and returns length; else
`Kernel::dns_queries` stores id + weak socket (spawn failure removes it, `EAGAIN`). A `dns-stub` thread
runs `hal::net::dns_query`, pushes (id, answer) to `Shared::dns_answers` and signals timer without holding
kernel. HAL rejects queries <12 B: Android calls `android_res_nsend` on default net 0 (30 s poll deadline,
cancel on failed poll); non-Android tries ≤3 resolv.conf servers (UDP checks header/ID in 3 s/server;
truncation retries length-framed TCP with fresh 3 s; no servers → `EHOSTUNREACH`). On error `servfail`
copies query, setting QR, RA, rcode 2 if ≥4 B exist.

`deliver_dns` drops completed ids and dead-socket replies, dropping new reply only if **already queued**
bytes strictly exceed host SO_RCVBUF (fallback 212992). Enqueue increments `answered` and wakes socket.
Recv serves this FIFO before host data with stub src, peek and truncation semantics; FIONREAD gives front
len. Queued answers supply POLLIN|POLLOUT (tapped UDP adds host fd + ICMP error bit); read generations use
`answered`.

### Low ports and inbound forwarding

Initial `PortMap` covers 1–1023 with nonzero offset (default 10000). Nonraw TCP/UDP qualify, MPTCP shares
TCP's key. All addresses/families share (protocol, guest port). A successful bind holds a lease even at
the original port. A live key or one released within 120 s fixes the host port. Its error is final. A
fresh key tries the original unless `always_map`, the other protocol's live host port, guest port plus 1–3
offsets (distinct, in range), then zero. Only nonzero-candidate `EADDRINUSE` or original-port `EACCES`
continues. Ephemeral bind reads back the host port. Newly changed mappings are logged. Last lease removes
the live lookup immediately and permits reverse translation for less than 10 s.

`map_dest` rewrites a live mapped port only on loopback, wildcard or a host interface address.
`unmap_addr` applies the socket's matching local pair. `translate_peer` passes the intact endpoint to
`netns::from_host_peer`. A matching saved peer pair precedes a namespace binding or local initial reverse
lookup. Accept, peer names, recv and proc share it. Non-loopback host listens enqueue comm, guest and host ports, host bind IP, dual-stack wildcard status
and the bound interface index. Only queued port pairs are deduplicated for the instance lifetime. A pair dropped
at the 32-event cap remains eligible. `Machine::take_listens` drains the queue.

`forward_target` considers initial TCP/MPTCP listeners and bound UDP with PREROUTING NAT. It restores the
guest port before `external_nat`, retries a dual-stack wildcard as IPv4, translates, excludes
isolated/filter-denied targets and chooses the namespace binding or initial mapped port where available.
`forward_socket` registers fd and target with the forwarder on datagram bind or listen. `forward_all`
rescans after an initial NETFILTER generation change, and after a new-namespace explicit port registration
or TCP listen marking a binding if initial PREROUTING NAT exists.

Updating the UDP handle in place lets replies drain while withdrawal pauses intake. Removing an ordinary
TCP target drops its handle. Deferred listen gives the forwarder a private TCP listener, copying host
v6-only and device binding and temporarily enabling guest reuse-address to bind the same endpoint. Target
loss or failed registration discards that queue. `ForwardListener::drop` shuts it down even while the
worker holds a dup. The guest fd keeps the port.

### Traffic and proc files

`Traffic::account` counts initial-namespace payload. Datagrams count once even if empty. Nonempty streams
contribute ceil(bytes / max(MSS,1)). Host receive skips PEEK and ERRQUEUE. Raw ICMP feeds tap arrivals.
Stub and Unix traffic bypass interface counters. Bind/connect invalidate the cached socket route. A
concrete local IP maps to interface/MSS (MTU minus 52). Wildcard traffic selects loopback or default by
peer. The interface cache expires after 5 s and can refresh on address miss after 1 s. Default selection
tries host IPv4 then IPv6 source selection, an up non-loopback interface, then loopback.

`Protocols` counts IPv4 packets/octets including mapped IPv4, IPv4 UDP datagrams, ping echoes/replies and
estimated TCP/MPTCP segments across families. Initial TCP/MPTCP connect counts success or EINPROGRESS once
per `opening` flag. Immediate refusal/timeout can count an open and failure together. Refusal, timeout or
host/net unreachable on a counted attempt increments AttemptFails and clears opening. Host accept counts
passive opens. MPTCP accounting is per guest socket.

Procfs selects the named process's pinned network namespace and translates inode uid through the reader's
user namespace. Its inode-sorted inventory deduplicates sockets and includes sockets held only in Unix
rights. `proc_listing` renders tcp/udp/icmp/raw and v6 variants. Host SO_PROTOCOL identifies TCP including MPTCP, UDP and ping, and supplies the raw local port (0 on
query failure), while the guest raw flag selects the raw table. Raw/ping peer port is zero.
Addresses reverse host and port translation and can retain the outside peer. TCP state uses the first
TCP_INFO byte, default CLOSE, or LISTEN for a held listener. Queue, timer and retransmit fields are
placeholders.

`sockstat` uses host protocol for TCP/MPTCP. In-use requires requested family and non-CLOSE. IPv4 alloc
counts both families/all states. Bound or stub-connected UDP and raw count by family. IPv4 used counts all
supplied sockets. Orphan, TIME_WAIT and memory are zero. `proc_snmp` counts CurrEstab by stored TCP/MPTCP
protocol and exactly ESTABLISHED. Noninitial namespaces have zero cumulative counters but live namespace
socket counts. Procfs reports IP Forwarding 1 for ip_forward on, else 2. In netstat, TcpExt/MPTcpExt are
zero. IpExt reports payload InOctets/OutOctets and inbound packets as InNoECTPkts. `PROC_ARP` contains
only the neighbour-table header.

### Original destination

`origdst::Table::register` keys (host client local, host destination), mapping IPv4 into IPv6. On
TCP/MPTCP host success or EINPROGRESS with a usable tuple, connect registers pre-OUTPUT or pre-PREROUTING
originals for each rewriting namespace before waiting. Empty originals clear a stale tuple. Client close
leaves the record intact. Nonempty insertions prune at most once a second and evict the oldest for a new
key at 4096. At accept, `claim` uses (host peer, host local), removing the whole entry before checking its
120 s age and matching the listener namespace.

SOL_IP/SOL_IPV6 SO_ORIGINAL_DST handles TCP/MPTCP streams. UDP gives `ENOPROTOOPT`. Other protocols, raw
ICMP and SOL_IPV6 on IPv4 fall through. Room below 16/28 bytes gives `EINVAL` before lookup. A saved
initiating peer is returned when the namespace tracks its family. Accept-side lookup requires a host peer
and returns the claimed original or, if tracking, the translated local endpoint. Missing or wrong-family
result gives `ENOENT`. Mapped IPv4 uses SOL_IP. Success leaves optlen unchanged.

## Dependencies (inbound / outbound)

### Inbound

- `kernel/process` dispatches syscalls and drains listens. `kernel/files` dispatches ioctls and updates
  socket ownership. `kernel/vfs` reads ownership for stat and sets up resolv.conf. `kernel/fileio` and
  `kernel/pipe` use socket transfers.
- `kernel/poll` combines ready bits, host fds, wake objects and arrivals. `kernel/time` delivers DNS
  completions. Unix/netlink use shared options and message helpers. Netlink reads link counters and
  triggers forwarding rescans. Raw ICMP uses the local-address translator and feeds tap arrivals to
  Traffic.
- `kernel/procfs` supplies socket inventory, reads interface counters for dev, renders if_inet6, and
  delegates route/ipv6_route to `kernel/route` and unix to `kernel/unix`. `kernel/sysfs` reads interface
  statistics. `kernel/route` calls `traffic::outgoing_source` for default interface selection.

### Outbound

- `appine_hal::net` supplies socket calls, interface enumeration, source selection and DNS.
  `kernel/process` supplies waits/BKL, `kernel/files` fd allocation, `kernel/fuse` page fills,
  `kernel/signal` restart, SIGPIPE, logs and unsupported-path counts.
- `kernel/netns` owns translation, bindings, virtual device/route mutations, forwarding workers and
  netconf. `kernel/sysctl` exposes the port threshold/range, bridge settings and fd-growth limit consumed
  on these paths. `kernel/nft` evaluates rules, returns verdicts and reports whether a family tracks
  connections (per-socket `Track` and `origdst::Table` belong to `kernel/net`).
- `kernel/netlink` owns family operations, message/attribute wrapping, snapshots and notifications
  (while `kernel/route` encodes route-entry bodies and `kernel/net` builds legacy ioctl route change
  requests). `kernel/rawicmp` owns ping emulation, taps, raw receive and error draining. `kernel/unix`
  owns local sockets and rights transfer. `kernel/bug` supplies TCP filter refusal injection.

## Invariants and gotchas

- Host sendmsg/recvmsg run nonblocking under the big kernel lock (BKL). Ordinary socket waits release it
  and register the socket wake object. A peek wait's temporary host low-water change is visible to
  concurrent pollers.
- Keep the original host peer port through namespace ownership lookup. Release the socket borrow before a
  raw tap drain that may borrow it again. Keep changing ioctls inside the watched snapshot/diff path and
  the private DNAT accept queue separate from guest accept.

## Confirmed bugs / technical debt

- In-process socket buffers keep fixed defaults/caps after guest core buffer sysctl writes.
- `sendmmsg` re-resolves numeric fd per record; despite best-effort dispatcher prefaulting, a post-send
  `msg_len` fault can hide earlier progress and whole-syscall FUSE retry can repeat output. In `recvmmsg`,
  SCM_RIGHTS fds install before outer `msg_len` store, so an unrecoverable `msg_len` fault can leak fds
  for an uncounted record.
- On inet streams, an unrecoverable scatter fault in a later WAITALL piece leaves it consumed but uncounted;
  continuing WAITALL after a short read drops earlier controls/flags (finishing piece wins; partial
  completion after failed wait/error returns empty controls and zero flags).
- `wait_peek` saves/raises/restores host SO_RCVLOWAT across an unlocked wait, risking overwriting a
  concurrent SO_RCVLOWAT update.
- Normal `connect` overwrites `guest_peer` before host connect, so on `EISCONN` later `getpeername`
  verifies the host peer and returns the rejected destination.
- DNS stub connect ignores host connect result (synthetic peer success can disagree with actual local
  endpoint); recv with MSG_ERRQUEUE checks ordinary stub FIFO and can consume a DNS reply.
- External DNAT relay registers no origdst tuple, so accepting socket returns translated local addr when
  tracking or `ENOENT`, losing pre-DNAT destination.
- Passthrough opts check host creds: authorized guest FORCE uses capped plain host opts; guest-authorized
  real-device bind ignores host `EPERM` and clearing host binding ignores host errors (saved iface and host
  routing can diverge).
- Host-refused RET 0 listener can give peers immediate refusal; reuse-addr conflict can surface at
  activation; packet-dependent filters, active-stream drops and reuse-port sharing exceed fallback. TCP
  drop blackhole keeps host fds past kernel teardown.

## Open questions

- Should UDP queries from a new namespace have a route to the stub, and should intercepted queries
  traverse nftables?
- Which creation errno should AF_PACKET expose to a caller without raw capability?

## To verify

- On target Android kernels, measure privileged passthrough option results, TCP filter acceptance and
  MPTCP TCP_INFO availability.
- On a phone, check reachability of guest loopback listeners from another app and default-network resolver
  behavior under Private DNS and VPN changes.
