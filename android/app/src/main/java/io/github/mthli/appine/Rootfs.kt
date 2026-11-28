package io.github.mthli.appine

import android.annotation.SuppressLint
import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.content.edit
import io.github.mthli.appine.Rootfs.BASH_HOOK
import io.github.mthli.appine.Rootfs.DEBIAN_BASH_HOOK
import io.github.mthli.appine.Rootfs.RESUMES
import io.github.mthli.appine.Rootfs.UNMARKED_PROFILE
import io.github.mthli.appine.Rootfs.ensureShellRcHooks
import io.github.mthli.appine.Rootfs.fetch
import io.github.mthli.appine.Rootfs.writeProfile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.thread
import kotlin.concurrent.write

/**
 * Importing and configuring the rootfs (the android/setup map): the first run imports the Alpine in
 * the APK, and Settings can switch to Debian (downloaded only when chosen; the version and sha256
 * are fixed here, and as with `scripts/fetch-rootfs` only a tarball whose digest matches is
 * accepted). During import, mirror probes run alongside local setup. The importer writes package
 * sources using the best available result before publishing the store for the guest to edit.
 */
internal object Rootfs {
    private const val TAG = "Rootfs"

    /**
     * A tarball to download: `urls` are several sources of the same file; the digest is fixed, so
     * any of them is equally trustworthy.
     */
    data class Download(val urls: List<String>, val sha256: String, val size: Long)

    enum class Mirror(val label: Int, val alpineHost: String, val debianHost: String = alpineHost) {
        OFFICIAL(R.string.mirror_official, "dl-cdn.alpinelinux.org", "deb.debian.org"),
        TUNA(R.string.mirror_tuna, "mirrors.tuna.tsinghua.edu.cn"),
        USTC(R.string.mirror_ustc, "mirrors.ustc.edu.cn"),
        ALIYUN(R.string.mirror_aliyun, "mirrors.aliyun.com"),
    }

    /**
     * The distros to choose from. `mirrors` is the built-in mirror list (the first one, the
     * official one, is used when probing fails), and `probe` is the file whose beginning is
     * downloaded when probing (relative to the mirror).
     */
    enum class Distro(
        val label: String,
        val download: Download?,
        val sourcesPath: String,
        val probe: String,
    ) {
        ALPINE(
            "Alpine Linux ${BuildConfig.ALPINE_BRANCH.removePrefix("v")}",
            null,
            "/etc/apk/repositories",
            "${BuildConfig.ALPINE_BRANCH}/main/aarch64/APKINDEX.tar.gz",
        ),

        /**
         * `debian:trixie` built by debuerreotype (the same build the official Docker image uses),
         * pinned to one commit. GitHub is the only source, and a direct connection from China is
         * very slow; no mirror site carries this build, and we do not host it ourselves for now.
         */
        DEBIAN(
            "Debian 13",
            Download(
                listOf(
                    "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/" +
                        "e36653f9967231373aeba7cff2102fd004ac4f4a/trixie/oci/blobs/rootfs.tar.gz",
                ),
                "dfb68c024d5cdf4872bb7f0be8d63874aa6d0b7a0050f1b06169573e34ea543a",
                49_776_179,
            ),
            "/etc/apt/sources.list.d/debian.sources",
            "debian/dists/trixie/InRelease",
        );

        fun address(mirror: Mirror): String =
            if (this == ALPINE) "https://${mirror.alpineHost}/alpine" else mirror.debianHost

        val mirrors: List<String> get() = Mirror.entries.map(::address)
    }

    /** Thrown when a download is aborted ([download]'s `cancelled` returns true). */
    class Cancelled : IOException("cancelled")

    /** The server sent more than the pinned length ([fetch]); resuming would not help. */
    private class Oversize(message: String) : IOException(message)

    /** An account in `/etc/passwd`. */
    data class Account(val name: String, val uid: Int, val gid: Int, val home: String)

    /** Result of [prepareStart]: whether `/etc/appine/browser` was written and OpenRC setup is already done. */
    data class StartPreparation(val browserReady: Boolean, val openRcDone: Boolean)

    /** Host read/write operations on a single open store session (mocked in JVM unit tests). */
    internal interface StoreAccess {
        @Throws(IOException::class)
        fun readFile(path: String): ByteArray?

        @Throws(IOException::class)
        fun writeFile(
            path: String,
            data: ByteArray,
            mode: Int = MODE_FILE,
            overwrite: Boolean = true,
            uid: Int = 0,
            gid: Int = 0,
        )
    }

    private const val MODE_FILE = 420 // 0644
    private const val MODE_EXEC = 493 // 0755

    /** The first argument that makes `login.sh` only rewrite the NOPASSWD rules ([LoginRules]). */
    private const val REFRESH_RULES = "--refresh-rules"

    /** The most bytes and time a probe downloads. */
    private const val PROBE_BYTES = 512 * 1024
    private const val PROBE_TIMEOUT_MS = 5000

    /** Download timeout: for connecting and between two reads. */
    private const val DOWNLOAD_TIMEOUT_MS = 30_000

    /** The most times a download on one source continues after breaking off. */
    private const val RESUMES = 5

    /**
     * The record (SharedPreferences): the installed distro (while there is no store, the one an
     * import was last started for).
     */
    private const val KEY_DISTRO = "distro"

    /**
     * The store's read-write lock. The kernel must be the only user of the store when it opens it;
     * only then does it do the boot cleanup (emptying `/dev/shm` and `/run`, deleting objects that
     * were not committed last time); the DocumentsProvider may open the store separately at any
     * time on binder threads, holding the read lock each time, and starting the kernel or deleting
     * the store holds the write lock.
     */
    val access = ReentrantReadWriteLock()
    /** Read and advanced under access so a staged import cannot publish into a replacement store. */
    var generation = 0L
        private set

    fun store(context: Context): File = File(context.filesDir, "rootfs")

    /** The store has been imported and configured. */
    fun isReady(context: Context): Boolean = isReady(store(context))

    private fun isReady(store: File) = File(store, "meta.db").exists()

    private fun prefs(context: Context) = context.getSharedPreferences("rootfs", Context.MODE_PRIVATE)

    /**
     * The distro installed in the app's store, null while there is no store. Recorded at import
     * (the record [importing] reads); stores imported before round 190 have no record and count as
     * Alpine (the app had not been released yet, so only development installs have such stores).
     */
    fun installed(context: Context): Distro? = if (isReady(context)) importing(context) else null

    /**
     * The distro to import when the app's store is missing: the one an import was last started for
     * (an import or distro switch the app was killed in, [prepare]), Alpine on the first run.
     */
    fun importing(context: Context): Distro {
        val name = prefs(context).getString(KEY_DISTRO, null)
        return Distro.entries.firstOrNull { it.name == name } ?: Distro.ALPINE
    }

    /**
     * Imports [distro] into store [store] (by default the one the app uses). It is first imported
     * into a temporary directory and renamed when done, so a kill midway means starting over next
     * time, with the same distro ([importing]). The tarball to download stays in the cache
     * directory once verified ([download]) and is deleted only once the store is in place, so
     * retrying a failed or killed import needs no new download. Blocks; call it on a background
     * thread.
     */
    @SuppressLint("ApplySharedPref") // The record must be on disk before the import starts
    @Throws(IOException::class)
    fun prepare(
        context: Context,
        distro: Distro,
        progress: (Long, Long) -> Unit = { _, _ -> },
        store: File = store(context),
        startMirrorProbes: (Distro) -> Map<String, Future<Double?>> = { emptyMap() },
    ) {
        if (isReady(store)) return
        // The next launch resumes this distro if the app is killed before the store is in place,
        // and once the store is there, the record is its own. Only the app's own store has one (the
        // instrumentation tests import into others)
        if (store == store(context)) prefs(context).edit(commit = true) { putString(KEY_DISTRO, distro.name) }
        val tmp = File(store.parentFile, "${store.name}.tmp")
        tmp.deleteRecursively()
        val tarball = tarball(context, distro)
        if (distro.download == null) {
            context.assets.open("rootfs.tgz").use { input ->
                tarball.outputStream().use { input.copyTo(it) }
            }
        } else {
            download(context, distro, progress)
        }
        val probes = startMirrorProbes(distro)
        try {
            Native.importRootfs(tmp.path, tarball.path)
            withStore(tmp.path) { access ->
                configure(context, access, distro)
                val mirror = selectMirror(distro, probes)
                write(access, distro.sourcesPath, distro.sources(mirror))
            }
        } finally {
            probes.values.forEach { it.cancel(true) }
        }
        // Sync before the rename: otherwise after a power loss the store could be in place while
        // its files are empty; after the rename, sync the directory too, so what is later written
        // into the store does not fall back into rootfs.tmp on a power loss and get deleted as an
        // unfinished import
        Native.syncRootfs(tmp.path)
        if (!tmp.renameTo(store)) throw IOException("cannot move the rootfs into place")
        syncDir(store.parentFile!!)
        tarball.delete()
    }

    fun tarball(context: Context, distro: Distro) = File(context.cacheDir, "${distro.name.lowercase()}.tar.gz")

    /**
     * Deletes any cached tarball (and partial download) for [distro]: called when abandoning a
     * failed import and falling back to Alpine, so the downloaded tarball does not stay in cache.
     */
    fun deleteTarball(context: Context, distro: Distro) {
        val file = tarball(context, distro)
        file.delete()
        File(file.path + ".part").delete()
    }

    @Throws(IOException::class)
    private fun configure(context: Context, access: StoreAccess, distro: Distro) {
        write(access, "/etc/hostname", "localhost\n")
        if (distro == Distro.ALPINE) {
            write(access, "/etc/hosts", "127.0.0.1\tlocalhost\n::1\tlocalhost\n", overwrite = false)
            useBash(context, access)
            cachePackages(context, access, "procps")
            cachePackages(context, access, "bubblewrap")
        } else {
            // Debian's has no hosts, and resolv.conf points at 1.1.1.1. DNS must go through the
            // kernel layer's stub (the kernel/net map) for the system's Private DNS and VPNs to
            // take effect
            write(access, "/etc/hosts", "127.0.0.1\tlocalhost\n::1\tlocalhost ip6-localhost ip6-loopback\n")
            write(access, "/etc/resolv.conf", "nameserver 127.0.0.53\n")
        }
        writeProfile(access, distro) { asset(context, it) }
        val (packages, install) = when (distro) {
            Distro.ALPINE -> "apk" to "apk add"
            Distro.DEBIAN -> "apt" to "apt install"
        }
        write(
            access, "/etc/motd",
            """
            Welcome to Appine!
            ${distro.label}, running on Android.

            Working with packages:
            - Update index:     $packages update
            - Search packages:  $packages search <package>
            - Install packages: $install <package>
            - Show help:        $packages --help

            For example, install Codex:
            $packages update
            $install nodejs npm
            npm install -g @openai/codex

            Report issues at:
            https://github.com/mthli/appine/issues
            """.trimIndent(),
        )
    }

    /** The complete package source preset shared by import and Settings. */
    private fun Distro.sources(mirror: Mirror): String {
        val address = address(mirror)
        return when (this) {
            Distro.ALPINE ->
                "$address/${BuildConfig.ALPINE_BRANCH}/main\n$address/${BuildConfig.ALPINE_BRANCH}/community\n"
            // The rootfs has no ca-certificates, so apt uses http (the packages are signed)
            Distro.DEBIAN ->
                """
                Types: deb
                URIs: http://$address/debian
                Suites: trixie trixie-updates
                Components: main
                Signed-By: /usr/share/keyrings/debian-archive-keyring.pgp

                Types: deb
                URIs: http://$address/debian-security
                Suites: trixie-security
                Components: main
                Signed-By: /usr/share/keyrings/debian-archive-keyring.pgp

                """.trimIndent()
        }
    }

    data class PackageSources(val mirror: Mirror?, val fingerprint: String)

    /** Use the same guest path for inspection and publication, including any guest mounts. */
    fun packageSources(machine: Long, distro: Distro): PackageSources {
        val fingerprint = sourceFingerprint(machine, distro.sourcesPath)
        val mirror = Mirror.entries.firstOrNull {
            val content = distro.sources(it).toByteArray()
            hex(MessageDigest.getInstance("SHA-256").digest(content)) == fingerprint
        }
        return PackageSources(mirror, fingerprint)
    }

    private fun sourceFingerprint(machine: Long, path: String): String {
        val console = Native.openConsole(machine, 1, 100)
        return try {
            val argv = arrayOf("sh", "-c", SOURCE_FINGERPRINT + "\nfingerprint \"\$1\"", "appine-mirror", path)
            val pid = Native.spawn(machine, console, "/bin/sh", argv, ConsolePty.ENVIRONMENT)
            val output = StringBuilder()
            val buf = ByteArray(128)
            while (true) {
                val n = Native.read(machine, console, buf)
                if (n <= 0) break
                if (output.length < 128) output.append(buf.decodeToString(0, n))
            }
            if (Native.waitFor(machine, pid) != 0) throw IOException("Cannot inspect package sources")
            output.toString().trim()
        } finally {
            Native.closeConsole(machine, console)
        }
    }

    // A symlink is custom even if its target contains a preset. A confirmed replacement changes
    // the directory entry itself and leaves the target file alone.
    private val SOURCE_FINGERPRINT = $$"""
        fingerprint() {
            if [ -L "$1" ]; then
                link=$(readlink "$1") || return 1
                sum=$(printf '%s' "$link" | sha256sum) || return 1
                printf 'link:%s\n' "${sum%% *}"
            elif [ -f "$1" ]; then
                sum=$(sha256sum < "$1") || return 1
                printf '%s\n' "${sum%% *}"
            elif [ ! -e "$1" ]; then
                echo missing
            else
                return 1
            fi
        }
    """.trimIndent()

    /** Publish through the running kernel so package readers see one complete file. */
    fun applyMirror(machine: Long, distro: Distro, mirror: Mirror, previous: PackageSources): Boolean {
        val argv = arrayOf(
            "sh", "-c", APPLY_MIRROR, "appine-mirror", distro.sourcesPath,
            distro.sources(mirror), previous.fingerprint,
        )
        val pid = Native.spawn(machine, -1, "/bin/sh", argv, ConsolePty.ENVIRONMENT)
        return when (val status = Native.waitFor(machine, pid)) {
            0 -> true
            3 -> false // The file changed since the picker or confirmation was shown.
            else -> throw IOException("Cannot apply package source preset (exit $status)")
        }
    }

    private val APPLY_MIRROR = SOURCE_FINGERPRINT + "\n" + $$"""
        set -eu
        path=$1
        tmp=$(mktemp "${1%/*}/.appine-mirror.XXXXXX")
        trap 'rm -f "$tmp"' EXIT
        printf '%s' "$2" > "$tmp"
        chmod 644 "$tmp"
        current=$(fingerprint "$path")
        [ "$current" = "$3" ] || exit 3
        mv -fT "$tmp" "$path"
    """.trimIndent()

    /** Copies a bundle from the APK to the guest's package cache. */
    @Throws(IOException::class)
    private fun cachePackages(context: Context, access: StoreAccess, bundle: String): Boolean {
        val packages = context.assets.list(bundle).orEmpty()
        for (name in packages) {
            cachePackage(context, access, "$bundle/$name")
        }
        return packages.isNotEmpty()
    }

    private fun cachePackage(
        context: Context,
        access: StoreAccess,
        source: String,
        target: String = source,
    ) = write(access, "/var/cache/appine/$target", asset(context, source))

    /**
     * Makes bash the login shell of a system imported from Alpine instead of busybox ash: puts the
     * packages bundled with the app (bash and the libraries it needs that the minirootfs lacks) into
     * `/var/cache/appine/bash/`, from which the login script installs them offline at the first
     * login (using `/bin/sh` until they are installed), has root's entry in `/etc/passwd` name
     * `/bin/bash`, and seeds `/etc/skel` and `/root` with `.bash_profile` and `.bashrc` so login
     * shells (`bash -l`) read `~/.bashrc`. Accounts made later with busybox's `adduser` get bash
     * too, as it gives them the shell of the account running it (`$SHELL`, which the login script
     * sets), and copy `/etc/skel`. Systems imported before keep their shells.
     */
    @Throws(IOException::class)
    private fun useBash(context: Context, access: StoreAccess) {
        if (!cachePackages(context, access, "bash")) return
        val passwd = access.readFile("/etc/passwd") ?: return
        val lines = String(passwd).split('\n').map { line ->
            val fields = line.split(':')
            if (fields.size == 7 && fields[0] == "root" && fields[6] == "/bin/sh") {
                (fields.dropLast(1) + "/bin/bash").joinToString(":")
            } else {
                line
            }
        }
        write(access, "/etc/passwd", lines.joinToString("\n"))
        ensureBashStartup(access)
    }

    /**
     * `/etc/profile.d/appine.sh`, the assets' `profile.sh`: shows the motd, sets the terminal and
     * language environment variables, and gives bash, busybox ash and zsh a coloured prompt with
     * shell integration, the OSC 133 marks the terminal finds prompts, commands and their output by (the
     * android/vtcore map). ash's marks are in `/etc/profile.d/appine.ashrc`, the assets' `ashrc.sh`,
     * which the profile names in `ENV`, so that every interactive ash reads it (a login shell after
     * `~/.profile`); bash's in `/etc/profile.d/appine.bashrc`, the assets' `bashrc.sh`, which the
     * profile reads, and on Alpine every interactive bash through `/etc/bash/appine.sh`
     * ([BASH_HOOK]); zsh's in `/etc/profile.d/appine.zshrc`, the assets' `zshrc.sh`, read by the
     * profile. They are written first, as the profile uses them only when they are there, and
     * their names do not end in `.sh`, so `/etc/profile` does not read them. Which directory the shell starts in is decided by the assets'
     * `login.sh` (restored tabs start in their original directory, the android/service map, new
     * tabs in the current tab's, the android/terminal map).
     */
    @Throws(IOException::class)
    private fun writeProfile(
        access: StoreAccess,
        distro: Distro,
        onFailure: (String, IOException) -> Unit = FAIL_FAST,
        readAsset: (String) -> ByteArray,
    ) {
        fun writeAsset(path: String, asset: String) =
            runSetupStep(path, onFailure) { write(access, path, readAsset(asset)) }
        writeAsset("/etc/profile.d/appine.ashrc", "ashrc.sh")
        writeAsset("/etc/profile.d/appine.bashrc", "bashrc.sh")
        writeAsset("/etc/profile.d/appine.zshrc", "zshrc.sh")
        writeStep(access, "/etc/appine/zsh/.zshenv", onFailure) { ZSHENV }
        if (distro == Distro.ALPINE) writeStep(access, "/etc/bash/appine.sh", onFailure) { BASH_HOOK }
        writeAsset("/etc/profile.d/appine.sh", "profile.sh")
        ensureShellRcHooks(access, distro, onFailure)
    }

    /**
     * `/etc/bash/appine.sh` on Alpine, whose `/etc/bash/bashrc` reads every `.sh` file in
     * `/etc/bash` in every interactive bash (a login one too, through
     * `/etc/profile.d/00-bashrc.sh`): bash's prompt marks for a nested bash, which reads no
     * profile. On Debian, [ensureShellRcHooks] appends [DEBIAN_BASH_HOOK] to `/etc/bash.bashrc`
     * once per store.
     */
    private const val BASH_HOOK = """# Written by Appine beside /etc/profile.d/appine.sh, and replaced together with it: Alpine's
# /etc/bash/bashrc reads this file in every interactive bash, so a nested bash (bash, su without
# -l) marks its prompts too.
if [ -r /etc/profile.d/appine.bashrc ]; then . /etc/profile.d/appine.bashrc; fi
"""

    private const val DEBIAN_BASH_HOOK = """# Added by Appine so nested interactive bash shells keep prompt marks and titles.
if [ -r /etc/profile.d/appine.bashrc ]; then . /etc/profile.d/appine.bashrc; fi
"""

    private const val ZSH_RC_HOOK = """# Added by Appine so interactive zsh shells keep prompt marks and titles.
if [[ -o interactive ]] && [[ -r /etc/profile.d/appine.zshrc ]]; then . /etc/profile.d/appine.zshrc; fi
"""

    /**
     * `/etc/appine/zsh/.zshenv`, pointed to via `ZDOTDIR` by `login.sh` for login `zsh` sessions.
     * Unsets `ZDOTDIR` immediately so user startup files and child processes never see it, sources
     * `~/.zshenv`, and on Debian (where `/etc/zsh/zprofile` does not read `/etc/profile`) sources
     * `/etc/profile.d/appine.sh` in sh emulation for interactive login shells.
     */
    private const val ZSHENV = """# Written by Appine beside /etc/profile.d/appine.sh, and replaced together with it.
'builtin' 'unset' 'ZDOTDIR'
{
    'builtin' 'typeset' __appine_zshenv="${'$'}{HOME}/.zshenv"
    [[ ! -r "${'$'}__appine_zshenv" ]] || 'builtin' 'source' '--' "${'$'}__appine_zshenv"
} always {
    if [[ -o 'interactive' ]]; then
        if [[ -o 'login' ]]; then
            if [[ ! -f /etc/alpine-release ]] && [[ -r /etc/profile.d/appine.sh ]]; then
                'builtin' 'emulate' 'sh' '-c' '. /etc/profile.d/appine.sh'
            fi
        elif [[ -r /etc/profile.d/appine.zshrc ]]; then
            'builtin' 'source' '--' /etc/profile.d/appine.zshrc
        fi
    fi
    'builtin' 'unset' '__appine_zshenv'
}
"""

    private const val DEBIAN_BASHRC_HOOK_DONE = "/var/lib/appine/debian-bashrc-hook-done"
    private const val ZSH_RC_HOOK_DONE = "/var/lib/appine/zsh-rc-hook-done"

    @Throws(IOException::class)
    private fun appendRcHook(
        access: StoreAccess,
        path: String,
        doneMarker: String,
        needle: String,
        hook: String,
    ): Boolean {
        val current = runCatching { access.readFile(path) }.getOrNull() ?: return false
        val text = current.decodeToString()
        if (!text.contains(needle)) {
            val sep = if (text.isEmpty() || text.endsWith("\n")) "\n" else "\n\n"
            write(access, path, text + sep + hook)
        }
        write(access, doneMarker, "")
        return true
    }

    /**
     * Installs interactive rc hooks once per store when the shell's own system rc file is present,
     * so nested non-login bash (Debian) and zsh (Alpine/Debian, once zsh is installed) receive
     * prompt marks and titles without conflicting with package installation or re-adding hooks a
     * user removed.
     */
    @Throws(IOException::class)
    private fun ensureShellRcHooks(
        access: StoreAccess,
        distro: Distro,
        onFailure: (String, IOException) -> Unit = FAIL_FAST,
    ) {
        if (distro == Distro.DEBIAN && readMarker(access, DEBIAN_BASHRC_HOOK_DONE, onFailure) == false) {
            runSetupStep("/etc/bash.bashrc", onFailure) {
                appendRcHook(access, "/etc/bash.bashrc", DEBIAN_BASHRC_HOOK_DONE, "/etc/profile.d/appine.bashrc", DEBIAN_BASH_HOOK)
            }
        }
        if (readMarker(access, ZSH_RC_HOOK_DONE, onFailure) == false) {
            runSetupStep("/etc/zsh/zshrc", onFailure) {
                val appended = appendRcHook(access, "/etc/zsh/zshrc", ZSH_RC_HOOK_DONE, "/etc/profile.d/appine.zshrc", ZSH_RC_HOOK)
                if (!appended &&
                    runCatching { access.readFile("/etc/zsh/zprofile") }.getOrNull() != null &&
                    !exists(access, "/etc/zsh/zshrc")
                ) {
                    write(access, "/etc/zsh/zshrc", ZSH_RC_HOOK, overwrite = false)
                    write(access, ZSH_RC_HOOK_DONE, "")
                }
            }
        }
    }

    private const val BASH_STARTUP_DONE = "/var/lib/appine/bash-startup-done"

    /**
     * `/etc/skel/.bash_profile` and `/root/.bash_profile` on Alpine: bash login shells (`bash -l`,
     * which Appine's tabs start) do not read `~/.bashrc` on their own, so source it here.
     */
    private const val BASH_PROFILE = """# Written by Appine for login bash shells.
if [ -f ~/.bashrc ] && [ -r ~/.bashrc ]; then
    . ~/.bashrc
fi
"""

    /**
     * `/etc/skel/.bashrc` and `/root/.bashrc` on Alpine: interactive guard so user additions do not
     * run in non-interactive login shells (`bash -lc`), while leaving `PS1` and history settings to
     * `/etc/profile.d/appine.sh` and `/etc/bash/bashrc`.
     */
    private const val BASHRC = """# Written by Appine for interactive bash shells.
case ${'$'}- in
    *i*) ;;
    *) return ;;
esac
"""

    /**
     * Seeds Alpine's user-level bash startup files once per store so login shells (`bash -l`) read
     * `~/.bashrc`: writes `/etc/skel/.bash_profile` and `/etc/skel/.bashrc` (copied by `adduser`
     * for accounts created later) and, for root and pre-existing regular accounts (UID 1000–65533)
     * whose login shell is `/bin/bash` and whose home directory exists, writes copies owned by each
     * account without overwriting existing files or shadowing an existing `~/.profile` or
     * `~/.bash_login`. Records completion so later deletions persist.
     */
    @Throws(IOException::class)
    fun ensureBashStartup(
        context: Context,
        onFailure: (String, IOException) -> Unit = FAIL_FAST,
    ) {
        if (installed(context) != Distro.ALPINE) return
        ensureBashStartup(store(context).path, onFailure)
    }

    /** Seeds Alpine's bash startup files in [store] once (used at import and by tests). */
    @Throws(IOException::class)
    fun ensureBashStartup(
        store: String,
        onFailure: (String, IOException) -> Unit = FAIL_FAST,
    ) {
        withStore(store) { access -> ensureBashStartup(access, onFailure) }
    }

    @Throws(IOException::class)
    private fun ensureBashStartup(
        access: StoreAccess,
        onFailure: (String, IOException) -> Unit = FAIL_FAST,
    ) {
        if (readMarker(access, BASH_STARTUP_DONE, onFailure) != false) return
        var complete = true
        if (!writeStep(access, "/etc/skel/.bash_profile", onFailure, overwrite = false) { BASH_PROFILE }) complete = false
        if (!writeStep(access, "/etc/skel/.bashrc", onFailure, overwrite = false) { BASHRC }) complete = false
        for ((account, shell) in accounts(access)) {
            if (shell != "/bin/bash" || account.gid < 0) continue
            if (account.name == ConsolePty.LOGIN_ROOT || account.uid in 1000..65533) {
                if (!seedBashHome(access, account, onFailure)) complete = false
            }
        }
        if (complete) writeStep(access, BASH_STARTUP_DONE, onFailure) { "" }
    }

    private fun seedBashHome(
        access: StoreAccess,
        account: Account,
        onFailure: (String, IOException) -> Unit,
    ): Boolean {
        val home = account.home.trimEnd('/')
        if (!home.startsWith("/") || home.isEmpty()) return true
        // Do not create missing home directories as root; symlinks are rejected below by `exists`.
        if (!hasNonRegularEntry(access, home)) return true
        var complete = true
        val hasLoginFile = listOf(".bash_profile", ".bash_login", ".profile")
            .any { exists(access, "$home/$it") }
        if (!hasLoginFile &&
            !writeStep(access, "$home/.bash_profile", onFailure, overwrite = false, uid = account.uid, gid = account.gid) { BASH_PROFILE }
        ) {
            complete = false
        }
        val bashrc = "$home/.bashrc"
        if (!exists(access, bashrc) &&
            !writeStep(access, bashrc, onFailure, overwrite = false, uid = account.uid, gid = account.gid) { BASHRC }
        ) {
            complete = false
        }
        return complete
    }

    @Throws(IOException::class)
    private fun <R> withStore(store: String, block: (StoreAccess) -> R): R {
        var result: Any? = null
        Native.withStore(store) { session ->
            val access = object : StoreAccess {
                override fun readFile(path: String): ByteArray? = Native.storeReadFile(session, path)

                override fun writeFile(
                    path: String,
                    data: ByteArray,
                    mode: Int,
                    overwrite: Boolean,
                    uid: Int,
                    gid: Int,
                ) = Native.storeWriteFile(session, path, data, mode, overwrite, uid, gid)
            }
            result = block(access)
        }
        @Suppress("UNCHECKED_CAST")
        return result as R
    }

    private val FAIL_FAST: (String, IOException) -> Unit = { _, e -> throw e }

    private fun writeStep(
        access: StoreAccess,
        path: String,
        onFailure: (String, IOException) -> Unit,
        overwrite: Boolean = true,
        uid: Int = 0,
        gid: Int = 0,
        content: () -> String,
    ): Boolean = runSetupStep(path, onFailure) {
        write(access, path, content(), overwrite, uid, gid)
    }

    internal fun runSetupStep(
        step: String,
        onFailure: (String, IOException) -> Unit,
        action: () -> Unit,
    ): Boolean = try {
        action()
        true
    } catch (e: IOException) {
        onFailure(step, e)
        false
    }

    /**
     * Reads a one-time marker: true when present, false when absent (`ENOENT`), or null when
     * another error (such as a symlink or directory at the marker or a parent) was reported to
     * [onFailure] and the caller should skip the one-time setup.
     */
    private fun readMarker(
        access: StoreAccess,
        path: String,
        onFailure: (String, IOException) -> Unit,
    ): Boolean? = try {
        access.readFile(path) != null
    } catch (e: IOException) {
        onFailure(path, e)
        null
    }

    private fun hasNonRegularEntry(access: StoreAccess, path: String): Boolean = try {
        access.readFile(path)
        false
    } catch (_: IOException) {
        true
    }

    private fun exists(access: StoreAccess, path: String): Boolean = try {
        access.readFile(path) != null
    } catch (_: IOException) {
        // Non-regular entries (such as symlinks) or non-directory parents must not be overwritten.
        true
    }

    private fun asset(context: Context, name: String): ByteArray =
        context.assets.open(name).use { it.readBytes() }

    @Throws(IOException::class)
    private fun writeIfChanged(
        access: StoreAccess,
        path: String,
        data: ByteArray,
        mode: Int = MODE_FILE,
    ) {
        val current = access.readFile(path)
        if (current != null && current.contentEquals(data)) return
        access.writeFile(path, data, mode, overwrite = true)
    }

    private fun write(
        access: StoreAccess,
        path: String,
        text: String,
        overwrite: Boolean = true,
        uid: Int = 0,
        gid: Int = 0,
    ) = write(access, path, text.toByteArray(), overwrite, uid, gid)

    private fun write(
        access: StoreAccess,
        path: String,
        data: ByteArray,
        overwrite: Boolean = true,
        uid: Int = 0,
        gid: Int = 0,
    ) = access.writeFile(path, data, MODE_FILE, overwrite, uid, gid)

    private fun write(store: String, path: String, text: String) =
        Native.writeFile(store, path, text.toByteArray(), MODE_FILE, overwrite = true)

    /** Makes renames and deletions in directory [dir] durable. */
    private fun syncDir(dir: File) {
        try {
            val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (e: ErrnoException) {
            Log.w(TAG, "cannot sync ${dir.path}", e)
        }
    }

    /** Only completed measurements count. Distro order breaks ties and puts the official fallback first. */
    private fun selectMirror(distro: Distro, probes: Map<String, Future<Double?>>): Mirror {
        val speeds = probes.filterValues { it.isDone }.mapValues { (_, result) ->
            runCatching { result.get() }.getOrNull()
        }
        val mirror = Mirror.entries.maxBy { speeds[distro.address(it)] ?: -1.0 }
        if (probes.isNotEmpty()) Log.i(TAG, "mirror speeds at import: $speeds, using ${distro.address(mirror)}")
        return mirror
    }

    /** Starts throughput probes for an import. Debian lists host names and probes them over HTTPS. */
    fun probeMirrors(distro: Distro): Map<String, Future<Double?>> {
        val mirrors = distro.mirrors
        val urls = mirrors.map { mirror ->
            val base = if (distro == Distro.ALPINE) mirror else "https://$mirror"
            "$base/${distro.probe}"
        }
        return mirrors.zip(startProbes(urls)).toMap()
    }

    /**
     * [urls] sorted by measured speed from fast to slow; those that could not be measured follow in
     * their original order. Not probed when there is only one.
     */
    private fun fastest(urls: List<String>): List<String> {
        if (urls.size < 2) return urls
        val probes = startProbes(urls)
        val speeds = try {
            probes.map { runCatching { it.get(PROBE_TIMEOUT_MS * 2L, TimeUnit.MILLISECONDS) }.getOrNull() }
        } finally {
            probes.forEach { it.cancel(true) }
        }
        Log.i(TAG, "download speeds: ${urls.zip(speeds)}")
        return urls.indices.sortedByDescending { speeds[it] ?: -1.0 }.map { urls[it] }
    }

    /** Submits one independent measurement per URL, in input order. */
    private fun startProbes(urls: List<String>): List<Future<Double?>> {
        val pool = Executors.newFixedThreadPool(urls.size)
        try {
            return urls.map { url -> pool.submit<Double?> { probe(url) } }
        } finally {
            pool.shutdown()
        }
    }

    /**
     * Downloads the beginning of [address] and returns the speed (bytes per ms, including
     * connection setup); null when not a single byte arrived. Compared by throughput rather than by
     * the time to finish a small piece: some mirrors are fast for a small piece at the start and
     * are throttled after that.
     */
    private fun probe(address: String): Double? = runCatching {
        val url = URL(address)
        val start = System.nanoTime()
        val elapsed = { (System.nanoTime() - start) / 1_000_000 }
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = PROBE_TIMEOUT_MS
        conn.readTimeout = PROBE_TIMEOUT_MS
        conn.setRequestProperty("Range", "bytes=0-${PROBE_BYTES - 1}")
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            val buf = ByteArray(16 * 1024)
            var total = 0
            conn.inputStream.use { input ->
                while (total < PROBE_BYTES && elapsed() < PROBE_TIMEOUT_MS) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
            }
            if (total == 0) null else total.toDouble() / elapsed().coerceAtLeast(1)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private const val NETWORK_CONFIG_PATH = "/etc/network/interfaces"
    private const val ROOTLESS_CONTAINERS_PATH = "/etc/containers/containers.rootless.conf.d/appine.conf"
    private const val GPU_COMMAND_PATH = "/usr/local/bin/appine-gpu"

    /**
     * Refreshes the per-start guest configuration files in [store] in a single store session before
     * starting the kernel. Fresh imports (`importing = true`) propagate the first I/O failure;
     * existing stores run each independent helper and sub-step on its own, reporting failures to
     * [onFailure] so a modified path or full disk cannot lock the user out of a repair shell.
     * Returns whether [browserConfiguration] was written to [BrowserBridge.CONFIG_PATH] and
     * whether Alpine's one-time OpenRC setup marker is already present.
     */
    @Throws(IOException::class)
    fun prepareStart(
        context: Context,
        store: String = store(context).path,
        distro: Distro = installed(context) ?: Distro.ALPINE,
        gpu: Boolean = false,
        browserConfiguration: String? = null,
        importing: Boolean = false,
        onFailure: (String, IOException) -> Unit = FAIL_FAST,
    ): StartPreparation {
        val handleFailure = if (importing) FAIL_FAST else onFailure
        var result = StartPreparation(browserReady = false, openRcDone = false)
        runSetupStep(store, handleFailure) {
            withStore(store) { access ->
                result = prepareStart(
                    access = access,
                    distro = distro,
                    gpu = gpu,
                    browserConfiguration = browserConfiguration,
                    importing = importing,
                    onFailure = onFailure,
                    readAsset = { asset(context, it) },
                )
            }
        }
        return result
    }

    @Throws(IOException::class)
    internal fun prepareStart(
        access: StoreAccess,
        distro: Distro = Distro.ALPINE,
        gpu: Boolean = false,
        browserConfiguration: String? = null,
        importing: Boolean = false,
        onFailure: (String, IOException) -> Unit = FAIL_FAST,
        readAsset: (String) -> ByteArray,
    ): StartPreparation {
        val handleFailure = if (importing) FAIL_FAST else onFailure
        runSetupStep(NETWORK_CONFIG_PATH, handleFailure) { ensureNetworkConfig(access) }
        runSetupStep(ROOTLESS_CONTAINERS_PATH, handleFailure) { ensureContainersConfig(access) }
        ensureLoginTools(access, distro, handleFailure, readAsset)
        if (distro == Distro.ALPINE) ensureBashStartup(access, handleFailure)
        if (gpu) runSetupStep(GPU_COMMAND_PATH, handleFailure) { ensureGpuCommand(access, readAsset) }
        val browserReady = browserConfiguration != null &&
            ensureBrowser(access, browserConfiguration, handleFailure, readAsset)
        updateProfile(access, distro, upgradeDoas = false, onFailure = handleFailure, readAsset = readAsset)
        // Precheck the OpenRC completion marker while the store is already open so normal Alpine
        // cold starts avoid opening a second concurrent connection right after Native.start.
        val openRcDone = distro == Distro.ALPINE && runCatching { access.readFile(OPENRC_DONE) }.getOrNull() != null
        return StartPreparation(browserReady = browserReady, openRcDone = openRcDone)
    }

    /**
     * An `/etc/network/interfaces` with only the loopback interface (written before every kernel
     * start, only when missing; one the user changed is left alone), the same as setup-alpine
     * writes. The network is the host's (the kernel/net map); services that need the network (nginx
     * and so on) have OpenRC bring up networking, which fails without this file, and then the
     * services do not start either. `rc_provide` in rc.conf is not used: it is added to every
     * service, and providing `clock` as well makes a cycle. Without OpenRC and ifupdown nobody
     * reads this file.
     */
    @Throws(IOException::class)
    fun ensureNetworkConfig(context: Context, store: String = store(context).path) =
        withStore(store) { ensureNetworkConfig(it) }

    @Throws(IOException::class)
    private fun ensureNetworkConfig(access: StoreAccess) = write(
        access, NETWORK_CONFIG_PATH,
        "auto lo\niface lo inet loopback\n",
        overwrite = false,
    )

    /**
     * The network rootless podman gives its containers by default, written before every kernel
     * start when that file is missing: the host's. Appine has no TUN device, so pasta and
     * slirp4netns, the networks podman makes for an ordinary user, cannot start, and every
     * `podman run` without `--network host` would fail (the kernel/netns map).
     * `containers.rootless.conf.d` is read only for rootless podman, so containers run as root
     * keep their bridge networks; a user's own `~/.config/containers/containers.conf` overrides it.
     * podman's overlay storage driver and docker's overlayfs snapshotter, the packages' defaults,
     * run on the kernel's overlayfs.
     */
    @Throws(IOException::class)
    fun ensureContainersConfig(context: Context, store: String = store(context).path) {
        withStore(store) { ensureContainersConfig(it) }
    }

    @Throws(IOException::class)
    private fun ensureContainersConfig(access: StoreAccess) {
        write(access, ROOTLESS_CONTAINERS_PATH, ROOTLESS_CONTAINERS, overwrite = false)
    }

    private const val ROOTLESS_CONTAINERS = """# Written by Appine when missing: Appine has no TUN device, so pasta and slirp4netns (the
# networks podman makes for an ordinary user) cannot start, and rootless containers use the
# host's network instead: they are not isolated from the host or from each other, and port
# mappings (-p) are dropped. Containers run as root keep their bridge networks, port mappings and
# lookups by container name, so run podman as root for those (sudo podman; doas podman on Alpine).
# Root gives up little here: every user, root included, runs inside the Android app's sandbox.
# To change the default, edit this file or set netns in ~/.config/containers/containers.conf.
[containers]
netns = "host"
"""

    /** Installs the per-command Venus launcher on new and existing stores. */
    @Throws(IOException::class)
    fun ensureGpuCommand(context: Context, store: String = store(context).path) =
        withStore(store) { ensureGpuCommand(it) { name -> asset(context, name) } }

    @Throws(IOException::class)
    private fun ensureGpuCommand(access: StoreAccess, readAsset: (String) -> ByteArray) {
        val existing = try { access.readFile(GPU_COMMAND_PATH) } catch (_: IOException) { null }
        if (existing == null) {
            access.writeFile(GPU_COMMAND_PATH, readAsset("appine-gpu.sh"), MODE_EXEC, overwrite = false)
        }
    }

    /**
     * Refreshes the bridge before kernel start, preserving user-provided local launchers.
     * Returns whether the endpoint configuration ([BrowserBridge.CONFIG_PATH]) was written.
     */
    @Throws(IOException::class)
    fun ensureBrowser(
        context: Context,
        configuration: String,
        store: String = store(context).path,
        onFailure: (String, IOException) -> Unit = FAIL_FAST,
    ): Boolean = withStore(store) { access ->
        ensureBrowser(access, configuration, onFailure) { asset(context, it) }
    }

    @Throws(IOException::class)
    private fun ensureBrowser(
        access: StoreAccess,
        configuration: String,
        onFailure: (String, IOException) -> Unit,
        readAsset: (String) -> ByteArray,
    ): Boolean {
        val openUrl = "/usr/local/libexec/appine-open-url"
        runSetupStep(openUrl, onFailure) {
            writeIfChanged(access, openUrl, readAsset("appine-open-url"), MODE_EXEC)
        }
        val configured = writeStep(access, BrowserBridge.CONFIG_PATH, onFailure) { configuration }
        val xdgOpen = "/usr/local/bin/xdg-open"
        runSetupStep(xdgOpen, onFailure) {
            val old = try { access.readFile(xdgOpen) } catch (_: IOException) { null }
            // Replace the previous Appine OSC helper, but leave a user's own launcher alone.
            val replace = old != null && hex(MessageDigest.getInstance("SHA-256").digest(old)) ==
                "abaf8d811e48df794b931f2fd5876e255c2c430464081e0d9fe880b80a3a1d76"
            if (old == null || replace) {
                access.writeFile(xdgOpen, readAsset("xdg-open.sh"), MODE_EXEC, overwrite = replace)
            }
        }
        return configured
    }

    private const val DOAS_WHEEL_PATH = "/etc/doas.d/20-wheel.conf"
    private const val OLD_DOAS_WHEEL_RULE = "permit persist :wheel"
    private const val DOAS_WHEEL_RULE = "permit persist setenv { ENV=/etc/profile.d/appine.ashrc } :wheel"

    /**
     * Upgrades an unmodified Appine `/etc/doas.d/20-wheel.conf` on Alpine so `doas -s` preserves
     * busybox ash's `ENV` hook without touching user-customized doas rules.
     */
    @Throws(IOException::class)
    private fun ensureDoasEnv(access: StoreAccess) {
        val current = runCatching { access.readFile(DOAS_WHEEL_PATH) }.getOrNull() ?: return
        if (current.decodeToString().trimEnd('\n') == OLD_DOAS_WHEEL_RULE) {
            write(access, DOAS_WHEEL_PATH, "$DOAS_WHEEL_RULE\n")
        }
    }

    /**
     * On Alpine, puts the doas package bundled with the app into the store
     * (`/var/cache/appine/doas.apk`): the login script installs it offline when a regular user
     * first becomes the login account (the android/setup map). Written before every kernel start,
     * so it is replaced by the new one after an app upgrade.
     */
    @Throws(IOException::class)
    fun ensureLoginTools(
        context: Context,
        store: String = store(context).path,
        distro: Distro? = installed(context),
        onFailure: (String, IOException) -> Unit = FAIL_FAST,
    ) {
        if (distro != Distro.ALPINE) return
        withStore(store) { access ->
            ensureLoginTools(access, distro, onFailure) { asset(context, it) }
        }
    }

    @Throws(IOException::class)
    private fun ensureLoginTools(
        access: StoreAccess,
        distro: Distro?,
        onFailure: (String, IOException) -> Unit,
        readAsset: (String) -> ByteArray,
    ) {
        if (distro != Distro.ALPINE) return
        runSetupStep("/var/cache/appine/doas.apk", onFailure) {
            writeIfChanged(access, "/var/cache/appine/doas.apk", readAsset("doas.apk"))
        }
        runSetupStep(DOAS_WHEEL_PATH, onFailure) { ensureDoasEnv(access) }
    }

    /**
     * The copy written from round 194 until shell integration came: the coloured prompt for any
     * shell (zsh and dash showed its bash escapes as they are), without prompt marks.
     */
    private val UNMARKED_PROFILE = """
        cat /etc/motd 2>/dev/null
        export TERM=xterm-256color
        export LANG=C.UTF-8
        export NMAP_UNPRIVILEGED=1
        PS1='\[\e[1;32m\]\u@\h\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]\$ '

    """.trimIndent()

    /**
     * The copy written before round 194: [UNMARKED_PROFILE] with one more line, `cd "$HOME"`, which
     * took restored tabs back to the home directory.
     */
    private val OLD_PROFILE = UNMARKED_PROFILE.trimEnd('\n') + "\ncd \"${'$'}HOME\" 2>/dev/null\n"

    /**
     * The sha256s of the assets' `profile.sh` as later versions replaced it, whose texts (shell
     * code full of `$`) are not kept here: the first with prompt marks, until taps on the prompt
     * moved the cursor, the one that marked them for bash and busybox ash only, until zsh got
     * its marks, the one with ash's marks in itself, until `ashrc.sh` took them, and the one with
     * bash's and zsh's marks in itself, until `bashrc.sh` and `zshrc.sh` took them. A change to
     * `profile.sh` adds the sha256 of the text it replaces
     * (`git show HEAD:android/app/src/main/assets/profile.sh | shasum -a 256` before committing).
     */
    private val REPLACED_PROFILES = setOf(
        "4a35d44e55e23aaf94543d87dc02deafcc7b324902e90ae18872fe20242642fc",
        "d4daedd69558e1efe41b143f71b74b67708388299584706a9bf55052a7fc3f21",
        "b6dbef9f24826d036570704f8ed15439375032f0f72163e3c6cd219ef5e83336",
        "86785a156574627210ded386f59ca574cb69292672688287c7b7dc7c8e7a4233",
        "747208df567cbf62252e21522712b96acb79564eafa8062ce08a1b159e1e4835",
        "2085a60332217920354a7979b98becad8a114e413724c1180954e3424d7157c5",
        "921a9cbb766e8427950aa5ff7a55e253f7e62b3b25997fcad6410beabb62643c",
        "3257013eafeb90230ae0d6772e73d8af87be62f8d6f74460dd4189ea56271021",
        "a8ab98eda14fd8438be521001373eb177b37c0d7229b288a2ae46298ff55b972",
        "bc9b601ec28f299ce3277bdc9187660e756b2ad47d12fa8d29dd5bda96f632c1",
        "2d5e818130b598104b81a5265e27d3e98b2459cfc109e2c77685e7725dc26860",
        "dea18539e7abc28cddf05d43641622e2e3ddb71bd56b3032c84d64fbd0593f0f",
    )

    /**
     * Replaces the profile the app wrote into a previously imported store with the current one
     * ([writeProfile], with the files of ash's, bash's and zsh's marks), if it is an earlier version left
     * unchanged. A change to `ashrc.sh`, `bashrc.sh` or `zshrc.sh` alone reaches no existing store: change
     * `profile.sh` with it. Also installs interactive rc hooks once per store when the shell's
     * system rc file is present ([ensureShellRcHooks]).
     */
    @Throws(IOException::class)
    fun updateProfile(
        context: Context,
        store: String = store(context).path,
        distro: Distro = installed(context) ?: Distro.ALPINE,
        onFailure: (String, IOException) -> Unit = FAIL_FAST,
    ) = withStore(store) { access ->
        updateProfile(access, distro, upgradeDoas = true, onFailure = onFailure) { asset(context, it) }
    }

    @Throws(IOException::class)
    private fun updateProfile(
        access: StoreAccess,
        distro: Distro,
        upgradeDoas: Boolean,
        onFailure: (String, IOException) -> Unit,
        readAsset: (String) -> ByteArray,
    ) {
        if (upgradeDoas && distro == Distro.ALPINE) {
            runSetupStep(DOAS_WHEEL_PATH, onFailure) { ensureDoasEnv(access) }
        }
        val path = "/etc/profile.d/appine.sh"
        val old = runCatching { access.readFile(path) }.getOrNull() ?: return
        val earlier = String(old) in listOf(OLD_PROFILE, UNMARKED_PROFILE) ||
            hex(MessageDigest.getInstance("SHA-256").digest(old)) in REPLACED_PROFILES
        if (earlier) {
            writeProfile(access, distro, onFailure, readAsset)
        } else {
            ensureShellRcHooks(access, distro, onFailure)
        }
    }

    /**
     * Run in the guest before every boot (`sh -c`): when OpenRC is installed and none of
     * hwclock/osclock/swclock is in any runlevel, adds osclock to boot. busybox's syslog does
     * `need clock`, and OpenRC provides clock with hwclock by default, which cannot read an RTC or
     * set the host's time, so the boot log always has a failure; osclock means "the operating
     * system manages the time" and does nothing (the kernel/process map). Whichever clock service
     * the user put in place is left alone.
     */
    const val PREFER_OSCLOCK = "[ -x /sbin/rc-update ] && [ -e /etc/init.d/osclock ] || exit 0; " +
        "for s in hwclock osclock swclock; do for l in /etc/runlevels/*/\$s; do " +
        "[ -e \"\$l\" ] || [ -L \"\$l\" ] && exit 0; done; done; " +
        "exec /sbin/rc-update -q add osclock boot >/dev/null 2>&1"

    private const val OPENRC_INSTALL = "/var/cache/appine/install-openrc.sh"
    private const val OPENRC_DONE = "/var/lib/appine/openrc-setup-done"

    private fun runBootCommand(machine: Long, command: String): Int {
        val argv = arrayOf("sh", "-c", command)
        val pid = Native.spawn(machine, -1, "/bin/sh", argv, ConsolePty.ENVIRONMENT)
        return Native.waitFor(machine, pid)
    }

    /** Prefers the guest clock and runs the existing inittab. */
    fun boot(machine: Long) {
        runCatching { runBootCommand(machine, PREFER_OSCLOCK) }
            .onFailure { Log.w(TAG, "cannot prefer osclock", it) }
        Native.boot(machine)
    }

    private fun needsOpenRcSetup(machine: Long, store: String): Boolean {
        if (Native.readFile(store, OPENRC_DONE) != null) return false
        // A staged installer from this or an older build must finish even if apk installed
        // openrc before the process was killed.
        if (Native.readFile(store, OPENRC_INSTALL) != null) return true

        val probe = "apk --no-network --repositories-file /dev/null info -e openrc >/dev/null"
        return when (val status = runBootCommand(machine, probe)) {
            0 -> {
                write(store, OPENRC_DONE, "")
                false
            }
            1 -> true // Package missing
            else -> throw IOException("Cannot check for OpenRC (exit $status)")
        }
    }

    private fun installOpenRc(context: Context, machine: Long, store: String) {
        // Rebuild this private cache on retry, including after an app update with new pins.
        if (runBootCommand(machine, "rm -rf /var/cache/appine/openrc") != 0) {
            throw IOException("Cannot prepare the OpenRC package cache")
        }
        withStore(store) { access ->
            cachePackages(context, access, "openrc")
            val libcap = context.assets.list("bubblewrap").orEmpty().single { it.startsWith("libcap2-") }
            cachePackage(context, access, "bubblewrap/$libcap", "openrc/$libcap")
            write(access, OPENRC_INSTALL, asset(context, "install-openrc.sh"))
        }
        val logPath = "/var/log/appine-openrc.log"
        val status = runBootCommand(machine, "sh $OPENRC_INSTALL >$logPath 2>&1")
        if (status != 0) {
            val log = Native.readFile(store, logPath)?.decodeToString()?.trim().orEmpty()
            throw IOException("Cannot install OpenRC (exit $status)\n$log".trimEnd())
        }
    }

    /**
     * Checks OpenRC once per Alpine store. Returns whether inittab ran, even if verification
     * failed and the caller lets an existing store open a terminal to repair its configuration.
     */
    fun ensureOpenRc(
        context: Context, machine: Long, store: String, onFailure: (IOException) -> Unit = { throw it },
    ): Boolean {
        var booted = false
        try {
            if (!needsOpenRcSetup(machine, store)) return false
            installOpenRc(context, machine, store)
            boot(machine)
            booted = true
            val finish = """
                set -e
                test -d /run/openrc/exclusive
                test "$(cat /run/openrc/softlevel)" = default
                rm -rf /var/cache/appine/openrc
                rm -f $OPENRC_INSTALL
            """.trimIndent()
            if (runBootCommand(machine, finish) != 0) {
                throw IOException("Cannot finish OpenRC setup. See /var/log/boot.log")
            }
            write(store, OPENRC_DONE, "")
        } catch (e: IOException) {
            onFailure(e)
        }
        return booted
    }

    /**
     * The services that did not start at this boot: OpenRC writes
     * ` * ERROR: <service> failed to start` into `/var/log/boot.log` (the kernel empties this file
     * at every boot, the kernel/process map). Empty without OpenRC or when it cannot be read.
     */
    fun failedServices(context: Context): List<String> {
        val log = runCatching { Native.readFile(store(context).path, "/var/log/boot.log") }.getOrNull()
            ?: return emptyList()
        return Regex("""ERROR: (\S+) failed to start""").findAll(String(log)).map { it.groupValues[1] }.distinct().toList()
    }

    /**
     * Which account the login setting [login] ([ConsolePty.LOGIN_AUTO], [ConsolePty.LOGIN_ROOT] or
     * an account name) actually is: the rules are the same as in `login.sh`; automatic is the first
     * regular user created, or root when there is none, and a named account that no longer exists
     * is root too. When `/etc/passwd` cannot be read, it is root with home directory `/root`.
     */
    fun loginAccount(context: Context, login: String): Account {
        val entries = accounts(context)
        val all = entries.map { it.first }
        val root = all.firstOrNull { it.name == ConsolePty.LOGIN_ROOT } ?: Account(ConsolePty.LOGIN_ROOT, 0, 0, "/root")
        return when (login) {
            ConsolePty.LOGIN_AUTO -> regularAccounts(entries).firstOrNull() ?: root
            ConsolePty.LOGIN_ROOT -> root
            else -> all.firstOrNull { it.name == login } ?: root
        }
    }

    /**
     * The regular users that can be the login account: UID 1000–65533 with a login shell (not
     * nologin or false), sorted by UID; the first one is the one logged in "automatically". The
     * rules are the same as in the assets' `login.sh`. Empty when it cannot be read.
     */
    fun regularAccounts(context: Context): List<Account> = regularAccounts(accounts(context))

    private fun regularAccounts(entries: List<Pair<Account, String>>): List<Account> = entries.mapNotNull { (account, shell) ->
        if (shell.isEmpty() || shell.endsWith("/nologin") || shell.endsWith("/false")) return@mapNotNull null
        if (account.uid !in 1000..65533) return@mapNotNull null
        account
    }.sortedBy { it.uid }

    /** The accounts in `/etc/passwd`, empty when it cannot be read. */
    private fun accounts(context: Context): List<Pair<Account, String>> =
        parsePasswd(runCatching { Native.readFile(store(context).path, "/etc/passwd") }.getOrNull())

    private fun accounts(access: StoreAccess): List<Pair<Account, String>> =
        parsePasswd(runCatching { access.readFile("/etc/passwd") }.getOrNull())

    private fun parsePasswd(passwd: ByteArray?): List<Pair<Account, String>> {
        if (passwd == null) return emptyList()
        return String(passwd).lines().mapNotNull { line ->
            val f = line.split(':')
            if (f.size < 7) return@mapNotNull null
            val uid = f[2].toIntOrNull() ?: return@mapNotNull null
            val gid = f[3].toIntOrNull() ?: return@mapNotNull null
            Account(f[0], uid, gid, f[5]) to f[6]
        }
    }

    /**
     * Per-machine state, polled by the service worker. A guest waiting on the rules lock must
     * not block the worker or shutdown. Only a completed refresh acknowledges its input sample.
     */
    class LoginRules(private val machine: Long) {
        @Volatile
        private var appliedInputs: LongArray? = null
        private var pendingRefresh: Thread? = null

        fun poll(context: Context): Thread? {
            if (pendingRefresh?.isAlive == true) return null
            val inputs = Native.loginRuleInputs(machine)
            if (inputs != null && inputs.contentEquals(appliedInputs)) return null
            appliedInputs = null
            val pid = try {
                val argv = ConsolePty.argv(ConsolePty.script(context), REFRESH_RULES)
                Native.spawn(machine, -1, "/bin/sh", argv, ConsolePty.ENVIRONMENT)
            } catch (e: IOException) {
                Log.w(TAG, "cannot start login rule refresh", e)
                return null
            }
            pendingRefresh = thread(name = "appine-login-rules") {
                if (Native.waitFor(machine, pid) == 0) appliedInputs = inputs
            }
            return pendingRefresh
        }
    }

    /**
     * Downloads the tarball of [distro] into the cache directory, unless a verified one is already
     * in the cache. While downloading, [progress] receives the bytes downloaded so far and in
     * total; when [cancelled] returns true the download aborts, throwing [Cancelled]. Blocks; call
     * it on a background thread.
     */
    @Throws(IOException::class)
    fun download(
        context: Context,
        distro: Distro,
        progress: (Long, Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ) {
        val download = distro.download ?: return
        val tarball = tarball(context, distro)
        if (tarball.length() == download.size && sha256(tarball) == download.sha256) return
        fetch(download, tarball, cancelled, progress)
    }

    /**
     * Downloads [download] to [dest]: with several sources their speed is probed first, and they
     * are tried from the fastest, moving to the next one when one fails.
     * The sha256 is computed while writing, and on a mismatch (or a wrong length) the file is
     * deleted and an exception thrown; it is written to `.part` first and renamed only once
     * verified.
     * When [cancelled] returns true no other source is tried, and [Cancelled] is thrown.
     */
    @Throws(IOException::class)
    fun fetch(
        download: Download,
        dest: File,
        cancelled: () -> Boolean = { false },
        progress: (Long, Long) -> Unit,
    ) {
        var error: IOException? = null
        for (url in fastest(download.urls)) {
            if (cancelled()) throw Cancelled()
            try {
                Log.i(TAG, "downloading $url")
                return fetch(url, download, dest, progress, cancelled)
            } catch (e: Cancelled) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "cannot download $url", e)
                error = e
            }
        }
        throw error ?: IOException("nowhere to download from")
    }

    /**
     * Downloads from [url]. When a timeout or a dropped connection interrupts it, it continues from
     * where it broke off with `Range` (counted only when this attempt got data, at most [RESUMES]
     * times); when the server does not support resuming (it replies 200), it starts over. An attempt
     * that got no data ends this source, and so does an error status on a resume.
     */
    @Throws(IOException::class)
    private fun fetch(
        url: String,
        download: Download,
        dest: File,
        progress: (Long, Long) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val part = File(dest.path + ".part")
        var digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        var reported = 0L
        var resumes = 0
        try {
            while (true) {
                val conn = URL(url).openConnection()
                conn.connectTimeout = DOWNLOAD_TIMEOUT_MS
                conn.readTimeout = DOWNLOAD_TIMEOUT_MS
                val http = conn as? HttpURLConnection
                if (done > 0) conn.setRequestProperty("Range", "bytes=$done-")
                val before = done
                try {
                    if (done > 0) when (val code = http?.responseCode) {
                        HttpURLConnection.HTTP_PARTIAL -> {}
                        HttpURLConnection.HTTP_OK -> {
                            done = 0
                            reported = 0
                            digest = MessageDigest.getInstance("SHA-256")
                        }
                        // Keeps what it has, so this counts as an attempt that got no data
                        else -> throw IOException("$url: HTTP $code on resuming at $done bytes")
                    }
                    conn.getInputStream().use { input ->
                        FileOutputStream(part, done > 0).use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (cancelled()) throw Cancelled()
                                val n = input.read(buf)
                                if (n < 0) break
                                // A server sending more than the pinned length is wrong whatever
                                // follows: stop rather than fill the cache
                                if (done + n > download.size) throw Oversize("$url: more than ${download.size} bytes")
                                digest.update(buf, 0, n)
                                output.write(buf, 0, n)
                                done += n
                                // Progress is reported about two hundred times at most
                                if (done - reported >= download.size / 200 || done == download.size) {
                                    reported = done
                                    progress(done, download.size)
                                }
                            }
                        }
                    }
                    break
                } catch (e: IOException) {
                    if (e is Cancelled || e is Oversize || http == null || done == before || ++resumes > RESUMES) throw e
                    // Everything arrived before it broke off: there is nothing left to ask for (a
                    // Range starting at the end is answered 416), only to verify
                    if (done == download.size) break
                    Log.w(TAG, "download of $url broke off at $done bytes, resuming", e)
                } finally {
                    http?.disconnect()
                }
            }
            val got = hex(digest.digest())
            if (done != download.size || got != download.sha256) {
                throw IOException("$url: got $done bytes with sha256 $got")
            }
            if (!part.renameTo(dest)) throw IOException("cannot move the download into place")
        } finally {
            part.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** Deletes the app's store (distro switch, rootfs reset). The kernel must be stopped first. */
    fun reset(context: Context) = access.write {
        generation++
        File(context.filesDir, "rootfs.tmp").deleteRecursively()
        if (!store(context).deleteRecursively()) Log.w(TAG, "cannot delete all of the rootfs")
    }
}
