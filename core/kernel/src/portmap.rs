//! Port mapping below 1024 (the kernel/net map).
//!
//! Apps on Android have no `CAP_NET_BIND_SERVICE` and generally cannot bind ports below 1024 on
//! the host; root in the guest still wants to bind 22, 80 and the like (the default configuration
//! of sshd and nginx). When a guest socket binds such a port and the host lets it bind the original
//! port, it binds the original port: Google Play system updates since May 2026 (Android 13 and
//! later, kernel 5.15 and later) let apps bind TCP 20–23, 80, 443, 445, 515, 631 and UDP 319, 320,
//! 443, and development hosts running as root or in a container can bind them too. When the host
//! refuses (EACCES) or another app on the phone holds the port (EADDRINUSE), the host socket binds
//! another port, and the guest side cannot tell: getsockname, getpeername, accept, recvfrom and
//! `/proc/net` all report the original port, and connecting to this port on the local machine is
//! switched to the host's port. Other devices and other apps on the phone have to connect to the
//! port on the host; the first time the app sees a listen on a non-loopback address it notifies
//! the user ([`PortMap::take_listens`]).
//!
//! After the original port it tries `port + offset`, `port + 2 × offset`, `port + 3 × offset` in
//! turn (up to 65535), and when all are taken lets the host assign one. The last three digits are
//! always the original port (with offset 10000, 22 becomes 10022, 20022, 30022), recognizable at a
//! glance and never taking another port's place. As long as any socket in the initial network
//! namespace binds a `(protocol, port)`, it keeps the same host port (`SO_REUSEPORT`, IPv4 and
//! IPv6 bound separately), and the guest's own conflicts are EADDRINUSE as usual; TCP and UDP on
//! the same port use the same host port when possible (DNS's 53). A bind within [`STICKY`] after
//! all of them closed still tries the original host port first and is EADDRINUSE when that fails:
//! after a TCP connection closes its host port is in TIME_WAIT, and on Linux binding again without
//! `SO_REUSEADDR` is EADDRINUSE too, so it must not be taken as held by another program and moved
//! to another port. New network namespaces manage their own port mappings in
//! [`crate::netns::Bindings`].

use std::collections::{HashMap, HashSet, VecDeque};
use std::net::IpAddr;
use std::rc::Rc;
use std::time::{Duration, Instant};

use crate::cell::RefCell;
use crate::errno::Errno;

/// The default offset.
pub const DEFAULT_OFFSET: u16 = 10000;

/// The most listen events kept that the app has not taken yet (appine-run does not take them).
const MAX_EVENTS: usize = 32;

/// How long after a mapping is fully released a new bind still uses the original host port:
/// longer than TIME_WAIT (60 seconds on both Linux and Android).
pub const STICKY: Duration = Duration::from_secs(120);

/// How long after a mapping is fully released the host port is still translated back to the guest
/// port. Linux translates when a packet arrives, here it happens only when the other side calls
/// recvfrom or accept: when a service closes its socket right after answering a datagram (as
/// `srv.py` does), the answer is still in the other side's receive queue, and when the other side
/// reads it the source is still the guest's port. The chance that the host gives this port to
/// another, unmapped socket in the same namespace during that time is small (the host picks
/// ephemeral ports at random).
pub const LINGER: Duration = Duration::from_secs(10);

/// The key of the registry: protocol, guest port (initial network namespace).
type Key = (i32, u16);

/// A host port: protocol, port on the host.
type HostKey = (i32, u16);

struct Entry {
    host: u16,
    refs: u32,
}

/// The guest listens on a port on a non-loopback address for the first time (design doc §12
/// item 7).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ListenEvent {
    /// The name (comm) of the listening process.
    pub comm: String,
    /// The port the guest sees.
    pub port: u16,
    /// The port on the host: other devices connect to it (the same as `port` without a mapping).
    pub host_port: u16,
    /// Host socket address, including wildcard binds.
    pub address: IpAddr,
    /// An IPv6 wildcard listener that also accepts IPv4 connections.
    pub dual_stack: bool,
    /// Bound interface index, or zero for any interface.
    pub interface: i32,
}

/// The initial-namespace low-port mapping registry, one for the whole kernel
/// ([`crate::kernel::Kernel::portmap`]).
pub struct PortMap {
    /// 0 means no mapping: binding low ports is left to the host as before (mostly EACCES on
    /// Android).
    pub offset: u16,
    /// Map even when the host allows the original port
    /// ([`crate::MachineOptions::always_map_low_ports`]): to test mapping on hosts that can bind
    /// low ports.
    pub always_map: bool,
    entries: HashMap<Key, Entry>,
    /// Host port → guest port (the same key, with the port on the host); fully released ones carry
    /// the release time ([`LINGER`]).
    by_host: HashMap<HostKey, (u16, Option<Instant>)>,
    /// Fully released mappings: the host port and the release time ([`STICKY`]).
    released: HashMap<Key, (u16, Instant)>,
    /// (guest port, host port) pairs already reported.
    reported: HashSet<(u16, u16)>,
    events: VecDeque<ListenEvent>,
}

impl PortMap {
    pub fn new() -> PortMap {
        PortMap {
            offset: DEFAULT_OFFSET,
            always_map: false,
            entries: HashMap::new(),
            by_host: HashMap::new(),
            released: HashMap::new(),
            reported: HashSet::new(),
            events: VecDeque::new(),
        }
    }

    /// Whether this port is mapped.
    pub fn maps(&self, port: u16) -> bool {
        self.offset != 0 && (1..1024).contains(&port)
    }

    /// Which host port an already mapped guest port is (connecting to this port on the local
    /// machine is switched to it).
    pub fn host_port(&self, proto: i32, port: u16) -> Option<u16> {
        self.entries.get(&(proto, port)).map(|e| e.host)
    }

    /// Live bindings as (protocol, host port, guest port), including binds to the original port.
    pub fn active(&self) -> Vec<(i32, u16, u16)> {
        let mut rows: Vec<_> = self
            .entries
            .iter()
            .map(|(&(proto, guest), entry)| (proto, entry.host, guest))
            .collect();
        rows.sort_unstable();
        rows
    }

    /// When a host port comes from a mapping (including just released ones, [`LINGER`]), the port
    /// the guest sees.
    pub fn guest_port(&self, proto: i32, host: u16) -> Option<u16> {
        self.by_host
            .get(&(proto, host))
            .filter(|(_, t)| t.is_none_or(|t| t.elapsed() < LINGER))
            .map(|&(g, _)| g)
    }

    /// Records a listen on a non-loopback address: queues an event the first time a (guest port,
    /// host port) appears. Only a queued one counts as reported, so a listen dropped by a full
    /// queue is reported when it happens again.
    pub fn listened(&mut self, event: ListenEvent) {
        if self.events.len() < MAX_EVENTS && self.reported.insert((event.port, event.host_port)) {
            self.events.push_back(event);
        }
    }

    /// Takes the queued listen events.
    pub fn take_listens(&mut self) -> Vec<ListenEvent> {
        self.events.drain(..).collect()
    }
}

impl Default for PortMap {
    fn default() -> Self {
        PortMap::new()
    }
}

/// A mapping a socket holds bound: returned on drop (the last return removes the registration).
pub struct Lease {
    map: Rc<RefCell<PortMap>>,
    key: Key,
}

impl Drop for Lease {
    fn drop(&mut self) {
        let mut m = self.map.borrow_mut();
        let Some(e) = m.entries.get_mut(&self.key) else {
            return;
        };
        e.refs -= 1;
        if e.refs == 0 {
            let host = e.host;
            m.entries.remove(&self.key);
            let (proto, port) = self.key;
            let now = Instant::now();
            m.by_host
                .retain(|_, (_, t)| t.is_none_or(|t| now.duration_since(t) < LINGER));
            if let Some(v) = m.by_host.get_mut(&(proto, host))
                && v.0 == port
            {
                v.1 = Some(now);
            }
            m.released
                .retain(|_, (_, t)| now.duration_since(*t) < STICKY);
            m.released.insert(self.key, (host, now));
        }
    }
}

/// Binds the guest port `port` on the host: `bind` binds the given host port (0 lets the host
/// assign one) and returns the host errno; `bound_port` reads the port the host actually bound.
/// Returns the host port (`port` itself when the original port could be bound) and the lease, and
/// whether this mapping is new (new ones whose port changed write a kernel log line). Binding
/// the original port is registered too: binding the same port again, or just after release
/// (TIME_WAIT), still uses it, and conflicts are EADDRINUSE as usual rather than being taken as
/// the host refusing and moved to another port.
pub fn bind(
    map: &Rc<RefCell<PortMap>>,
    proto: i32,
    port: u16,
    mut bind: impl FnMut(u16) -> Result<(), i32>,
    bound_port: impl Fn() -> Option<u16>,
) -> Result<(u16, Lease, bool), Errno> {
    let key = (proto, port);
    let existing = map.borrow().host_port(proto, port).or_else(|| {
        // Just released: still the original host port (STICKY)
        let m = map.borrow();
        m.released
            .get(&key)
            .filter(|(_, t)| t.elapsed() < STICKY)
            .map(|&(h, _)| h)
    });
    let lease = |map: &Rc<RefCell<PortMap>>, host: u16| {
        let mut m = map.borrow_mut();
        m.released.remove(&key);
        m.entries.entry(key).or_insert(Entry { host, refs: 0 }).refs += 1;
        m.by_host.insert((proto, host), (port, None));
        Lease {
            map: map.clone(),
            key,
        }
    };
    // A socket already binds this port, or it was just released: use the same host port, and a
    // conflict is the guest's own
    if let Some(host) = existing {
        bind(host).map_err(Errno)?;
        return Ok((host, lease(map, host), false));
    }
    let (offset, always_map) = {
        let m = map.borrow();
        (u32::from(m.offset), m.always_map)
    };
    let mut candidates = Vec::new();
    // When the host allows the original port, bind the original port
    if !always_map {
        candidates.push(port);
    }
    // The other protocol on the same port is already mapped: try its host port next
    let other = if proto == crate::net::IPPROTO_TCP {
        crate::net::IPPROTO_UDP
    } else {
        crate::net::IPPROTO_TCP
    };
    if let Some(h) = map.borrow().host_port(other, port)
        && !candidates.contains(&h)
    {
        candidates.push(h);
    }
    for k in 1..=3u32 {
        let h = u32::from(port) + k * offset;
        if h <= u32::from(u16::MAX) && !candidates.contains(&(h as u16)) {
            candidates.push(h as u16);
        }
    }
    candidates.push(0);
    for h in candidates {
        match bind(h) {
            Ok(()) => {
                let host = if h == 0 {
                    bound_port().ok_or(Errno::EADDRINUSE)?
                } else {
                    h
                };
                return Ok((host, lease(map, host), true));
            }
            // Held by a program outside the guest (or the guest bound this high port directly):
            // try the next one
            Err(e) if e == Errno::EADDRINUSE.0 && h != 0 => {}
            // The host refuses the original port (on Android, not one of the opened few): try
            // another
            Err(e) if e == Errno::EACCES.0 && h == port => {}
            Err(e) => return Err(Errno(e)),
        }
    }
    Err(Errno::EADDRINUSE)
}

#[cfg(test)]
mod tests {
    use super::*;

    const TCP: i32 = crate::net::IPPROTO_TCP;
    const UDP: i32 = crate::net::IPPROTO_UDP;

    fn map() -> Rc<RefCell<PortMap>> {
        Rc::new(RefCell::new(PortMap::new()))
    }

    /// A host that refuses low ports (on Android, ports not among the opened few): those in `busy`
    /// are held by others.
    fn refusing(busy: &[u16]) -> impl FnMut(u16) -> Result<(), i32> + '_ {
        move |p| {
            if (1..1024).contains(&p) {
                Err(Errno::EACCES.0)
            } else if busy.contains(&p) {
                Err(Errno::EADDRINUSE.0)
            } else {
                Ok(())
            }
        }
    }

    #[test]
    fn tries_the_offsets_then_lets_the_host_choose() {
        let m = map();
        let mut tried = Vec::new();
        let (h, _a, new) = bind(
            &m,
            TCP,
            22,
            |p| {
                tried.push(p);
                refusing(&[10022, 20022])(p)
            },
            || None,
        )
        .unwrap();
        assert_eq!((h, new), (30022, true));
        assert_eq!(tried, [22, 10022, 20022, 30022]);
        let (h, _b, _) = bind(&m, TCP, 80, refusing(&[30080, 10080, 20080]), || {
            Some(45678)
        })
        .unwrap();
        assert_eq!(h, 45678);
        assert_eq!(m.borrow().guest_port(TCP, 45678), Some(80));
        // Other errors stop the search: when a moved-to port is also EACCES, no further move
        assert_eq!(
            bind(&m, TCP, 23, |_| Err(Errno::EACCES.0), || None).err(),
            Some(Errno::EACCES)
        );
        assert_eq!(
            bind(&m, TCP, 24, |_| Err(Errno::EADDRNOTAVAIL.0), || None).err(),
            Some(Errno::EADDRNOTAVAIL)
        );
    }

    /// The host allows the original port (opened, or running as root): bind the original, register
    /// it too, and use it again on rebinding and just after release; move only when another
    /// program holds it or only one protocol is opened.
    #[test]
    fn the_real_port_when_the_host_allows_it() {
        let m = map();
        let (h, a, new) = bind(&m, TCP, 22, |_| Ok(()), || None).unwrap();
        assert_eq!((h, new), (22, true));
        assert_eq!(m.borrow().host_port(TCP, 22), Some(22));
        assert_eq!(m.borrow().guest_port(TCP, 22), Some(22));
        // A second socket on the same port: the guest's own conflict is reported as usual, no move
        assert_eq!(
            bind(&m, TCP, 22, |_| Err(Errno::EADDRINUSE.0), || None).err(),
            Some(Errno::EADDRINUSE)
        );
        let (h, b, new) = bind(&m, TCP, 22, |_| Ok(()), || None).unwrap();
        assert_eq!((h, new), (22, false));
        // After closing, the host port is in TIME_WAIT: EADDRINUSE as on Linux, not taken as held
        // by someone else
        drop((a, b));
        assert_eq!(
            bind(&m, TCP, 22, |_| Err(Errno::EADDRINUSE.0), || None).err(),
            Some(Errno::EADDRINUSE)
        );
        // A port opened only for TCP: UDP may bind neither the original nor TCP's port (the same
        // one), so it moves to another
        let (_, _c, _) = bind(&m, TCP, 22, |_| Ok(()), || None).unwrap();
        let mut tried = Vec::new();
        let (h, _d, _) = bind(
            &m,
            UDP,
            22,
            |p| {
                tried.push(p);
                refusing(&[])(p)
            },
            || None,
        )
        .unwrap();
        assert_eq!((h, tried), (10022, vec![22, 10022]));
        // Another app on the phone holds the original port: move to another
        let (h, _e, _) = bind(
            &m,
            TCP,
            80,
            |p| {
                if p == 80 {
                    Err(Errno::EADDRINUSE.0)
                } else {
                    Ok(())
                }
            },
            || None,
        )
        .unwrap();
        assert_eq!(h, 10080);
        // A refused low port (DNS's 53): UDP tries the original first, then the port TCP moved to
        let (_, _f, _) = bind(&m, TCP, 53, refusing(&[10053]), || None).unwrap();
        let mut tried = Vec::new();
        let (h, _g, _) = bind(
            &m,
            UDP,
            53,
            |p| {
                tried.push(p);
                refusing(&[])(p)
            },
            || None,
        )
        .unwrap();
        assert_eq!((h, tried), (20053, vec![53, 20053]));
        // Always map: move even when the host allows the port
        m.borrow_mut().always_map = true;
        let (h, _h, _) = bind(&m, TCP, 443, |_| Ok(()), || None).unwrap();
        assert_eq!(h, 10443);
    }

    #[test]
    fn a_bound_port_keeps_its_host_port_until_the_last_lease() {
        let m = map();
        m.borrow_mut().always_map = true;
        let (h, a, _) = bind(&m, TCP, 22, |_| Ok(()), || None).unwrap();
        assert_eq!(h, 10022);
        // A second socket (SO_REUSEPORT, the other address family) uses the same host port, and
        // conflicts are reported as usual
        let (h2, b, new) = bind(
            &m,
            TCP,
            22,
            |p| if p == 10022 { Ok(()) } else { Err(1) },
            || None,
        )
        .unwrap();
        assert_eq!((h2, new), (10022, false));
        assert_eq!(
            bind(&m, TCP, 22, |_| Err(Errno::EADDRINUSE.0), || None).err(),
            Some(Errno::EADDRINUSE)
        );
        drop(a);
        assert_eq!(m.borrow().host_port(TCP, 22), Some(10022));
        drop(b);
        assert_eq!(m.borrow().host_port(TCP, 22), None);
        // The host port still translates back to the guest port for a while (answers the other
        // side has not read yet)
        assert_eq!(m.borrow().guest_port(TCP, 10022), Some(22));
        m.borrow_mut().by_host.get_mut(&(TCP, 10022)).unwrap().1 = Some(Instant::now() - LINGER);
        assert_eq!(m.borrow().guest_port(TCP, 10022), None);
        // Rebinding a just released port still uses the original host port; when it is held
        // (TIME_WAIT) it is EADDRINUSE, no move
        assert_eq!(
            bind(&m, TCP, 22, |_| Err(Errno::EADDRINUSE.0), || None).err(),
            Some(Errno::EADDRINUSE)
        );
        let (h, again, _) = bind(
            &m,
            TCP,
            22,
            |p| if p == 10022 { Ok(()) } else { Err(1) },
            || None,
        )
        .unwrap();
        assert_eq!(h, 10022);
        drop(again);
        // Protocols are independent; UDP tries TCP's host port first
        let (_, _c, _) = bind(
            &m,
            TCP,
            53,
            |p| {
                if p == 10053 {
                    Err(Errno::EADDRINUSE.0)
                } else {
                    Ok(())
                }
            },
            || None,
        )
        .unwrap();
        let (h, _d, _) = bind(&m, UDP, 53, |_| Ok(()), || None).unwrap();
        assert_eq!(h, 20053);
    }

    #[test]
    fn active_snapshot_lists_each_live_binding_once() {
        let m = map();
        let (_, udp, _) = bind(&m, UDP, 53, refusing(&[]), || None).unwrap();
        let (_, tcp, _) = bind(&m, TCP, 22, |_| Ok(()), || None).unwrap();
        let (_, shared, _) = bind(&m, TCP, 22, |_| Ok(()), || None).unwrap();
        let rows = [(TCP, 22, 22), (UDP, 10053, 53)];
        assert_eq!(m.borrow().active(), rows);
        drop(tcp);
        assert_eq!(m.borrow().active(), rows);
        drop(shared);
        assert_eq!(m.borrow().active(), [(UDP, 10053, 53)]);
        drop(udp);
        assert!(m.borrow().active().is_empty());
    }

    #[test]
    fn listens_are_reported_once() {
        let mut m = PortMap::new();
        let listen = |comm: &str, port, host_port| ListenEvent {
            comm: comm.into(),
            port,
            host_port,
            address: std::net::Ipv4Addr::UNSPECIFIED.into(),
            dual_stack: false,
            interface: 0,
        };
        m.listened(listen("sshd", 22, 10022));
        m.listened(listen("sshd", 22, 10022));
        m.listened(listen("sshd", 22, 20022));
        let e = m.take_listens();
        assert_eq!(e.len(), 2);
        assert_eq!(e[1].host_port, 20022);
        assert!(m.take_listens().is_empty());
        // A listen the full queue dropped is reported the next time
        for p in 0..MAX_EVENTS as u16 {
            m.listened(listen("x", 1000 + p, 1000 + p));
        }
        m.listened(listen("late", 80, 10080));
        assert_eq!(m.take_listens().len(), MAX_EVENTS);
        m.listened(listen("late", 80, 10080));
        assert_eq!(m.take_listens()[0].comm, "late");
        assert!(m.maps(22) && !m.maps(0) && !m.maps(1024));
        m.offset = 0;
        assert!(!m.maps(22));
    }
}
