package io.github.mthli.appine

import android.content.ContentResolver
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.Os
import android.system.OsConstants
import android.util.LruCache
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** A read-only HTTP bridge to the selected document tree. */
internal class SharedDocuments(
    private val resolver: ContentResolver,
    private val tree: Uri,
    token: String,
) : Closeable {
    private val top = DocumentsContract.getTreeDocumentId(tree)
    private val authorization = "Basic " + Base64.getEncoder().encodeToString("appine:$token".toByteArray())
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(16))
    val port: Int get() = listener.localPort
    private val folders = LruCache<String, Folder>(16)
    // One-shot tickets from authenticated opens. Evict unused tickets without retaining open files.
    private val pending = LruCache<Long, Uri>(64)
    private val nextTicket = AtomicLong()
    val backing: LocalSocket? = startBacking()

    private data class Folder(val queriedAt: Long, val entries: Map<String, Entry>)

    private data class Entry(val id: String, val name: String, val directory: Boolean, val size: Long, val changed: Long) {
        fun json() = JSONObject().put("name", name).put("dir", directory).put("size", size).put("mtime", changed)
    }

    init {
        thread(isDaemon = true, name = "appine-share-documents") {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: IOException) { break }
                clients.add(socket)
                try {
                    workers.execute {
                        try {
                            socket.use { serve(it) }
                        } catch (_: IOException) {
                            // Includes a client closing a streaming response early.
                        } finally {
                            clients.remove(socket)
                        }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    clients.remove(socket)
                    socket.close()
                }
            }
        }
    }

    private fun startBacking(): LocalSocket? = runCatching {
        val name = "appine-tree-fd-${UUID.randomUUID()}"
        val client = LocalSocket()
        try {
            LocalServerSocket(name).use { listener ->
                client.connect(LocalSocketAddress(name))
                val server = listener.accept()
                try {
                    // Both endpoints belong to this process; no other app may redeem a ticket.
                    if (server.peerCredentials.pid != Process.myPid()) throw IOException("Invalid share socket peer")
                    thread(isDaemon = true, name = "appine-share-tree-fd") {
                        server.use {
                            try {
                                sendDescriptors(it)
                            } catch (_: IOException) {
                                // The mount stopped or the peer fell back to HTTP reads.
                            }
                        }
                    }
                } catch (e: Exception) {
                    server.close()
                    throw e
                }
            }
            client
        } catch (e: Exception) {
            client.close()
            throw e
        }
    }.getOrNull()

    private fun sendDescriptors(socket: LocalSocket) {
        val input = DataInputStream(socket.inputStream)
        val output = socket.outputStream
        while (true) {
            val uri = pending.remove(java.lang.Long.reverseBytes(input.readLong()))
            val file = uri?.let(::openSeekable)
            file.use {
                if (it != null) socket.setFileDescriptorsForSend(arrayOf(it.fileDescriptor))
                output.write(if (it != null) 1 else 0)
            }
        }
    }

    private fun openSeekable(uri: Uri): ParcelFileDescriptor? {
        val file = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
        val seekable = runCatching {
            val fd = file.fileDescriptor
            val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
            if (flags and OsConstants.O_ACCMODE != OsConstants.O_RDONLY || !OsConstants.S_ISREG(Os.fstat(fd).st_mode)) {
                false
            } else {
                // Some providers return streams or descriptors without positional reads.
                Os.pread(fd, ByteArray(1), 0, 1, 0)
                true
            }
        }.getOrDefault(false)
        if (seekable) return file
        file.close()
        return null
    }

    private fun list(parent: String): Map<String, Entry> {
        val cached = folders.get(parent)
        if (cached != null && SystemClock.elapsedRealtime() - cached.queriedAt < 2000) return cached.entries
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        val cursor = resolver.query(uri, columns, null, null, null) ?: throw IOException("Cannot list the folder")
        val entries = cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val name = it.getString(1) ?: continue
                    if (name.isEmpty() || name == "." || name == ".." || name.contains('/')) continue
                    add(Entry(it.getString(0), name, it.getString(2) == Document.MIME_TYPE_DIR,
                        if (it.isNull(3)) -1L else it.getLong(3), it.getLong(4)))
                }
            }.distinctBy { it.name }.associateBy { it.name }
        }
        folders.put(parent, Folder(SystemClock.elapsedRealtime(), entries))
        return entries
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream().buffered()
        val output = socket.getOutputStream().buffered()
        val deadline = SystemClock.elapsedRealtime() + 5000
        var headerBytes = 0
        fun line(): String {
            val bytes = StringBuilder()
            while (headerBytes++ < 16384) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) throw IOException("Request timeout")
                socket.soTimeout = remaining.toInt()
                val c = input.read()
                if (c < 0) throw EOFException()
                if (c == 10) return bytes.toString().trimEnd('\r')
                bytes.append(c.toChar())
            }
            throw IOException("Request too large")
        }
        val request = line().split(' ', limit = 3)
        if (request.size != 3 || request[0] !in listOf("GET", "HEAD")) return
        val head = request[0] == "HEAD"
        var auth = ""
        var range: String? = null
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            val key = header.substringBefore(':').lowercase(Locale.ROOT)
            val value = header.substringAfter(':', "").trim()
            if (key == "authorization") auth = value
            if (key == "range") range = value
        }
        fun headers(status: String, length: Long = 0, extra: String = "") {
            output.write(("HTTP/1.1 $status\r\nConnection: close\r\nContent-Length: $length\r\n$extra\r\n").toByteArray())
        }
        fun reject(status: String) {
            headers(status)
            output.flush()
        }
        if (!MessageDigest.isEqual(auth.toByteArray(), authorization.toByteArray())) {
            reject("401 Unauthorized")
            return
        }

        val op = request[1].toUri().getQueryParameter("op")
        val path = request[1].substringBefore('?')
        if (!path.startsWith('/')) {
            reject("400 Bad Request")
            return
        }
        val names = path.trim('/').split('/').filter { it.isNotEmpty() }.map(Uri::decode)
        if (names.any { it == "." || it == ".." || it.contains('/') }) {
            reject("404 Not Found")
            return
        }
        try {
            var node = Entry(top, "", true, 0, 0)
            for (name in names) {
                val child = if (node.directory) list(node.id)[name] else null
                if (child == null) {
                    reject("404 Not Found")
                    return
                }
                node = child
            }
            when {
                op == "stat" || op == "list" && node.directory -> {
                    val json = if (op == "stat") node.json() else JSONArray(list(node.id).values.map { it.json() })
                    val body = json.toString().toByteArray()
                    headers("200 OK", body.size.toLong(), "Content-Type: application/json\r\n")
                    if (!head) output.write(body)
                    output.flush()
                    return
                }
                op == "backing" && !node.directory && !head && backing != null -> {
                    val ticket = nextTicket.incrementAndGet()
                    pending.put(ticket, DocumentsContract.buildDocumentUriUsingTree(tree, node.id))
                    val body = ticket.toString().toByteArray()
                    headers("200 OK", body.size.toLong(), "Content-Type: application/json\r\n")
                    output.write(body)
                    output.flush()
                    return
                }
                op != "read" || node.directory -> {
                    reject("403 Forbidden")
                    return
                }
            }
            if (node.size < 0) {
                reject("409 Conflict")
                return
            }
            val chunk = range?.let { parseRange(it, node.size) }
            if (range != null && chunk == null) {
                headers("416 Range Not Satisfiable", extra = "Content-Range: bytes */${node.size}\r\n")
                output.flush()
                return
            }
            val count = chunk?.let { it.last - it.first + 1 } ?: node.size
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, node.id)
            val content = if (head || count == 0L) null else resolver.openInputStream(uri) ?: throw IOException("Cannot read document")
            content.use { stream ->
                if (stream != null && chunk != null) skip(stream, chunk.first)
                val changed = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }.format(Date(node.changed))
                val extra = "Accept-Ranges: bytes\r\nLast-Modified: $changed\r\nContent-Type: application/octet-stream\r\n"
                if (chunk == null) headers("200 OK", count, extra)
                else headers("206 Partial Content", count, extra + "Content-Range: bytes ${chunk.first}-${chunk.last}/${node.size}\r\n")
                if (stream != null) {
                    val buffer = ByteArray(64 * 1024)
                    var left = count
                    while (left > 0) {
                        val n = stream.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        left -= n
                    }
                }
                output.flush()
            }
        } catch (_: SecurityException) {
            reject("403 Forbidden")
        } catch (_: IllegalArgumentException) {
            reject("404 Not Found")
        }
    }

    override fun close() {
        backing?.let {
            runCatching { it.shutdownOutput() }
            runCatching { it.close() }
        }
        pending.evictAll()
        listener.close()
        workers.shutdownNow()
        clients.forEach { runCatching { it.close() } }
    }

    companion object {
        internal fun parseRange(value: String, size: Long): LongRange? {
            if (!value.startsWith("bytes=") || size == 0L) return null
            val bounds = value.removePrefix("bytes=").split('-', limit = 2)
            if (bounds.size != 2) return null
            if (bounds[0].isEmpty()) {
                val suffix = bounds[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
                return (size - minOf(size, suffix)) until size
            }
            val first = bounds[0].toLongOrNull()?.takeIf { it in 0 until size } ?: return null
            val last = if (bounds[1].isEmpty()) size - 1 else bounds[1].toLongOrNull() ?: return null
            return if (last < first) null else first..minOf(last, size - 1)
        }

        private fun skip(input: InputStream, offset: Long) {
            var left = offset
            while (left > 0) {
                val n = input.skip(left)
                if (n == 0L && input.read() < 0) throw EOFException()
                left -= if (n == 0L) 1 else n
            }
        }
    }
}
