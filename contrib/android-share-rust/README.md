# Rust Android share helper

The Android build packages one statically linked AArch64 Linux ELF as
`lib/arm64-v8a/libappine-share.so`. The app uses it both as the in-guest share
client and as the Android root directory browser and managed share server.

The client opens the guest's `/dev/fuse`, mounts it using the syscall and
hands the descriptor to `fuser::Session::from_fd`. The Android settings,
mount lifecycle and SAF provider remain the same. With a selected document
tree the mount is read-only. A root-managed mount uses the existing loopback
protocol and is writable only when the app starts both ends in writable mode.
The root server uses Android kernel support for `openat2` to resolve requests
under the exported directory and rejects unsupported hosts at startup.

Read-only shares can use a per-mount Unix socket to attach a host file
descriptor to a FUSE handle with `SCM_RIGHTS`. The root helper supplies its
opened file. For document trees, the app opens the selected document through
SAF and checks for positional read support. Ordinary guest reads then use
that descriptor and the Android page cache. Unsupported descriptors fall
back to the existing FUSE read protocol. Stopping the share revokes access
through descriptors already attached to guest handles.

A root-managed read-only share also sends lookup, stat and directory
listing replies over the same socket. The root helper resolves them inside
the authorized directory. It can open a regular file read-only on that
socket and return its handle and descriptor. Mapping reads can use the
same descriptor. The app can queue a one-way release on the socket when
a native-open handle is dropped. The HTTP service handles fallback
requests using the same inode numbers and open-file handles.

To build the helper on Arch, install the ARM64 musl Rust target and run:

```sh
rustup target add --toolchain 1.90.0 aarch64-unknown-linux-musl
CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER=rust-lld cargo build --locked --release --target aarch64-unknown-linux-musl
```

Gradle's `androidShare` task runs the same build.
