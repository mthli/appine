package io.github.mthli.appine

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process as AndroidProcess
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.core.net.toUri
import org.json.JSONArray
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Mount state belongs to the kernel owned by TerminalService. */
internal class AndroidShare(private val context: Context, private val changed: (Map<String, Status>) -> Unit) : Closeable {
    sealed interface Source {
        val saved: String

        data class Tree(val uri: Uri) : Source {
            override val saved: String get() = "tree:$uri"
        }

        data class Root(val path: String, val write: Boolean) : Source {
            override val saved: String get() = (if (write) "rw:" else "ro:") + path
        }
    }

    data class Config(val source: Source, val target: String)

    enum class Status {
        OFF, STARTING, MOUNTED, ROOT_FAILED, TREE_FAILED, MOUNT_FAILED,
    }

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "appine-share").apply { isDaemon = true }
    }
    private val generation = AtomicLong()
    // Only the mount worker reads and writes these maps.
    private val mounts = mutableMapOf<String, Mount>()
    private val states = mutableMapOf<String, Status>()

    private class Endpoint(
        val port: Int,
        val resource: Closeable,
        val backing: LocalSocket? = null,
        val protocol: Int = 0,
    ) : Closeable by resource

    private inner class Mount(
        val machine: Long,
        val config: Config,
        val device: String,
        val endpoint: Endpoint,
        val console: Int,
        val process: Int,
        val backed: Boolean,
    ) : Closeable {
        val exit = FutureTask {
            // Drain the private console to avoid blocking a mount daemon which writes errors.
            val buffer = ByteArray(4096)
            while (Native.read(machine, console, buffer) > 0) Unit
            Native.waitFor(machine, process)
        }

        fun ready(): Boolean = command(machine, READY, config.target, device) == 0

        override fun close() {
            if (backed) runCatching { Native.setFuseBacking(machine, device, -1) }
            runCatching { command(machine, STOP, config.target, device) }
            runCatching { Native.closeConsole(machine, console) }
            runCatching { endpoint.close() }
        }
    }

    // Called on the main thread. A newer configuration cancels pending work. The worker
    // retains endpoints and consoles for shares that did not change.
    fun update(machine: Long, shares: List<Config>) {
        val n = generation.incrementAndGet()
        if (machine == 0L) changed(emptyMap())
        worker.execute {
            if (n != generation.get()) return@execute
            val desired = shares.associateBy { it.target }
            val iterator = mounts.iterator()
            while (iterator.hasNext()) {
                val (target, mount) = iterator.next()
                if (mount.machine != machine || desired[target] != mount.config) {
                    mount.close()
                    iterator.remove()
                }
            }
            states.clear()
            if (machine == 0L) return@execute
            for (share in shares) {
                states[share.target] = if (mounts.containsKey(share.target)) Status.MOUNTED else Status.STARTING
            }
            publish(n)
            for (share in shares) {
                if (n != generation.get()) break
                if (mounts.containsKey(share.target)) continue
                states[share.target] = startMount(machine, share, n)
                publish(n)
            }
        }
    }

    private fun startMount(machine: Long, share: Config, n: Long): Status {
        val source = share.source
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val endpoint = try {
            when (source) {
                is Source.Root -> startRoot(source, token, n)
                is Source.Tree -> {
                    val resolver = context.contentResolver
                    if (resolver.persistedUriPermissions.none { it.uri == source.uri && it.isReadPermission }) {
                        throw IOException("No persisted permission for the selected folder")
                    }
                    SharedDocuments(resolver, source.uri, token).let { Endpoint(it.port, it, it.backing) }
                }
            }
        } catch (_: Exception) {
            return if (source is Source.Root) Status.ROOT_FAILED else Status.TREE_FAILED
        }
        if (n != generation.get()) {
            runCatching { endpoint.close() }
            return Status.OFF
        }
        val mount = try {
            guestMount(machine, share, endpoint, token)
        } catch (_: Exception) {
            runCatching { endpoint.close() }
            return Status.MOUNT_FAILED
        }
        thread(isDaemon = true, name = "appine-share-mount") {
            mount.exit.run()
            runCatching {
                worker.execute {
                    if (mounts[share.target] === mount) {
                        mounts.remove(share.target)
                        mount.close()
                        states[share.target] = Status.MOUNT_FAILED
                        publish(generation.get())
                    }
                }
            }
        }
        val deadline = SystemClock.elapsedRealtime() + 12000
        try {
            while (n == generation.get() && !mount.exit.isDone && SystemClock.elapsedRealtime() < deadline) {
                if (mount.ready()) {
                    mounts[share.target] = mount
                    return Status.MOUNTED
                }
                Thread.sleep(150)
            }
        } catch (_: Exception) {
            // A kernel being detached can disappear during the mount probe.
        }
        mount.close()
        return Status.MOUNT_FAILED
    }

    private fun publish(n: Long) {
        val snapshot = states.toMap()
        context.mainExecutor.execute { if (generation.get() == n) changed(snapshot) }
    }

    private fun startRoot(source: Source.Root, token: String, n: Long): Endpoint {
        val name = "appine-share-fd-${UUID.randomUUID()}"
        val listener = if (source.write) null else runCatching { LocalServerSocket(name) }.getOrNull()
        listener.use {
            val args = buildList {
                addAll(listOf("-managed", "-dir", source.path, "-port", "0"))
                if (source.write) add("-write")
                if (listener != null) addAll(listOf("-fd-socket", "${AndroidProcess.myUid()}:$name"))
            }
            val process = rootHelper(context, args)
            val lease = Closeable {
                runCatching { process.outputStream.close() }
                if (!runCatching { process.waitFor(1, TimeUnit.SECONDS) }.getOrDefault(false)) process.destroy()
            }
            try {
                process.outputStream.write("$token\n".toByteArray())
                process.outputStream.flush()
                val ready = FutureTask {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.take(16).firstNotNullOfOrNull { line ->
                            if (!line.startsWith("READY ")) return@firstNotNullOfOrNull null
                            val fields = line.split(' ')
                            val port = fields.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..65535 }
                            val protocol = when (fields.getOrNull(3)) {
                                "META2" -> 2
                                "META" -> 1
                                else -> 0
                            }
                            port?.let { Triple(it, fields.getOrNull(2) == "FD", protocol) }
                        }
                    }
                }
                thread(isDaemon = true, name = "appine-share-root") { ready.run() }
                val deadline = SystemClock.elapsedRealtime() + 30000
                while (n == generation.get() && SystemClock.elapsedRealtime() < deadline) {
                    try {
                        val (port, hasBacking, protocol) = ready.get(150, TimeUnit.MILLISECONDS) ?: break
                        val backing = if (hasBacking && listener != null) {
                            runCatching { listener.accept() }.getOrNull()?.let { socket ->
                                if (runCatching { socket.peerCredentials.uid == 0 }.getOrDefault(false)) socket
                                else {
                                    socket.close()
                                    null
                                }
                            }
                        } else null
                        return Endpoint(port, Closeable {
                            runCatching { backing?.close() }
                            lease.close()
                        }, backing, if (backing != null) protocol else 0)
                    } catch (_: TimeoutException) {
                        // The root manager may wait for the user's decision.
                    }
                }
                throw IOException("The root helper did not start")
            } catch (e: Exception) {
                lease.close()
                throw e
            }
        }
    }

    private fun guestMount(machine: Long, config: Config, endpoint: Endpoint, token: String): Mount {
        val device = "appine-share-${UUID.randomUUID()}"
        val console = Native.openConsole(machine, 24, 80)
        var backed = false
        try {
            backed = endpoint.backing?.let { socket ->
                runCatching {
                    ParcelFileDescriptor.dup(socket.fileDescriptor).use {
                        Native.setFuseBacking(machine, device, it.detachFd(), endpoint.protocol)
                    }
                }.getOrDefault(false)
            } == true
            val env = ConsolePty.ENVIRONMENT + arrayOf(
                "GOMAXPROCS=2",
                "APPINE_SHARE_TOKEN=$token",
                "APPINE_SHARE_MODE=" + when (val source = config.source) {
                    is Source.Tree -> "tree"
                    is Source.Root -> if (source.write) "rw" else "ro"
                },
            ) + (if (backed) arrayOf("APPINE_SHARE_BACKING=1") else emptyArray()) +
                (if (backed && endpoint.protocol >= 1) arrayOf("APPINE_SHARE_METADATA=1") else emptyArray())
            val argv = arrayOf(HELPER, "-mount", config.target, "-port", endpoint.port.toString(), "-device", device)
            val pid = Native.spawn(machine, console, HELPER, argv, env)
            return Mount(machine, config, device, endpoint, console, pid, backed)
        } catch (e: Exception) {
            if (backed) runCatching { Native.setFuseBacking(machine, device, -1) }
            Native.closeConsole(machine, console)
            throw e
        }
    }

    override fun close() {
        generation.incrementAndGet()
        worker.execute {
            mounts.values.forEach { it.close() }
            mounts.clear()
            states.clear()
        }
        worker.shutdown()
    }

    companion object {
        const val HELPER = "/usr/local/libexec/appine-share"
        const val DEFAULT_TARGET = "/mnt/android"

        fun normalizeTarget(input: String): String? {
            if (input.any { it.isISOControl() }) return null
            val path = input.trim().ifEmpty { DEFAULT_TARGET }
            if (!path.startsWith('/') || path.encodeToByteArray().size >= 4096) return null
            val parts = path.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty() || parts.any { it == "." || it == ".." || it.encodeToByteArray().size > 255 }) return null
            return parts.joinToString("/", prefix = "/")
        }

        fun targetsConflict(first: String, second: String): Boolean =
            first == second || first.startsWith("$second/") || second.startsWith("$first/")

        fun decode(value: String?): Source? {
            val path = value?.substringAfter(':', "") ?: return null
            return when (value.substringBefore(':')) {
                "tree" -> path.toUri().takeIf { it.scheme == "content" && DocumentsContract.isTreeUri(it) }
                    ?.let { Source.Tree(it) }
                "ro", "rw" -> if (validRoot(path)) Source.Root(path, value.startsWith("rw:")) else null
                else -> null
            }
        }

        fun validRoot(path: String): Boolean = path.startsWith('/') && path.none { it.isISOControl() }

        private fun rootHelper(context: Context, arguments: List<String>): Process {
            val helper = File(context.applicationInfo.nativeLibraryDir, "libappine-share.so")
            val command = (listOf(helper.path) + arguments).joinToString(" ", transform = ::shellWord)
            return ProcessBuilder("su", "-c", "exec $command").redirectErrorStream(true).start()
        }

        /** Returns null for a denied root probe or unreadable folder. */
        fun rootDirectories(context: Context, path: String): List<String>? {
            if (!validRoot(path)) return null
            val process = runCatching { rootHelper(context, listOf("-browse", "-dir", path)) }.getOrNull() ?: return null
            val response = FutureTask {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.firstOrNull { it.startsWith("DIRS ") }?.removePrefix("DIRS ")
                }
            }
            thread(isDaemon = true, name = "appine-share-picker") { response.run() }
            return try {
                val payload = response.get(8, TimeUnit.SECONDS) ?: return null
                val array = JSONArray(payload)
                (0 until array.length()).map(array::getString).filter { name ->
                    name.isNotEmpty() && name != "." && name != ".." && '/' !in name && name.none { it.isISOControl() }
                }
            } catch (_: Exception) {
                null
            } finally {
                process.destroy()
            }
        }

        private fun shellWord(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        private fun command(machine: Long, script: String, target: String, device: String): Int =
            Native.waitFor(machine, Native.spawn(
                machine, -1, "/bin/sh", arrayOf("sh", "-c", script, "share", target, device), ConsolePty.ENVIRONMENT,
            ))

        // The source label is unique to the mount attempt. Only tear down a mount we own.
        private const val IS_MOUNTED = "grep -Fq -- \" - fuse.appine-share \$2 \" /proc/self/mountinfo"
        private const val READY = "$IS_MOUNTED && timeout 3 ls -a -- \"\$1\" >/dev/null"
        private const val STOP = "if $IS_MOUNTED; then umount -- \"\$1\" || umount -l -- \"\$1\"; fi"
    }
}
