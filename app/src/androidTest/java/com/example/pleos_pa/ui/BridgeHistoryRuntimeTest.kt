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
import com.example.pleos_pa.RuntimeMcpClient
import com.example.pleos_pa.awaitFocusedActivity
import com.example.pleos_pa.connection.BridgeService
import com.example.pleos_pa.connection.ConnectionStatus
import com.example.pleos_pa.connection.ConnectionStatusBoard
import com.example.pleos_pa.contract.BridgeResult
import com.example.pleos_pa.contract.ItemStatus
import com.example.pleos_pa.contract.ReadSelection
import com.example.pleos_pa.contract.VehicleReadCatalog
import com.example.pleos_pa.contract.VehicleReadValidator
import com.example.pleos_pa.mcp.EmbeddedMcpServer
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlinx.serialization.json.jsonPrimitive
import org.hamcrest.CoreMatchers.sameInstance
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real MCP tools/call -> production gateway/reader -> shared history -> restricted MainActivity.
 */
@RunWith(AndroidJUnit4::class)
class BridgeHistoryRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

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
    fun mcpCallsReachSharedHistoryWithoutLeakingRejectedInputOrUnlockingDetails() {
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
            await("production MCP endpoint") {
                if (ConnectionStatusBoard.status is ConnectionStatus.Failed)
                    fail("MCP server could not start; another application's port is not stopped")
                ConnectionStatusBoard.status is ConnectionStatus.Connected
            }
            val requestIds = mutableListOf<String>()
            val codes = JSONArray()
            val sources = JSONArray()
            var okValueObserved = false
            RuntimeMcpClient().use { client ->
                val invalid = client.call("get_energy_level", mapOf("privateText" to sentinel))
                assertEquals(true, invalid.isError)
                assertNull("Invalid tool input has no vehicle result", invalid.structuredContent)
                assertFalse(
                    "Rejected input must not be reflected",
                    invalid.content.filterIsInstance<TextContent>().any {
                        it.text.contains(sentinel)
                    },
                )
                assertTrue(
                    "Rejected input must not enter history",
                    DriverHistory.history.snapshot().isEmpty(),
                )
                for ((attempt, probe) in probes.withIndex()) {
                    val (query, property, tool) = probe
                    val legacy = catalog.tools.getValue(query).legacy
                    val args: Map<String, Any?> =
                        if (legacy) emptyMap() else mapOf("properties" to listOf(property))
                    val result = client.call(tool, args)
                    val content =
                        result.structuredContent
                            ?: error("Valid tool call needs a structured result")
                    // Gateway IDs are now generated by VehicleReadTool, not supplied by a wire
                    // caller.
                    val id = content.getValue("requestId").jsonPrimitive.content
                    UUID.fromString(id)
                    requestIds += id
                    await("terminal shared record") {
                        DriverHistory.history.snapshot().let {
                            it.size == attempt + 1 && it.none { row -> row.state == "RECEIVED" }
                        }
                    }
                    val row = DriverHistory.history.snapshot().first()
                    assertEquals(id, row.requestId)
                    assertEquals(tool, row.tool)
                    assertTrue(
                        "Validated selection belongs to this call",
                        row.arguments.contains(if (legacy) "도구 기본 선택" else property),
                    )
                    assertFalse(
                        "Rejected text must not enter a record",
                        row.arguments.contains(sentinel) || row.details.contains(sentinel),
                    )
                    val code: String
                    if (content.containsKey("outcome")) {
                        val verdict =
                            validator.validate(
                                200,
                                content.toString(),
                                id,
                                query,
                                if (legacy) ReadSelection() else ReadSelection(setOf(property)),
                            )
                        assertTrue(
                            "MCP vehicle content must obey the gateway result contract",
                            verdict is BridgeResult.Accepted,
                        )
                        val response = (verdict as BridgeResult.Accepted).response
                        code = response.outcome.name
                        assertEquals(code, row.state)
                        assertEquals(response.source, row.source)
                        assertTrue(
                            "Response source identifies the environment",
                            response.source == "pleos_emulator" ||
                                response.source == "real_vehicle",
                        )
                        val item = response.items.single { it.property == property }
                        assertTrue(
                            "Actual item status reaches memory",
                            row.details.contains("$property: ${item.status.name}"),
                        )
                        if (item.status == ItemStatus.OK) {
                            val value =
                                row.details.substringAfter("$property: OK · ").substringBefore(' ')
                            val actual = item.rawValue
                            val matches =
                                if (actual is Number) value.toDoubleOrNull() == actual.toDouble()
                                else value == actual.toString()
                            assertTrue("Validated value matches in memory", matches)
                            okValueObserved = true
                        }
                        sources.put(response.source)
                    } else {
                        code = content.getValue("code").jsonPrimitive.content
                        assertEquals(
                            "Expected honest unsupported-platform result",
                            "APP_NOT_READY",
                            code,
                        )
                        assertEquals(true, result.isError)
                        assertEquals("android", content.getValue("origin").jsonPrimitive.content)
                        assertEquals(query, content.getValue("query").jsonPrimitive.content)
                        assertEquals(code, row.state)
                        assertNull(row.source)
                        assertFalse("No fabricated successful value", row.details.contains(": OK"))
                        sources.put(JSONObject.NULL)
                    }
                    codes.put(code)
                    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
                        assertEquals(
                            "Generic Android must not invent SDK success",
                            "APP_NOT_READY",
                            code,
                        )
                }
            }
            assertEquals(
                "Each tool call has its own server-generated gateway ID",
                2,
                requestIds.toSet().size,
            )
            val before = DriverHistory.history.snapshot()
            assertEquals(2, before.size)
            assertNotSame("Calls are distinct attempts", before[0].token, before[1].token)
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
                "Restricted summary observes real history",
                visible.contains(context.getString(R.string.history_hidden, 2)),
            )
            assertFalse(
                "Production policy is not bypassed",
                requestIds.any { visible.contains(it) } ||
                    probes.any { visible.contains(it.property) } ||
                    visible.contains(sentinel),
            )
            assertTrue(visible.contains(context.getString(R.string.client_unknown)))
            assertTrue(
                "Closing the MCP client does not report an interrupted connection",
                visible.contains(context.getString(R.string.connection_connected)),
            )
            onView(withText(context.getString(R.string.action_stop)))
                .inRoot(withDecorView(sameInstance(activity.window.decorView)))
                .perform(click())
            await("production stop") { ConnectionStatusBoard.status is ConnectionStatus.Stopped }
            try {
                Socket().use {
                    it.connect(InetSocketAddress(loopback, EmbeddedMcpServer.PORT), 300)
                }
                fail("Stopped MCP endpoint must refuse new connections")
            } catch (_: java.net.ConnectException) {}
            instrumentation.waitForIdleSync()
            onView(withText(context.getString(R.string.action_resume)))
                .inRoot(withDecorView(sameInstance(activity.window.decorView)))
                .check(matches(isEnabled()))
            assertTrue(
                "Other calls must be stopped; preserve their records",
                DriverHistory.history.snapshot().let {
                    it.size == 2 && it.all { row -> row.requestId in requestIds }
                },
            )
            onView(withText(context.getString(R.string.history_clear)))
                .inRoot(withDecorView(sameInstance(activity.window.decorView)))
                .perform(scrollTo(), click())
            await("explicit UI clear") { DriverHistory.history.snapshot().isEmpty() }
            assertTrue(screenText(activity).contains(context.getString(R.string.history_hidden, 0)))
            val evidence =
                JSONObject()
                    .put("source", "production_mcp_shared_history")
                    .put("transport", "streamable_http")
                    .put("port", EmbeddedMcpServer.PORT)
                    .put("path", "/mcp")
                    .put("validToolCalls", 2)
                    .put("queries", JSONArray(probes.map { it.query }))
                    .put("rejectedToolCalls", 1)
                    .put("mcpCodes", codes)
                    .put("responseSources", sources)
                    .put("sdkOkValueObserved", okValueObserved)
                    .put("requestIdsCorrelated", true)
                    .put("distinctServerRequestIds", true)
                    .put("activityRecreationPreservedRecords", true)
                    .put("resumeLabelRestored", true)
                    .put("restrictedDetailsHidden", true)
                    .put("explicitUiClear", true)
                    .put("stoppedConnectionRefused", true)
                    .put(
                        "scope",
                        "Real MCP tools/call, no injected vehicle values/UX. SDK OK only when sdkOkValueObserved=true; Pleos detail/policy acceptance is separate.",
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
