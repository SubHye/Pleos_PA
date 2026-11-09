package com.example.pleos_pa.connection

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pleos_pa.MainActivity
import com.example.pleos_pa.R
import com.example.pleos_pa.RuntimeMcpClient
import com.example.pleos_pa.awaitFocusedActivity
import com.example.pleos_pa.mcp.EmbeddedMcpServer
import com.example.pleos_pa.ui.DriverHistory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import org.hamcrest.CoreMatchers.sameInstance
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real production service, notification and sockets. MCP initialization/tools/list are real; no
 * vehicle tools/call is sent.
 */
@RunWith(AndroidJUnit4::class)
class BridgeServiceRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager
        get() = context.getSystemService(NotificationManager::class.java)

    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private fun await(label: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            Thread.sleep(50)
        }
        fail("Timed out: $label")
    }

    @Suppress("DEPRECATION")
    private fun serviceGone() =
        context
            .getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .none {
                it.service.packageName == context.packageName &&
                    it.service.className == BridgeService::class.java.name
            }

    private fun notificationAction(title: String): PendingIntent? =
        manager.activeNotifications
            .filter { it.packageName == context.packageName }
            .flatMap { it.notification.actions?.toList() ?: emptyList() }
            .singleOrNull { it.title.toString() == title }
            ?.actionIntent

    private fun exercise(test: (ActivityScenario<MainActivity>, MainActivity) -> Unit) {
        assertEquals("Diagnostic package only", "com.example.pleos_pa.com5", context.packageName)
        if (Build.VERSION.SDK_INT >= 33) {
            // Only our app's normal notification runtime permission; no Car permission adoption.
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        val intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        lateinit var activity: MainActivity
        scenario.onActivity { activity = it }
        try {
            awaitFocusedActivity(activity)
            await("no service before test") { serviceGone() }
            assertTrue(ConnectionStatusBoard.status is ConnectionStatus.Stopped)
            manager.cancelAll()
            test(scenario, activity)
        } finally {
            // Our diagnostic package only. Do not stop another application occupying the port.
            context.stopService(Intent(context, BridgeService::class.java))
            await("service cleanup") { serviceGone() }
            manager.cancelAll()
            scenario.close()
        }
    }

    private fun start(activity: MainActivity) {
        onView(withText(context.getString(R.string.action_start)))
            .inRoot(withDecorView(sameInstance(activity.window.decorView)))
            .perform(click())
        await("real listener ready; stop other callers manually if port 18766 is occupied") {
            if (ConnectionStatusBoard.status is ConnectionStatus.Failed) {
                fail("Real service failed: ${ConnectionStatusBoard.status}")
            }
            ConnectionStatusBoard.status is ConnectionStatus.Connected
        }
        RuntimeMcpClient().use { client ->
            val names = client.toolNames()
            assertEquals(20, names.size)
            assertTrue(names.contains("get_energy_level"))
        }
        // Closing an MCP client is not a server/AI disconnection signal under ADR-0008.
        assertTrue(ConnectionStatusBoard.status is ConnectionStatus.Connected)
        await("running notification replaces old resume action") {
            notificationAction(context.getString(R.string.action_stop)) != null &&
                notificationAction(context.getString(R.string.action_resume)) == null
        }
    }

    private fun idleSocket() =
        Socket().apply {
            soTimeout = 3_000
            connect(InetSocketAddress(loopback, EmbeddedMcpServer.PORT), 1_000)
            // No bytes: this cannot reach the gateway or issue a Vehicle SDK query.
        }

    private fun assertStopped(socket: Socket? = null) {
        await("stopped service and released resources") {
            ConnectionStatusBoard.status is ConnectionStatus.Stopped && serviceGone()
        }
        socket?.let {
            try {
                // The header deadline may already have buffered an HTTP error. Drain it,
                // then require EOF/reset: a response byte is not an open connection.
                val input = it.getInputStream()
                val buffer = ByteArray(512)
                var received = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    received += count
                    assertTrue("Unexpected response to an idle socket", received <= 4096)
                }
            } catch (_: SocketException) {
                /* Reset is also a closed peer. A read timeout fails. */
            }
        }
        try {
            Socket().use { it.connect(InetSocketAddress(loopback, EmbeddedMcpServer.PORT), 300) }
            fail("New connections must be refused after stop")
        } catch (_: java.net.ConnectException) {
            /* Expected refusal, not an arbitrary timeout. */
        }
    }

    private fun evidence(scenario: String) {
        val text =
            JSONObject()
                .put("scenario", scenario)
                .put("source", "production_mcp_service")
                .put("transport", "streamable_http")
                .put("port", EmbeddedMcpServer.PORT)
                .put("toolsListVerified", scenario != "cold_repeated_stop")
                .put("testVehicleReadRequests", 0)
                .put("connectionStopped", true)
                .put("serviceGone", true)
                .put("newConnectionRefused", true)
                .put("notificationPermission", "granted_for_test")
                .put("scope", "No actual Car UX transition, notification panel touch or SDK read")
        Log.i("COM22_SERVICE", text.toString())
        instrumentation.addResults(Bundle().apply { putString("com22_$scenario", text.toString()) })
    }

    @Test
    fun appStopClosesProductionServiceAndIdleSocketWithBlockedHistory() = exercise { _, activity ->
        val tokens = DriverHistory.history.snapshot().map { it.token.sequence }
        start(activity)
        idleSocket().use { socket ->
            onView(withText(context.getString(R.string.ux_restricted)))
                .inRoot(withDecorView(sameInstance(activity.window.decorView)))
                .check(
                    androidx.test.espresso.assertion.ViewAssertions.matches(
                        androidx.test.espresso.matcher.ViewMatchers.isDisplayed()
                    )
                )
            onView(withText(context.getString(R.string.action_stop)))
                .inRoot(withDecorView(sameInstance(activity.window.decorView)))
                .perform(click())
            assertStopped(socket)
        }
        await("app stop leaves explicit resume notification") {
            notificationAction(context.getString(R.string.action_resume)) != null
        }
        assertEquals(
            "No vehicle request was submitted",
            tokens,
            DriverHistory.history.snapshot().map { it.token.sequence },
        )
        evidence("app_stop")
    }

    @Test
    fun postedNotificationActionStopsProductionServiceWhileScreenIsBackgrounded() =
        exercise { scenario, activity ->
            start(activity)
            await("posted stop action") {
                notificationAction(context.getString(R.string.action_stop)) != null
            }
            val action = notificationAction(context.getString(R.string.action_stop))!!
            assertEquals(context.packageName, action.creatorPackage)
            assertEquals(context.applicationInfo.uid, action.creatorUid)
            idleSocket().use { socket ->
                scenario.moveToState(Lifecycle.State.CREATED)
                action.send() // Actual posted PendingIntent -> actual service STOP intent.
                assertStopped(socket)
            }
            await("stopped notification offers explicit resume") {
                notificationAction(context.getString(R.string.action_resume)) != null
            }
            evidence("notification_action")
        }

    @Test
    fun coldAndRepeatedStopNeverOpenTheBridgeOrKeepAServiceAlive() = exercise { _, _ ->
        repeat(2) {
            manager.cancelAll()
            instrumentation.runOnMainSync { BridgeService.stop(context) }
            await("stop command handled") {
                notificationAction(context.getString(R.string.action_resume)) != null
            }
            assertStopped()
        }
        evidence("cold_repeated_stop")
    }
}
