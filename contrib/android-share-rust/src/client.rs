use std::collections::HashMap;
use std::ffi::{CString, OsStr};
use std::fs::{self, OpenOptions};
use std::io::{self, BufRead, BufReader};
use std::os::fd::AsRawFd;
use std::path::Path;
use std::sync::Mutex;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use base64::{Engine, engine::general_purpose::STANDARD};
use fuser::*;
use percent_encoding::{NON_ALPHANUMERIC, utf8_percent_encode};
use serde::{Deserialize, Serialize};

const TTL: Duration = Duration::ZERO;
pub(crate) const MAX_IO: usize = 128 * 1024;
const ROOT: INodeNo = INodeNo(1);
const OPEN_MASK: i32 =
    libc::O_ACCMODE | libc::O_CREAT | libc::O_EXCL | libc::O_TRUNC | libc::O_APPEND;

pub(crate) type Result<T> = std::result::Result<T, Errno>;

#[derive(Deserialize, Serialize)]
pub(crate) struct Entry {
    pub name: String,
    pub dir: bool,
    pub size: i64,
    pub mtime: i64,
}

impl Entry {
    fn kind(&self) -> FileType {
        if self.dir {
            FileType::Directory
        } else {
            FileType::RegularFile
        }
    }
    fn attr(&self, ino: INodeNo, writable: bool) -> FileAttr {
        let size = if self.dir { 0 } else { self.size.max(0) as u64 };
        let ts = UNIX_EPOCH + Duration::from_millis(self.mtime.max(0) as u64);
        FileAttr {
            ino,
            size,
            blocks: size.div_ceil(512),
            atime: ts,
            mtime: ts,
            ctime: ts,
            crtime: ts,
            kind: self.kind(),
            perm: match (self.dir, writable) {
                (true, true) => 0o777,
                (true, false) => 0o555,
                (false, true) => 0o666,
                (false, false) => 0o444,
            },
            nlink: if self.dir { 2 } else { 1 },
            uid: 0,
            gid: 0,
            rdev: 0,
            blksize: 4096,
            flags: 0,
        }
    }
}

#[derive(Deserialize, Serialize)]
pub(crate) struct Opened {
    #[serde(rename = "ID")]
    pub id: u64,
    #[serde(rename = "Entry")]
    pub entry: Entry,
}

#[derive(Deserialize, Serialize)]
pub(crate) struct NodePath {
    pub path: String,
    pub parent: u64,
}

struct Bridge {
    agent: ureq::Agent,
    prefix: String,
    auth: String,
    tree: bool,
    writable: bool,
    backing: bool,
    metadata: bool,
}
impl Bridge {
    fn new(port: u16, token: &str, mode: &str) -> Self {
        let config = ureq::Agent::config_builder()
            .proxy(None)
            .max_redirects(0)
            .http_status_as_error(false)
            .timeout_global(Some(Duration::from_secs(10)))
            .build();
        Self {
            agent: config.into(),
            prefix: format!("http://127.0.0.1:{port}"),
            auth: format!("Basic {}", STANDARD.encode(format!("appine:{token}"))),
            tree: mode == "tree",
            writable: mode == "rw",
            backing: mode != "rw" && std::env::var_os("APPINE_SHARE_BACKING").is_some(),
            metadata: mode == "ro" && std::env::var_os("APPINE_SHARE_METADATA").is_some(),
        }
    }

    fn request(
        &self,
        op: &str,
        path: &str,
        q: &[(&str, String)],
        data: &[u8],
    ) -> Result<ureq::Body> {
        let path = path
            .split('/')
            .map(|part| utf8_percent_encode(part, NON_ALPHANUMERIC).to_string())
            .collect::<Vec<_>>()
            .join("/");
        let mut query = form_urlencoded::Serializer::new(String::new());
        query.append_pair("op", op);
        query.extend_pairs(q.iter().map(|(k, v)| (*k, v.as_str())));
        let url = format!("{}{path}?{}", self.prefix, query.finish());
        let response = if self.tree {
            let mut req = self.agent.get(&url).header("Authorization", &self.auth);
            if op == "read" {
                let get = |key| {
                    q.iter()
                        .find(|(k, _)| *k == key)
                        .map(|(_, v)| v.as_str())
                        .unwrap_or("0")
                };
                req = req.header("Range", format!("bytes={}-{}", get("offset"), get("end")));
            }
            req.call()
        } else {
            self.agent
                .post(&url)
                .header("Authorization", &self.auth)
                .send(data)
        }
        .map_err(|_| Errno::EIO)?;
        if response.status().is_success() {
            return Ok(response.into_body());
        }
        if let Some(code) = response
            .headers()
            .get("X-Appine-Errno")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse::<i32>().ok())
            .filter(|v| (1..4096).contains(v))
        {
            return Err(Errno::from_i32(code));
        }
        Err(match response.status().as_u16() {
            401 | 403 => Errno::EACCES,
            404 => Errno::ENOENT,
            416 => Errno::ENXIO,
            _ => Errno::EIO,
        })
    }

    fn json<T: serde::de::DeserializeOwned>(
        &self,
        op: &str,
        path: &str,
        q: &[(&str, String)],
    ) -> Result<T> {
        self.request(op, path, q, &[])?
            .with_config()
            .limit(32 * 1024 * 1024)
            .read_json()
            .map_err(|_| Errno::EIO)
    }

    fn writeable(&self) -> Result<()> {
        if self.writable {
            Ok(())
        } else {
            Err(Errno::EROFS)
        }
    }
}

fn valid_name(name: &OsStr) -> Result<&str> {
    name.to_str()
        .filter(|n| !n.is_empty() && !n.contains('/') && *n != "." && *n != "..")
        .ok_or(Errno::EINVAL)
}

struct Node {
    path: Option<String>,
    refs: u64,
}

struct Handle {
    path: String,
    remote: u64,
    append: bool,
}
impl Handle {
    fn query(&self) -> Vec<(&'static str, String)> {
        if self.remote == 0 {
            vec![]
        } else {
            vec![("id", self.remote.to_string())]
        }
    }
}

struct State {
    last: u64,
    nodes: HashMap<INodeNo, Node>,
    files: HashMap<FileHandle, Handle>,
    dirs: HashMap<FileHandle, Vec<(INodeNo, FileType, String)>>,
}
impl State {
    fn id(&mut self) -> u64 {
        self.last += 1;
        self.last
    }

    fn path(&self, ino: INodeNo) -> Result<&str> {
        self.nodes
            .get(&ino)
            .and_then(|n| n.path.as_deref())
            .ok_or(Errno::ENOENT)
    }

    fn child(&self, parent: INodeNo, name: &OsStr) -> Result<String> {
        let name = valid_name(name)?;
        Ok(format!(
            "{}/{}",
            self.path(parent)?.trim_end_matches('/'),
            name
        ))
    }

    fn lookup(&mut self, path: String) -> INodeNo {
        if let Some((&ino, node)) = self
            .nodes
            .iter_mut()
            .find(|(_, n)| n.path.as_ref() == Some(&path))
        {
            node.refs += 1;
            ino
        } else {
            let ino = INodeNo(self.id());
            self.nodes.insert(
                ino,
                Node {
                    path: Some(path),
                    refs: 1,
                },
            );
            ino
        }
    }

    fn remove(&mut self, path: &str) {
        for node in self.nodes.values_mut() {
            if node.path.as_ref().is_some_and(|value| {
                value == path
                    || value
                        .strip_prefix(path)
                        .is_some_and(|tail| tail.starts_with('/'))
            }) {
                node.path = None;
            }
        }
    }

    fn rename(&mut self, from: &str, to: &str) {
        if from == to {
            return;
        }
        self.remove(to);
        for node in self.nodes.values_mut() {
            if let Some(tail) = node
                .path
                .as_ref()
                .and_then(|path| path.strip_prefix(from))
                .filter(|tail| tail.is_empty() || tail.starts_with('/'))
            {
                node.path = Some(format!("{to}{tail}"));
            }
        }
    }
}

struct Share {
    bridge: Bridge,
    state: Mutex<State>,
}
impl Share {
    fn root_file_id(&self, fh: FileHandle) -> Option<u64> {
        let tag = 1 << 63;
        let id = fh.0 & !tag;
        (self.bridge.metadata && fh.0 & tag != 0 && id != 0).then_some(id)
    }

    // The root helper keeps files opened on either transport in the same table.
    fn file_route<'a>(
        &self,
        s: &'a State,
        fh: FileHandle,
    ) -> Option<(&'a str, Vec<(&'static str, String)>)> {
        if let Some(file) = s.files.get(&fh) {
            Some((&file.path, file.query()))
        } else {
            self.root_file_id(fh)
                .map(|id| ("/", vec![("id", id.to_string())]))
        }
    }

    fn root_location(&self, ino: INodeNo) -> Result<NodePath> {
        self.bridge
            .json("nodepath", "/", &[("node", ino.0.to_string())])
    }

    fn path(&self, s: &State, ino: INodeNo) -> Result<String> {
        match s.path(ino) {
            Ok(path) => Ok(path.to_owned()),
            Err(_) if self.bridge.metadata => self.root_location(ino).map(|location| location.path),
            Err(error) => Err(error),
        }
    }

    fn file(&self, s: &mut State, path: String, flags: i32) -> Result<(FileHandle, Option<Entry>)> {
        let flags = flags & OPEN_MASK;
        if flags != 0 {
            self.bridge.writeable()?;
        }
        let (remote, entry) = if self.bridge.tree {
            (0, None)
        } else {
            let result: Opened =
                self.bridge
                    .json("open", &path, &[("flags", flags.to_string())])?;
            (result.id, Some(result.entry))
        };
        let backing_id = if !self.bridge.backing {
            0
        } else if self.bridge.tree {
            self.bridge.json("backing", &path, &[]).unwrap_or(0)
        } else {
            remote
        };
        let fh = if backing_id != 0 && backing_id < (1 << 63) {
            // Appine's host VFS can resolve this opened file via the sidecar socket.
            FileHandle((1 << 63) | backing_id)
        } else {
            FileHandle(s.id())
        };
        s.files.insert(
            fh,
            Handle {
                path,
                remote,
                append: flags & libc::O_APPEND != 0,
            },
        );
        Ok((fh, entry))
    }

    fn attr(
        &self,
        s: &State,
        ino: INodeNo,
        fh: Option<FileHandle>,
        size: Option<u64>,
    ) -> Result<FileAttr> {
        let open = fh
            .filter(|_| !self.bridge.tree)
            .and_then(|fh| self.file_route(s, fh));
        let (path, mut q) = match open {
            Some((path, query)) => (path.to_owned(), query),
            None => (self.path(s, ino)?, vec![]),
        };
        if let Some(size) = size {
            q.push(("size", size.to_string()));
            self.bridge.request("truncate", &path, &q, &[])?;
        }
        let entry: Entry = self.bridge.json("stat", &path, &q)?;
        Ok(entry.attr(ino, self.bridge.writable))
    }

    fn remove(&self, parent: INodeNo, name: &OsStr, op: &str) -> Result<()> {
        self.bridge.writeable()?;
        let mut s = self.state.lock().unwrap();
        let path = s.child(parent, name)?;
        self.bridge.request(op, &path, &[], &[])?;
        s.remove(&path);
        Ok(())
    }
}

fn empty(reply: ReplyEmpty, result: Result<()>) {
    match result {
        Ok(()) => reply.ok(),
        Err(e) => reply.error(e),
    }
}

impl Filesystem for Share {
    fn init(&mut self, _: &Request, cfg: &mut KernelConfig) -> io::Result<()> {
        let _ = cfg.set_max_write(MAX_IO as u32);
        Ok(())
    }

    fn lookup(&self, _: &Request, parent: INodeNo, name: &OsStr, reply: ReplyEntry) {
        let result = (|| {
            let mut s = self.state.lock().unwrap();
            if self.bridge.metadata {
                let name = valid_name(name)?;
                let path = format!("{}/{}", self.path(&s, parent)?.trim_end_matches('/'), name);
                let opened: Opened = self.bridge.json("lookup", &path, &[])?;
                return Ok(opened.entry.attr(INodeNo(opened.id), false));
            }
            let path = s.child(parent, name)?;
            let entry: Entry = self.bridge.json("stat", &path, &[])?;
            Ok(entry.attr(s.lookup(path), self.bridge.writable))
        })();
        match result {
            Ok(attr) => reply.entry(&TTL, &attr, Generation(0)),
            Err(e) => reply.error(e),
        }
    }

    fn forget(&self, _: &Request, ino: INodeNo, nlookup: u64) {
        if self.bridge.metadata && ino != ROOT {
            let _ = self.bridge.request(
                "forget",
                "/",
                &[("node", ino.0.to_string()), ("refs", nlookup.to_string())],
                &[],
            );
            return;
        }
        let mut s = self.state.lock().unwrap();
        if ino != ROOT
            && let Some(node) = s.nodes.get_mut(&ino)
        {
            node.refs = node.refs.saturating_sub(nlookup);
            if node.refs == 0 {
                s.nodes.remove(&ino);
            }
        }
    }

    fn getattr(&self, _: &Request, ino: INodeNo, fh: Option<FileHandle>, reply: ReplyAttr) {
        match self.attr(&self.state.lock().unwrap(), ino, fh, None) {
            Ok(attr) => reply.attr(&TTL, &attr),
            Err(e) => reply.error(e),
        }
    }

    fn setattr(
        &self,
        _: &Request,
        ino: INodeNo,
        _: Option<u32>,
        _: Option<u32>,
        _: Option<u32>,
        size: Option<u64>,
        _: Option<TimeOrNow>,
        _: Option<TimeOrNow>,
        _: Option<SystemTime>,
        fh: Option<FileHandle>,
        _: Option<SystemTime>,
        _: Option<SystemTime>,
        _: Option<SystemTime>,
        _: Option<BsdFileFlags>,
        reply: ReplyAttr,
    ) {
        let result = self.bridge.writeable().and_then(|()| {
            let size = size.ok_or(Errno::EOPNOTSUPP)?;
            self.attr(&self.state.lock().unwrap(), ino, fh, Some(size))
        });
        match result {
            Ok(attr) => reply.attr(&TTL, &attr),
            Err(e) => reply.error(e),
        }
    }

    fn opendir(&self, _: &Request, ino: INodeNo, _: OpenFlags, reply: ReplyOpen) {
        let result = (|| {
            let mut s = self.state.lock().unwrap();
            let (path, parent) = if self.bridge.metadata {
                let location = self.root_location(ino)?;
                (location.path, INodeNo(location.parent))
            } else {
                let path = s.path(ino)?;
                let parent_path = path
                    .rsplit_once('/')
                    .map(|(p, _)| if p.is_empty() { "/" } else { p })
                    .unwrap_or("/");
                let parent = s
                    .nodes
                    .iter()
                    .find(|(_, n)| n.path.as_deref() == Some(parent_path))
                    .map_or(ROOT, |(&ino, _)| ino);
                (path.to_owned(), parent)
            };
            let entries: Vec<Entry> = self.bridge.json("list", &path, &[])?;
            let mut values = vec![
                (ino, FileType::Directory, ".".into()),
                (parent, FileType::Directory, "..".into()),
            ];
            values.extend(entries.into_iter().map(|e| (INodeNo(0), e.kind(), e.name)));
            let fh = FileHandle(s.id());
            s.dirs.insert(fh, values);
            Ok(fh)
        })();
        match result {
            Ok(fh) => reply.opened(fh, FopenFlags::empty()),
            Err(e) => reply.error(e),
        }
    }

    fn readdir(
        &self,
        _: &Request,
        _: INodeNo,
        fh: FileHandle,
        offset: u64,
        mut reply: ReplyDirectory,
    ) {
        let s = self.state.lock().unwrap();
        let Some(entries) = s.dirs.get(&fh) else {
            reply.error(Errno::EBADF);
            return;
        };
        for (i, (ino, kind, name)) in entries.iter().enumerate().skip(offset as usize) {
            if reply.add(*ino, (i + 1) as u64, *kind, name) {
                break;
            }
        }
        reply.ok();
    }

    fn releasedir(&self, _: &Request, _: INodeNo, fh: FileHandle, _: OpenFlags, reply: ReplyEmpty) {
        self.state.lock().unwrap().dirs.remove(&fh);
        reply.ok();
    }

    fn open(&self, _: &Request, ino: INodeNo, flags: OpenFlags, reply: ReplyOpen) {
        let result = (|| {
            let mut s = self.state.lock().unwrap();
            let path = self.path(&s, ino)?;
            self.file(&mut s, path, flags.0)
        })();
        match result {
            Ok((fh, _)) => {
                let flags = if self.bridge.writable {
                    FopenFlags::FOPEN_DIRECT_IO
                } else {
                    FopenFlags::empty()
                };
                reply.opened(fh, flags);
            }
            Err(e) => reply.error(e),
        }
    }

    fn create(
        &self,
        _: &Request,
        parent: INodeNo,
        name: &OsStr,
        _: u32,
        _: u32,
        flags: i32,
        reply: ReplyCreate,
    ) {
        let result = (|| {
            self.bridge.writeable()?;
            let mut s = self.state.lock().unwrap();
            let path = s.child(parent, name)?;
            let (fh, entry) = self.file(&mut s, path.clone(), flags | libc::O_CREAT)?;
            Ok((
                fh,
                entry
                    .ok_or(Errno::EIO)?
                    .attr(s.lookup(path), self.bridge.writable),
            ))
        })();
        match result {
            Ok((fh, attr)) => {
                reply.created(&TTL, &attr, Generation(0), fh, FopenFlags::FOPEN_DIRECT_IO)
            }
            Err(e) => reply.error(e),
        }
    }

    fn read(
        &self,
        _: &Request,
        _: INodeNo,
        fh: FileHandle,
        offset: u64,
        size: u32,
        _: OpenFlags,
        _: Option<LockOwner>,
        reply: ReplyData,
    ) {
        let result = (|| {
            let s = self.state.lock().unwrap();
            let (path, mut q) = self.file_route(&s, fh).ok_or(Errno::EBADF)?;
            if size == 0 {
                return Ok(vec![]);
            }
            let limit = u64::from(size).min(MAX_IO as u64);
            q.extend([
                ("offset", offset.to_string()),
                ("length", limit.to_string()),
                ("end", offset.saturating_add(limit - 1).to_string()),
            ]);
            match self.bridge.request("read", path, &q, &[]) {
                Err(e) if self.bridge.tree && e == Errno::ENXIO => Ok(vec![]),
                Ok(mut body) => body
                    .with_config()
                    .limit(limit)
                    .read_to_vec()
                    .map_err(|_| Errno::EIO),
                Err(e) => Err(e),
            }
        })();
        match result {
            Ok(data) => reply.data(&data),
            Err(e) => reply.error(e),
        }
    }

    fn write(
        &self,
        _: &Request,
        _: INodeNo,
        fh: FileHandle,
        offset: u64,
        data: &[u8],
        _: WriteFlags,
        _: OpenFlags,
        _: Option<LockOwner>,
        reply: ReplyWrite,
    ) {
        let result = (|| {
            self.bridge.writeable()?;
            let s = self.state.lock().unwrap();
            let f = s.files.get(&fh).ok_or(Errno::EBADF)?;
            let mut q = f.query();
            q.push(("offset", offset.to_string()));
            if f.append {
                q.push(("append", "1".into()));
            }
            self.bridge.request("write", &f.path, &q, data)?;
            Ok(())
        })();
        match result {
            Ok(()) => reply.written(data.len() as u32),
            Err(e) => reply.error(e),
        }
    }

    fn flush(&self, _: &Request, _: INodeNo, _: FileHandle, _: LockOwner, reply: ReplyEmpty) {
        reply.ok();
    }

    fn fsync(&self, _: &Request, _: INodeNo, fh: FileHandle, _: bool, reply: ReplyEmpty) {
        let result = (|| {
            if !self.bridge.writable {
                return Ok(());
            }
            let s = self.state.lock().unwrap();
            let f = s.files.get(&fh).ok_or(Errno::EBADF)?;
            self.bridge
                .request("sync", &f.path, &f.query(), &[])
                .map(|_| ())
        })();
        empty(reply, result);
    }

    fn release(
        &self,
        _: &Request,
        _: INodeNo,
        fh: FileHandle,
        _: OpenFlags,
        _: Option<LockOwner>,
        _: bool,
        reply: ReplyEmpty,
    ) {
        let f = self.state.lock().unwrap().files.remove(&fh);
        let result = match f {
            Some(f) if f.remote != 0 => self
                .bridge
                .request("close", &f.path, &f.query(), &[])
                .map(|_| ()),
            None => match self.root_file_id(fh) {
                Some(id) => self
                    .bridge
                    .request("close", "/", &[("id", id.to_string())], &[])
                    .map(|_| ()),
                None => Ok(()),
            },
            Some(_) => Ok(()),
        };
        empty(reply, result);
    }

    fn mkdir(&self, _: &Request, parent: INodeNo, name: &OsStr, _: u32, _: u32, reply: ReplyEntry) {
        let result = (|| {
            self.bridge.writeable()?;
            let mut s = self.state.lock().unwrap();
            let path = s.child(parent, name)?;
            self.bridge.request("mkdir", &path, &[], &[])?;
            let entry: Entry = self.bridge.json("stat", &path, &[])?;
            Ok(entry.attr(s.lookup(path), self.bridge.writable))
        })();
        match result {
            Ok(attr) => reply.entry(&TTL, &attr, Generation(0)),
            Err(e) => reply.error(e),
        }
    }

    fn unlink(&self, _: &Request, parent: INodeNo, name: &OsStr, reply: ReplyEmpty) {
        empty(reply, self.remove(parent, name, "unlink"));
    }
    fn rmdir(&self, _: &Request, parent: INodeNo, name: &OsStr, reply: ReplyEmpty) {
        empty(reply, self.remove(parent, name, "rmdir"));
    }
    fn rename(
        &self,
        _: &Request,
        parent: INodeNo,
        name: &OsStr,
        newparent: INodeNo,
        newname: &OsStr,
        flags: RenameFlags,
        reply: ReplyEmpty,
    ) {
        let result = (|| {
            self.bridge.writeable()?;
            if !flags.is_empty() {
                return Err(Errno::EINVAL);
            }
            let mut s = self.state.lock().unwrap();
            let from = s.child(parent, name)?;
            let to = s.child(newparent, newname)?;
            self.bridge
                .request("rename", &from, &[("destination", to.clone())], &[])?;
            s.rename(&from, &to);
            Ok(())
        })();
        empty(reply, result);
    }
}

fn prepare_mountpoint(point: &str) -> io::Result<()> {
    if !point.starts_with('/')
        || point == "/"
        || point[1..]
            .split('/')
            .any(|p| p.is_empty() || p == "." || p == "..")
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "mount point must be a canonical absolute directory",
        ));
    }
    let escaped = point
        .replace('\\', "\\134")
        .replace(' ', "\\040")
        .replace('\t', "\\011")
        .replace('\n', "\\012");
    for line in BufReader::new(fs::File::open("/proc/self/mountinfo")?).lines() {
        if line?.split(' ').nth(4) == Some(&escaped) {
            return Err(io::Error::other(
                "another filesystem is already mounted at the destination",
            ));
        }
    }
    let mut path = Path::new("/").to_path_buf();
    for part in point[1..].split('/') {
        path.push(part);
        match fs::create_dir(&path) {
            Ok(()) => (),
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => (),
            Err(e) => return Err(e),
        }
        if !fs::symlink_metadata(&path)?.is_dir() {
            return Err(io::Error::other(
                "mount point has a component that is not a directory",
            ));
        }
    }
    if fs::read_dir(point)?.next().transpose()?.is_some() {
        return Err(io::Error::other("mount point is not empty"));
    }
    Ok(())
}

pub(crate) fn mount(
    point: &str,
    port: u16,
    token: &str,
    mode: &str,
    device: &str,
) -> io::Result<()> {
    prepare_mountpoint(point)?;
    let bridge = Bridge::new(port, token, mode);
    let fuse = OpenOptions::new()
        .read(true)
        .write(true)
        .open("/dev/fuse")?;
    let point = CString::new(point)?;
    let device = CString::new(device)?;
    let options = CString::new(format!(
        "fd={},rootmode=40000,user_id=0,group_id=0,allow_other,default_permissions",
        fuse.as_raw_fd()
    ))?;
    let mut flags = libc::MS_NOSUID | libc::MS_NODEV | libc::MS_NOEXEC;
    if !bridge.writable {
        flags |= libc::MS_RDONLY;
    }
    // Use only the guest kernel's FUSE device and the mount syscall.
    let rc = unsafe {
        libc::mount(
            device.as_ptr(),
            point.as_ptr(),
            c"fuse.appine-share".as_ptr(),
            flags,
            options.as_ptr().cast(),
        )
    };
    if rc != 0 {
        return Err(io::Error::last_os_error());
    }
    let mut nodes = HashMap::new();
    nodes.insert(
        ROOT,
        Node {
            path: Some("/".into()),
            refs: 1,
        },
    );
    let state = Mutex::new(State {
        last: 1,
        nodes,
        files: HashMap::new(),
        dirs: HashMap::new(),
    });
    let result = Session::from_fd(
        Share { bridge, state },
        fuse.into(),
        SessionACL::All,
        Config::default(),
    )
    .and_then(Session::run);
    unsafe {
        libc::umount2(point.as_ptr(), libc::MNT_DETACH);
    }
    result
}
