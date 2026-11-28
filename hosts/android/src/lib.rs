//! Appine's Android native library (design doc §3, the android/native map): the Kotlin shell uses
//! the kernel layer through JNI.
//!
//! The matching Kotlin declarations are in
//! `android/app/src/main/java/io/github/mthli/appine/Native.kt`.
//! [`Machine`]s are registered in a table, and Kotlin gets a number (a `long` handle). Each call
//! takes a temporary reference, so `stop` is safe even concurrently with a read blocked on another
//! thread: the Machine is dropped only when the last reference goes away. `stop` removes it from
//! the table, and later calls with the handle (such as `closeConsole`, cleaned up only after the
//! session ends) do not find it and treat the console as closed.
//! Errors throw `java.io.IOException`. Blocking calls (reading a console, waiting for a process to
//! end) are made by Kotlin on background threads. Kernel-layer bugs (panics) are meant to be caught
//! inside the [`Machine`] methods and become errors of these calls, so that they do not cross the
//! JNI boundary and abort the app process. Only the methods that wrap their work in the kernel's
//! `host_call` do so; a panic in an unwrapped one (such as [`Machine::wait`] or the wait loops of
//! console reads) or in this crate's own code is caught by every JNI function ([`guarded`]) and
//! fails that call instead.
//!
//! Compiled only for Android; on other hosts it is an empty library.
#![cfg(target_os = "android")]

use std::collections::BTreeMap;
use std::ffi::CString;
use std::path::PathBuf;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, Once, PoisonError};

use appine_kernel::errno::Errno;
use appine_kernel::vfs::browse;
use appine_kernel::vfs::store::Store;
use appine_kernel::{ExitStatus, HostRoute, Machine, MachineOptions, Power, Stdio, Tier};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObject, JObjectArray, JString};
use jni::sys::{
    jboolean, jbooleanArray, jbyteArray, jint, jlong, jlongArray, jobjectArray, jstring,
};

/// Runs the body of a JNI function. A panic in it (a kernel-layer bug outside the calls the kernel
/// wraps itself, or one in this crate) would unwind out of `extern "system"` and abort the app
/// with every session in it; it is caught instead, logged to logcat after the panic message the
/// panic hook writes to stderr, and the call returns `fallback`, which Kotlin takes as a failure of
/// that call: an `IOException` with `throws env` (the functions Kotlin declares `@Throws`), an
/// ended console or process, or an empty answer.
macro_rules! guarded {
    ($function:literal, $fallback:expr, $body:block) => {
        match std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || $body)) {
            Ok(r) => r,
            Err(_) => {
                crate::panicked($function, None);
                $fallback
            }
        }
    };
    ($function:literal, throws $env:ident, $fallback:expr, $body:block) => {{
        let raw = $env.get_raw();
        match std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || $body)) {
            Ok(r) => r,
            Err(_) => {
                crate::panicked($function, Some(raw));
                $fallback
            }
        }
    }};
}

mod gpu;
mod vt;

/// A JNI function's body panicked ([`guarded`]): logs it, and when Kotlin expects an exception
/// from the call, throws one unless one is already pending.
fn panicked(function: &str, env: Option<*mut jni::sys::JNIEnv>) {
    eprintln!("appine: internal error in {function} (a panic, logged above); the call failed");
    // SAFETY: the pointer is the JNIEnv this call was made with, on this thread
    if let Some(Ok(mut env)) = env.map(|raw| unsafe { JNIEnv::from_raw(raw) })
        && !env.exception_check().unwrap_or(true)
    {
        throw(&mut env, format!("internal error in {function}"));
    }
}

/// Called before any other function: stderr goes to logcat, and with `verbose` the kernel layer's
/// debug log is turned on.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_init<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    verbose: jboolean,
) {
    guarded!("Native.init", (), {
        redirect_stderr();
        if verbose != 0 {
            // SAFETY: set before the kernel layer is used; no other thread reads the environment yet
            unsafe { std::env::set_var("APPINE_LOG", "1") };
        }
    })
}

/// Sends stderr (kernel-layer logs, internal errors) to logcat.
fn redirect_stderr() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        #[link(name = "log")]
        unsafe extern "C" {
            fn __android_log_write(
                prio: i32,
                tag: *const libc::c_char,
                text: *const libc::c_char,
            ) -> i32;
        }
        const ANDROID_LOG_INFO: i32 = 4;
        let mut fds = [0; 2];
        if unsafe { libc::pipe(fds.as_mut_ptr()) } != 0 {
            return;
        }
        let [read_end, write_end] = fds;
        // Spawn the reader before redirecting fd 2 so a spawn failure never leaves stderr pointing
        // at an unread pipe; write_end stays open across spawn so the reader cannot see EOF early.
        let spawned = std::thread::Builder::new()
            .name("stderr-log".into())
            .spawn(move || {
                use std::io::{BufRead, BufReader};
                use std::os::fd::FromRawFd;
                let input = unsafe { std::fs::File::from_raw_fd(read_end) };
                let tag = c"appine";
                for line in BufReader::new(input).split(b'\n').map_while(Result::ok) {
                    if let Ok(text) = CString::new(line) {
                        unsafe {
                            __android_log_write(ANDROID_LOG_INFO, tag.as_ptr(), text.as_ptr())
                        };
                    }
                }
            });
        if spawned.is_err() {
            unsafe {
                libc::close(read_end);
                libc::close(write_end);
            }
            return;
        }
        if unsafe { libc::dup2(write_end, 2) } < 0 {
            // Closing write_end delivers EOF to stderr-log so it exits cleanly.
            unsafe { libc::close(write_end) };
            return;
        }
        if write_end != 2 {
            unsafe { libc::close(write_end) };
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_importRootfs<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    store: JString<'l>,
    tarball: JString<'l>,
) {
    guarded!("Native.importRootfs", throws env, (), {
        let (Some(store), Some(tarball)) = (string(&mut env, &store), string(&mut env, &tarball))
        else {
            return;
        };
        if let Err(e) = appine_kernel::import_rootfs(store.as_ref(), tarball.as_ref()) {
            throw(&mut env, format!("cannot import the rootfs: {e}"));
        }
    })
}

/// Writes the store to disk (after an import and writing the configuration, before renaming it into
/// place).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_syncRootfs<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    store: JString<'l>,
) {
    guarded!("Native.syncRootfs", throws env, (), {
        let Some(store) = string(&mut env, &store) else {
            return;
        };
        if let Err(e) = appine_kernel::sync_store(store.as_ref()) {
            throw(&mut env, format!("cannot sync the rootfs: {e}"));
        }
    })
}

/// Opens the store once without a background reaper thread and passes a thread-local session
/// handle to `action` (`LongConsumer.accept(long)`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_withStore<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    store: JString<'l>,
    action: JObject<'l>,
) {
    guarded!("Native.withStore", throws env, (), {
        let Some(store) = string(&mut env, &store) else {
            return;
        };
        let r = appine_kernel::with_store(store.as_ref(), |s| {
            let guard = StoreSessionGuard::new(s);
            env.call_method(&action, "accept", "(J)V", &[guard.id.into()])
                .map(drop)
                .map_err(|_| Errno::EIO)
        });
        if let Err(e) = r
            && !env.exception_check().unwrap_or(true)
        {
            throw(&mut env, format!("cannot open {store}: {e}"));
        }
    })
}

/// Writes a file on an active [`Java_io_github_mthli_appine_Native_withStore`] session.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_io_github_mthli_appine_Native_storeWriteFile<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    session: jlong,
    path: JString<'l>,
    data: JByteArray<'l>,
    mode: jint,
    overwrite: jboolean,
    uid: jint,
    gid: jint,
) {
    guarded!("Native.storeWriteFile", throws env, (), {
        let Some(path) = string(&mut env, &path) else {
            return;
        };
        let Ok(data) = env.convert_byte_array(&data) else {
            return;
        };
        let r = with_store_session(session, |s| {
            appine_kernel::write_file_in(
                s,
                &path,
                &data,
                mode as u32,
                overwrite != 0,
                uid as u32,
                gid as u32,
            )
        });
        if let Err(e) = r {
            throw(&mut env, format!("cannot write {path}: {e}"));
        }
    })
}

/// Reads a regular file on an active [`Java_io_github_mthli_appine_Native_withStore`] session.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_storeReadFile<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    session: jlong,
    path: JString<'l>,
) -> jbyteArray {
    guarded!("Native.storeReadFile", throws env, std::ptr::null_mut(), {
        let Some(path) = string(&mut env, &path) else {
            return std::ptr::null_mut();
        };
        let r = with_store_session(session, |s| appine_kernel::read_file_in(s, &path));
        read_file_result(&mut env, &path, r)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_writeFile<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    store: JString<'l>,
    path: JString<'l>,
    data: JByteArray<'l>,
    mode: jint,
    overwrite: jboolean,
    uid: jint,
    gid: jint,
) {
    guarded!("Native.writeFile", throws env, (), {
        let (Some(store), Some(path)) = (string(&mut env, &store), string(&mut env, &path)) else {
            return;
        };
        let Ok(data) = env.convert_byte_array(&data) else {
            return;
        };
        let r = appine_kernel::write_file(
            store.as_ref(),
            &path,
            &data,
            mode as u32,
            overwrite != 0,
            uid as u32,
            gid as u32,
        );
        if let Err(e) = r {
            throw(&mut env, format!("cannot write {path}: {e}"));
        }
    })
}

/// Reads a regular file in the store (for the host to look at configuration files, such as listing
/// accounts in Settings): returns null when it does not exist and throws IOException for other
/// errors. Also works while the kernel runs: when the store is opened separately, the kernel's
/// deferred write transaction commits first.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_readFile<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    store: JString<'l>,
    path: JString<'l>,
) -> jbyteArray {
    guarded!("Native.readFile", throws env, std::ptr::null_mut(), {
        let (Some(store), Some(path)) = (string(&mut env, &store), string(&mut env, &path)) else {
            return std::ptr::null_mut();
        };
        let r = appine_kernel::read_file(store.as_ref(), &path);
        read_file_result(&mut env, &path, r)
    })
}

fn read_file_result(
    env: &mut JNIEnv<'_>,
    path: &str,
    result: Result<Vec<u8>, Errno>,
) -> jbyteArray {
    match result {
        Ok(data) => env
            .byte_array_from_slice(&data)
            .map(JByteArray::into_raw)
            .unwrap_or(std::ptr::null_mut()),
        Err(Errno::ENOENT) => std::ptr::null_mut(),
        Err(e) => {
            throw(env, format!("cannot read {path}: {e}"));
            std::ptr::null_mut()
        }
    }
}

/// Active stack-scoped [`Store`] session bound to the opening thread for the duration of
/// [`Java_io_github_mthli_appine_Native_withStore`].
struct ActiveStoreSession {
    store: *const Store,
    in_use: bool,
}

static NEXT_STORE_SESSION: AtomicI64 = AtomicI64::new(1);

thread_local! {
    static STORE_SESSIONS: std::cell::RefCell<BTreeMap<jlong, ActiveStoreSession>> =
        const { std::cell::RefCell::new(BTreeMap::new()) };
}

struct StoreSessionGuard {
    id: jlong,
}

impl StoreSessionGuard {
    fn new(store: &Store) -> Self {
        let id = NEXT_STORE_SESSION.fetch_add(1, Ordering::Relaxed);
        STORE_SESSIONS.with(|sessions| {
            sessions.borrow_mut().insert(
                id,
                ActiveStoreSession {
                    store: std::ptr::from_ref(store),
                    in_use: false,
                },
            );
        });
        Self { id }
    }
}

impl Drop for StoreSessionGuard {
    fn drop(&mut self) {
        STORE_SESSIONS.with(|sessions| {
            sessions.borrow_mut().remove(&self.id);
        });
    }
}

fn with_store_session<R>(
    handle: jlong,
    f: impl FnOnce(&Store) -> Result<R, Errno>,
) -> Result<R, Errno> {
    let ptr = STORE_SESSIONS.with(|sessions| {
        let mut map = sessions.borrow_mut();
        let entry = map.get_mut(&handle)?;
        if entry.in_use {
            return None;
        }
        entry.in_use = true;
        Some(entry.store)
    });
    let Some(ptr) = ptr else {
        return Err(Errno::EBADF);
    };
    struct BusyGuard(jlong);
    impl Drop for BusyGuard {
        fn drop(&mut self) {
            STORE_SESSIONS.with(|sessions| {
                if let Some(entry) = sessions.borrow_mut().get_mut(&self.0) {
                    entry.in_use = false;
                }
            });
        }
    }
    let _busy = BusyGuard(handle);
    // SAFETY: `StoreSessionGuard` removes `handle` from this thread's map before the `Store` on
    // `Native_withStore`'s stack is dropped, `STORE_SESSIONS` is thread-local so no other thread
    // can observe `ptr`, and `in_use` prevents re-entrant access on the same handle.
    f(unsafe { &*ptr })
}

// ---- The host browsing the store (DocumentsProvider, the android/documents map) ----
//
// On the Java side paths are strings (document IDs): guest file names are arbitrary bytes; the
// valid UTF-8 parts stay as they are, while `%`, control characters (each line of a list is
// separated by tabs) and bytes that are not UTF-8 are written as `%XX`, so they convert back
// exactly.
// Each entry is `<type d|f>\t<bytes>\t<modification time (ms)>\t<permission bits>`; list and search
// results are also prefixed with the name or path and a tab. Errors map by errno to `IOException`
// subclasses: `ENOENT`/`ENOTDIR` throw `FileNotFoundException`, `EEXIST` throws
// `FileAlreadyExistsException`, `EACCES` throws `AccessDeniedException`, and all other errnos
// throw `IOException`.

/// The most entries (directory entries) one search looks at: it does not search forever in a big
/// tree like /usr.
const SEARCH_BUDGET: usize = 50_000;

/// The directory or regular file a path (document ID) points at (following symbolic links).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_browseStat<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    store: JString<'l>,
    path: JString<'l>,
) -> jstring {
    guarded!("Native.browseStat", throws env, std::ptr::null_mut(), {
        let (Some(store), Some(path)) = (string(&mut env, &store), string(&mut env, &path)) else {
            return std::ptr::null_mut();
        };
        let r = appine_kernel::browse(store.as_ref(), |s| {
            browse::stat(s, &browse::decode_path(&path))
        });
        match r {
            Ok(st) => env
                .new_string(stat_fields(&st))
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut()),
            Err(e) => {
                browse_error(&mut env, "find", &path, e);
                std::ptr::null_mut()
            }
        }
    })
}

/// Lists a directory: each entry is `<name>\t<type>\t…`, with the name written by
/// [`browse::encode_path`].
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_browseList<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    store: JString<'l>,
    dir: JString<'l>,
) -> jobjectArray {
    guarded!("Native.browseList", throws env, std::ptr::null_mut(), {
        let (Some(store), Some(dir)) = (string(&mut env, &store), string(&mut env, &dir)) else {
            return std::ptr::null_mut();
        };
        let r = appine_kernel::browse(store.as_ref(), |s| {
            browse::list(s, &browse::decode_path(&dir))
        });
        match r {
            Ok(entries) => {
                let lines: Vec<String> = entries
                    .iter()
                    .map(|e| format!("{}\t{}", browse::encode_path(&e.name), stat_fields(&e.stat)))
                    .collect();
                string_array(&mut env, &lines)
            }
            Err(e) => {
                browse_error(&mut env, "list", &dir, e);
                std::ptr::null_mut()
            }
        }
    })
}

/// Current SAF document generations in one read snapshot, including symlink traversal.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_browseGenerations<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    store: JString<'l>,
    paths: JObjectArray<'l>,
) -> jlongArray {
    guarded!("Native.browseGenerations", throws env, std::ptr::null_mut(), {
        let (Some(store), Some(paths)) = (string(&mut env, &store), strings(&mut env, &paths)) else {
            return std::ptr::null_mut();
        };
        let paths: Vec<_> = paths.iter()
            .map(|p| browse::decode_path(&String::from_utf8_lossy(p)))
            .collect();
        let result = appine_kernel::browse(store.as_ref(), |s| browse::document_generations(s, &paths));
        let generations = match result {
            Ok(g) => g.into_iter().map(|n| n as i64).collect::<Vec<_>>(),
            Err(e) => {
                browse_error(&mut env, "lookup document generations in", &store, e);
                return std::ptr::null_mut();
            }
        };
        long_array(&env, &generations)
    })
}

/// Opens a regular file for reading and returns the host fd (handed to
/// ParcelFileDescriptor.adoptFd, which closes it).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_browseOpen<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    store: JString<'l>,
    path: JString<'l>,
) -> jint {
    guarded!("Native.browseOpen", throws env, -1, {
        use std::os::fd::IntoRawFd;
        let (Some(store), Some(path)) = (string(&mut env, &store), string(&mut env, &path)) else {
            return -1;
        };
        let r = appine_kernel::browse(store.as_ref(), |s| {
            browse::open(s, &browse::decode_path(&path))
        });
        match r {
            Ok(file) => file.into_raw_fd(),
            Err(e) => {
                browse_error(&mut env, "open", &path, e);
                -1
            }
        }
    })
}

/// Whether the regular files at these paths look like text ([`browse::looks_like_text`]), all
/// checked with one opening of the store. Only the first 1000 are looked at; the rest count as not
/// text.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_browseSniff<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    store: JString<'l>,
    paths: JObjectArray<'l>,
) -> jbooleanArray {
    guarded!("Native.browseSniff", throws env, std::ptr::null_mut(), {
        /// The most files looked at in one call: each has to be opened on the host and 4 KB read, so a
        /// big directory cannot go on forever.
        const SNIFF_MAX: usize = 1000;
        let (Some(store), Some(paths)) = (string(&mut env, &store), strings(&mut env, &paths)) else {
            return std::ptr::null_mut();
        };
        let r = appine_kernel::browse(store.as_ref(), |s| {
            Ok(paths
                .iter()
                .enumerate()
                .map(|(i, p)| {
                    let p = browse::decode_path(&String::from_utf8_lossy(p));
                    jboolean::from(i < SNIFF_MAX && browse::looks_like_text(s, &p))
                })
                .collect::<Vec<jboolean>>())
        });
        let texts = match r {
            Ok(t) => t,
            Err(e) => {
                browse_error(&mut env, "open", &store, e);
                return std::ptr::null_mut();
            }
        };
        let Ok(array) = env.new_boolean_array(texts.len() as jint) else {
            return std::ptr::null_mut();
        };
        if env.set_boolean_array_region(&array, 0, &texts).is_err() {
            return std::ptr::null_mut();
        }
        array.into_raw()
    })
}

/// Searches by name under a directory (case-insensitive): each entry is `<path>\t<type>\t…`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_browseSearch<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    store: JString<'l>,
    dir: JString<'l>,
    query: JString<'l>,
    limit: jint,
) -> jobjectArray {
    guarded!("Native.browseSearch", throws env, std::ptr::null_mut(), {
        let (Some(store), Some(dir), Some(query)) = (
            string(&mut env, &store),
            string(&mut env, &dir),
            string(&mut env, &query),
        ) else {
            return std::ptr::null_mut();
        };
        let r = appine_kernel::browse(store.as_ref(), |s| {
            browse::search(
                s,
                &browse::decode_path(&dir),
                &query,
                limit.max(0) as usize,
                SEARCH_BUDGET,
            )
        });
        match r {
            Ok(found) => {
                let lines: Vec<String> = found
                    .iter()
                    .map(|(p, st)| format!("{}\t{}", browse::encode_path(p), stat_fields(st)))
                    .collect();
                string_array(&mut env, &lines)
            }
            Err(e) => {
                browse_error(&mut env, "search", &dir, e);
                std::ptr::null_mut()
            }
        }
    })
}

// Changes: all confined to `top` (the login account's home directory), judged by real path (see
// [`browse`]). New names come from the user and are used as UTF-8 as they are, without decoding
// `%XX`; returned names are written by [`browse::encode_path`].

/// Calls the writer with a borrowed staging fd, then publishes the complete file in the home.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_browseImport<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    store: JString<'l>,
    home: JString<'l>,
    name: JString<'l>,
    uid: jint,
    gid: jint,
    writer: JObject<'l>,
) -> jstring {
    use appine_kernel::errno::Errno;
    use std::os::fd::AsRawFd;

    guarded!("Native.browseImport", throws env, std::ptr::null_mut(), {
        let (Some(store), Some(home), Some(name)) = (
            string(&mut env, &store),
            string(&mut env, &home),
            string(&mut env, &name),
        ) else {
            return std::ptr::null_mut();
        };
        let r = appine_kernel::browse_changing(store.as_ref(), |s, changes| {
            browse::import_file(
                s,
                &browse::decode_path(&home),
                name.as_bytes(),
                (uid as u32, gid as u32),
                |file| {
                    env.call_method(&writer, "accept", "(I)V", &[file.as_raw_fd().into()])
                        .map(drop)
                        .map_err(|_| Errno::EIO)
                },
                changes,
            )
        });
        match r {
            Ok(name) => env
                .new_string(browse::encode_path(&name))
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut()),
            Err(e) => {
                if !env.exception_check().unwrap_or(true) {
                    browse_error(&mut env, &format!("import {name} into"), &home, e);
                }
                std::ptr::null_mut()
            }
        }
    })
}

/// Creates a file or directory ([directory]) in directory [dir], owned by [uid] and [gid]; when the
/// name is taken another one is used, and the name actually used is returned.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_io_github_mthli_appine_Native_browseCreate<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    store: JString<'l>,
    top: JString<'l>,
    dir: JString<'l>,
    name: JString<'l>,
    directory: jboolean,
    uid: jint,
    gid: jint,
) -> jstring {
    guarded!("Native.browseCreate", throws env, std::ptr::null_mut(), {
        let (Some(store), Some(top), Some(dir), Some(name)) = (
            string(&mut env, &store),
            string(&mut env, &top),
            string(&mut env, &dir),
            string(&mut env, &name),
        ) else {
            return std::ptr::null_mut();
        };
        let r = appine_kernel::browse_changing(store.as_ref(), |s, changes| {
            browse::create(
                s,
                &browse::decode_path(&top),
                &browse::decode_path(&dir),
                name.as_bytes(),
                directory != 0,
                (uid as u32, gid as u32),
                changes,
            )
        });
        match r {
            Ok(name) => env
                .new_string(browse::encode_path(&name))
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut()),
            Err(e) => {
                browse_error(&mut env, "create in", &dir, e);
                std::ptr::null_mut()
            }
        }
    })
}

/// Removes a file, a symbolic link or a whole directory tree.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_browseRemove<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    store: JString<'l>,
    top: JString<'l>,
    path: JString<'l>,
) {
    guarded!("Native.browseRemove", throws env, (), {
        let (Some(store), Some(top), Some(path)) = (
            string(&mut env, &store),
            string(&mut env, &top),
            string(&mut env, &path),
        ) else {
            return;
        };
        let r = appine_kernel::browse_changing(store.as_ref(), |s, changes| {
            browse::remove(
                s,
                &browse::decode_path(&top),
                &browse::decode_path(&path),
                changes,
            )
        });
        if let Err(e) = r {
            browse_error(&mut env, "delete", &path, e);
        }
    })
}

/// Renames within the same directory ([name] is the new name), without replacing.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_browseRename<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    store: JString<'l>,
    top: JString<'l>,
    path: JString<'l>,
    name: JString<'l>,
) -> jstring {
    guarded!("Native.browseRename", throws env, std::ptr::null_mut(), {
        let (Some(store), Some(top), Some(path), Some(name)) = (
            string(&mut env, &store),
            string(&mut env, &top),
            string(&mut env, &path),
            string(&mut env, &name),
        ) else {
            return std::ptr::null_mut();
        };
        let r = appine_kernel::browse_changing(store.as_ref(), |s, changes| {
            browse::rename(
                s,
                &browse::decode_path(&top),
                &browse::decode_path(&path),
                name.as_bytes(),
                changes,
            )
        });
        match r {
            Ok(()) => env
                .new_string(browse::encode_path(name.as_bytes()))
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut()),
            Err(e) => {
                browse_error(&mut env, "rename", &path, e);
                std::ptr::null_mut()
            }
        }
    })
}

/// Moves to another directory [to], keeping the name, without replacing.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_browseMove<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    store: JString<'l>,
    top: JString<'l>,
    path: JString<'l>,
    to: JString<'l>,
) {
    guarded!("Native.browseMove", throws env, (), {
        let (Some(store), Some(top), Some(path), Some(to)) = (
            string(&mut env, &store),
            string(&mut env, &top),
            string(&mut env, &path),
            string(&mut env, &to),
        ) else {
            return;
        };
        let r = appine_kernel::browse_changing(store.as_ref(), |s, changes| {
            browse::move_to(
                s,
                &browse::decode_path(&top),
                &browse::decode_path(&path),
                &browse::decode_path(&to),
                changes,
            )
        });
        if let Err(e) = r {
            browse_error(&mut env, "move", &path, e);
        }
    })
}

/// Opens a regular file for reading and writing (truncating first with [truncate], opening with
/// O_APPEND with [append]) and returns the host fd.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_browseOpenWrite<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    store: JString<'l>,
    top: JString<'l>,
    path: JString<'l>,
    truncate: jboolean,
    append: jboolean,
) -> jint {
    guarded!("Native.browseOpenWrite", throws env, -1, {
        use std::os::fd::IntoRawFd;
        let (Some(store), Some(top), Some(path)) = (
            string(&mut env, &store),
            string(&mut env, &top),
            string(&mut env, &path),
        ) else {
            return -1;
        };
        let r = appine_kernel::browse_changing(store.as_ref(), |s, changes| {
            browse::open_write(
                s,
                &browse::decode_path(&top),
                &browse::decode_path(&path),
                truncate != 0,
                append != 0,
                changes,
            )
        });
        match r {
            Ok(file) => file.into_raw_fd(),
            Err(e) => {
                browse_error(&mut env, "open", &path, e);
                -1
            }
        }
    })
}

/// A file handed out by [browseOpenWrite] was closed: tells the running kernel it was written
/// (programs watching in the guest receive `IN_MODIFY` and `IN_CLOSE_WRITE`). No error if the file
/// disappeared meanwhile.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_browseWritten<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    store: JString<'l>,
    top: JString<'l>,
    path: JString<'l>,
) {
    guarded!("Native.browseWritten", (), {
        let (Some(store), Some(top), Some(path)) = (
            string(&mut env, &store),
            string(&mut env, &top),
            string(&mut env, &path),
        ) else {
            return;
        };
        let _ = appine_kernel::browse_changing(store.as_ref(), |s, changes| {
            browse::written(
                s,
                &browse::decode_path(&top),
                &browse::decode_path(&path),
                changes,
            )
        });
    })
}

fn stat_fields(st: &appine_kernel::vfs::store::Stat) -> String {
    let kind = if st.is_dir() { 'd' } else { 'f' };
    let perm = st.mode & 0o7777;
    format!(
        "{kind}\t{}\t{}\t{perm}",
        st.size,
        st.mtime_ns.div_euclid(1_000_000)
    )
}

fn browse_error(env: &mut JNIEnv<'_>, what: &str, path: &str, e: appine_kernel::errno::Errno) {
    use appine_kernel::errno::Errno;
    let class = match e {
        Errno::ENOENT | Errno::ENOTDIR => "java/io/FileNotFoundException",
        Errno::EEXIST => "java/nio/file/FileAlreadyExistsException",
        Errno::EACCES => "java/nio/file/AccessDeniedException",
        _ => "java/io/IOException",
    };
    let _ = env.throw_new(class, format!("cannot {what} {path}: {e}"));
}

// ---- The kernel ----

struct RegisteredMachine {
    machine: Arc<Machine>,
    has_gpu: bool,
}

/// Handle → Machine (module docs).
static MACHINES: Mutex<BTreeMap<jlong, RegisteredMachine>> = Mutex::new(BTreeMap::new());
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_start<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    store: JString<'l>,
    tier: jint,
    gpu: jboolean,
) -> jlong {
    guarded!("Native.start", throws env, 0, {
        let Some(store) = string(&mut env, &store) else {
            return 0;
        };
        let options = MachineOptions {
            tier: if tier == 1 {
                Tier::Dbt
            } else {
                Tier::Interpreter
            },
            rootfs: Some(PathBuf::from(store)),
            memory_limit: None,
            low_port_offset: None,
            always_map_low_ports: false,
            // Guest crashes are reported in the kernel log, which the app's bug reports carry
            exception_trace: true,
        };
        let renderer = if gpu != 0 {
            gpu::renderer(&mut env)
        } else {
            None
        };
        let has_gpu = renderer.is_some();
        match Machine::start_with_renderer(&options, renderer) {
            Ok(m) => {
                let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
                machines().insert(
                    handle,
                    RegisteredMachine {
                        machine: Arc::new(m),
                        has_gpu,
                    },
                );
                handle
            }
            Err(e) => {
                throw(&mut env, format!("cannot start: {e}"));
                0
            }
        }
    })
}

/// Whether the running kernel has a probed GPU renderer registered.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_hasGpu<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
) -> jboolean {
    guarded!("Native.hasGpu", 0, {
        jboolean::from(machines().get(&handle).is_some_and(|e| e.has_gpu))
    })
}

/// Boots by `/etc/inittab` (the kernel/boot map): blocks until the sysinit and wait commands
/// have all finished.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_boot<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
) {
    guarded!("Native.boot", (), {
        if let Some(m) = machine(handle) {
            m.boot();
        }
    })
}

/// The host's default network changed: the routing table the guest sees becomes `routes` (each
/// entry as in [`parse_route`]); with null it goes back to being synthesised from the interfaces'
/// addresses.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_setRoutes<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    routes: JObject<'l>,
) {
    guarded!("Native.setRoutes", (), {
        let routes = if routes.is_null() {
            None
        } else {
            let Some(lines) = strings(&mut env, &JObjectArray::from(routes)) else {
                return;
            };
            Some(
                lines
                    .iter()
                    .filter_map(|l| parse_route(std::str::from_utf8(l).ok()?))
                    .collect(),
            )
        };
        if let Some(m) = machine(handle) {
            m.set_routes(routes);
        }
    })
}

/// One route passed in from `Native.kt`:
/// `<destination network in hex>/<prefix length> <gateway in hex or -> <interface>`. The metric is
/// the same as for the same route on Linux: 0 for IPv4; for IPv6, 1024 for the default route (RA)
/// and 256 for others (the networks the kernel adds for addresses).
fn parse_route(line: &str) -> Option<HostRoute> {
    // Slicing by bytes needs ASCII: a multi-byte character would split and panic, which `guarded!`
    // would turn into a failed setRoutes call setting none of the routes
    let hex = |s: &str| -> Option<Vec<u8>> {
        (s.is_ascii() && s.len().is_multiple_of(2))
            .then(|| {
                (0..s.len() / 2)
                    .map(|i| u8::from_str_radix(&s[2 * i..2 * i + 2], 16).ok())
                    .collect()
            })
            .flatten()
    };
    let mut f = line.split_whitespace();
    let (dst, prefix) = f.next()?.split_once('/')?;
    let (dst, prefix): (Vec<u8>, u8) = (hex(dst)?, prefix.parse().ok()?);
    let gateway = match f.next()? {
        "-" => None,
        g => Some(hex(g)?),
    };
    let iface = f.next()?.to_owned();
    let (family, metric) = match dst.len() {
        4 => (libc::AF_INET, 0),
        16 if prefix == 0 => (libc::AF_INET6, 1024),
        16 => (libc::AF_INET6, 256),
        _ => return None,
    };
    if gateway.as_ref().is_some_and(|g| g.len() != dst.len()) {
        return None;
    }
    Some(HostRoute {
        family,
        dst,
        prefix,
        gateway,
        iface,
        metric,
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_openConsole<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    rows: jint,
    cols: jint,
) -> jint {
    guarded!("Native.openConsole", throws env, -1, {
        let (rows, cols) = (rows.clamp(1, 0xffff) as u16, cols.clamp(1, 0xffff) as u16);
        let Some(m) = machine(handle) else {
            throw(&mut env, "the machine is stopped");
            return -1;
        };
        match m.open_console(rows, cols) {
            Ok(id) => id as jint,
            Err(e) => {
                throw(&mut env, format!("cannot open a console: {e}"));
                -1
            }
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_spawn<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    console: jint,
    program: JString<'l>,
    argv: JObjectArray<'l>,
    envp: JObjectArray<'l>,
) -> jint {
    guarded!("Native.spawn", throws env, -1, {
        let Some(program) = string(&mut env, &program) else {
            return -1;
        };
        let (Some(argv), Some(envp)) = (strings(&mut env, &argv), strings(&mut env, &envp)) else {
            return -1;
        };
        // Negative: no console, use the host's stdin/stdout/stderr (for the app, /dev/null and logcat)
        let stdio = u32::try_from(console).map_or(Stdio::Host, Stdio::Console);
        let Some(m) = machine(handle) else {
            throw(&mut env, "the machine is stopped");
            return -1;
        };
        match m.spawn(program.as_bytes(), &argv, &envp, stdio) {
            Ok(pid) => pid as jint,
            Err(e) => {
                throw(&mut env, format!("{program}: {e}"));
                -1
            }
        }
    })
}

/// Reads console output: returns the number of bytes read, 0 when the session ended, -1 when the
/// console is closed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_read<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    console: jint,
    buffer: JByteArray<'l>,
) -> jint {
    guarded!("Native.read", -1, {
        let Ok(len) = env.get_array_length(&buffer) else {
            return -1;
        };
        let Some(m) = machine(handle) else {
            return -1;
        };
        let mut buf = vec![0u8; len as usize];
        match m.read_console(console as u32, &mut buf) {
            Ok(n) => {
                // SAFETY: u8 and i8 have the same layout
                let bytes = unsafe { std::slice::from_raw_parts(buf.as_ptr().cast::<i8>(), n) };
                match env.set_byte_array_region(&buffer, 0, bytes) {
                    Ok(()) => n as jint,
                    Err(_) => -1,
                }
            }
            Err(_) => -1,
        }
    })
}

/// Input to a console: returns the number of bytes written, -1 when the session ended or the
/// console is closed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_write<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    console: jint,
    buffer: JByteArray<'l>,
    offset: jint,
    length: jint,
) -> jint {
    guarded!("Native.write", -1, {
        let mut data = vec![0i8; length.max(0) as usize];
        if env
            .get_byte_array_region(&buffer, offset, &mut data)
            .is_err()
        {
            return -1;
        }
        // SAFETY: u8 and i8 have the same layout
        let data = unsafe { std::slice::from_raw_parts(data.as_ptr().cast::<u8>(), data.len()) };
        let Some(m) = machine(handle) else {
            return -1;
        };
        let mut done = 0;
        while done < data.len() {
            match m.write_console(console as u32, &data[done..]) {
                Ok(n) => done += n,
                Err(_) => return -1,
            }
        }
        done as jint
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_resize<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    console: jint,
    rows: jint,
    cols: jint,
) {
    guarded!("Native.resize", (), {
        if let Some(m) = machine(handle) {
            let _ = m.resize_console(
                console as u32,
                rows.clamp(1, 0xffff) as u16,
                cols.clamp(1, 0xffff) as u16,
            );
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_closeConsole<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    console: jint,
) {
    guarded!("Native.closeConsole", (), {
        if let Some(m) = machine(handle) {
            let _ = m.close_console(console as u32);
        }
    })
}

/// Waits for a process to end: returns the exit code, or the negated signal number when a signal
/// terminated it (the same convention as Termux). Once the kernel is stopped, all processes have
/// been ended by SIGKILL.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_waitFor<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    pid: jint,
) -> jint {
    guarded!("Native.waitFor", -9, {
        let Some(m) = machine(handle) else {
            return -9;
        };
        match m.wait(pid as u32) {
            ExitStatus::Exited(code) => code,
            ExitStatus::Signaled { signal, .. } => -signal.number(),
        }
    })
}

/// The current directory of the console's foreground process group (where restored tabs and new
/// tabs start): null when it cannot be had (the shell has exited, the handle is invalid). A
/// path that is not UTF-8 is converted with UTF-8 replacement rules.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_consoleCwd<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    console: jint,
) -> jstring {
    guarded!("Native.consoleCwd", std::ptr::null_mut(), {
        let Some(path) = machine(handle).and_then(|m| m.console_cwd(console as u32).ok()) else {
            return std::ptr::null_mut();
        };
        env.new_string(String::from_utf8_lossy(&path))
            .map(JString::into_raw)
            .unwrap_or(std::ptr::null_mut())
    })
}

/// Drains first listens as tab-separated guest port, host port, bind IP (or `*` for a dual-stack
/// wildcard), bound interface index and process name. The final field may contain tabs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_takeListens<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
) -> jobjectArray {
    guarded!("Native.takeListens", std::ptr::null_mut(), {
        let lines: Vec<String> = machine(handle)
            .map(|m| m.take_listens())
            .unwrap_or_default()
            .iter()
            .map(|e| {
                let bind = if e.dual_stack {
                    "*".into()
                } else {
                    e.address.to_string()
                };
                format!(
                    "{}\t{}\t{}\t{}\t{}",
                    e.port, e.host_port, bind, e.interface, e.comm
                )
            })
            .collect();
        string_array(&mut env, &lines)
    })
}

/// Takes the guest processes the kernel layer ended (kernel bug, engine fault, OOM killer): each
/// entry is `<console or -1>\t<pid>\t<process name>\t<bug|oom>\t<a>\t<b>\t<c>`; for bug, a is the
/// report, b the last few lines of the kernel log and c the unimplemented paths the guest reached
/// most (both separated by U+001E); for oom, a is the cgroup (empty for the app's limit), b and c
/// the usage and the limit (bytes). Newlines, tabs and U+001E inside the fields are replaced by
/// spaces.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_takeKills<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
) -> jobjectArray {
    guarded!("Native.takeKills", std::ptr::null_mut(), {
        let clean = |s: &str| s.replace(['\t', '\n', '\r', '\u{1e}'], " ");
        let lines: Vec<String> = machine(handle)
            .map(|m| m.take_kills())
            .unwrap_or_default()
            .iter()
            .map(|k| {
                let console = k.console.map_or(-1, i64::from);
                let tail = match &k.reason {
                    appine_kernel::KillReason::Bug { report, log, stubs } => {
                        let join = |lines: &[String]| {
                            lines
                                .iter()
                                .map(|l| clean(l))
                                .collect::<Vec<_>>()
                                .join("\u{1e}")
                        };
                        format!("bug\t{}\t{}\t{}", clean(report), join(log), join(stubs))
                    }
                    appine_kernel::KillReason::OutOfMemory {
                        cgroup,
                        used,
                        limit,
                    } => format!(
                        "oom\t{}\t{used}\t{limit}",
                        clean(cgroup.as_deref().unwrap_or(""))
                    ),
                };
                format!("{console}\t{}\t{}\t{tail}", k.pid, clean(&k.comm))
            })
            .collect();
        string_array(&mut env, &lines)
    })
}

/// The latest hold of the BKL that lasted longer than the watchdog's threshold, ended or not:
/// `<id>\t<seconds>\t<report>\t<kernel log record once ended, else empty>`, or null for none.
/// Does not take the BKL, so the app can call it on the main thread while the worker waits for
/// that hold to end. Tabs and newlines inside the fields are replaced by spaces.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_bklStall<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
) -> jstring {
    guarded!("Native.bklStall", std::ptr::null_mut(), {
        let Some(s) = machine(handle).and_then(|m| m.stall()) else {
            return std::ptr::null_mut();
        };
        let clean = |s: &str| s.replace(['\t', '\n', '\r'], " ");
        let ended = s.ended.as_deref().map(clean).unwrap_or_default();
        let line = format!("{}\t{}\t{}\t{ended}", s.id, s.secs, clean(&s.report));
        env.new_string(line)
            .map(JString::into_raw)
            .unwrap_or(std::ptr::null_mut())
    })
}

/// Takes the guest's power request (busybox's reboot, halt, poweroff): 0 none, 1 reboot, 2 halt,
/// 3 power off.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_takePower<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
) -> jint {
    guarded!("Native.takePower", 0, {
        match machine(handle).and_then(|m| m.take_power()) {
            None => 0,
            Some(Power::Reboot) => 1,
            Some(Power::Halt) => 2,
            Some(Power::PowerOff) => 3,
        }
    })
}

/// Number of guest processes still running (not counting init).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_processes<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
) -> jint {
    guarded!("Native.processes", 0, {
        machine(handle).map_or(0, |m| m.processes() as jint)
    })
}

/// Guest CPU nanoseconds, including exited threads; unavailable samples must not imply idleness.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_guestCpuTime<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
) -> jlong {
    guarded!("Native.guestCpuTime", -1, {
        machine(handle).map_or(-1, |m| m.cpu_time_ns().min(jlong::MAX as u64) as jlong)
    })
}

/// Identity, size and nanosecond change times of the login rule inputs, excluding access times
/// (reading the inputs to refresh the rules must not trigger another refresh). Entry 1
/// (`/etc/passwd`, elements 4..8) is also read by `Documents.AutoRootsWatcher` to refresh Files
/// roots when `login` is `"auto"`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_loginRuleInputs<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
) -> jlongArray {
    guarded!("Native.loginRuleInputs", std::ptr::null_mut(), {
        let paths: &[&[u8]] = &[
            b"/etc",
            b"/etc/passwd",
            b"/etc/group",
            b"/etc/shadow",
            b"/var/lib/appine/admins",
        ];
        let Some(stats) = machine(handle).and_then(|m| m.rootfs_stats(paths).ok()) else {
            return std::ptr::null_mut();
        };
        let stamps: Vec<_> = stats
            .into_iter()
            .flat_map(|stat| match stat {
                Some(s) => [s.ino as jlong, s.size as jlong, s.mtime_ns, s.ctime_ns],
                None => [0; 4],
            })
            .collect();
        long_array(&env, &stamps)
    })
}

/// Takes the directories whose entries the guest changed (written as by [`browse::encode_path`],
/// that is, document IDs): the Files app refreshes them.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_takeChangedDirs<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
) -> jobjectArray {
    guarded!("Native.takeChangedDirs", std::ptr::null_mut(), {
        let dirs: Vec<String> = machine(handle)
            .map(|m| m.take_changed_dirs())
            .unwrap_or_default()
            .iter()
            .map(|d| browse::encode_path(d))
            .collect();
        string_array(&mut env, &dirs)
    })
}

/// The host is short of memory: drop caches that can be rebuilt (translations kept in the pool and
/// so on) and commit the deferred write transaction.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_trimMemory<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
) {
    guarded!("Native.trimMemory", (), {
        if let Some(m) = machine(handle) {
            m.trim_memory();
        }
    })
}

/// Shuts down ([`Machine::shutdown`]): runs the shutdown commands in inittab, sends SIGTERM to all
/// processes, ends those left, and then removes the handle from the table. Other calls keep working
/// during the shutdown commands.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_shutdown<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
) {
    guarded!("Native.shutdown", (), {
        if let Some(m) = machine(handle) {
            m.shutdown(Power::PowerOff, appine_kernel::SHUTDOWN_TIMEOUT);
        }
        // Dropped only after the table's lock is released: when this is the last reference, Machine's
        // drop syncs the store and joins threads, and every other call would wait for it
        let m = machines().remove(&handle);
        drop(m);
    })
}

/// Ends all guest processes and removes the handle from the table.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mthli_appine_Native_stop<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
) {
    guarded!("Native.stop", (), {
        // Remove first, then kill: kill_all waits, so the table's lock must not be held
        let m = machines().remove(&handle);
        if let Some(m) = m {
            m.machine.kill_all();
        }
    })
}

/// The Machine of a handle, holding a reference for the duration of the call; None once stopped.
fn machine(handle: jlong) -> Option<Arc<Machine>> {
    machines().get(&handle).map(|entry| entry.machine.clone())
}

fn machines() -> std::sync::MutexGuard<'static, BTreeMap<jlong, RegisteredMachine>> {
    MACHINES.lock().unwrap_or_else(PoisonError::into_inner)
}

// ---- JNI values and errors ----

/// Throws `java.io.IOException`; Kotlin sees it after the JNI call returns.
fn throw(env: &mut JNIEnv<'_>, message: impl AsRef<str>) {
    let _ = env.throw_new("java/io/IOException", message.as_ref());
}

fn string(env: &mut JNIEnv<'_>, s: &JString<'_>) -> Option<String> {
    env.get_string(s).ok().map(Into::into)
}

fn strings(env: &mut JNIEnv<'_>, array: &JObjectArray<'_>) -> Option<Vec<Vec<u8>>> {
    let n = env.get_array_length(array).ok()?;
    let mut out = Vec::with_capacity(n as usize);
    for i in 0..n {
        let item = JString::from(env.get_object_array_element(array, i).ok()?);
        out.push(string(env, &item)?.into_bytes());
        env.delete_local_ref(item).ok()?;
    }
    Some(out)
}

fn string_array(env: &mut JNIEnv<'_>, items: &[String]) -> jobjectArray {
    let Ok(array) = env.new_object_array(items.len() as jint, "java/lang/String", JObject::null())
    else {
        return std::ptr::null_mut();
    };
    for (i, item) in items.iter().enumerate() {
        let Ok(s) = env.new_string(item) else {
            return std::ptr::null_mut();
        };
        let set = env.set_object_array_element(&array, i as jint, &s);
        let _ = env.delete_local_ref(s);
        if set.is_err() {
            return std::ptr::null_mut();
        }
    }
    array.into_raw()
}

fn long_array(env: &JNIEnv<'_>, items: &[jlong]) -> jlongArray {
    let Ok(array) = env.new_long_array(items.len() as jint) else {
        return std::ptr::null_mut();
    };
    if env.set_long_array_region(&array, 0, items).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}
