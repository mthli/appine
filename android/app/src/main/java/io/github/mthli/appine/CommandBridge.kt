package io.github.mthli.appine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Terminal access is opt-in and bound to IPv4 loopback. */
internal class CommandBridge(private val context: Context) : Closeable {
    private val listener = ServerSocket().apply {
        try {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8)
        } catch (e: IOException) {
            close()
            throw e
        }
    }
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val worker = ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, SynchronousQueue())

    init {
        thread(name = "appine-exec-listener", isDaemon = true) {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: IOException) { break }
                clients.add(socket)
                try {
                    worker.execute {
                        try {
                            socket.use { serve(it) }
                        } catch (_: IOException) {
                            // The client may close its socket at any time.
                        } finally {
                            clients.remove(socket)
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    clients.remove(socket)
                    runCatching { socket.close() }
                }
                if (listener.isClosed) runCatching { socket.close() }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.tcpNoDelay = true
        val input = DataInputStream(socket.getInputStream().buffered())
        val output = DataOutputStream(socket.getOutputStream().buffered())
        val expires = SystemClock.elapsedRealtime() + 3000
        fun readDeadline(size: Int): ByteArray {
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val remaining = expires - SystemClock.elapsedRealtime()
                if (remaining <= 0) throw IOException("Request timed out")
                socket.soTimeout = remaining.toInt()
                val read = input.read(bytes, offset, size - offset)
                if (read < 0) throw IOException("Incomplete request")
                offset += read
            }
            return bytes
        }
        val acknowledgments = when (readDeadline(4).decodeToString()) {
            "APX2" -> false
            "APX3" -> true
            else -> return
        }
        if (listener.isClosed || !Settings.terminalAccess(context)) return
        val request = java.nio.ByteBuffer.wrap(readDeadline(8))
        val rows = request.short.toInt() and 0xffff
        val cols = request.short.toInt() and 0xffff
        val size = request.int
        if ((rows == 0) != (cols == 0) || size !in 1..GuestCommands.MAX_REQUEST) {
            GuestCommands.error(output, "Invalid command request")
            return
        }
        val bytes = readDeadline(size)
        val argv = try { GuestCommands.decodeArgv(bytes) } catch (_: Exception) {
            GuestCommands.error(output, "Invalid command arguments")
            return
        }
        socket.soTimeout = 0
        if (acknowledgments) GuestCommands.acknowledge(output)
        Input(socket, input) {
            if (acknowledgments) GuestCommands.acknowledge(output)
        }.use { incoming ->
            val terminal = if (rows == 0) null else GuestCommands.TerminalRequest(rows, cols, incoming)
            GuestCommands.execute(context, argv, output, terminal) {
                incoming.connected && !listener.isClosed && Settings.terminalAccess(context)
            }
        }
    }

    /** Keeps the socket's read side separate so a disconnect also cancels a quiet job. */
    internal class Input(
        private val socket: Socket, input: DataInputStream, accepted: () -> Unit,
    ) : Closeable {
        private data class Console(val machine: Long, val id: Int)

        @Volatile private var console: Console? = null
        @Volatile var connected = true
            private set

        private val reader = thread(name = "appine-exec-input", isDaemon = true) {
            try {
                val buffer = ByteArray(8192)
                while (connected) {
                    val kind = input.read()
                    val target = console ?: break
                    when (kind) {
                        1 -> {
                            val size = input.readInt()
                            if (size !in 1..buffer.size) break
                            input.readFully(buffer, 0, size)
                            var offset = 0
                            while (connected && offset < size) {
                                val written = Native.write(
                                    target.machine, target.id, buffer, offset, size - offset, 150,
                                )
                                if (written < 0) break
                                offset += written
                            }
                        }
                        2 -> {
                            if (input.readInt() != 4) break
                            val rows = input.readUnsignedShort()
                            val cols = input.readUnsignedShort()
                            if (rows == 0 || cols == 0) break
                            Native.resize(target.machine, target.id, rows, cols)
                        }
                        else -> break
                    }
                    accepted()
                }
            } catch (_: IOException) {
                // EOF or a disconnected peer.
            } finally {
                connected = false
            }
        }

        fun attach(machine: Long, id: Int) {
            console = Console(machine, id)
        }

        override fun close() {
            connected = false
            runCatching { socket.shutdownInput() }
            // The console number can be reused only once this writer has left all native calls.
            reader.join()
        }
    }

    override fun close() {
        runCatching { listener.close() }
        worker.shutdown()
        clients.forEach { runCatching { it.close() } }
    }

    companion object {
        // The official and side-by-side APK can run at the same time.
        private val port: Int
            get() = if (BuildConfig.APPLICATION_ID == "io.github.mthli.appine") 38662 else 38663

        fun open(context: Context): CommandBridge? = try {
            CommandBridge(context)
        } catch (_: IOException) {
            Log.w("AppineExec", "Could not bind the local command socket")
            null
        }
    }
}
