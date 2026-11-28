package io.github.mthli.appine

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.RouteInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import io.github.mthli.appine.TerminalService.Companion.POLL_MS
import io.github.mthli.appine.TerminalService.Companion.SAVE_MS
import io.github.mthli.appine.TerminalService.Companion.TRIM_DELAY_MS
import java.io.Closeable
import java.io.IOException
import java.text.DateFormat
import java.text.DecimalFormat
import java.util.Date
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.concurrent.write

/**
 * Runs per-start guest configuration around a browser endpoint: fresh imports abort on the first
 * I/O error and close the endpoint, while existing stores report each failure independently and
 * close the endpoint whenever its guest configuration could not be written.
 */
internal fun <B : Closeable> prepareStartBridge(
    importing: Boolean,
    onFailure: (String, IOException) -> Unit,
    openBridge: () -> B,
    configure: (B?, (String, IOException) -> Unit) -> Boolean,
): B? {
    val handleFailure: (String, IOException) -> Unit = { step, e ->
        if (importing) throw e
        onFailure(step, e)
    }
    val bridge: B? = try {
        openBridge()
    } catch (e: IOException) {
        handleFailure(BrowserBridge.CONFIG_PATH, e)
        null
    }
    try {
        if (configure(bridge, handleFailure)) return bridge
        bridge?.close()
        return null
    } catch (e: Throwable) {
        bridge?.close()
        throw e
    }
}

internal class StartedKernel(
    val handle: Long, val browser: Closeable?, val booted: Boolean, val gpuAvailable: Boolean,
    val setupError: String?,
)

/** Main-thread state shared by kernel handoffs and the wake-lock policy. */
internal class ServiceLifecycle(private val updateWakeLock: () -> Unit) {
    var destroyed = false

    /** Import or private kernel setup is in progress. */
    var starting = false

    /** Restart or replacement has detached the old kernel. Its completion owns any pending exit. */
    var transitioning = false
    var booting = false
    var exiting = false

    fun wantsWakeLock(policy: RunPolicy, machine: Long, switching: Boolean, isBusy: () -> Boolean): Boolean {
        if (destroyed) return false
        val transition = exiting || starting || transitioning || booting
        return when (policy) {
            RunPolicy.SLEEP -> false
            RunPolicy.ALWAYS -> machine != 0L || transition
            RunPolicy.BUSY -> transition || switching || (machine != 0L && isBusy())
        }
    }

    fun completeStart(
        result: Result<StartedKernel>,
        shutdown: (Long) -> Unit,
        started: (StartedKernel) -> Unit,
        failed: (Throwable) -> Unit,
        exit: (StartedKernel?) -> Unit,
    ) {
        starting = false
        if (destroyed) {
            updateWakeLock()
            result.onSuccess { kernel ->
                kernel.browser?.close()
                thread(name = "appine-stop") { shutdown(kernel.handle) }
            }
        } else if (exiting) {
            val kernel = result.getOrNull()
            kernel?.browser?.close()
            exit(kernel)
        } else {
            result.onSuccess(started).onFailure(failed)
        }
    }

    fun completeTransition(distro: Rootfs.Distro?, start: (Rootfs.Distro?) -> Unit, finishExit: (Long) -> Unit) {
        transitioning = false
        if (destroyed) {
            updateWakeLock()
        } else if (distro == null && exiting) {
            finishExit(0)
        } else {
            // After replacement the store is gone, so even a pending exit must finish the import.
            start(distro)
        }
    }

    fun finishExit(handle: Long, worker: Executor, stop: (Long) -> Unit, onStopped: () -> Unit) {
        if (destroyed) {
            exiting = false
            updateWakeLock()
            if (handle != 0L) thread(name = "appine-stop") { stop(handle) }
            return
        }
        worker.execute {
            if (handle != 0L) stop(handle)
            onStopped()
        }
    }

    fun completeExit(
        hasRestore: Boolean, hasListeners: Boolean,
        clearTabs: () -> Unit, startOrImport: () -> Unit, stopSelf: () -> Unit,
    ) {
        exiting = false
        updateWakeLock()
        if (destroyed) return
        if (!hasRestore) clearTabs()
        if (hasListeners) startOrImport() else stopSelf()
    }
}

/** Guest CPU samples only: app rendering and the service's own polling must not keep it awake. */
internal class GuestActivity {
    private var cpu: Long? = null
    private var activeAt = 0L

    fun reset(now: Long) {
        cpu = null
        activeAt = now
    }

    fun sample(value: Long, now: Long, receivedAt: Long = now) {
        // A queued worker result can predate a policy change that reset the grace period.
        if (now < activeAt) return
        // Do not release on evidence that waited behind a busy main thread or device suspend.
        if (receivedAt - now >= SAMPLE_MS) {
            reset(receivedAt)
            return
        }
        // Unknown samples keep the grace period open; failures must not look like idle time.
        if (value < 0 || cpu != value) activeAt = now
        cpu = value.takeIf { it >= 0 }
    }

    fun isBusy(now: Long): Boolean = now - activeAt < IDLE_MS

    companion object {
        const val IDLE_MS = 30_000L
        const val SAMPLE_MS = 2_000L
    }
}

/**
 * Serializes console opens and closes with [Native.takeKills] using a monotonic epoch so each
 * console lifetime owns a disjoint `[openedEpoch, closedEpoch]` interval and a kill report from
 * an ended tab cannot be delivered to a newer tab that reused its console number.
 */
internal class ConsoleTracker(
    private val takeKills: () -> Array<String>?,
    private val onDrainedKills: (Array<String>, Long) -> Unit,
) {
    private var epoch = 0L

    /** Opens a console and returns `(console, openedEpoch)`. */
    fun open(open: () -> Int): Pair<Int, Long> = synchronized(this) {
        val opened = ++epoch
        val console = open()
        console to opened
    }

    /**
     * Closes a console, drains any kills recorded before the close, records the closing epoch via
     * [onClosed], and advances the epoch past it before another console can reuse the number.
     */
    fun close(close: () -> Unit, onClosed: (Long) -> Unit) {
        val (drained, closed) = synchronized(this) {
            close()
            val kills = takeKills()
            val closed = epoch
            onClosed(closed)
            epoch++
            kills to closed
        }
        if (drained != null && drained.isNotEmpty()) onDrainedKills(drained, closed)
    }

    /** Takes pending kills together with the epoch at which they were observed. */
    fun take(): Pair<Array<String>?, Long> = synchronized(this) {
        takeKills() to epoch
    }
}

/**
 * Tracks inactive tabs' Kitty image caches ([VtNative.dropImages]) on Android 14+, where foreground
 * services no longer receive `onTrimMemory`'s `RUNNING_*` or cached-background callbacks. Keeps up
 * to [maxRetained] most recently left tabs untrimmed for [delayMs], evicts older ones immediately,
 * and remembers trimmed tabs so periodic saves re-trim unseen tabs that keep emitting images.
 */
internal class BackgroundImageTrim(
    private val delayMs: Long = DELAY_MS,
    private val maxRetained: Int = MAX_RETAINED,
) {
    /** Started background tab IDs in LRU order (oldest first) to when they left the foreground. */
    private val pending = LinkedHashMap<Int, Long>()

    /** Already-trimmed background tab IDs still unseen (eligible for periodic re-trim). */
    private val trimmed = LinkedHashSet<Int>()

    /**
     * Records a foreground change from [previousId] (when still open and started) to [currentId]
     * at monotonic [now], returning background tab IDs evicted past [maxRetained].
     */
    fun onCurrentChanged(previousId: Int?, currentId: Int?, now: Long): List<Int> {
        if (currentId != null) remove(currentId)
        if (previousId != null && previousId != currentId) {
            remove(previousId)
            pending[previousId] = now
        }
        if (pending.size <= maxRetained) return emptyList()
        val evicted = ArrayList<Int>(pending.size - maxRetained)
        while (pending.size > maxRetained && pending.isNotEmpty()) {
            val id = pending.keys.first()
            pending.remove(id)
            trimmed.add(id)
            evicted.add(id)
        }
        return evicted
    }

    /** Returns background tab IDs unseen for at least [delayMs] as of [now] and marks them trimmed. */
    fun takeDue(now: Long): List<Int> {
        val due = pending.entries.mapNotNull { (id, leftAt) -> id.takeIf { now - leftAt >= delayMs } }
        for (id in due) {
            pending.remove(id)
            trimmed.add(id)
        }
        return due
    }

    /** Milliseconds until the next pending tab becomes due, or null when none wait. */
    fun nextDelayMs(now: Long): Long? {
        val earliest = pending.values.minOrNull() ?: return null
        return (delayMs - (now - earliest)).coerceAtLeast(0L)
    }

    /** Records that every started tab in [openIds] except [currentId] was just trimmed. */
    fun markAllTrimmed(currentId: Int?, openIds: Iterable<Int>) {
        clear()
        for (id in openIds) {
            if (id != currentId) trimmed.add(id)
        }
    }

    fun trimmedIds(): Set<Int> = trimmed.toSet()

    fun remove(id: Int) {
        pending.remove(id)
        trimmed.remove(id)
    }

    fun clear() {
        pending.clear()
        trimmed.clear()
    }

    companion object {
        const val DELAY_MS = 60_000L
        const val MAX_RETAINED = 2
    }
}

/**
 * The foreground service that holds the guest kernel and all terminal sessions (the android/service
 * map). The sessions' lifecycle hangs on the service, not on an Activity: shells keep running after
 * the Activity is closed. The notification shows the number of sessions and offers "Exit all".
 *
 * Once the kernel starts it boots by `/etc/inittab` (OpenRC services on Alpine), and on exit it
 * first shuts down by it ([exitAll]). After the last tab is closed the service stays in the
 * background while guest processes (services) are still running, and exits only when there are
 * none; the guest's `poweroff` and `halt` mean exit all, and `reboot` restarts the kernel.
 *
 * On the first run the Alpine in the APK is imported; switching distros or resetting the rootfs in
 * Settings downloads in the background first while the old system keeps running, and only once the
 * download is done is the kernel stopped and the store replaced ([switchTo]).
 */
class TerminalService : Service() {

    /**
     * Events the UI (terminal, settings) cares about. Callbacks all run on the main thread; each UI
     * implements only the ones it needs.
     */
    interface Listener {
        /** Progress of preparing the rootfs ([progress]). */
        fun onProgress(text: String) {}

        /** The kernel has started (or failed to start, with [error] non-null). */
        fun onMachineReady(error: String?) {}

        /** The session list, a title or a state changed. */
        fun onSessionsChanged() {}

        /** A bell or program notification requests haptic feedback in the tab in front ([inFront]). */
        fun onBell() {}

        /**
         * A program in the tab in front ([inFront], so the current one) asks to read the clipboard
         * (OSC 52). Return true if handling the request for [sessionId], including an empty answer.
         */
        fun onClipboardRead(sessionId: Int): Boolean = false

        /** A guest requests a browser; true means Android accepted the launch. */
        fun onOpenUrl(url: String): Boolean = false

        /** A tab's progress or program status changed ([VtSession.progress], [VtSession.status]). */
        fun onTabStatus() {}

        /**
         * State shown in Settings changed: the download progress of a distro switch ([switching]).
         */
        fun onSettingsChanged() {}

        /** There is something to tell the user ([takeNotice]): a distro switch failed. */
        fun onNotice() {}

        /**
         * About to restart: the guest's `reboot`, or the execution tier or Vulkan GPU setting
         * changed in Settings. All sessions have ended; the kernel shuts down and starts again
         * (followed by [onMachineReady]).
         */
        fun onRestarting() {}

        /** Exit all: the user chose "Exit all" in the notification, or the guest shut down. */
        fun onExitAll() {}
    }

    /**
     * A distro switch in Settings (resetting the rootfs is a switch to the same one): the tarball
     * of [distro] is downloading in the background while the old system keeps running.
     */
    internal class Switch(val distro: Rootfs.Distro) {
        @Volatile var done = 0L
        @Volatile var total = distro.download?.size ?: 0L
        @Volatile var cancelled = false
    }

    inner class LocalBinder : Binder() {
        val service: TerminalService get() = this@TerminalService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val notifications = Notifications(this)
    private val privacyChanged: () -> Unit = { settingsChanged() }

    /**
     * Releasing caches and stopping the kernel are done in order on this thread: both take the BKL,
     * so they stay off the main thread; and after the stop, no cache-releasing call can use the
     * released handle.
     */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "appine-worker") }

    /**
     * The visible UIs (terminal, settings), added by Activities in onStart and removed in onStop;
     * when all have stopped (switched to another app, screen off) and none has come back after
     * [TRIM_DELAY_MS], the kernel's rebuildable caches are released, background tabs' Kitty image
     * caches are trimmed, and the tabs' scrollback is compressed ([trimMemory]): since Android 14
     * a process with a foreground service no longer gets `onTrimMemory`'s `RUNNING_*` or cached
     * background callbacks, so it has to watch whether the UI is there itself; when the user
     * switches back quickly, nothing has to be translated or discarded either.
     */
    private val listeners = mutableListOf<Listener>()

    /**
     * Caches are released only when the UI has been hidden for a while and has not come back
     * ([addListener]).
     */
    private val trimWhenHidden = Runnable { trimMemory() }

    /** Tracks inactive background tabs so their Kitty images are trimmed even while the UI stays visible. */
    private val backgroundImages = BackgroundImageTrim()

    /** Trims background tabs whose grace window in [backgroundImages] has expired. */
    private val trimBackgroundImages = Runnable {
        dropImagesForIds(backgroundImages.takeDue(SystemClock.elapsedRealtime()))
        scheduleBackgroundImageTrim()
    }

    /**
     * Every [POLL_MS], asks the kernel on [worker] (this takes the BKL):
     * - when the guest first listens on a port on a non-loopback address, posts a notification
     *   (design doc §12 item 7): other devices can reach it, and a port below 1024 that the host
     *   refuses was moved, so the user must be told which one to connect to;
     * - the guest's power requests (busybox's reboot, halt, poweroff);
     * - directories the guest changed: the Files app is told to refresh them (the android/documents
     *   map). The login rule inputs are sampled separately (the android/setup map);
     * - whether guest processes remain when there are no sessions and no UI: if none, exit (the
     *   android/service map).
     *
     * Before that, on the main thread, whether one kernel operation has held the kernel lock too
     * long ([Notifications.stall]): the worker would wait for it to end like everything else.
     */
    private val poll = object : Runnable {
        override fun run() {
            val m = machine
            if (m == 0L) return
            val tracker = consoleTracker
            val rules = loginRules
            val roots = autoRoots
            Native.bklStall(m)?.let { notifications.stall(m, it) }
            runCatching {
                worker.execute {
                    val lines = Native.takeListens(m)
                    val (kills, killsEpoch) = tracker?.take() ?: (Native.takeKills(m) to 0L)
                    val power = Native.takePower(m)
                    val processes = Native.processes(m)
                    val changedDirs = Native.takeChangedDirs(m)
                    changedDirs?.let {
                        Documents.dirsChanged(this@TerminalService, it)
                    }
                    roots?.poll(this@TerminalService, changedDirs)
                    rules?.poll(this@TerminalService)
                    val cpu = Native.guestCpuTime(m)
                    val sampledAt = SystemClock.elapsedRealtime()
                    main.post {
                        if (machine != m) return@post
                        guestActivity.sample(cpu, sampledAt, SystemClock.elapsedRealtime())
                        updateWakeLock()
                        lines?.forEach(notifications::listen)
                        kills?.forEach { reportKill(it, killsEpoch) }
                        when (power) {
                            Native.POWER_REBOOT -> restart()
                            Native.POWER_HALT, Native.POWER_OFF -> exitAll()
                            // Tabs whose shell has ended do not count (restored ones not shown yet
                            // do: their shell has not started)
                            else -> if (sessions.none { it.isRunning } && listeners.isEmpty() && processes == 0) exitAll()
                        }
                    }
                }
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    private val lifecycle = ServiceLifecycle(::updateWakeLock)

    /** The browser endpoint for the current kernel, independent of terminal sessions. */
    private var browserBridge: Closeable? = null

    /** Console-lifetime epoch tracker for the current kernel's kill reports. */
    private var consoleTracker: ConsoleTracker? = null
    private var loginRules: Rootfs.LoginRules? = null
    private var autoRoots: Documents.AutoRootsWatcher? = null

    /**
     * A distro switch whose download finished while the kernel was restarting (a guest reboot):
     * replaced as soon as the new kernel is up or has failed.
     */
    private var pendingReplace: Rootfs.Distro? = null

    /** The kernel handle, 0 before the start. */
    var machine = 0L
        private set

    /** Whether the running kernel registered a host GPU renderer. */
    var gpuAvailable = false
        private set

    /** Why the start failed. */
    var startError: String? = null
        private set

    /**
     * Progress while preparing the rootfs (import, distro switch); null once the kernel has
     * started.
     */
    var progress: String? = null
        private set

    /** A distro switch from Settings that is downloading ([switchTo]). */
    internal var switching: Switch? = null
        private set

    /**
     * The distro switch whose download thread has not finished yet. After cancelling, the thread
     * may still be stuck connecting or reading (for at most one timeout), and no new download
     * starts meanwhile: two threads would write the same `.part`, and the one that finishes first
     * would also delete it.
     */
    private var downloader: Switch? = null

    /** Something to tell the user that has not been told yet ([Listener.onNotice]). */
    private var notice: String? = null

    /**
     * The next tab's [VtSession.id], counting up from the clock, so a notification still shown
     * from an earlier service does not open a new tab with its number.
     */
    private var nextSessionId = (System.currentTimeMillis() % Notifications.TAB_NOTIFICATION_COUNT).toInt()

    /** [network] is registered. */
    private var watchingNetwork = false

    /** The `PARTIAL_WAKE_LOCK` held while keeping running with the screen off. */
    private var wakeLock: PowerManager.WakeLock? = null
    private val guestActivity = GuestActivity()

    /** The tabs. */
    internal val sessions = mutableListOf<VtSession>()

    /**
     * The saved copy of restored sessions not shown yet (their emulator is not created yet, so the
     * transcript and the directory are still the original ones).
     */
    private val unshown = HashMap<VtSession, Tabs.Tab>()

    /**
     * Tabs saved when the system last killed the app, restored after the kernel first comes up (the
     * android/service map).
     */
    private var restore: Tabs.Saved? = null

    /** Why the run whose tabs are restored ended: the start of their notice ([endedText]). */
    private var ended = R.string.ended_android

    /** The tab to show after restoring (taken by the Activity). */
    internal var focus: VtSession? = null
        private set

    /**
     * The tab the Activity is showing (saving tabs records which one it is); showing a tab takes
     * away its notification ([Notifications.cancelTab]), marks its done and error statuses seen,
     * and protects its Kitty image cache while scheduling the previous tab for background trimming.
     */
    internal var current: VtSession? = null
        set(value) {
            val previous = field
            field = value
            value?.let(notifications::cancelTab)
            value?.takeIf(::inFront)?.statusSeen()
            if (previous !== value) {
                val previousId = previous?.takeIf { it in sessions && it.tab != 0L }?.id
                dropImagesForIds(backgroundImages.onCurrentChanged(previousId, value?.id, SystemClock.elapsedRealtime()))
                scheduleBackgroundImageTrim()
            }
        }

    /**
     * What the sessions report (title changes, ends) goes to the current Activity (if any); their
     * screens go to their views directly.
     */
    private val sessionEvents = object : VtSession.Events {
        override fun onTitleChanged(session: VtSession) {
            each { it.onSessionsChanged() }
        }

        override fun onBell(session: VtSession) {
            if (inFront(session)) each { it.onBell() } else notifications.bell(session)
        }

        /**
         * Programs write the clipboard as Ghostty lets them (clipboard-write = allow), from any
         * tab; Android 13 and later show the copy themselves.
         */
        override fun onClipboard(session: VtSession, text: String?) {
            if (Privacy.locked(this@TerminalService)) return
            val clipboard = getSystemService(ClipboardManager::class.java)
            if (text == null) {
                clipboard.clearPrimaryClip()
                return
            }
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(this@TerminalService, R.string.program_copied, Toast.LENGTH_SHORT).show()
            }
        }

        override fun onNotification(session: VtSession, title: String, body: String) {
            if (inFront(session)) each { it.onBell() } else notifications.program(session, title, body)
        }

        /** The session keeps an unclaimed read until its waiting deadline. */
        override fun onClipboardRead(session: VtSession) {
            if (!inFront(session)) return
            for (listener in listeners.toList()) {
                if (listener.onClipboardRead(session.id)) return
            }
        }

        override fun onStatusChanged(session: VtSession) {
            each { it.onTabStatus() }
            notifications.status(session, inFront(session))
        }

        /** A long command finished: told in the tab's notification when it is not in front. */
        override fun onCommandFinished(session: VtSession, exitCode: Int?, millis: Long) {
            if (!inFront(session)) notifications.commandFinished(session, exitCode, millis)
        }

        override fun onTmuxDetached(session: VtSession) {
            session.showMessage("[appine] " + getString(R.string.tmux_control_detached))
        }

        override fun onFinished(session: VtSession) {
            // The notification counts running tabs, and an ended tab is no longer saved
            sessionsChanged()
        }
    }

    /**
     * Tabs are saved every [SAVE_MS] while the kernel runs, and at once when the UI is hidden or
     * memory is short. Already-trimmed background tabs are also re-trimmed here so unseen tabs
     * emitting new Kitty images stay capped.
     */
    private val periodicSave = object : Runnable {
        override fun run() {
            dropImagesForIds(backgroundImages.trimmedIds())
            saveTabs()
            main.postDelayed(this, SAVE_MS)
        }
    }

    /** A tab was opened or closed: save a little later, once for several in a row. */
    private val saveSoon = Runnable { saveTabs() }

    /**
     * The default network's routes go to the guest (the kernel/route map): since Android 10 apps
     * cannot read the host's routing table, and the guest's `ip route` and `/proc/net/route` rely
     * on this. Callbacks come on system threads and are handed to [worker] to run in order.
     */
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            setRoutes(routes(lp))
        }

        override fun onLost(network: Network) {
            setRoutes(emptyArray())
        }
    }

    override fun onCreate() {
        super.onCreate()
        Privacy.addListener(privacyChanged)
        notifications.createChannels()
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // The last run did not exit normally: restore the tabs once the kernel is up, saying why
        // it ended as far as Android recorded it; a crash of Appine itself is reported now
        Tabs.takeKilled(this)?.let { killed ->
            val exit = lastExit(killed.pid)
            restore = killed.saved
            ended = endedText(exit?.reason)
            if (exit != null && (ended == R.string.ended_crash || ended == R.string.ended_anr)) notifications.crash(exit)
        }
        startOrImport()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val exit = Notifications.serviceAction(this, 1, ACTION_EXIT_ALL)
        // Tabs whose shell has ended stay open but are not running
        val n = sessions.count { it.isRunning }
        val sw = switching
        val error = startError.takeIf { !lifecycle.exiting && machine == 0L && progress == null }
        val state = when {
            lifecycle.exiting -> getString(R.string.stopping)
            machine == 0L && progress != null -> progress
            error != null -> getString(R.string.start_failed_title)
            n == 0 && machine != 0L -> getString(R.string.background_running)
            else -> resources.getQuantityString(R.plurals.sessions_running, n, n)
        }
        val privacyEnabled = Settings.privacyMode(this)
        val text = if (!privacyEnabled && error != null) error else getString(runPolicy.label)
        val builder = Notification.Builder(this, Notifications.SESSIONS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (privacyEnabled) getString(R.string.settings_privacy) else state)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.exit_all), exit).build())
        if (sw != null) {
            // A distro switch download: progress on the second line, cancellable from the
            // notification
            val cancel = Notifications.serviceAction(this, 2, ACTION_CANCEL_SWITCH)
            if (!privacyEnabled) {
                builder.setSubText(getString(R.string.downloading, sw.distro.label, sw.done shr 20, sw.total shr 20))
                if (sw.total > 0) builder.setProgress(sw.total.toInt(), sw.done.toInt(), false)
            }
            builder.addAction(Notification.Action.Builder(null, getString(R.string.cancel_download), cancel).build())
        }
        if (!lifecycle.exiting) {
            val toggle = Notifications.serviceAction(this, 3, ACTION_TOGGLE_KEEP_AWAKE)
            val label = getString(Settings.nextRunPolicy(this).label)
            builder.addAction(Notification.Action.Builder(null, label, toggle).build())
        }
        return builder.build()
    }

    private fun updateNotification() {
        if (lifecycle.destroyed) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    /**
     * How this app's process [pid] ended, as Android recorded it; null when that is unknown (no pid,
     * or the record is gone: Android keeps a limited number per app).
     */
    private fun lastExit(pid: Int): ApplicationExitInfo? {
        if (pid <= 0) return null
        return runCatching {
            getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(packageName, pid, 1).firstOrNull()
        }.onFailure { Log.w(TAG, "cannot read how the last run ended", it) }.getOrNull()
    }

    /**
     * The start of the restore notice for a run that ended with [reason] (null when unknown).
     * Android reports the low-memory killer only where `isLowMemoryKillReportSupported`; elsewhere
     * such a kill is a plain signal and gets the general text.
     */
    @SuppressLint("InlinedApi") // REASON_PACKAGE_UPDATED (API 34) is a constant earlier versions never report
    private fun endedText(reason: Int?): Int = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> R.string.ended_memory
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> R.string.ended_resources
        ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> R.string.ended_user
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> R.string.ended_updated
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> R.string.ended_crash
        ApplicationExitInfo.REASON_ANR -> R.string.ended_anr
        else -> R.string.ended_android
    }

    /**
     * Starts the kernel, importing first when no store is ready: on the first run the Alpine in
     * the APK (other distros are switched to in Settings); after an import or switch that the app
     * was killed in, or that failed, the same distro again, its download reused when it is still
     * in the cache.
     */
    private fun startOrImport() = start(if (Rootfs.isReady(this)) null else Rootfs.importing(this))

    /** Owns the new handle and browser endpoint until both can be handed to the main thread. */
    private fun startKernel(tier: Int, gpu: Boolean, importing: Boolean): StartedKernel {
        val store = Rootfs.store(this).path
        val distro = Rootfs.installed(this) ?: Rootfs.Distro.ALPINE
        var openRcDone = false
        val browser = prepareStartBridge(
            importing = importing,
            onFailure = { step, e ->
                Log.w(TAG, "cannot prepare $step; continuing startup", e)
                Diagnostics.record("Per-start configuration skipped ($step):\n${Log.getStackTraceString(e)}")
            },
            openBridge = {
                BrowserBridge { url ->
                    machine != 0L && !lifecycle.destroyed && listeners.any { it.onOpenUrl(url) }
                }
            },
            configure = { bridge, onFailure ->
                val prep = Rootfs.prepareStart(
                    context = this,
                    store = store,
                    distro = distro,
                    gpu = gpu,
                    browserConfiguration = bridge?.configuration,
                    importing = importing,
                    onFailure = onFailure,
                )
                openRcDone = prep.openRcDone
                prep.browserReady
            },
        )
        var handle = 0L
        try {
            // Exclude DocumentsProvider opens while the kernel opens the store.
            handle = Rootfs.access.write { Native.start(store, tier, gpu) }
            val gpuAvailable = gpu && Native.hasGpu(handle)
            var setupError: String? = null
            val booted = distro == Rootfs.Distro.ALPINE && !openRcDone &&
                Rootfs.ensureOpenRc(this, handle, store) { e ->
                    if (importing) throw e
                    Log.w(TAG, "OpenRC setup will retry at the next start", e)
                    setupError = e.message ?: e.toString()
                }
            return StartedKernel(handle, browser, booted, gpuAvailable, setupError)
        } catch (e: Throwable) {
            browser?.close()
            if (handle != 0L) Native.stop(handle)
            throw e
        }
    }

    /**
     * Starts the kernel; when [distro] is non-null, prepares its rootfs first (the store must
     * already be deleted).
     */
    private fun start(distro: Rootfs.Distro?) {
        if (lifecycle.destroyed) return
        startError = null
        lifecycle.starting = true
        updateWakeLock()
        if (distro != null) preparing(getString(R.string.preparing, distro.label))
        val tier = tier
        val gpu = gpu
        thread(name = "appine-start") {
            val result = runCatching {
                if (distro != null) {
                    Rootfs.prepare(this, distro, startMirrorProbes = Rootfs::probeMirrors, progress = { done, total ->
                        val text = getString(R.string.downloading, distro.label, done shr 20, total shr 20)
                        main.post { if (!lifecycle.destroyed) preparing(text) }
                    })
                }
                startKernel(tier, gpu, importing = distro != null)
            }
            main.post {
                lifecycle.completeStart(
                    result,
                    shutdown = Native::shutdown,
                    started = ::started,
                    failed = { startFailed(distro, it) },
                    exit = { kernel ->
                        progress = null
                        finishExit(kernel?.handle ?: 0L, booted = kernel?.booted == true)
                    },
                )
            }
        }
    }

    /**
     * Publishes the kernel, restores the tabs of a killed run, boots if needed and starts polling;
     * or replaces the store at once when a distro download finished meanwhile.
     */
    private fun started(kernel: StartedKernel) {
        val m = kernel.handle
        browserBridge = kernel.browser
        consoleTracker = ConsoleTracker(
            takeKills = { Native.takeKills(m) },
            onDrainedKills = { kills, epoch ->
                main.post {
                    if (machine != m) return@post
                    kills.forEach { reportKill(it, epoch) }
                }
            },
        )
        loginRules = Rootfs.LoginRules(m)
        autoRoots = Documents.AutoRootsWatcher(m)
        machine = m
        gpuAvailable = kernel.gpuAvailable
        lifecycle.booting = true
        guestActivity.reset(SystemClock.elapsedRealtime())
        progress = null
        // The store may have just been imported (first run, distro switch)
        Documents.rootsChanged(this)
        val next = pendingReplace
        if (next != null) {
            // A distro download finished while this kernel was starting
            pendingReplace = null
            replace(next)
            return
        }
        updateWakeLock()
        kernel.setupError?.let(::tell)
        Tabs.markRunning(this)
        restore?.let { saved -> restoreTabs(saved) }
        restore = null
        main.removeCallbacks(periodicSave)
        main.postDelayed(periodicSave, SAVE_MS)
        // Booting waits for the sysinit and wait commands (OpenRC starting services) without
        // occupying worker: tabs open as usual
        thread(name = "appine-boot") {
            if (!kernel.booted) Rootfs.boot(m)
            // Boot finished: notify when OpenRC services did not start (the output is in
            // boot.log, which nobody reads)
            val failed = Rootfs.failedServices(this)
            main.post {
                if (machine != m) return@post
                lifecycle.booting = false
                guestActivity.reset(SystemClock.elapsedRealtime())
                updateWakeLock()
                if (failed.isNotEmpty()) notifications.bootFailures(failed)
            }
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
        watchingNetwork = true
        main.post(poll)
        updateNotification()
        each { l -> l.onMachineReady(null) }
    }

    /**
     * The start failed with [e], after preparing [distro]'s rootfs when it is non-null: falls back
     * to Alpine, replaces the store with a distro downloaded meanwhile, or reports the failure.
     */
    private fun startFailed(distro: Rootfs.Distro?, e: Throwable) {
        Log.e(TAG, "cannot start", e)
        Diagnostics.record("Cannot start:\n${Log.getStackTraceString(e)}")
        val error = e.message ?: e.toString()
        if (distro != null && distro != Rootfs.Distro.ALPINE && !Rootfs.isReady(this)) {
            // The import failed while switching to another distro (the download was already
            // verified, so most likely space ran out) or while resuming one the app was killed
            // in (its download may have been needed again): the old store is already deleted,
            // so drop the abandoned tarball and fall back to the Alpine in the APK rather than
            // leave the user facing an app that does not work
            Rootfs.deleteTarball(this, distro)
            tell(getString(R.string.switch_failed, distro.label, error, Rootfs.Distro.ALPINE.label))
            start(Rootfs.Distro.ALPINE)
        } else {
            val next = pendingReplace
            pendingReplace = null
            if (next != null) {
                // A distro download finished while this kernel was starting: replacing the store
                // is also how a failed one is recovered
                replace(next)
            } else {
                progress = null
                startError = error
                updateWakeLock()
                updateNotification()
                each { l -> l.onMachineReady(error) }
                stopIfFailed()
            }
        }
    }

    /**
     * ALWAYS holds throughout the kernel's lifetime. BUSY protects lifecycle transitions too,
     * but allows sleep after 30 seconds without guest CPU activity. The normal poll can reacquire
     * the lock only while Android is awake; it is not an alarm that wakes a suspended device.
     */
    // User-selected policy; BUSY releases through the poll, ALWAYS has no fixed duration.
    @SuppressLint("WakelockTimeout", "Wakelock")
    private fun updateWakeLock() {
        val want = lifecycle.wantsWakeLock(runPolicy, machine, switching != null) {
            guestActivity.isBusy(SystemClock.elapsedRealtime())
        }
        val held = wakeLock
        if (want && held == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } else if (!want && held != null) {
            held.release()
            wakeLock = null
        } else {
            return
        }
        updateNotification()
    }

    private fun preparing(text: String) {
        progress = text
        updateNotification()
        each { it.onProgress(text) }
    }

    /**
     * Records something to tell the user; a visible UI takes it at once ([takeNotice]), otherwise
     * it waits for the UI to come back.
     */
    private fun tell(text: String) {
        notice = text
        each { it.onNotice() }
    }

    /** Takes what there is to tell the user (given to one UI only). */
    fun takeNotice(): String? = notice.also { notice = null }

    /** Reads the current kernel's bindings on the worker and delivers the snapshot on main. */
    internal fun portMappings(result: (List<PortMapping>?) -> Unit) {
        val handle = machine
        if (handle == 0L) {
            result(null)
            return
        }
        worker.execute {
            val mappings = Native.readPortMappings(handle)
            main.post { result(if (machine == handle) mappings else null) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT_ALL -> exitAll()
            ACTION_CANCEL_SWITCH -> cancelSwitch()
            ACTION_TOGGLE_KEEP_AWAKE -> setRunPolicy(Settings.nextRunPolicy(this))
            // Legacy report PendingIntents still pass through the clipboard privacy check.
            Notifications.ACTION_COPY_REPORT -> notifications.copyReport(intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    /**
     * The user changed the system's or the app's language while the service runs: the channels'
     * names and the ongoing notification follow it (the other notifications keep the language
     * they were posted in).
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (notifications.createChannels()) updateNotification()
    }

    fun addListener(l: Listener) {
        if (l !in listeners) listeners.add(l)
        main.removeCallbacks(trimWhenHidden)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
        if (listeners.isEmpty()) {
            main.removeCallbacks(trimWhenHidden)
            main.postDelayed(trimWhenHidden, TRIM_DELAY_MS)
            // The UI is gone and the app may be killed at any time: save the tabs first
            saveTabs()
            stopIfFailed()
        }
    }

    /**
     * The start failed and no UI is left to show it: stop rather than keep a foreground service
     * and its notification with nothing running (without a kernel there is no poll to end it).
     * Opening the app again tries again. A distro switch still downloading keeps the service (its
     * progress and cancel action are in the notification) until it ends one way or the other.
     */
    private fun stopIfFailed() {
        if (machine == 0L && startError != null && switching == null && listeners.isEmpty() &&
            !lifecycle.starting && !lifecycle.transitioning && !lifecycle.exiting
        ) {
            stopSelf()
        }
    }

    /** A UI may remove itself during a callback, so iterate over a copy. */
    private fun each(f: (Listener) -> Unit) = listeners.toList().forEach(f)

    /**
     * Opens a new terminal session (a login shell), logged in as [account] and starting in [cwd]
     * (null is the home directory). The shell starts when the window size is first known.
     */
    internal fun createSession(account: String = login, cwd: String? = null): VtSession {
        val pty = ConsolePty(machine, ConsolePty.script(this), account, cwd, consoleTracker)
        val session = VtSession(nextSessionId++, pty, machine, sessionEvents)
        session.setCursor(cursorStyle, cursorBlink)
        sessions.add(session)
        sessionsChanged()
        return session
    }

    /** Closes a session: the shell receives SIGHUP. */
    internal fun removeSession(session: VtSession) {
        session.release()
        sessions.remove(session)
        unshown.remove(session)
        backgroundImages.remove(session.id)
        if (current === session) current = null else scheduleBackgroundImageTrim()
        notifications.cancelTab(session)
        sessionsChanged()
    }

    /**
     * Whether [session] is the tab in front of the user: the terminal Activity shows it while
     * visible (not another tab, and not Settings in front of the terminal).
     */
    private fun inFront(session: VtSession) = !Privacy.locked(this) &&
        session === current && listeners.any { it is MainActivity }

    private fun sessionsChanged() {
        updateNotification()
        each { it.onSessionsChanged() }
        main.removeCallbacks(saveSoon)
        if (machine != 0L) main.postDelayed(saveSoon, SAVE_SOON_MS)
    }

    /**
     * Restores the tabs from when the app was last killed (the android/service map): each starts a
     * new shell, logged in as the original account and starting in the original directory, below
     * its terminal as it was (from its snapshot, else its transcript) and a line of notice.
     * Processes are not restored.
     */
    private fun restoreTabs(saved: Tabs.Saved) {
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(saved.time))
        val notice = "\u001b[33m[appine] " + getString(R.string.restored_notice, getString(ended), time) +
            "\u001b[0m\r\n\r\n"
        for (tab in saved.tabs) {
            val session = createSession(tab.login, tab.cwd)
            val body = tab.transcript.trimEnd().replace("\r\n", "\n").replace("\n", "\r\n")
            // Read when the tab is first shown, so tabs never shown cost no memory
            val snapshot = tab.snapshot?.let { name -> { Tabs.readSnapshot(this, name) } }
            session.setRestored(
                (if (body.isEmpty()) "" else body + "\r\n").toByteArray(),
                snapshot,
                notice.toByteArray(),
            )
            unshown[session] = tab
        }
        focus = sessions.getOrNull(saved.current) ?: sessions.lastOrNull()
    }

    /**
     * Saves the tabs ([Tabs]): the running session list and unshown restored records are captured
     * on the main thread; transcripts (which can decompress cold scrollback pages), foreground
     * working directories (which need the BKL), terminal snapshots and writing the files all run
     * on [worker]. Tabs that have ended are not saved.
     */
    private fun saveTabs() {
        val m = machine
        if (m == 0L) return
        main.removeCallbacks(saveSoon)
        val live = sessions.filter { it.isRunning }
        val taken = live.map { s ->
            // A restored tab not shown yet: its shell has not started, so save the original copy
            val old = if (s.tab == 0L) unshown[s] else null.also { unshown.remove(s) }
            s to old
        }
        val index = live.indexOf(current).coerceAtLeast(0)
        runCatching {
            worker.execute {
                val tabs = taken.map { (session, old) ->
                    val transcript = session.takeTranscript() ?: old?.transcript.orEmpty()
                    val console = session.console
                    val cwd = if (console >= 0) Native.consoleCwd(m, console) else old?.cwd
                    val snapshot = if (console >= 0) snapshotOf(session) else old?.snapshot
                    // An "auto" tab is saved as the account it logged in as: the setting would be
                    // resolved again at restore and might name another user by then
                    val login = if (session.login != ConsolePty.LOGIN_AUTO) {
                        session.login
                    } else {
                        session.resolvedLogin
                            ?: Rootfs.loginAccount(this, ConsolePty.LOGIN_AUTO).name.also { session.resolvedLogin = it }
                    }
                    Tabs.Tab(login, cwd, transcript, snapshot)
                }
                Tabs.save(this, tabs, index)
            }
        }
    }

    /**
     * The file of [session]'s terminal snapshot for saving it (on [worker]): written anew when the
     * terminal changed since the last save, else the last one; null when none can be taken.
     */
    private fun snapshotOf(session: VtSession): String? {
        val snapshot = session.takeSnapshot() ?: return session.savedSnapshot
        val name = if (snapshot.isEmpty()) null else Tabs.writeSnapshot(this, snapshot)
        session.savedSnapshot = name
        return name
    }

    /**
     * An entry of [Native.takeKills]: the kernel layer ended a process (kernel bug, engine fault,
     * OOM killer). When its controlling terminal is a tab, the reason is explained in that tab (the
     * shell only prints "Killed" or "Segmentation fault"); when it is not in a tab (a background
     * service), when the tab is in a full-screen program, or when the UI is gone, a notification is
     * posted. Appine's own bugs always get a notification, with "Copy report" on it
     * ([Notifications.kill]).
     */
    private fun reportKill(line: String, epoch: Long) {
        val f = line.split('\t')
        if (f.size != 7) return
        val console = f[0].toIntOrNull() ?: return
        val pid = f[1].toIntOrNull() ?: return
        val comm = f[2]
        // memory.max's 16M is 16 MiB: use powers of 1024, otherwise the limit shows as 17 MB and
        // the overrun is not visible
        val size = { bytes: String ->
            val b = bytes.toLongOrNull() ?: 0
            if (b >= 1L shl 30) DecimalFormat("0.#").format(b / 1073741824.0) + " GiB"
            else DecimalFormat("0.#").format(b / 1048576.0) + " MiB"
        }
        val bug = f[3] == "bug"
        val text = when (f[3]) {
            "oom" -> if (f[4].isEmpty()) {
                getString(R.string.killed_oom_app, comm, pid, size(f[5]), size(f[6]))
            } else {
                getString(R.string.killed_oom_cgroup, comm, pid, f[4], size(f[5]), size(f[6]))
            }
            else -> getString(R.string.killed_bug, comm, pid)
        }
        val session = sessionForKill(sessions, console, epoch)
        val shown = session?.showMessage("[appine] $text") == true
        // Enough only when that tab is the one in front of the user
        if (shown && inFront(session) && !bug) return
        notifications.kill(comm, text, if (bug) f.subList(4, 7) else null)
    }

    private fun setRoutes(routes: Array<String>) {
        val m = machine
        // Callbacks may interleave with onDestroy: once worker is shut down there is no need to
        // hand it over
        if (m != 0L) runCatching { worker.execute { Native.setRoutes(m, routes) } }
    }

    /**
     * When host memory is short, drops the kernel's rebuildable caches at once (the Tier 1
     * translations kept in the pool take up to 70–80 MB), cuts the images of the tabs other than
     * [current] down, and compresses the tabs' scrollback ([trimMemory]). Since Android 14 the
     * `RUNNING_*` levels are no longer sent and a foreground service never receives cached
     * background trims; UI hiding is handled by [removeListener] and inactive background tabs by
     * [backgroundImages].
     */
    @Suppress("DEPRECATION") // RUNNING_LOW and RUNNING_CRITICAL are sent only before Android 14
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN) {
            // Save before trimming on the serial worker so compressAll re-compresses any cold
            // scrollback pages touched by transcript formatting.
            saveTabs()
            trimMemory()
        }
    }

    /**
     * Drops the kernel's rebuildable caches, deletes the oldest Kitty images of every started tab
     * other than [current] (the one shown, or to be shown when the app comes back) down to a few
     * MiB per screen ([VtNative.dropImages]), and compresses every tab's cold scrollback,
     * including that of tabs whose output never paused long enough for the idle compression
     * (`vt.rs`).
     */
    private fun trimMemory() {
        val m = machine
        if (m == 0L) return
        val active = current?.takeIf { it in sessions }
        val started = sessions.filter { it.tab != 0L }
        val others = started.filter { it !== active }.map { it.tab }
        backgroundImages.markAllTrimmed(active?.id, started.map { it.id })
        main.removeCallbacks(trimBackgroundImages)
        runCatching {
            worker.execute {
                Native.trimMemory(m)
                others.forEach(VtNative::dropImages)
                VtNative.compressAll()
            }
        }
    }

    /** Schedules [trimBackgroundImages] for the next pending background tab, or cancels it. */
    private fun scheduleBackgroundImageTrim() {
        main.removeCallbacks(trimBackgroundImages)
        val delay = backgroundImages.nextDelayMs(SystemClock.elapsedRealtime()) ?: return
        main.postDelayed(trimBackgroundImages, delay)
    }

    /** Trims Kitty images on [worker] for every started non-current session whose id is in [ids]. */
    private fun dropImagesForIds(ids: Collection<Int>) {
        if (machine == 0L || ids.isEmpty()) return
        val active = current
        val tabs = sessions.filter { it !== active && it.tab != 0L && it.id in ids }.map { it.tab }
        if (tabs.isEmpty()) return
        runCatching {
            worker.execute { tabs.forEach(VtNative::dropImages) }
        }
    }

    // The settings ([Settings]), which the setters below act on

    val login: String get() = Settings.login(this)

    /**
     * A login account was chosen in Settings: tabs opened from now on use it, open ones are
     * unchanged; the home directory in the Files app follows.
     */
    fun setLogin(value: String) {
        Settings.setLogin(this, value)
        Documents.rootsChanged(this)
        settingsChanged()
    }

    internal val runPolicy: RunPolicy get() = Settings.runPolicy(this)

    internal fun setRunPolicy(policy: RunPolicy) {
        if (policy == runPolicy) return
        Settings.setRunPolicy(this, policy)
        guestActivity.reset(SystemClock.elapsedRealtime())
        updateWakeLock()
        settingsChanged()
    }

    val fileAccess: Boolean get() = Settings.fileAccess(this)

    fun setFileAccess(on: Boolean) {
        if (on && Settings.privacyMode(this)) return
        Settings.setFileAccess(this, on)
        if (!on) Documents.revokeAccess(this)
        Documents.rootsChanged(this)
        settingsChanged()
    }

    val fillWide: Boolean get() = Settings.fillWide(this)

    fun setFillWide(on: Boolean) {
        Settings.setFillWide(this, on)
        settingsChanged()
    }

    val cursorStyle: Int get() = Settings.cursorStyle(this)

    fun setCursorStyle(style: Int) {
        Settings.setCursorStyle(this, style)
        cursorChanged()
    }

    val cursorBlink: Boolean get() = Settings.cursorBlink(this)

    fun setCursorBlink(on: Boolean) {
        Settings.setCursorBlink(this, on)
        cursorChanged()
    }

    /** Gives every tab, restored ones not shown yet included, the cursor settings. */
    private fun cursorChanged() {
        val style = cursorStyle
        val blink = cursorBlink
        sessions.forEach { it.setCursor(style, blink) }
        settingsChanged()
    }

    val volumeKeysSwitchTabs: Boolean get() = Settings.volumeKeysSwitchTabs(this)

    fun setVolumeKeysSwitchTabs(on: Boolean) {
        Settings.setVolumeKeysSwitchTabs(this, on)
        settingsChanged()
    }

    val showTabList: Boolean get() = Settings.showTabList(this)

    fun setShowTabList(on: Boolean) {
        Settings.setShowTabList(this, on)
        settingsChanged()
    }

    val tier: Int get() = Settings.tier(this)

    /** The execution tier was changed in Settings: record it and restart the kernel. */
    fun setTier(value: Int) {
        if (value == tier) return
        Settings.setTier(this, value)
        restart()
    }

    val gpu: Boolean get() = Settings.gpu(this)

    /** The experimental Vulkan GPU toggle was changed in Settings: record it and restart the kernel. */
    fun setGpu(on: Boolean) {
        if (on == gpu) return
        Settings.setGpu(this, on)
        restart()
    }

    private fun settingsChanged() {
        updateNotification()
        each { it.onSettingsChanged() }
    }

    /**
     * Switching distros or resetting the rootfs is possible: the kernel is running, or the start
     * failed (this is how a broken store is recovered).
     */
    val canSwitch: Boolean get() = switching == null && downloader == null && (machine != 0L || startError != null)

    /**
     * Switches to [distro] (from Settings; resetting the rootfs switches to the installed one). The
     * tarball is first downloaded and verified in the background while the old system keeps
     * running, which can be cancelled ([cancelSwitch]); only when the download is done are all
     * sessions ended, the kernel stopped and the store deleted, and the new one imported. If the
     * download fails the old system stays untouched.
     */
    internal fun switchTo(distro: Rootfs.Distro) {
        if (!canSwitch) return
        val sw = Switch(distro)
        switching = sw
        downloader = sw
        updateWakeLock()
        settingsChanged()
        thread(name = "appine-download") {
            val result = runCatching {
                Rootfs.download(this, distro, progress = { done, total ->
                    sw.done = done
                    sw.total = total
                    main.post { if (switching === sw) settingsChanged() }
                }, cancelled = { sw.cancelled })
            }
            main.post {
                if (downloader === sw) downloader = null
                if (switching !== sw) {
                    // Already cancelled: the result is not wanted, it is just that switching is
                    // possible again now
                    settingsChanged()
                    return@post
                }
                switching = null
                result.onSuccess {
                    when {
                        machine != 0L || startError != null -> replace(distro)
                        // The kernel is restarting (a guest reboot): replace once it is up
                        lifecycle.starting || lifecycle.transitioning -> pendingReplace = distro
                    }
                }.onFailure {
                    if (it !is Rootfs.Cancelled) {
                        Log.w(TAG, "cannot download ${distro.label}", it)
                        tell(getString(R.string.download_failed, distro.label, it.message ?: it.toString()))
                    }
                    // The old system stays: after a failed start, nothing is left to keep running
                    stopIfFailed()
                }
                updateWakeLock()
                settingsChanged()
            }
        }
    }

    /** No switch after all: abort the download (keeping no partial file). */
    fun cancelSwitch() {
        val sw = switching ?: return
        sw.cancelled = true
        switching = null
        updateWakeLock()
        settingsChanged()
        stopIfFailed()
    }

    /**
     * After [switchTo] has downloaded: ends all sessions, stops the kernel (there is none if the
     * start failed), deletes the store and imports [distro]. Stopping the kernel and deleting files
     * are done on [worker].
     */
    private fun replace(distro: Rootfs.Distro) {
        if (lifecycle.destroyed) return
        // The old accounts and path-based document grants belong to the store being removed.
        Settings.clearLogin(this)
        Documents.revokeAccess(this)
        Documents.rootsChanged(this)
        lifecycle.transitioning = true
        val m = detach()
        startError = null
        preparing(getString(R.string.preparing, distro.label))
        worker.execute {
            if (m != 0L) Native.stop(m)
            Rootfs.reset(this)
            // Revoke again after deletion in case a grant was issued while stopping the old kernel.
            Documents.revokeAccess(this)
            Documents.rootsChanged(this)
            main.post { lifecycle.completeTransition(distro, ::start, ::finishExit) }
        }
    }

    /**
     * Restart (the guest's `reboot`, or the execution tier or Vulkan GPU changed in Settings):
     * shut down, then start a new kernel (after which the Activity opens new sessions).
     */
    private fun restart() {
        if (lifecycle.destroyed || machine == 0L) return
        lifecycle.transitioning = true
        val m = detach()
        each { it.onRestarting() }
        worker.execute {
            Native.shutdown(m)
            main.post { lifecycle.completeTransition(null, ::start, ::finishExit) }
        }
    }

    /**
     * Exit all (the button in the notification, the guest's `poweroff`, or no guest processes left
     * after the last tab was closed): first shut down by inittab (OpenRC stops the services, so
     * PostgreSQL and the like need no crash recovery next time), and only then stop the service;
     * meanwhile the foreground service and the notification remain, so the app process is not
     * killed as a cached process.
     */
    fun exitAll() {
        if (lifecycle.destroyed || lifecycle.exiting) return
        lifecycle.exiting = true
        // Activities release only in onStop, and the shutdown may be faster than that: treat them
        // as already gone, and a UI present after the shutdown means the user opened the app again
        switching?.cancelled = true
        switching = null
        pendingReplace = null
        each { it.onExitAll() }
        listeners.clear()
        val m = detach()
        updateNotification()
        // A start, restart or replacement in progress finishes exiting itself once its kernel is up
        // (or has failed): there is nothing to shut down yet, and stopping the service now would
        // leave that kernel running
        if (lifecycle.starting || lifecycle.transitioning) return
        finishExit(m)
    }

    /**
     * The rest of [exitAll]: shuts [m] down by inittab (or, not [booted] yet, just stops it), then
     * stops the service.
     */
    private fun finishExit(m: Long, booted: Boolean = true) {
        lifecycle.finishExit(m, worker, stop = { handle ->
            if (booted) Native.shutdown(handle) else Native.stop(handle)
        }) {
            main.post {
                lifecycle.completeExit(
                    hasRestore = restore != null, hasListeners = listeners.isNotEmpty(),
                    clearTabs = { Tabs.clear(this) }, startOrImport = ::startOrImport, stopSelf = ::stopSelf,
                )
            }
        }
    }

    /**
     * Stops using the current kernel: stops listening to network callbacks and polling, ends all
     * sessions, and returns the old handle. The handle is cleared first: otherwise, seeing the
     * kernel still there when the sessions end, the Activity would think the last tab was closed.
     */
    private fun detach(): Long {
        browserBridge?.close()
        browserBridge = null
        val m = machine
        if (watchingNetwork) {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network)
            watchingNetwork = false
        }
        machine = 0
        gpuAvailable = false
        lifecycle.booting = false
        updateWakeLock()
        main.removeCallbacks(poll)
        main.removeCallbacks(periodicSave)
        releaseSessions()
        consoleTracker = null
        loginRules = null
        autoRoots = null
        sessionsChanged()
        return m
    }

    /** Ends every session (its shell receives SIGHUP) and takes its notification away. */
    private fun releaseSessions() {
        for (s in sessions) {
            s.release()
            notifications.cancelTab(s)
        }
        sessions.clear()
        unshown.clear()
        backgroundImages.clear()
        main.removeCallbacks(trimBackgroundImages)
        current = null
    }

    override fun onDestroy() {
        Privacy.removeListener(privacyChanged)
        lifecycle.destroyed = true
        browserBridge?.close()
        browserBridge = null
        // A download still under way has nothing left to replace
        switching?.cancelled = true
        switching = null
        main.removeCallbacks(trimWhenHidden)
        main.removeCallbacks(trimBackgroundImages)
        main.removeCallbacks(poll)
        main.removeCallbacks(periodicSave)
        main.removeCallbacks(saveSoon)
        // The service stopped normally (after exitAll, or a failed start with no UI): do not restore
        // next time, except tabs of a killed run that were never restored. Being killed never gets
        // here
        if (restore == null) Tabs.clear(this)
        if (watchingNetwork) getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network)
        val m = machine
        machine = 0
        gpuAvailable = false
        releaseSessions()
        consoleTracker = null
        loginRules = null
        autoRoots = null
        // Stopped without going through exitAll: shut down anyway, best effort. It has to wait for
        // guest threads to exit, so not on the main thread
        if (m != 0L) worker.execute { Native.shutdown(m) }
        worker.shutdown()
        updateWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TerminalService"

        /**
         * Finds the tab that owned [console] when kill reports were taken at [epoch]; disjoint
         * `[openedEpoch, closedEpoch]` intervals prevent misdelivery when a new tab reuses a freed
         * console number.
         */
        internal fun sessionForKill(sessions: List<VtSession>, console: Int, epoch: Long): VtSession? {
            if (console < 0) return null
            return sessions.firstOrNull { it.ownedConsoleAt(console, epoch) }
        }

        /**
         * The format of [Native.setRoutes]. Before Android 13 the route type cannot be told, so all
         * count as unicast.
         */
        fun routes(lp: LinkProperties): Array<String> = lp.routes.mapNotNull { r ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && r.type != RouteInfo.RTN_UNICAST) {
                return@mapNotNull null
            }
            val iface = r.`interface` ?: lp.interfaceName ?: return@mapNotNull null
            val dst = r.destination
            val gateway = r.gateway?.takeIf { r.hasGateway() }?.address?.let(::hex) ?: "-"
            "${hex(dst.rawAddress)}/${dst.prefixLength} $gateway $iface"
        }.toTypedArray()

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        /** The ongoing notification (the other ids are [Notifications]'). */
        private const val NOTIFICATION_ID = 1
        private const val POLL_MS = GuestActivity.SAMPLE_MS
        private const val ACTION_EXIT_ALL = "io.github.mthli.appine.EXIT_ALL"
        private const val ACTION_CANCEL_SWITCH = "io.github.mthli.appine.CANCEL_SWITCH"
        private const val ACTION_TOGGLE_KEEP_AWAKE = "io.github.mthli.appine.TOGGLE_KEEP_AWAKE"
        private const val WAKE_LOCK_TAG = "appine:keep-awake"
        private const val TRIM_DELAY_MS = BackgroundImageTrim.DELAY_MS
        private const val SAVE_MS = 60_000L
        private const val SAVE_SOON_MS = 2_000L
    }
}
