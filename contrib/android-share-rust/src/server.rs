use std::collections::HashMap;
use std::ffi::CString;
use std::fs::{self, File, Metadata};
use std::io::{self, BufRead, Read, Write};
use std::os::fd::{AsRawFd, FromRawFd};
use std::os::linux::net::SocketAddrExt;
use std::os::unix::fs::FileExt;
use std::os::unix::net::{SocketAddr, UnixStream};
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::UNIX_EPOCH;

use base64::{Engine, engine::general_purpose::STANDARD};
use percent_encoding::percent_decode_str;
use tiny_http::{Header, Method, Request, Response, Server, StatusCode};

use crate::client::{Entry, MAX_IO, Opened};

#[path = "root_metadata.rs"]
mod root_metadata;
use root_metadata::Nodes;

#[repr(C)]
struct OpenHow {
    flags: u64,
    mode: u64,
    resolve: u64,
}

// Android's host kernel performs the full anchored walk, including under concurrent renames.
fn anchored(root: &File, path: &str, flags: i32, mode: u32) -> io::Result<File> {
    let path = CString::new(path)?;
    const RESOLVE_BENEATH: u64 = 0x08;
    const RESOLVE_NO_MAGICLINKS: u64 = 0x02;
    let how = OpenHow {
        flags: (flags | libc::O_CLOEXEC) as u64,
        mode: u64::from(mode),
        resolve: RESOLVE_BENEATH | RESOLVE_NO_MAGICLINKS,
    };
    let fd = unsafe {
        libc::syscall(
            libc::SYS_openat2,
            root.as_raw_fd(),
            path.as_ptr(),
            &how,
            size_of::<OpenHow>(),
        )
    };
    if fd < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(unsafe { File::from_raw_fd(fd as i32) })
    }
}

fn errno(value: i32) -> io::Error {
    io::Error::from_raw_os_error(value)
}

fn share_path(path: &str) -> io::Result<&str> {
    if path == "/" {
        return Ok(".");
    }
    path.strip_prefix('/')
        .filter(|path| {
            path.split('/')
                .all(|part| !part.is_empty() && part != "." && part != ".." && !part.contains('\0'))
        })
        .ok_or_else(|| errno(libc::EINVAL))
}

fn entry(meta: Metadata, path: &str) -> io::Result<Entry> {
    if !meta.is_dir() && !meta.is_file() {
        return Err(errno(libc::ENOENT));
    }
    let name = path.rsplit('/').next().unwrap_or(path).into();
    let mtime = meta
        .modified()?
        .duration_since(UNIX_EPOCH)
        .map_or(0, |age| age.as_millis() as i64);
    Ok(Entry {
        name,
        dir: meta.is_dir(),
        size: meta.len() as i64,
        mtime,
    })
}

fn json(value: &impl serde::Serialize) -> io::Result<Vec<u8>> {
    Ok(serde_json::to_vec(value)?)
}

struct Host {
    root: File,
    writable: bool,
    next: u64,
    files: HashMap<u64, File>,
    nodes: Nodes,
}
impl Host {
    fn metadata(&self, path: &str) -> io::Result<Metadata> {
        anchored(&self.root, path, libc::O_PATH | libc::O_NOFOLLOW, 0)?.metadata()
    }

    fn parent(&self, path: &str) -> io::Result<(File, CString)> {
        if path == "." {
            return Err(errno(libc::EPERM));
        }
        let (parent, name) = path.rsplit_once('/').unwrap_or((".", path));
        let file = anchored(&self.root, parent, libc::O_PATH | libc::O_DIRECTORY, 0)?;
        Ok((file, CString::new(name)?))
    }

    fn lookup_node(&mut self, path: &str) -> io::Result<Opened> {
        let attr = entry(self.metadata(share_path(path)?)?, path)?;
        let id = self.nodes.retain(path);
        Ok(Opened { id, entry: attr })
    }

    fn regular(&self, path: &str, flags: i32) -> io::Result<File> {
        // Linux O_PATH does not open a special device. Reopen only a validated regular inode.
        for _ in 0..4 {
            match anchored(&self.root, path, libc::O_PATH | libc::O_NOFOLLOW, 0) {
                Ok(hold) => {
                    if flags & (libc::O_CREAT | libc::O_EXCL) == libc::O_CREAT | libc::O_EXCL {
                        return Err(errno(libc::EEXIST));
                    }
                    if !hold.metadata()?.is_file() {
                        return Err(errno(libc::EACCES));
                    }
                    let path = CString::new(format!("/proc/self/fd/{}", hold.as_raw_fd()))?;
                    let fd = unsafe {
                        libc::open(
                            path.as_ptr(),
                            flags & !(libc::O_CREAT | libc::O_EXCL) | libc::O_CLOEXEC,
                        )
                    };
                    return if fd < 0 {
                        Err(io::Error::last_os_error())
                    } else {
                        Ok(unsafe { File::from_raw_fd(fd) })
                    };
                }
                Err(error)
                    if error.raw_os_error() == Some(libc::ENOENT) && flags & libc::O_CREAT != 0 =>
                {
                    match anchored(
                        &self.root,
                        path,
                        flags | libc::O_EXCL | libc::O_NOFOLLOW,
                        0o666,
                    ) {
                        Err(error)
                            if error.raw_os_error() == Some(libc::EEXIST)
                                && flags & libc::O_EXCL == 0 =>
                        {
                            continue;
                        }
                        result => return result,
                    }
                }
                Err(error) => return Err(error),
            }
        }
        Err(errno(libc::EAGAIN))
    }

    fn keep_file(&mut self, file: File) -> u64 {
        self.next += 1;
        self.files.insert(self.next, file);
        self.next
    }

    fn list(&self, path: &str) -> io::Result<Vec<Entry>> {
        let fd = anchored(&self.root, path, libc::O_PATH | libc::O_DIRECTORY, 0)?;
        let mut entries = Vec::new();
        for item in fs::read_dir(format!("/proc/self/fd/{}", fd.as_raw_fd()))? {
            let name = item?.file_name();
            let Some(name) = name.to_str() else {
                continue;
            };
            if let Ok(meta) =
                anchored(&fd, name, libc::O_PATH | libc::O_NOFOLLOW, 0).and_then(|f| f.metadata())
                && let Ok(entry) = entry(meta, name)
            {
                entries.push(entry);
            }
        }
        Ok(entries)
    }

    fn handle(&mut self, request: &mut Request) -> io::Result<Vec<u8>> {
        if request.method() != &Method::Post {
            return Err(errno(libc::ENOSYS));
        }
        let (path, query) = request.url().split_once('?').unwrap_or((request.url(), ""));
        let decoded = percent_decode_str(path)
            .decode_utf8()
            .map_err(|_| errno(libc::EINVAL))?;
        let path = share_path(&decoded)?;
        let query: HashMap<_, _> = form_urlencoded::parse(query.as_bytes()).collect();
        let get = |key| query.get(key).map_or("", |v| v.as_ref());
        let op = get("op");
        if !self.writable {
            let node = || get("node").parse().map_err(|_| errno(libc::EINVAL));
            match op {
                "lookup" => return json(&self.lookup_node(&decoded)?),
                "nodepath" => return json(&self.nodes.location(node()?)?),
                "forget" => {
                    let refs = get("refs").parse().map_err(|_| errno(libc::EINVAL))?;
                    self.nodes.forget(node()?, refs);
                    return Ok(vec![]);
                }
                _ => {}
            }
        }
        let id = if get("id").is_empty() {
            None
        } else {
            Some(get("id").parse::<u64>().map_err(|_| errno(libc::EBADF))?)
        };
        let file = id
            .map(|id| self.files.get(&id).ok_or_else(|| errno(libc::EBADF)))
            .transpose()?;
        if !self.writable && !matches!(op, "stat" | "list" | "open" | "read" | "close") {
            return Err(errno(libc::EROFS));
        }
        match op {
            "stat" => json(&entry(
                if let Some(file) = file {
                    file.metadata()?
                } else {
                    self.metadata(path)?
                },
                path,
            )?),
            "list" => json(&self.list(path)?),
            "open" => {
                const MASK: i32 =
                    libc::O_ACCMODE | libc::O_CREAT | libc::O_EXCL | libc::O_TRUNC | libc::O_APPEND;
                let flags = get("flags")
                    .parse::<i32>()
                    .map_err(|_| errno(libc::EINVAL))?;
                if flags & !MASK != 0 || flags & libc::O_ACCMODE == libc::O_ACCMODE {
                    return Err(errno(libc::EINVAL));
                }
                if flags != 0 && !self.writable {
                    return Err(errno(libc::EROFS));
                }
                let file = self.regular(path, flags)?;
                let entry = entry(file.metadata()?, path)?;
                let id = self.keep_file(file);
                json(&Opened { id, entry })
            }
            "read" | "write" => {
                let mut file = file.ok_or_else(|| errno(libc::EBADF))?;
                let offset = get("offset")
                    .parse::<u64>()
                    .ok()
                    .filter(|n| *n <= i64::MAX as u64)
                    .ok_or_else(|| errno(libc::EINVAL))?;
                if op == "read" {
                    let len = get("length")
                        .parse::<usize>()
                        .ok()
                        .filter(|n| *n <= MAX_IO)
                        .ok_or_else(|| errno(libc::EINVAL))?;
                    let mut data = vec![0; len];
                    let count = file.read_at(&mut data, offset)?;
                    data.truncate(count);
                    return Ok(data);
                }
                let append = get("append") == "1";
                let mut data = Vec::new();
                request
                    .as_reader()
                    .take((MAX_IO + 1) as u64)
                    .read_to_end(&mut data)?;
                if data.len() > MAX_IO {
                    return Err(errno(libc::EINVAL));
                }
                if append {
                    file.write_all(&data)?;
                } else {
                    file.write_all_at(&data, offset)?;
                }
                Ok(vec![])
            }
            "close" => {
                self.files.remove(&id.ok_or_else(|| errno(libc::EBADF))?);
                Ok(vec![])
            }
            "sync" => {
                file.ok_or_else(|| errno(libc::EBADF))?.sync_all()?;
                Ok(vec![])
            }
            "truncate" => {
                let size = get("size")
                    .parse::<u64>()
                    .ok()
                    .filter(|n| *n <= i64::MAX as u64)
                    .ok_or_else(|| errno(libc::EINVAL))?;
                if let Some(file) = file {
                    file.set_len(size)?;
                } else {
                    self.regular(path, libc::O_WRONLY)?.set_len(size)?;
                }
                Ok(vec![])
            }
            "mkdir" | "unlink" | "rmdir" => {
                let (dir, name) = self.parent(path)?;
                let ret = unsafe {
                    if op == "mkdir" {
                        libc::mkdirat(dir.as_raw_fd(), name.as_ptr(), 0o777)
                    } else {
                        libc::unlinkat(
                            dir.as_raw_fd(),
                            name.as_ptr(),
                            if op == "rmdir" { libc::AT_REMOVEDIR } else { 0 },
                        )
                    }
                };
                if ret < 0 {
                    Err(io::Error::last_os_error())
                } else {
                    Ok(vec![])
                }
            }
            "rename" => {
                let (from, name) = self.parent(path)?;
                let (to, new_name) = self.parent(share_path(get("destination"))?)?;
                let ret = unsafe {
                    libc::renameat(
                        from.as_raw_fd(),
                        name.as_ptr(),
                        to.as_raw_fd(),
                        new_name.as_ptr(),
                    )
                };
                if ret < 0 {
                    Err(io::Error::last_os_error())
                } else {
                    Ok(vec![])
                }
            }
            _ => Err(errno(libc::ENOSYS)),
        }
    }
}

pub(crate) fn browse(dir: &str) -> io::Result<()> {
    let mut names = Vec::new();
    for item in fs::read_dir(dir)? {
        let item = item?;
        if (item.file_type()?.is_dir() || fs::metadata(item.path()).is_ok_and(|meta| meta.is_dir()))
            && let Ok(name) = item.file_name().into_string()
        {
            names.push(name);
        }
    }
    names.sort_unstable();
    println!("DIRS {}", serde_json::to_string(&names)?);
    Ok(())
}

fn equal_token(actual: &[u8], expected: &[u8]) -> bool {
    if actual.len() != expected.len() {
        return false;
    }
    let diff = actual
        .iter()
        .zip(expected)
        .fold(0u8, |acc, (a, b)| acc | (a ^ b));
    std::hint::black_box(diff) == 0
}

// The Appine app owns the listener. Both transports share the same inode and
// file-handle tables for this read-only export.
fn serve_backing(mut socket: UnixStream, host: Arc<Mutex<Host>>) {
    let mut request = [0u8; 8];
    while socket.read_exact(&mut request).is_ok() {
        let id = u64::from_le_bytes(request);
        if id == u64::MAX {
            if root_metadata::reply(&mut socket, &host).is_err() {
                break;
            }
            continue;
        }
        const RELEASE: u64 = 1 << 63;
        if id & RELEASE != 0 {
            host.lock()
                .unwrap_or_else(|e| e.into_inner())
                .files
                .remove(&(id & !RELEASE));
            continue;
        }
        let file = host
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .files
            .get(&id)
            .and_then(|f| f.try_clone().ok());
        if send_backing(&socket, file.as_ref()).is_err() {
            break;
        }
    }
}

fn connect_backing(address: &str) -> Option<UnixStream> {
    let (uid, name) = address.split_once(':')?;
    let uid = uid.parse::<libc::uid_t>().ok()?;
    let address = SocketAddr::from_abstract_name(name.as_bytes()).ok()?;
    let socket = UnixStream::connect_addr(&address).ok()?;
    // SAFETY: SO_PEERCRED writes one ucred into the initialized target.
    let ok = unsafe {
        let mut peer: libc::ucred = std::mem::zeroed();
        let mut size = std::mem::size_of_val(&peer) as libc::socklen_t;
        libc::getsockopt(
            socket.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            (&mut peer as *mut libc::ucred).cast(),
            &mut size,
        ) == 0
            && peer.uid == uid
    };
    if ok {
        socket
            .set_write_timeout(Some(std::time::Duration::from_secs(2)))
            .ok()?;
        Some(socket)
    } else {
        None
    }
}

fn send_backing(socket: &UnixStream, file: Option<&File>) -> io::Result<()> {
    let mut byte = u8::from(file.is_some());
    let mut vector = libc::iovec {
        iov_base: (&mut byte as *mut u8).cast(),
        iov_len: 1,
    };
    // Aligned, initialized storage for one SCM_RIGHTS message.
    let mut control = [0usize; 8];
    // SAFETY: iovec and control storage outlive sendmsg, and the header fits in control.
    let sent = unsafe {
        let mut message: libc::msghdr = std::mem::zeroed();
        message.msg_iov = &mut vector;
        message.msg_iovlen = 1;
        if let Some(file) = file {
            let len = std::mem::size_of::<libc::c_int>() as u32;
            message.msg_control = control.as_mut_ptr().cast();
            message.msg_controllen = libc::CMSG_SPACE(len) as _;
            let header = libc::CMSG_FIRSTHDR(&message);
            (*header).cmsg_level = libc::SOL_SOCKET;
            (*header).cmsg_type = libc::SCM_RIGHTS;
            (*header).cmsg_len = libc::CMSG_LEN(len) as _;
            libc::CMSG_DATA(header)
                .cast::<libc::c_int>()
                .write_unaligned(file.as_raw_fd());
        }
        libc::sendmsg(socket.as_raw_fd(), &message, libc::MSG_NOSIGNAL)
    };
    if sent == 1 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

pub(crate) fn serve(
    dir: &str,
    port: u16,
    writable: bool,
    backing_socket: Option<&str>,
) -> io::Result<()> {
    let root = File::open(Path::new(dir))?;
    if !root.metadata()?.is_dir() {
        return Err(errno(libc::ENOTDIR));
    }
    // Fail at startup on hosts which cannot provide the required confinement.
    anchored(&root, ".", libc::O_PATH | libc::O_DIRECTORY, 0)?;
    let mut lease = io::BufReader::new(io::stdin());
    let mut token = String::new();
    (&mut lease).take(128).read_line(&mut token)?;
    let token = token.trim();
    if token.len() != 64 || !token.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid share token",
        ));
    }
    let authorization = format!("Basic {}", STANDARD.encode(format!("appine:{token}")));
    let server = Server::http(("127.0.0.1", port)).map_err(io::Error::other)?;
    let port = server
        .server_addr()
        .to_ip()
        .ok_or_else(|| io::Error::other("not a TCP listener"))?
        .port();
    // Do not retain root access after the APK closes its pipe, even if a client stalls.
    std::thread::spawn(move || {
        let _ = io::copy(&mut lease, &mut io::sink());
        std::process::exit(0);
    });
    let host = Arc::new(Mutex::new(Host {
        root,
        writable,
        next: 0,
        files: HashMap::new(),
        nodes: Nodes::default(),
    }));
    let backing = backing_socket
        .filter(|_| !writable)
        .and_then(connect_backing);
    if let Some(socket) = backing {
        let host = host.clone();
        std::thread::spawn(move || serve_backing(socket, host));
        println!("READY {port} FD META2");
    } else {
        println!("READY {port}");
    }
    io::stdout().flush()?;
    loop {
        let mut req = server.recv()?;
        if !req.headers().iter().any(|h| {
            h.field.equiv("Authorization")
                && equal_token(h.value.as_bytes(), authorization.as_bytes())
        }) {
            let _ = req.respond(Response::empty(StatusCode(401)));
            continue;
        }
        let result = host
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .handle(&mut req);
        match result {
            Ok(data) => {
                let _ = req.respond(Response::from_data(data));
            }
            Err(error) => {
                let code = error.raw_os_error().unwrap_or(libc::EIO);
                let header = Header::from_bytes("X-Appine-Errno", code.to_string()).unwrap();
                let _ = req.respond(Response::empty(StatusCode(400)).with_header(header));
            }
        }
    }
}
