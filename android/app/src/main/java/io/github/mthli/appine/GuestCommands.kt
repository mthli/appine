package io.github.mthli.appine

import android.annotation.SuppressLint
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import org.json.JSONArray
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore
import kotlin.concurrent.read
import kotlin.concurrent.thread

/** The same command runner serves Android IPC and the shell client. */
internal object GuestCommands {
    const val STDOUT = 1
    const val STDERR = 2
    const val EXIT = 3
    const val ERROR = 4
    private const val READY = 5
    private const val ACK = 6
    private const val IO_TIMEOUT = 150

    data class TerminalRequest(val rows: Int, val cols: Int, val input: CommandBridge.Input)
    const val MAX_REQUEST = 32768
    private const val MAX_COMMANDS = 4
    private val slots = Semaphore(MAX_COMMANDS)
    val hasActiveRequests: Boolean get() = slots.availablePermits() < MAX_COMMANDS

    @Volatile
    var service: TerminalService? = null

    fun decodeArgv(bytes: ByteArray): Array<String> {
        val array = JSONArray(bytes.decodeToString(throwOnInvalidSequence = true))
        return Array(array.length()) { index ->
            array.get(index) as? String ?: throw IOException("Expected a string argument")
        }
    }

    fun frame(output: DataOutputStream, type: Int, data: ByteArray, size: Int = data.size) {
        synchronized(output) {
            output.writeByte(type)
            output.writeInt(size)
            output.write(data, 0, size)
            output.flush()
        }
    }

    fun acknowledge(output: DataOutputStream) = frame(output, ACK, ByteArray(0))

    fun error(output: DataOutputStream, message: String) =
        frame(output, ERROR, message.encodeToByteArray())

    fun valid(argv: Array<String>): Boolean =
        argv.size in 1..256 && argv[0].isNotEmpty() && !argv[0].startsWith('-') &&
            argv.none { '\u0000' in it } &&
            argv.sumOf { it.encodeToByteArray().size.toLong() } <= MAX_REQUEST

    fun execute(
        context: Context, argv: Array<String>, output: DataOutputStream,
        terminal: TerminalRequest? = null, authorized: () -> Boolean = { true },
    ) {
        if (!valid(argv)) {
            error(output, "Invalid command arguments")
            return
        }
        if (!slots.tryAcquire()) {
            error(output, "Too many commands running")
            return
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + 90000
            while (authorized() && Settings.terminalAccess(context) &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                val runtime = service
                val machine = runtime?.commandMachine ?: 0L
                if (runtime != null && machine != 0L) {
                    executeReady(context, runtime, machine, argv, output, terminal, authorized)
                    return
                }
                if (runtime?.commandBlocked == true) break
                Thread.sleep(80)
            }
            error(output, "Appine Linux did not become available")
        } catch (e: Exception) {
            val site = e.stackTrace.firstOrNull()?.let { "${it.className}:${it.lineNumber}" }
            Log.w("AppineExec", "Guest command failed: ${e.javaClass.simpleName} at $site")
            runCatching { error(output, "Could not run the Linux command") }
        } finally {
            slots.release()
        }
    }

    @SuppressLint("WakelockTimeout") // Held for the lifetime of the interactive connection.
    private fun executeReady(
        context: Context, runtime: TerminalService, machine: Long, argv: Array<String>,
        output: DataOutputStream, terminal: TerminalRequest?, authorized: () -> Boolean,
    ) {
        val account = Rootfs.access.read { Rootfs.loginAccount(context, Settings.login(context)) }
        val launch = launchArguments(account, argv)
        if (!authorized() || service !== runtime || machine != runtime.commandMachine ||
            !Settings.terminalAccess(context)
        ) {
            error(output, "Appine Linux is unavailable")
            return
        }
        if (terminal != null) {
            val wakeLock = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "appine:interactive-shell")
            wakeLock.acquire()
            try {
                executeTerminal(machine, launch, environment(account, tty = true), output, terminal) {
                    authorized() && service === runtime && machine == runtime.commandMachine
                }
            } finally {
                wakeLock.release()
            }
            return
        }
        CommandPipe().use { stdout ->
            CommandPipe().use { stderr ->
                val pid = try {
                    ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_READ_ONLY).use { stdin ->
                        Native.spawnRedirected(
                            machine, "/bin/sh", launch, environment(account), stdin.fd, stdout.writer.fd, stderr.writer.fd,
                        )
                    }
                } catch (_: IOException) {
                    error(output, "Could not start the Linux command")
                    return
                } finally {
                    stdout.writer.close()
                    stderr.writer.close()
                }
                val exited = FutureTask { Native.waitCommand(machine, pid) }
                try {
                    thread(isDaemon = true, name = "appine-command-wait") { exited.run() }
                    stream(runtime, machine, output, stdout, stderr, exited, authorized)
                } finally {
                    if (!exited.isDone) Native.cancelCommand(machine, pid)
                }
            }
        }
    }

    private fun stream(
        runtime: TerminalService, machine: Long, output: DataOutputStream,
        stdout: CommandPipe, stderr: CommandPipe, exited: FutureTask<Int>, authorized: () -> Boolean,
    ) {
        val descriptors = arrayOf(stdout.poll, stderr.poll)
        val flags = OsConstants.POLLIN or OsConstants.POLLHUP
        val chunk = ByteArray(8192)
        while (authorized() && service === runtime && machine == runtime.commandMachine) {
            val active = descriptors.filter { it.fd != null }.toTypedArray()
            val more = if (active.isEmpty()) {
                Thread.sleep(20)
                0
            } else {
                Os.poll(active, 200)
            }
            for ((index, descriptor) in descriptors.withIndex()) {
                val fd = descriptor.fd ?: continue
                if (descriptor.revents.toInt() and flags == 0) continue
                val n = Os.read(fd, chunk, 0, chunk.size)
                if (n > 0) frame(output, if (index == 0) STDOUT else STDERR, chunk, n)
                else descriptor.fd = null
            }
            if (exited.isDone && (more == 0 || descriptors.all { it.fd == null })) {
                exit(output, exited.get())
                return
            }
        }
        error(output, "Command execution ended because Appine or access stopped")
    }

    /**
     * The guest's line discipline handles Ctrl+C, Ctrl+D and echo. Keep the console reserved until
     * the socket reader has stopped so late input cannot hit a later session with the same number.
     */
    private fun executeTerminal(
        machine: Long, launch: Array<String>, env: Array<String>, output: DataOutputStream,
        terminal: TerminalRequest, authorized: () -> Boolean,
    ) {
        val console = Native.openConsole(machine, terminal.rows, terminal.cols)
        var pid = -1
        try {
            if (!authorized()) return
            pid = Native.spawn(machine, console, "/bin/sh", launch, env)
            val job = pid
            val exited = FutureTask { Native.waitCommand(machine, job) }
            thread(isDaemon = true, name = "appine-command-wait") { exited.run() }
            terminal.input.attach(machine, console)
            frame(output, READY, ByteArray(0))
            val chunk = ByteArray(8192)
            while (authorized()) {
                val n = Native.read(machine, console, chunk, IO_TIMEOUT)
                if (n > 0) {
                    frame(output, STDOUT, chunk, n)
                } else if (n != -2 || exited.isDone) {
                    while (authorized() && !exited.isDone) Thread.sleep(30)
                    if (authorized()) exit(output, exited.get())
                    return
                }
            }
        } finally {
            terminal.input.close()
            Native.closeConsole(machine, console)
            if (pid > 0) Native.cancelCommand(machine, pid)
        }
    }

    private fun exit(output: DataOutputStream, status: Int) {
        val code = if (status < 0) 128 - status else status
        frame(output, EXIT, ByteBuffer.allocate(Int.SIZE_BYTES).putInt(code).array())
    }

    private class CommandPipe : Closeable {
        private val ends = ParcelFileDescriptor.createPipe()
        val writer get() = ends[1]
        val poll = StructPollfd().apply {
            fd = ends[0].fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }

        override fun close() {
            ends.forEach { runCatching { it.close() } }
        }
    }

    private fun launchArguments(account: Rootfs.Account, argv: Array<String>): Array<String> {
        if (account.uid == 0) {
            return arrayOf("sh", "-c", "cd -- \"\$HOME\" 2>/dev/null || cd /; exec \"\$@\"", "appine-exec") + argv
        }
        val command = "cd -- \"\$HOME\" 2>/dev/null || cd /; exec " +
            argv.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        return arrayOf(
            "sh", "-c",
            "if su --help 2>&1 | grep -q -- --session-command; then " +
                "exec su -s /bin/sh --session-command=\"\$2\" -- \"\$1\"; " +
                "else exec su -s /bin/sh -- \"\$1\" -c \"\$2\"; fi",
            "appine-exec", account.name, command,
        )
    }

    private fun environment(account: Rootfs.Account, tty: Boolean = false) = ConsolePty.ENVIRONMENT
        .filterNot { it.startsWith("HOME=") || it.startsWith("USER=") || it.startsWith("LOGNAME=") || (!tty && it.startsWith("TERM=")) }
        .toTypedArray() + arrayOf("HOME=${account.home}", "USER=${account.name}", "LOGNAME=${account.name}")
}
