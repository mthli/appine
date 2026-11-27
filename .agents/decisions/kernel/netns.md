# Network namespaces (kernel/netns) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: kernel/netns"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12). D1–D8 were seeded by hand from the retired design doc
> §10 and the round records in `docs/rounds/`, so their sources cite rounds; later entries cite
> commits.

## Active

### D1: Network namespaces translate addresses instead of running a network stack
- **What**: A new namespace is a block of host loopback addresses (127.a.b.0/24); its sockets stay host sockets and addresses are rewritten at the syscall boundary in both directions.
- **Why**: bubblewrap's `--unshare-net` (Codex's sandbox) needs `CLONE_NEWNET` with a working lo; the app has no usable host network namespaces, and a separate TCP/IP stack was not worth building.
- **Tradeoffs**: Isolation only holds inside the guest: host loopback sockets are reachable from the initial namespace and from other apps; IPv6 has only loopback and host-routed paths.
- **Watch out**: Every address returned to the guest must be translated for the viewing socket's namespace.
- **Source**: round 122 (`122-net-namespaces`)

### D2: Virtual devices are kept as Linux state, without packets
- **What**: bridge, veth and dummy devices, their addresses, routes and sysctls follow Linux 6.1 per namespace and are visible through netlink, ioctl, `/proc` and `/sys`; no packet ever passes through them.
- **Why**: dockerd and netavark failed at the first netlink request (`Failed to create bridge docker0`), so containers could not start; matching Linux's state lets `ip addr`/`ip route` in containers look native.
- **Tradeoffs**: Device statistics are zero; `forwarding` and similar settings are recorded only.
- **Watch out**: Host interfaces in the initial namespace must stay unchangeable (requests that change nothing still succeed).
- **Source**: rounds 169 (`169-container-network-survey`), 170 (`170-netdev`)

### D3: Virtual IPv4 addresses map to host loopback addresses at the socket layer
- **What**: Connections and datagrams to addresses on virtual devices go through the host's loopback, to the owning namespace's 127.c.d.1 (or 127.255.x.y for the initial namespace); traffic routed through a gateway to unowned addresses goes to the host network. Recycle unused virtual addresses, reserve container loopback blocks beginning at 127.0.2.0 and preserve reported guest identities across the full 127/8 range.
- **Why**: After devices existed, containers still could not reach each other or be reached from outside ("Network unreachable"), docker's `-p` containers failed and aardvark-dns could not bind the gateway; this reuses the namespace loopback mapping.
- **Tradeoffs**: IPv6 virtual addresses are not mapped.
- **Watch out**: The owner of an address is found from routes over virtual devices; directly connected misses are `EHOSTUNREACH`.
- **Source**: round 171 (`171-netmap`), 6feb4b9f, d11379f7, 11cb85a0

### D4: nftables is recorded like Linux and evaluated at connect and sendto
- **What**: The ruleset arrives over `NETLINK_NETFILTER` in Linux's format (iptables-nft works); nat chains run at connect/sendto and DNAT rewrites destinations in the socket layer.
- **Why**: docker's embedded DNS (127.0.0.11) relies on iptables nat rules, dockerd with `"iptables": true` and netavark's nftables driver failed without `NETLINK_NETFILTER`; with no packets, connect/sendto are the only points where rules can act.
- **Tradeoffs**: No conntrack, no POSTROUTING effects, no per-segment filtering; counters are zero.
- **Watch out**: Unknown expressions must make rules not match rather than fail evaluation.
- **Source**: round 172 (`172-nftables`)

### D5: Filter chains are evaluated along the emulated path
- **What**: OUTPUT of the sender, PREROUTING/FORWARD/INPUT of the namespaces a connection passes through are evaluated; rejects return errors immediately and TCP drops time out like Linux. Evaluate bridge and endpoint hooks in their own namespaces, retain blocking verdicts and marks, and select the original or translated destination according to PREROUTING priority.
- **Why**: Without filtering, docker network isolation, `--internal` networks, `DOCKER-USER` rules and podman's isolate had no effect.
- **Tradeoffs**: IPv4 only; UDP drops outside the sender are silent successes.
- **Watch out**: The path steps must match how the address mapping routes the connection.
- **Source**: round 175 (`175-filter`), f7ddba97, 76da8eff, 3db35725

### D6: Namespace ports are registered in the namespace, not left to the host
- **What**: Explicit binds get separate host ports, IPv4 and IPv6-only binds of one port each their own; listeners and bound UDP sockets are registered per namespace, by family and v6only; connections without a matching listener are refused. Address-less UDP sends recheck destination listeners and preserve a blocked verdict from a prior connect. Bindings now enforce guest address overlap, reuse flags and socket-owner rules before host bind or TCP listen.
- **Why**: A port bound on the outer wildcard made the same port `EADDRINUSE` inside containers (`-p 8080:8080` style publishes failed); connections to unused ports were captured by outer wildcard listeners, and services bound only to a container's 127.0.0.1 were reachable from other containers.
- **Tradeoffs**: 127.0.0.1 and the virtual address share one host address, so the same port on both conflicts.
- **Watch out**: Unregister listeners when sockets close.
- **Source**: round 174 (`174-namespace-ports`); ea08993, a1fd0beb, 7fad509d

### D7: A host thread forwards outside connections to published ports
- **What**: Initial-namespace sockets matched by PREROUTING DNAT are served by a forwarder thread that accepts and connects to the target; IPv4 TCP clients from outside keep their real address through a 127.254.x.y binding.
- **Why**: Connections from outside Appine (the phone's browser, other devices) reached podman's port-holding socket, which never accepts; Linux delivers them through DNAT, and containers' access logs, rate limits and allow lists need the real client address.
- **Tradeoffs**: The decision comes from a synthetic probe; loopback, IPv6 and UDP clients see the gateway.
- **Watch out**: The thread must never take the BKL.
- **Source**: rounds 173 (`173-port-forwarding`), 176 (`176-real-client-address`)

### D8: Containers going out pass the outer namespace's PREROUTING
- **What**: Connections from a namespace to the host network run the initial namespace's PREROUTING nat, and unbound UDP sockets keep working when they first talk inside the namespace and then outside.
- **Why**: Transparent proxies for containers are written as outer `REDIRECT` rules on traffic entering from the bridge (`iifname "podman0"`); UDP sockets that first sent to the gateway then got `EINVAL` sending outside, unlike native.
- **Tradeoffs**: PREROUTING nat runs only once and only for IPv4.
- **Watch out**: Wide UDP sockets must keep both paths usable.
- **Source**: rounds 182 (`182-outer-prerouting`), 183 (`183-udp-wide-first`)

### D9: Network namespaces own packet paths and forwarding; nftables has its own module
- **What**: kernel/netns owns netns.rs, netdev.rs, forward.rs and netconf.rs, including namespace packet paths and hook selection, while nft.rs rulesets and expression evaluation belong to kernel/nft.
- **Why**: Separating independently maintained subsystems makes the oversized parent map practical to read and maintain.
- **Tradeoffs**: Cross-boundary work follows child maps, while historical decisions keep their original parent tags and stable IDs.
- **Watch out**: Bugs confirmed at the split or found since stay open in the map, among them no TUN/TAP device (so rootless podman's pasta and slirp4netns cannot start), per-key rate limits doing nothing, the initial namespace reaching containers' loopback sockets, same-port binds judged by host address, ephemeral ports aliasing explicit ones, connected UDP escaping namespace checks, nat drops becoming `EPERM`, dual-stack listeners not forwarded and route requests whose address sizes, gateway devices and next hops are checked unlike Linux 6.1's; these pre-split qualifications remain discoverable through the child maps and are not resolved by the ownership split.
- **Source**: c3df5d3 (bug list since updated by ce8a9ce, ac5da40, b43ff37 and 5d7b936); 60790c14; 39e55b4b

### D10: `nft flush ruleset` flushes tables as Linux's `nf_tables_deltable` does
- **What**: DELTABLE and DESTROYTABLE with family 0, or with neither a name nor a handle, remove every table of the family (all families for 0), only those with the given name if one is present, checked before the family validation; matching nothing succeeds.
- **Why**: Firewall configurations conventionally start with `flush ruleset`, so none of them could be loaded with `nft -f`.
- **Tradeoffs**: None; tables are self-contained, so dropping them whole matches `nft_flush_table`.
- **Watch out**: Owner-flagged tables, which Linux skips for other sockets, cannot exist here, since the flag is `EOPNOTSUPP`.
- **Source**: d27f421

### D11: Namespace files read in the order their code runs
- **What**: netdev.rs follows `do_setlink`'s changes, then names, addresses, routes, attributes, ids, ioctl and sysctls before the shared parts; netns.rs goes from `World` through translation, listeners, nat, filter chains and forwarding to address helpers and lo; nft.rs runs requests, changes, queries, execution (nat before filter), expressions and encoding; forward.rs puts the forwarding thread before the client address registry; netconf.rs puts the `NetConf` impls first.
- **Why**: The user wanted each Rust file to read in the order its code runs, as the Kotlin code does since a546fea; netdev.rs and netns.rs mostly put helpers above their callers.
- **Tradeoffs**: git blame on the moved lines points at this commit unless read with -M or an ignore-revs file; netns.rs now has 10 sections, and `impl Default for NetConf` stays before the inherent impl as its constructor.
- **Watch out**: Code appended at the end of a file drifts from this order.
- **Source**: 5bc3be5

### D12: The port forwarder backs off instead of spinning
- **What**: A port whose first accept of a round fails with anything but `EAGAIN` is skipped for `ACCEPT_BACKOFF` (1 s), with the poll timeout shortened to its return; a poll error other than `EINTR` sleeps `POLL_BACKOFF` (100 ms); and a connection ends when a side reports `POLLERR` with a pending `SO_ERROR`, or `POLLHUP` after its end of file while the forwarder has not shut it for writing.
- **Why**: The forwarder polled a readable listener whose accept kept failing (`EMFILE`, shut down), retried a failing poll at once, and kept polling a reset side it no longer read from, so each case spun the thread until something else changed.
- **Tradeoffs**: A listener that recovers waits up to a second for its next accept, and a failing poll still retries ten times a second without a report.
- **Watch out**: A host that reported `POLLHUP` for a cleanly half-closed side before the forwarder shut it would cut such connections; Linux reports it only with both directions shut or after a reset.
- **Source**: ac5da40

### D13: Onlink routes and nftables rate limits and quotas work as on Linux
- **What**: Route requests read `rtm_flags`, and with `RTNH_F_ONLINK` the next hop needs a device that is up and, for IPv4, a scope below link and a unicast gateway, with dumps showing the flag; `Rule::rates` holds token buckets and quota counts by expression position (shared by batch copies), which `limit`, `quota` and xt `limit` use.
- **Why**: Onlink routes failed with `ENETUNREACH`, "limit rate over" and "quota over" dropped every packet, "limit rate" accepted everything, and `iptables -m limit` never matched.
- **Tradeoffs**: Byte limits and quotas count headers only, like `meta len`, and meters, hashlimit and recent still do nothing.
- **Watch out**: A rule replaced in a batch starts with a full bucket, as a new expression does on Linux.
- **Source**: b43ff37

### D14: ping_group_range maps its group IDs with the writer's credentials
- **What**: `parse_ping_group` maps both IDs through the writer's credentials: `EINVAL` when one is unmapped, `(1, 0)` when the range is reversed, kernel IDs otherwise.
- **Why**: The sysctl stored the raw numbers, so a write from a user namespace recorded the wrong group IDs and accepted unmapped ones.
- **Tradeoffs**: The value is still only recorded, since ping sockets are handed to the host and Android allows all groups.
- **Watch out**: Writers of IDs unmapped in their namespace now get `EINVAL`, as on Linux.
- **Source**: 2aca260

### D15: Forwarder spin tests read only the forwarder's own thread ticks

- **What**: Under `#[cfg(test)]`, `Shared::tid` records the forwarding thread's host TID in a `OnceLock<i32>` at the start of `run()` so `resets_and_dead_listeners_do_not_spin` reads only `/proc/self/task/<tid>/stat` via `rsplit_once(") ")`.
- **Why**: Scanning all of `/proc/self/task` panicked when unrelated test threads exited mid-scan and mixed ticks across parallel forwarder tests.
- **Tradeoffs**: Adds a test-only field and `gettid()` call to production types and assumes the test interacts with the forwarder before reading `tid`.
- **Watch out**: A future test reading `shared.tid` before the forwarder handles a connection can observe `None` unless synchronized.
- **Source**: 45495baf

### D16: Route change parsing accepts Linux-valid destination attribute sizes

- **What**: Validate route add and delete attributes using family-specific policy and address length rules.
- **Why**: Requiring every attribute to equal the full address width rejected native-valid requests.
- **Tradeoffs**: Handling differs for IPv4 and IPv6.
- **Watch out**: Keep destination, gateway and preferred-source checks consistent with the routing ABI.
- **Source**: 11cb85a0

### D17: Established forwarded connections survive DNAT rule removal

- **What**: Dropping a DNAT forwarder `Handle` stops new intake while keeping established forwarded TCP connections and in-flight UDP relays alive, matching Linux 6.1 conntrack NAT and pinned by `tcp_connection_survives_handle_drop`.
- **Why**: Resolving `kernel/netns.md`'s open teardown question aligned firewall reloads with Linux 6.1 conntrack semantics instead of forcibly resetting active sessions.
- **Tradeoffs**: Terminating an already-forwarded session still requires endpoint close or reset rather than DNAT rule removal alone.
- **Watch out**: Per-segment filter re-evaluation remains absent on established forwarded connections.
- **Source**: 8bd3fa4c

### D18: Namespace `Bindings` indexes ports and IDs and expires linger entries via a FIFO queue

- **What**: Index `Bindings` with `by_id`, insertion-ordered `PortIndex` buckets for guest and host ports, `udp_host_ips` counts for `unique_udp_host_ip`, and an amortized FIFO `released` queue for 10 s linger expiry.
- **Why**: Linear scans over a flat `Vec<Binding>` made conflict checks, target resolution, and ephemeral port probing `O(N)` per namespace, while every close scanned all lingering entries with `linger.retain`.
- **Tradeoffs**: Maintains four synchronized index structures inside `Bindings` on every insertion and removal.
- **Watch out**: Mutating a binding's address, port, or device without updating all four indexes will corrupt conflict checks or lookup priority.
- **Source**: b38371ff

## Superseded
- ~~D9 combined ownership of the newly extracted child modules~~ → replaced by **D9** in 60790c14 (2026-11-15; scope only, inherited implementation decisions remain here)
- ~~Keeping virtual namespace bind ownership in the initial-namespace port map (kernel/net D11)~~ → replaced by **D6** in 7fad509d (2026-11-25)
- ~~Flat `Vec<Binding>` linear scans and per-close `linger.retain` (D6 in part, 7fad509d)~~ → replaced by **D18** in b38371ff (2026-11-27)
