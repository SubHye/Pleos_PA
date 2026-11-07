package com.example.pleos_pa.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pleos_pa.MainActivity
import com.example.pleos_pa.R
import com.example.pleos_pa.awaitFocusedActivity
import com.example.pleos_pa.connection.BridgeService
import com.example.pleos_pa.connection.ConnectionStatus
import com.example.pleos_pa.connection.ConnectionStatusBoard
import com.example.pleos_pa.contract.BridgeResult
import com.example.pleos_pa.contract.ItemStatus
import com.example.pleos_pa.contract.ReadSelection
import com.example.pleos_pa.contract.VehicleReadCatalog
import com.example.pleos_pa.contract.VehicleReadValidator
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import org.hamcrest.CoreMatchers.sameInstance
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real HTTP -> production gateway/reader -> shared history -> restricted MainActivity. */
@RunWith(AndroidJUnit4::class)
class BridgeHistoryRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val property = "EV_BATTERY_PERCENT"
    private val query = "energy_level"

    private data class Probe(val query: String, val property: String, val tool: String)

    private val probes =
        listOf(
            Probe("energy_level", "EV_BATTERY_PERCENT", "get_energy_level"),
            Probe("display_units", "TEMPERATURE_DISPLAY_UNIT", "get_display_units"),
        )

    private fun await(label: String, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < until) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            Thread.sleep(50)
        }
        fail("Timed out: $label")
    }

    private data class Reply(val status: Int, val body: String)

    private fun post(body: String): Reply =
        Socket().use { socket ->
            socket.soTimeout = 15_000
            socket.connect(InetSocketAddress(loopback, 18765), 1_000)
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head =
                "POST /v3/vehicle-read HTTP/1.1\r\nHost: 127.0.0.1:18765\r\n" +
                    "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
            }
            // Do not half-close before the response: that is cancellation in this protocol.
            val input = socket.getInputStream()
            val header = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                assertTrue("HTTP response header closed early", next >= 0)
                header.write(next)
                assertTrue("HTTP response header must be bounded", header.size() <= 8_192)
                val h = header.toString(Charsets.US_ASCII.name())
                if (!h.endsWith("\r\n\r\n")) continue
                val lines = h.split("\r\n")
                val status = lines.first().split(' ')[1].toInt()
                val size =
                    lines
                        .single { it.startsWith("Content-Length:", ignoreCase = true) }
                        .substringAfter(':')
                        .trim()
                        .toInt()
                assertTrue("HTTP response body must be bounded", size in 0..65_536)
                val content = ByteArray(size)
                var offset = 0
                while (offset < size) {
                    val count = input.read(content, offset, size - offset)
                    assertTrue("HTTP response body closed early", count > 0)
                    offset += count
                }
                return@use Reply(status, content.toString(Charsets.UTF_8))
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

    private fun screenText(activity: MainActivity): String {
        fun texts(view: View): List<String> =
            (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
                (if (view is ViewGroup)
                    (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
                else emptyList())
        var result = ""
        instrumentation.runOnMainSync {
            result = texts(activity.window.decorView).joinToString("\n")
        }
        return result
    }

    @Test
    fun validatedRequestsReachSharedHistoryWithoutLeakingRejectedInputOrUnlockingDetails() {
        assertEquals("Diagnostic package only", "com.example.pleos_pa.com5", context.packageName)
        assertTrue(
            "Preserve existing diagnostic records; clear them explicitly before testing",
            DriverHistory.history.snapshot().isEmpty(),
        )
        instrumentation.runOnMainSync {
            assertTrue(
                "Stop the diagnostic connection before testing",
                ConnectionStatusBoard.status is ConnectionStatus.Stopped,
            )
        }
        if (Build.VERSION.SDK_INT >= 33)
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        val catalog =
            VehicleReadCatalog.parse(
                context.assets.open("catalog.json").use { it.readBytes().decodeToString() }
            )
        val validator = VehicleReadValidator(catalog)
        val requestId = UUID.randomUUID().toString()
        val body =
            JSONObject()
                .put("requestId", requestId)
                .put("remainingMs", 8_000)
                .put("query", query)
                .put("properties", JSONArray().put(property))
                .toString()
        val sentinel = "synthetic-private-input-${UUID.randomUUID()}"
        val scenario =
            ActivityScenario.launch<MainActivity>(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        lateinit var activity: MainActivity
        scenario.onActivity { activity = it }
        var started = false
        try {
            awaitFocusedActivity(activity)
            started = true
            onView(withText(context.getString(R.string.action_start)))
                .inRoot(withDecorView(sameInstance(activity.window.decorView)))
                .perform(click())
            await("production listener") {
                if (ConnectionStatusBoard.status is ConnectionStatus.Failed)
                    fail(
                        "Production bridge could not start; another application's port is not stopped"
                    )
                ConnectionStatusBoard.status is ConnectionStatus.Connected
            }
            val invalid = post(JSONObject(body).put("privateText", sentinel).toString())
            assertEquals(400, invalid.status)
            assertFalse("Invalid input must not be reflected", invalid.body.contains(sentinel))
            assertTrue(
                "Rejected input must not enter history",
                DriverHistory.history.snapshot().isEmpty(),
            )

            val replies = mutableListOf<Reply>()
            val outcomes = JSONArray()
            val sources = JSONArray()
            var okValueObserved = false
            repeat(2) { attempt ->
                val (query, property, tool) = probes[attempt]
                val requestBody =
                    JSONObject()
                        .put("requestId", requestId)
                        .put("remainingMs", 8_000)
                        .put("query", query)
                        .put("properties", JSONArray().put(property))
                        .toString()
                val reply = post(requestBody)
                replies += reply
                val verdict =
                    validator.validate(
                        reply.status,
                        reply.body,
                        requestId,
                        query,
                        ReadSelection(setOf(property)),
                    )
                await("terminal shared record") {
                    val rows = DriverHistory.history.snapshot()
                    rows.size == attempt + 1 && rows.none { it.state == "RECEIVED" }
                }
                val row = DriverHistory.history.snapshot().first()
                assertEquals(requestId, row.requestId)
                assertEquals(tool, row.tool)
                assertTrue(
                    "Validated selection belongs to this request",
                    row.arguments.contains(property),
                )
                assertFalse(
                    "Rejected text must never enter a record",
                    row.arguments.contains(sentinel) || row.details.contains(sentinel),
                )
                when (verdict) {
                    is BridgeResult.Accepted -> {
                        val response = verdict.response
                        assertEquals(response.outcome.name, row.state)
                        assertEquals(response.source, row.source)
                        assertTrue(
                            "Response source must identify the selected environment",
                            response.source == "pleos_emulator" ||
                                response.source == "real_vehicle",
                        )
                        val item = response.items.single()
                        assertEquals(property, item.property)
                        assertTrue(
                            "Actual item status must reach the record",
                            row.details.contains("$property: ${item.status.name}"),
                        )
                        if (item.status == ItemStatus.OK) {
                            val inMemory =
                                row.details.substringAfter("$property: OK · ").substringBefore(' ')
                            val actual = item.rawValue
                            val matches =
                                if (actual is Number) inMemory.toDoubleOrNull() == actual.toDouble()
                                else inMemory == actual.toString()
                            assertTrue("Validated read-only value must match in memory", matches)
                            okValueObserved = true
                        }
                        outcomes.put(response.outcome.name)
                        sources.put(response.source)
                    }
                    is BridgeResult.Failed -> {
                        assertEquals(
                            "Expected honest platform-not-ready response",
                            "APP_NOT_READY",
                            verdict.code,
                        )
                        assertEquals(503, reply.status)
                        assertEquals("APP_NOT_READY", row.state)
                        assertNull(row.source)
                        assertFalse("No fabricated successful value", row.details.contains(": OK"))
                        outcomes.put(verdict.code)
                        sources.put(JSONObject.NULL)
                    }
                }
                if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
                    assertEquals(
                        "Generic Android must not invent vehicle success",
                        503,
                        reply.status,
                    )
            }
            val before = DriverHistory.history.snapshot()
            assertEquals(2, before.size)
            assertNotSame(
                "Duplicate request IDs are distinct attempts",
                before[0].token,
                before[1].token,
            )
            val sequences = before.map { it.token.sequence }
            scenario.recreate()
            scenario.onActivity { activity = it }
            awaitFocusedActivity(activity)
            assertEquals(
                "Activity recreation preserves process-local attempts",
                sequences,
                DriverHistory.history.snapshot().map { it.token.sequence },
            )
            val visible = screenText(activity)
            assertTrue(
                "Restricted summary updates from real shared history",
                visible.contains(context.getString(R.string.history_hidden, 2)),
            )
            assertFalse(
                "Production policy is not bypassed",
                visible.contains(requestId) ||
                    probes.any { visible.contains(it.property) } ||
                    visible.contains(sentinel),
            )
            assertTrue(visible.contains(context.getString(R.string.client_unknown)))
            assertTrue(
                "Live service state survives screen recreation",
                visible.contains(context.getString(R.string.connection_connected)),
            )

            onView(withText(context.getString(R.string.action_stop)))
                .inRoot(withDecorView(sameInstance(activity.window.decorView)))
                .perform(click())
            await("production stop") { ConnectionStatusBoard.status is ConnectionStatus.Stopped }
            try {
                Socket().use { it.connect(InetSocketAddress(loopback, 18765), 300) }
                fail("Stopped bridge must refuse new connections")
            } catch (_: java.net.ConnectException) {}
            instrumentation.waitForIdleSync()
            onView(withText(context.getString(R.string.action_resume)))
                .inRoot(withDecorView(sameInstance(activity.window.decorView)))
                .check(matches(isEnabled()))
            // Only our two attempts may be deleted. Fail before clearing if another caller
            // intervened.
            assertTrue(
                "External calls must be stopped for this diagnostic",
                DriverHistory.history.snapshot().let { rows ->
                    rows.size == 2 && rows.all { it.requestId == requestId }
                },
            )
            onView(withText(context.getString(R.string.history_clear)))
                .inRoot(withDecorView(sameInstance(activity.window.decorView)))
                .perform(scrollTo(), click())
            await("explicit UI clear") { DriverHistory.history.snapshot().isEmpty() }
            assertTrue(screenText(activity).contains(context.getString(R.string.history_hidden, 0)))
            val evidence =
                JSONObject()
                    .put("source", "production_http_shared_history")
                    .put("validReadOnlyRequests", 2)
                    .put("queries", JSONArray(probes.map { it.query }))
                    .put("rejectedRequests", 1)
                    .put("httpStatuses", JSONArray(replies.map { it.status }))
                    .put("outcomes", outcomes)
                    .put("responseSources", sources)
                    .put("sdkOkValueObserved", okValueObserved)
                    .put("duplicateAttemptsSeparated", true)
                    .put("activityRecreationPreservedRecords", true)
                    .put("resumeLabelRestored", true)
                    .put("restrictedDetailsHidden", true)
                    .put("explicitUiClear", true)
                    .put("stoppedConnectionRefused", true)
                    .put(
                        "scope",
                        "No injected vehicle values or Car UX. SDK success only when sdkOkValueObserved=true; actual result-detail UI and Pleos policy acceptance remain separate.",
                    )
            Log.i("COM25_RUNTIME", evidence.toString())
            instrumentation.addResults(
                Bundle().apply { putString("com25_history", evidence.toString()) }
            )
        } finally {
            if (started) context.stopService(Intent(context, BridgeService::class.java))
            scenario.close()
        }
    }
}
