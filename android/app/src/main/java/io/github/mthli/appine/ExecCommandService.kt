package io.github.mthli.appine

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.DataOutputStream
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Android clients request the runtime permission and bind with action RUN_COMMAND. */
internal class ExecCommandService : Service() {
    private val worker = ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, SynchronousQueue())
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { request ->
        if (request.what == RUN) runCommand(request)
        true
    })

    override fun onBind(intent: Intent): IBinder? =
        messenger.binder.takeIf { intent.action == BuildConfig.APPLICATION_ID + ".RUN_COMMAND" }

    private fun allowed(uid: Int): Boolean = uid >= 0 &&
        checkPermission(BuildConfig.APPLICATION_ID + ".permission.RUN_COMMAND", -1, uid) == PackageManager.PERMISSION_GRANTED &&
        Settings.terminalAccess(this) && !Settings.privacyMode(this)

    private fun runCommand(request: Message) {
        val receiver = request.replyTo ?: return
        val uid = request.sendingUid
        if (!allowed(uid)) {
            reply(receiver, Bundle().apply { putString("error", "Command access is not authorized") })
            return
        }
        val argv = try { request.data.getStringArray("argv") } catch (_: RuntimeException) { null }
        if (argv == null || !GuestCommands.valid(argv)) {
            reply(receiver, Bundle().apply { putString("error", "Invalid command arguments") })
            return
        }
        if (GuestCommands.service == null) {
            try {
                startForegroundService(Intent(this, TerminalService::class.java))
            } catch (_: IllegalStateException) {
                reply(receiver, Bundle().apply { putString("error", "Android blocked a background start. Retry from a visible app.") })
                return
            }
        }
        val pipes = try { ParcelFileDescriptor.createPipe() } catch (_: java.io.IOException) {
            reply(receiver, Bundle().apply { putString("error", "Could not open the command result pipe") })
            return
        }
        try {
            worker.execute {
                try {
                    pipes[0].use { read ->
                        val data = Bundle().apply { putParcelable("result", read) }
                        if (!reply(receiver, data)) return@execute
                    }
                    DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).buffered()).use { out ->
                        GuestCommands.execute(this, argv, out) {
                            !stopped && checkPermission(
                                BuildConfig.APPLICATION_ID + ".permission.RUN_COMMAND", -1, uid,
                            ) == PackageManager.PERMISSION_GRANTED
                        }
                    }
                } catch (_: Exception) {
                    // The requesting process can close its end of the pipe at any time.
                } finally {
                    pipes.forEach { runCatching { it.close() } }
                }
            }
        } catch (_: RejectedExecutionException) {
            pipes.forEach { runCatching { it.close() } }
            reply(receiver, Bundle().apply { putString("error", "Too many commands running") })
        }
    }

    private fun reply(receiver: Messenger, data: Bundle): Boolean = try {
        receiver.send(Message.obtain(null, RESULT).apply { this.data = data })
        true
    } catch (_: RemoteException) {
        false
    }

    @Volatile private var stopped = false

    override fun onDestroy() {
        stopped = true
        worker.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val RUN = 1
        const val RESULT = 2
    }
}
