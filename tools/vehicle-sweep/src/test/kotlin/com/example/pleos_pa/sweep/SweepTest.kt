package com.example.pleos_pa.sweep

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The sweep's rules with a scripted MCP server and Console. No device, no SDK. */
class SweepTest {
    private val catalog =
        Catalog(
            Json.parseToJsonElement(File("../../contracts/vehicle-read/catalog.json").readText())
                .jsonObject
        )
    private val calls = mutableListOf<Pair<String, Map<String, Any?>>>()

    private fun items(
        outcome: String,
        vararg items: Triple<String, String, JsonElement>,
        source: String = "pleos_emulator",
    ) = buildJsonObject {
        put("outcome", outcome)
        put("source", source)
        putJsonArray("items") {
            for ((property, status, raw) in items) {
                add(
                    buildJsonObject {
                        put("property", property)
                        put("area", JsonNull)
                        put("status", status)
                        put("rawValue", raw)
                    }
                )
            }
        }
    }

    private fun energy(battery: Int, source: String = "pleos_emulator") =
        items(
            "PARTIAL",
            Triple("EV_BATTERY_PERCENT", "OK", JsonPrimitive(battery)),
            Triple("FUEL_PERCENT", "PROPERTY_UNSUPPORTED", JsonNull),
            source = source,
        )

    private fun sweep(
        offered: Set<String>,
        repeat: Int = 2,
        answer: (String, Map<String, Any?>) -> JsonObject?,
    ) =
        Sweep(
            catalog,
            { name, arguments ->
                calls += name to arguments
                answer(name, arguments)
            },
            offered,
            repeat = repeat,
            clock = { 0L },
        )

    private class FakeConsole(
        var open: Boolean = true,
        var follows: Boolean = true,
        var soc: Int = 100,
    ) : VirtualConsole {
        val puts = mutableListOf<String>()

        override fun state() = buildJsonObject {
            putJsonObject("browser") { put("clients", if (open) 1 else 0) }
            putJsonObject("availability") { put("available", open) }
            putJsonObject("ev_battery") { put("state_of_charge", soc.toDouble()) }
        }

        override fun put(endpoint: String, params: Map<String, String>) {
            puts += "$endpoint ${params.getValue("value")}"
            if (follows) soc = params.getValue("value").toInt()
        }
    }

    private val socRow =
        ConsoleRow(
            property = "EV_BATTERY_PERCENT",
            area = null,
            endpoint = "/vehicle/ev-battery",
            field = "value",
            params = mapOf("field" to "soc"),
            statePath = "ev_battery.state_of_charge",
            values = listOf("40", "80"),
            expected = listOf(JsonPrimitive(40), JsonPrimitive(80)),
        )

    @Test
    fun everyOfferedToolIsCalledTheGivenNumberOfTimesAndTheRestAreNamed() = runBlocking {
        val report = sweep(setOf("get_energy_level")) { _, _ -> energy(57) }.run()

        assertEquals(List(2) { "get_energy_level" to emptyMap<String, Any?>() }, calls)
        val tool = report.getValue("tools").jsonArray.single().jsonObject
        assertEquals(
            true,
            tool.getValue("r19").jsonObject["passed"]!!.jsonPrimitive.content.toBoolean(),
        )
        val notSwept = report.getValue("notSwept").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("get_toll_status" in notSwept)
        assertTrue("get_energy_level" !in notSwept)
        // Only the swept tool's properties get a verification value.
        assertEquals(
            setOf("FUEL_PERCENT", "EV_BATTERY_PERCENT"),
            report.getValue("verification").jsonObject.keys,
        )
    }

    @Test
    fun aTimeoutFailsTheToolIsCountedAndIsNotRetried() = runBlocking {
        var n = 0
        val report =
            sweep(setOf("get_energy_level"), repeat = 3) { _, _ ->
                    n += 1
                    if (n == 2) items("TIMEOUT") else energy(57)
                }
                .run()

        assertEquals(3, calls.size)
        val tool = report.getValue("tools").jsonArray.single().jsonObject
        assertEquals(1, tool.getValue("timeouts").jsonPrimitive.int)
        assertEquals("false", tool.getValue("r19").jsonObject["passed"]!!.jsonPrimitive.content)
    }

    @Test
    fun aPropertyNoWholeCallReachedIsAskedAloneAndStaysNone() = runBlocking {
        val report =
            sweep(setOf("get_brake_status"), repeat = 1) { _, arguments ->
                    if (arguments.isEmpty()) items("TIMEOUT") else items("TIMEOUT")
                }
                .run()

        assertEquals(
            listOf(
                "get_brake_status" to emptyMap(),
                "get_brake_status" to mapOf("properties" to listOf("BRAKE_PEDAL_STATUS")),
            ),
            calls,
        )
        val tool = report.getValue("tools").jsonArray.single().jsonObject
        assertEquals(
            "TIMEOUT",
            tool.getValue("alone").jsonObject["BRAKE_PEDAL_STATUS"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "none",
            report
                .getValue("verification")
                .jsonObject["BRAKE_PEDAL_STATUS"]!!
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun theScalarFormIsReadAsOneItem() = runBlocking {
        val report =
            sweep(setOf("get_front_left_door_position"), repeat = 1) { _, _ ->
                    buildJsonObject {
                        put("ok", true)
                        put("source", "pleos_emulator")
                        put("area", "ROW_1_LEFT")
                        put("status", "OPEN")
                        put("rawValue", 1)
                    }
                }
                .run()

        val tool = report.getValue("tools").jsonArray.single().jsonObject
        val item = tool.getValue("finalItems").jsonArray.single().jsonObject
        assertEquals("DOOR_POSITION", item["property"]!!.jsonPrimitive.content)
        assertEquals("OK", item["status"]!!.jsonPrimitive.content)
        // DOOR_POSITION belongs to door_status, so only that tool sets its verification.
        assertTrue("DOOR_POSITION" !in report.getValue("verification").jsonObject)
    }

    @Test
    fun anotherSourceStopsTheSweepBeforeTheConsoleIsTouched() = runBlocking {
        val console = FakeConsole()
        try {
            sweep(setOf("get_energy_level")) { _, _ -> energy(57, source = "simulation") }
                .run(console, listOf(socRow))
            fail("the sweep went on")
        } catch (e: SweepStopped) {
            assertTrue(e.message!!.contains("simulation"))
        }
        assertEquals(emptyList<String>(), console.puts)
    }

    @Test
    fun aConsoleChangeTheSdkFollowsIsMatchedAndTheBaselineIsRestored() = runBlocking {
        val console = FakeConsole(soc = 57)
        val report =
            sweep(setOf("get_energy_level"), repeat = 1) { _, _ -> energy(console.soc) }
                .run(console, listOf(socRow))

        assertEquals(
            listOf("/vehicle/ev-battery 40", "/vehicle/ev-battery 80", "/vehicle/ev-battery 57"),
            console.puts,
        )
        assertEquals(
            "console_matched",
            report
                .getValue("verification")
                .jsonObject["EV_BATTERY_PERCENT"]!!
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun aConsoleChangeTheSdkDoesNotFollowIsAMismatch() = runBlocking {
        val console = FakeConsole(soc = 57)
        val report =
            sweep(setOf("get_energy_level"), repeat = 1) { _, _ -> energy(57) }
                .run(console, listOf(socRow))

        assertEquals(
            "console_mismatch",
            report
                .getValue("verification")
                .jsonObject["EV_BATTERY_PERCENT"]!!
                .jsonPrimitive
                .content,
        )
        assertEquals("/vehicle/ev-battery 57", console.puts.last())
    }

    @Test
    fun aConsoleValueThatDoesNotChangeStopsTheSweepAfterRestoring() = runBlocking {
        val console = FakeConsole(follows = false, soc = 57)
        try {
            sweep(setOf("get_energy_level"), repeat = 1) { _, _ -> energy(57) }
                .run(console, listOf(socRow))
            fail("the sweep went on")
        } catch (e: SweepStopped) {
            assertTrue(e.message!!.contains("did not change"))
        }
        assertEquals(listOf("/vehicle/ev-battery 40", "/vehicle/ev-battery 57"), console.puts)
    }

    @Test
    fun aConsolePageThatIsNotOpenStopsTheSweepBeforeAnyWrite() = runBlocking {
        val console = FakeConsole(open = false)
        try {
            sweep(setOf("get_energy_level"), repeat = 1) { _, _ -> energy(57) }
                .run(console, listOf(socRow))
            fail("the sweep went on")
        } catch (e: SweepStopped) {
            assertTrue(e.message!!.contains("not open"))
        }
        assertEquals(emptyList<String>(), console.puts)
    }

    @Test
    fun theCommittedConsoleMapParses() {
        val rows =
            ConsoleRow.parse(Json.parseToJsonElement(File("console-map.json").readText()).jsonArray)
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.values.size == 2 && it.expected.size == 2 })
    }
}
