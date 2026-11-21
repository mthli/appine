mod client;
mod server;

use std::io;

fn invalid(message: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, message)
}

fn run() -> io::Result<()> {
    let mut args = std::env::args().skip(1);
    let (mut dir, mut point, mut device) = (
        String::new(),
        String::new(),
        "appine-android-share".to_owned(),
    );
    let mut backing_socket = None;
    let (mut port, mut browse, mut managed, mut write) = (8877, false, false, false);
    while let Some(arg) = args.next() {
        let mut value = || args.next().ok_or(invalid("missing argument value"));
        match arg.as_str() {
            "-dir" => dir = value()?,
            "-mount" => point = value()?,
            "-device" => device = value()?,
            "-port" => {
                port = value()?
                    .parse::<u16>()
                    .map_err(|_| invalid("invalid port"))?
            }
            "-browse" => browse = true,
            "-managed" => managed = true,
            "-write" => write = true,
            "-fd-socket" => backing_socket = Some(value()?),
            _ => return Err(invalid("unsupported argument")),
        }
    }
    if !point.is_empty() {
        let token =
            std::env::var("APPINE_SHARE_TOKEN").map_err(|_| invalid("missing share token"))?;
        let mode = std::env::var("APPINE_SHARE_MODE").map_err(|_| invalid("missing share mode"))?;
        if !matches!(mode.as_str(), "tree" | "ro" | "rw")
            || token.len() != 64
            || !token.bytes().all(|b| b.is_ascii_hexdigit())
            || port == 0
        {
            return Err(invalid("invalid mount parameters"));
        }
        return client::mount(&point, port, &token, &mode, &device);
    }
    if unsafe { libc::geteuid() } != 0 || !dir.starts_with('/') {
        return Err(invalid("root and an absolute share directory are required"));
    }
    if browse {
        return server::browse(&dir);
    }
    if managed {
        return server::serve(&dir, port, write, backing_socket.as_deref());
    }
    Err(invalid("expected -mount, -browse or -managed"))
}

fn main() {
    if let Err(err) = run() {
        eprintln!("appine-share: {err}");
        std::process::exit(1);
    }
}
