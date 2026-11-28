package io.github.mthli.appine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.net.InetAddresses
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class RunPolicyTest {
    // Keep these tests independent of the user's settings and running kernel.
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("run-policy-test-$name", mode)
    }
    private val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Before
    @After
    fun clear() { prefs.edit(commit = true) { clear() } }

    @Test
    fun oldSwitchRetainsItsBehaviour() {
        assertEquals(RunPolicy.SLEEP, Settings.runPolicy(context))
        assertEquals(RunPolicy.ALWAYS, Settings.nextRunPolicy(context))
        prefs.edit { putBoolean("keep_awake", true) }
        assertEquals(RunPolicy.ALWAYS, Settings.runPolicy(context))
        assertEquals(RunPolicy.SLEEP, Settings.nextRunPolicy(context))
        Settings.setRunPolicy(context, RunPolicy.BUSY)
        assertFalse(prefs.contains("keep_awake"))
        assertEquals(RunPolicy.BUSY, Settings.runPolicy(context))
    }

    @Test
    fun notificationRemembersTheLastNonAlwaysChoice() {
        Settings.setRunPolicy(context, RunPolicy.BUSY)
        repeat(2) {
            Settings.setRunPolicy(context, Settings.nextRunPolicy(context))
            assertEquals(RunPolicy.ALWAYS, Settings.runPolicy(context))
            assertEquals(RunPolicy.BUSY, Settings.nextRunPolicy(context))
            Settings.setRunPolicy(context, Settings.nextRunPolicy(context))
            assertEquals(RunPolicy.BUSY, Settings.runPolicy(context))
        }
        Settings.setRunPolicy(context, RunPolicy.SLEEP)
        Settings.setRunPolicy(context, RunPolicy.ALWAYS)
        assertEquals(RunPolicy.SLEEP, Settings.nextRunPolicy(context))
    }

    @Test
    fun idleGraceHandlesActivityFailuresAndStaleResults() {
        val activity = GuestActivity()
        activity.reset(0)
        activity.sample(10, 0)
        activity.sample(10, GuestActivity.IDLE_MS - 1)
        assertTrue(activity.isBusy(GuestActivity.IDLE_MS - 1))
        assertFalse(activity.isBusy(GuestActivity.IDLE_MS))
        activity.sample(11, 40_000)
        assertTrue(activity.isBusy(40_000))
        assertFalse(activity.isBusy(70_000))
        activity.sample(-1, 70_000)
        assertTrue(activity.isBusy(70_000))
        activity.reset(100_000)
        activity.sample(12, 80_000)
        assertTrue(activity.isBusy(129_999))
        assertFalse(activity.isBusy(130_000))
        activity.sample(0, 130_000)
        assertTrue(activity.isBusy(130_000))
        activity.sample(1, 131_000, 180_000)
        assertTrue(activity.isBusy(180_000))
        assertFalse(activity.isBusy(210_000))
    }

    @Test
    fun killRoutingIgnoresReusedConsoleNumbers() {
        val pendingKills = ArrayDeque<String>()
        val drained = mutableListOf<Pair<List<String>, Long>>()
        val tracker = ConsoleTracker(
            takeKills = {
                val taken = pendingKills.toTypedArray()
                pendingKills.clear()
                taken
            },
            onDrainedKills = { kills, epoch -> drained.add(kills.toList() to epoch) },
        )
        val events = Proxy.newProxyInstance(
            VtSession.Events::class.java.classLoader, arrayOf(VtSession.Events::class.java),
        ) { _, _, _ -> null } as VtSession.Events
        val consoleField = ConsolePty::class.java.getDeclaredField("console").apply { isAccessible = true }
        val openedField = ConsolePty::class.java.getDeclaredField("openedEpoch").apply { isAccessible = true }
        val closedField = ConsolePty::class.java.getDeclaredField("closedEpoch").apply { isAccessible = true }

        fun openSession(id: Int, console: Int): Pair<VtSession, ConsolePty> {
            val pty = ConsolePty(0L, "", "root", tracker = tracker)
            val (c, opened) = tracker.open { console }
            consoleField.setInt(pty, c)
            openedField.setLong(pty, opened)
            return VtSession(id, pty, 0L, events) to pty
        }

        val (first, firstPty) = openSession(1, 0)
        // A poll taken before first closes captures a kill on console 0.
        pendingKills.add("0\t10\tsh\toom\t\t100\t50")
        val (_, polledEpoch) = tracker.take()
        // A second kill happens right as first's shell exits and is drained on close.
        pendingKills.add("0\t10\tsh\toom\t\t100\t50")
        tracker.close(close = {}, onClosed = { closedField.setLong(firstPty, it) })
        assertEquals(1, drained.size)
        val drainedEpoch = drained.single().second

        // A new tab immediately reuses console 0.
        val (second, secondPty) = openSession(2, 0)
        val both = listOf(first, second)

        // Both the earlier poll and the close-drained kill route to first, never second.
        assertSame(first, TerminalService.sessionForKill(both, 0, polledEpoch))
        assertSame(first, TerminalService.sessionForKill(both, 0, drainedEpoch))
        // If first was already closed/removed, its kill is not misdelivered to second.
        assertNull(TerminalService.sessionForKill(listOf(second), 0, polledEpoch))
        assertNull(TerminalService.sessionForKill(listOf(second), 0, drainedEpoch))

        // A kill taken after second opened routes to second, never ended first.
        pendingKills.add("0\t20\tawk\toom\t\t100\t50")
        val (_, secondEpoch) = tracker.take()
        assertSame(second, TerminalService.sessionForKill(both, 0, secondEpoch))
        // If second is later closed and removed, its kill is not misdelivered back to first.
        tracker.close(close = {}, onClosed = { closedField.setLong(secondPty, it) })
        assertNull(TerminalService.sessionForKill(listOf(first), 0, secondEpoch))
    }

    @Test
    fun listenEndpointsRespectTheBindAndUseTheHostPort() {
        val v4 = InetAddresses.parseNumericAddress("192.0.2.1")
        val v6 = InetAddresses.parseNumericAddress("2001:db8::1")
        val local = InetAddresses.parseNumericAddress("fe80::1")
        val lan = { sequenceOf(local, v4, v6) }
        assertEquals("192.0.2.1:10022", Notifications.listenEndpoint("*", 10022, lan))
        assertEquals("192.0.2.1:22", Notifications.listenEndpoint("0.0.0.0", 22, lan))
        assertEquals("[${v6.hostAddress}]:443", Notifications.listenEndpoint("::", 443, lan))
        assertEquals("[2001:db8::2]:443", Notifications.listenEndpoint("2001:db8::2", 443) { error("Exact bind") })
        assertNull(Notifications.listenEndpoint("::", 22) { sequenceOf(v4, local) })
        assertNull(Notifications.listenEndpoint("*", 22) { emptySequence() })
    }

    @Test
    fun listenNotificationKeysDistinguishHostPortRemappings() {
        val first = Notifications.listenKey(22, 10022)
        val second = Notifications.listenKey(22, 20022)
        val direct = Notifications.listenKey(22, 22)
        val swapped = Notifications.listenKey(10022, 10022)

        assertNotEquals(first, second)
        assertNotEquals(first, direct)
        assertNotEquals(first, swapped)
        assertEquals(first, Notifications.listenKey(22, 10022))
        // Listen IDs stay disjoint from ongoing (1), fixed reports (2..4), kills (100000+) and tabs (200000+).
        for (port in listOf(0, 22, 80, 10022, 65535)) {
            val (_, id) = Notifications.listenKey(port, port)
            assertTrue("listen ID $id out of expected range", id in 1000..66535)
        }

        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = target.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("network", "Network", NotificationManager.IMPORTANCE_HIGH),
        )
        fun post(key: Pair<String, Int>, text: String) {
            val n = Notification.Builder(target, "network")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("sshd")
                .setContentText(text)
                .build()
            manager.notify(key.first, key.second, n)
        }
        try {
            post(first, "port 10022")
            post(second, "port 20022")
            if (manager.areNotificationsEnabled()) {
                val active = manager.activeNotifications.map { it.tag to it.id }.toSet()
                assertTrue("Expected first listen notification $first in $active", first in active)
                assertTrue("Expected second listen notification $second in $active", second in active)
            }
        } finally {
            manager.cancel(first.first, first.second)
            manager.cancel(second.first, second.second)
        }
    }
}
