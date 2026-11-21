//! Metadata for a root-managed read-only share. The socket and HTTP fallback
//! use the same inode numbers and release lookup references through FUSE_FORGET.

use std::collections::HashMap;
use std::io::{self, Read, Write};
use std::os::unix::net::UnixStream;
use std::sync::{Arc, Mutex};

use super::{Host, entry, errno, send_backing, share_path};
use crate::client::{Entry, NodePath};

const ROOT: u64 = 1;
const LOOKUP: u32 = 1;
const GETATTR: u32 = 3;
const OPEN: u32 = 14;
const READDIR: u32 = 28;
const MAX_REPLY: usize = 8 * 1024 * 1024;

#[derive(Default)]
pub(super) struct Nodes {
    next: u64,
    by_id: HashMap<u64, (String, u64)>,
    by_path: HashMap<String, u64>,
}

impl Nodes {
    pub(super) fn path(&self, id: u64) -> io::Result<&str> {
        if id == ROOT {
            Ok("/")
        } else {
            self.by_id
                .get(&id)
                .map(|v| v.0.as_str())
                .ok_or_else(|| errno(libc::ESTALE))
        }
    }

    pub(super) fn location(&self, id: u64) -> io::Result<NodePath> {
        let path = self.path(id)?;
        let parent = path
            .rsplit_once('/')
            .and_then(|(parent, _)| self.by_path.get(parent));
        Ok(NodePath {
            path: path.to_owned(),
            parent: parent.copied().unwrap_or(ROOT),
        })
    }

    pub(super) fn retain(&mut self, path: &str) -> u64 {
        if let Some(&id) = self.by_path.get(path) {
            self.by_id.get_mut(&id).unwrap().1 += 1;
            return id;
        }
        self.next = self.next.max(ROOT) + 1;
        self.by_id.insert(self.next, (path.to_owned(), 1));
        self.by_path.insert(path.to_owned(), self.next);
        self.next
    }

    pub(super) fn forget(&mut self, id: u64, count: u64) {
        let Some((_, refs)) = self.by_id.get_mut(&id) else {
            return;
        };
        *refs = refs.saturating_sub(count);
        if *refs == 0 {
            let (path, _) = self.by_id.remove(&id).unwrap();
            self.by_path.remove(&path);
        }
    }
}

fn u32_out(reply: &mut Vec<u8>, value: u32) {
    reply.extend(value.to_le_bytes());
}

fn u64_out(reply: &mut Vec<u8>, value: u64) {
    reply.extend(value.to_le_bytes());
}

// Linux fuse_attr with the same guest permissions and timestamp policy as Entry::attr.
fn attr(reply: &mut Vec<u8>, id: u64, entry: &Entry) {
    let size = if entry.dir {
        0
    } else {
        entry.size.max(0) as u64
    };
    let mtime = entry.mtime.max(0) as u64;
    for field in [
        id,
        size,
        size.div_ceil(512),
        mtime / 1000,
        mtime / 1000,
        mtime / 1000,
    ] {
        u64_out(reply, field);
    }
    for _ in 0..3 {
        u32_out(reply, (mtime % 1000) as u32 * 1_000_000);
    }
    let (mode, nlink) = if entry.dir {
        (libc::S_IFDIR | 0o555, 2)
    } else {
        (libc::S_IFREG | 0o444, 1)
    };
    for field in [mode, nlink, 0, 0, 0, 4096, 0] {
        u32_out(reply, field);
    }
}

fn dirent(reply: &mut Vec<u8>, index: usize, id: u64, dir: bool, name: &str) {
    u64_out(reply, id);
    u64_out(reply, index as u64 + 1);
    u32_out(reply, name.len() as u32);
    u32_out(
        reply,
        u32::from(if dir { libc::DT_DIR } else { libc::DT_REG }),
    );
    reply.extend_from_slice(name.as_bytes());
    reply.resize(reply.len().next_multiple_of(8), 0);
}

fn answer(host: &mut Host, opcode: u32, id: u64, args: &[u8]) -> io::Result<Vec<u8>> {
    let path = host.nodes.path(id)?;
    let mut out = Vec::new();
    match opcode {
        LOOKUP => {
            let name = args
                .strip_suffix(&[0])
                .and_then(|s| std::str::from_utf8(s).ok())
                .filter(|s| !s.is_empty() && !s.contains(['/', '\0']) && *s != "." && *s != "..")
                .ok_or_else(|| errno(libc::EINVAL))?;
            let found = host.lookup_node(&format!("{}/{name}", path.trim_end_matches('/')))?;
            u64_out(&mut out, found.id);
            // generation and cache validity match the ordinary FUSE client (all zero).
            out.resize(40, 0);
            attr(&mut out, found.id, &found.entry);
        }
        GETATTR => {
            let file = if args.len() == 16 {
                let fh = u64::from_le_bytes(args[8..16].try_into().unwrap());
                host.files
                    .get(&(fh & !(1 << 63)))
                    .filter(|_| fh & (1 << 63) != 0)
            } else {
                None
            };
            let stat = match file {
                Some(file) => file.metadata()?,
                None => host.metadata(share_path(path)?)?,
            };
            out.resize(16, 0);
            attr(&mut out, id, &entry(stat, path)?);
        }
        READDIR => {
            let bytes = <[u8; 8]>::try_from(args).map_err(|_| errno(libc::EINVAL))?;
            dirent(&mut out, 0, id, true, ".");
            dirent(&mut out, 1, u64::from_le_bytes(bytes), true, "..");
            for (i, entry) in host.list(share_path(path)?)?.into_iter().enumerate() {
                dirent(&mut out, i + 2, 0, entry.dir, &entry.name);
                if out.len() > MAX_REPLY {
                    return Err(errno(libc::EOPNOTSUPP));
                }
            }
        }
        _ => return Err(errno(libc::ENOSYS)),
    }
    Ok(out)
}

fn write_reply(socket: &mut UnixStream, result: io::Result<Vec<u8>>) -> io::Result<()> {
    match result {
        Ok(bytes) => {
            let mut frame = Vec::with_capacity(8 + bytes.len());
            u32_out(&mut frame, 0);
            u32_out(&mut frame, bytes.len() as u32);
            frame.extend(bytes);
            socket.write_all(&frame)
        }
        Err(error) => {
            let mut frame = [0; 8];
            frame[..4].copy_from_slice(&error.raw_os_error().unwrap_or(libc::EIO).to_le_bytes());
            socket.write_all(&frame)
        }
    }
}

fn open(socket: &mut UnixStream, host: &mut Host, node: u64, args: &[u8]) -> io::Result<()> {
    if !args.is_empty() || host.writable {
        return write_reply(socket, Err(errno(libc::EINVAL)));
    }
    let opened = host
        .nodes
        .path(node)
        .and_then(share_path)
        .and_then(|path| host.regular(path, libc::O_RDONLY));
    let file = match opened {
        Ok(file) => file,
        Err(error) => return write_reply(socket, Err(error)),
    };
    let id = host.keep_file(file);
    let result = write_reply(socket, Ok(id.to_le_bytes().to_vec()))
        .and_then(|()| send_backing(socket, host.files.get(&id)));
    if result.is_err() {
        host.files.remove(&id);
    }
    result
}

pub(super) fn reply(socket: &mut UnixStream, host: &Arc<Mutex<Host>>) -> io::Result<()> {
    let mut header = [0u8; 16];
    socket.read_exact(&mut header)?;
    let opcode = u32::from_le_bytes(header[..4].try_into().unwrap());
    let len = u32::from_le_bytes(header[4..8].try_into().unwrap()) as usize;
    let id = u64::from_le_bytes(header[8..16].try_into().unwrap());
    if len > 4096 {
        return Err(errno(libc::EINVAL));
    }
    let mut args = vec![0; len];
    socket.read_exact(&mut args)?;
    if opcode == OPEN {
        return open(
            socket,
            &mut host.lock().unwrap_or_else(|e| e.into_inner()),
            id,
            &args,
        );
    }
    let reply = answer(
        &mut host.lock().unwrap_or_else(|e| e.into_inner()),
        opcode,
        id,
        &args,
    );
    write_reply(socket, reply)
}
