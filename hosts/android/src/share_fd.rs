use std::fs::File;
use std::io::{self, Read, Write};
use std::net::Shutdown;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::net::UnixStream;
use std::sync::Mutex;
use std::time::Duration;

use appine_kernel::FuseBacking;

pub(crate) struct ShareFd {
    socket: Mutex<Option<UnixStream>>,
    protocol: i32,
}

impl ShareFd {
    pub(crate) fn new(fd: OwnedFd, protocol: i32) -> io::Result<Self> {
        let socket = UnixStream::from(fd);
        let timeout = Some(Duration::from_secs(2));
        socket.set_read_timeout(timeout)?;
        socket.set_write_timeout(timeout)?;
        Ok(Self {
            socket: Mutex::new(Some(socket)),
            protocol,
        })
    }

    fn exchange<T>(&self, request: impl FnOnce(&mut UnixStream) -> io::Result<T>) -> Option<T> {
        let mut state = self.socket.lock().ok()?;
        match request(state.as_mut()?) {
            Ok(reply) => Some(reply),
            Err(_) => {
                // An incomplete frame cannot satisfy the next request.
                state.take();
                None
            }
        }
    }
}

impl FuseBacking for ShareFd {
    fn supports_metadata(&self) -> bool {
        self.protocol >= 1
    }

    fn duplicate(&self, remote: u64) -> Option<File> {
        self.exchange(|socket| {
            socket.write_all(&remote.to_le_bytes())?;
            receive(socket)
        })
        .flatten()
    }

    fn try_release_file(&self, id: u64) -> bool {
        // The kernel calls from its drop path while the BKL is held.
        const TAG: u64 = 1 << 63;
        if self.protocol < 2 || id == 0 || id >= TAG - 1 {
            return false;
        }
        let Ok(mut state) = self.socket.try_lock() else {
            return false;
        };
        let Some(socket) = state.as_ref() else {
            return false;
        };
        let bytes = (TAG | id).to_le_bytes();
        // SAFETY: the stream and its buffer are live, and this call cannot wait for socket space.
        let sent = unsafe {
            libc::send(
                socket.as_raw_fd(),
                bytes.as_ptr().cast(),
                bytes.len(),
                libc::MSG_DONTWAIT | libc::MSG_NOSIGNAL,
            )
        };
        if sent == bytes.len() as isize {
            return true;
        }
        if sent < 0
            && matches!(
                io::Error::last_os_error().raw_os_error(),
                Some(libc::EAGAIN | libc::EINTR)
            )
        {
            return false;
        }
        // Stop on a partial frame so later requests cannot be mistaken for its remaining bytes.
        let _ = socket.shutdown(Shutdown::Both);
        state.take();
        false
    }

    fn metadata(&self, opcode: u32, node: u64, args: &[u8]) -> Option<Result<Vec<u8>, i32>> {
        if !self.supports_metadata() || args.len() > 4096 {
            return None;
        }
        self.exchange(|socket| request(socket, opcode, node, args))
    }

    fn open_file(&self, node: u64) -> Option<(u64, File)> {
        const OPEN: u32 = 14;
        if !self.supports_metadata() {
            return None;
        }
        self.exchange(|socket| {
            let Ok(reply) = request(socket, OPEN, node, &[])? else {
                return Ok(None);
            };
            let id = u64::from_le_bytes(reply.try_into().map_err(|_| io::ErrorKind::InvalidData)?);
            let file = receive(socket)?.ok_or(io::ErrorKind::InvalidData)?;
            if id == 0 || id >= (1 << 63) {
                return Err(io::ErrorKind::InvalidData.into());
            }
            Ok(Some((id, file)))
        })
        .flatten()
    }
}

fn request(
    socket: &mut UnixStream,
    opcode: u32,
    node: u64,
    args: &[u8],
) -> io::Result<Result<Vec<u8>, i32>> {
    let mut frame = Vec::with_capacity(24 + args.len());
    frame.extend(u64::MAX.to_le_bytes());
    frame.extend(opcode.to_le_bytes());
    frame.extend((args.len() as u32).to_le_bytes());
    frame.extend(node.to_le_bytes());
    frame.extend_from_slice(args);
    socket.write_all(&frame)?;

    let mut header = [0; 8];
    socket.read_exact(&mut header)?;
    let errno = i32::from_le_bytes(header[..4].try_into().unwrap());
    let size = u32::from_le_bytes(header[4..].try_into().unwrap()) as usize;
    if !(0..4096).contains(&errno) || size > 8 * 1024 * 1024 || errno != 0 && size != 0 {
        return Err(io::Error::from(io::ErrorKind::InvalidData));
    }
    if errno != 0 {
        return Ok(Err(errno));
    }
    let mut reply = vec![0; size];
    socket.read_exact(&mut reply)?;
    Ok(Ok(reply))
}

fn receive(socket: &UnixStream) -> io::Result<Option<File>> {
    let mut status = 0u8;
    let mut vector = libc::iovec {
        iov_base: (&mut status as *mut u8).cast(),
        iov_len: 1,
    };
    let mut control = [0usize; 8];
    // SAFETY: the iovec and ancillary data both point into live, aligned storage.
    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
    msg.msg_iov = &mut vector;
    msg.msg_iovlen = 1;
    msg.msg_control = control.as_mut_ptr().cast();
    msg.msg_controllen = std::mem::size_of_val(&control);
    // SAFETY: recvmsg writes only to the supplied iovec and control buffer.
    let n = unsafe { libc::recvmsg(socket.as_raw_fd(), &mut msg, libc::MSG_CMSG_CLOEXEC) };
    if n < 0 {
        return Err(io::Error::last_os_error());
    }
    let mut files = Vec::new();
    // SAFETY: CMSG_* traverses the kernel-provided control data. Each received descriptor
    // becomes owned here, including descriptors in a frame subsequently refused.
    unsafe {
        let mut cmsg = libc::CMSG_FIRSTHDR(&msg);
        while !cmsg.is_null() {
            if (*cmsg).cmsg_level == libc::SOL_SOCKET && (*cmsg).cmsg_type == libc::SCM_RIGHTS {
                let len = (*cmsg).cmsg_len.saturating_sub(libc::CMSG_LEN(0) as usize);
                let fds = libc::CMSG_DATA(cmsg).cast::<libc::c_int>();
                for index in 0..(len / std::mem::size_of::<libc::c_int>()) {
                    files.push(OwnedFd::from_raw_fd(fds.add(index).read_unaligned()));
                }
            }
            cmsg = libc::CMSG_NXTHDR(&msg, cmsg);
        }
    }
    if n != 1 || msg.msg_flags & libc::MSG_CTRUNC != 0 {
        return Err(io::Error::from(io::ErrorKind::InvalidData));
    }
    match (status, files.len()) {
        (0, 0) => Ok(None),
        (1, 1) => {
            let file = File::from(files.pop().unwrap());
            // SAFETY: file owns a live descriptor.
            let flags = unsafe { libc::fcntl(file.as_raw_fd(), libc::F_GETFL) };
            if flags < 0
                || flags & (libc::O_PATH | libc::O_ACCMODE) != libc::O_RDONLY
                || !file.metadata()?.is_file()
            {
                return Err(io::Error::from(io::ErrorKind::InvalidData));
            }
            Ok(Some(file))
        }
        _ => Err(io::Error::from(io::ErrorKind::InvalidData)),
    }
}
