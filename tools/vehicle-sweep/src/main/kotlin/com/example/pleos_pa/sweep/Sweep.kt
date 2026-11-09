package com.example.pleos_pa.sweep

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What the sweep needs from contracts/vehicle-read/catalog.json. */
class Catalog(root: JsonObject) {
    class Tool(
        val name: String,
        val query: String,
        val legacy: Boolean,
        val resultForm: String,
        val fixed: List<Pair<String, String?>>,
    )

    class Property(val id: String, val query: String, val noCapabilityApi: Boolean)

    val tools: List<Tool> =
        root.getValue("tools").jsonArray.map {
            val tool = it.jsonObject
            Tool(
                name = tool.text("name")!!,
                query = tool.text("query")!!,
                legacy = tool.getValue("legacy").jsonPrimitive.booleanOrNull == true,
                resultForm = tool.text("resultForm")!!,
                fixed =
                    tool.getValue("fixedItems").jsonArray.map { item ->
                        item.jsonObject.text("property")!! to item.jsonObject.text("area")
                    },
            )
        }
    val properties: List<Property> =
        root.getValue("properties").jsonArray.map {
            val property = it.jsonObject
            Property(
                id = property.text("id")!!,
                query = property.text("query")!!,
                noCapabilityApi = property["noCapabilityApi"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }

    /** Fixed properties first, then the rest of the query's, as both servers list them. */
    fun propertiesOf(tool: Tool): List<String> =
        (tool.fixed.map { it.first } + properties.filter { it.query == tool.query }.map { it.id })
            .distinct()
}

/** One answered item, from the items form or the scalar form of a tool result. */
data class Item(
    val property: String,
    val area: String?,
    val status: String,
    val rawValue: JsonElement,
)

/**
 * One tool call as the sweep sees it. [outcome] is the read outcome, or the code of a call-level
 * failure. [source] is null when the call never reached the reader.
 */
data class Call(
    val outcome: String,
    val source: String?,
    val items: List<Item>,
    val bytes: Int,
    val elapsedMs: Long,
) {
    val finished
        get() = outcome in FINISHED

    companion object {
        val FINISHED = setOf("COMPLETE", "PARTIAL", "FAILED")

        /** Reads a tool result's structuredContent. A result without one is a refused call. */
        fun of(tool: Catalog.Tool, structured: JsonObject?, elapsedMs: Long): Call {
            if (structured == null)
                return Call("NO_STRUCTURED_CONTENT", null, emptyList(), 0, elapsedMs)
            val bytes = structured.toString().toByteArray().size
            val source = structured.text("source")
            val outcome = structured.text("outcome")
            if (outcome != null) {
                val items =
                    structured.getValue("items").jsonArray.map {
                        val item = it.jsonObject
                        Item(
                            item.text("property")!!,
                            item.text("area"),
                            item.text("status")!!,
                            item["rawValue"] ?: JsonNull,
                        )
                    }
                return Call(outcome, source, items, bytes, elapsedMs)
            }
            // Scalar form, or a call-level failure of either form.
            val ok = structured["ok"]?.jsonPrimitive?.booleanOrNull == true
            val code = structured.text("code")
            if (source == null) return Call(code ?: "UNKNOWN", null, emptyList(), bytes, elapsedMs)
            val (property, area) = tool.fixed.single()
            if (ok) {
                val item = Item(property, area, "OK", structured["rawValue"] ?: JsonNull)
                return Call("COMPLETE", source, listOf(item), bytes, elapsedMs)
            }
            if (code == "TIMEOUT" || code == "CANCELLED") {
                return Call(code, source, emptyList(), bytes, elapsedMs)
            }
            val observed = structured["observation"] as? JsonObject
            val item = Item(property, area, code!!, observed?.get("rawValue") ?: JsonNull)
            return Call("FAILED", source, listOf(item), bytes, elapsedMs)
        }
    }
}

/** Calls one MCP tool with arguments and returns its structuredContent, if any. */
fun interface ToolCaller {
    suspend fun call(name: String, arguments: Map<String, Any?>): JsonObject?
}

/** The Virtual Console's unofficial HTTP paths (see the pleos-emulator-test skill). */
interface VirtualConsole {
    fun state(): JsonObject

    /** Throws [SweepStopped] when the Console refuses the change. */
    fun put(endpoint: String, params: Map<String, String>)
}

/** One row of console-map.json: a Console change and the SDK values it should give. */
data class ConsoleRow(
    val property: String,
    val area: String?,
    val endpoint: String,
    val field: String,
    val params: Map<String, String>,
    val statePath: String,
    val values: List<String>,
    val expected: List<JsonElement>,
) {
    companion object {
        fun parse(rows: JsonArray): List<ConsoleRow> = rows.map {
            val row = it.jsonObject
            ConsoleRow(
                property = row.text("property")!!,
                area = row.text("area"),
                endpoint = row.text("endpoint")!!,
                field = row.text("field")!!,
                params =
                    (row["params"] as? JsonObject)?.mapValues { (_, value) ->
                        value.jsonPrimitive.content
                    } ?: emptyMap(),
                statePath = row.text("statePath")!!,
                values =
                    row.getValue("values").jsonArray.map { value -> value.jsonPrimitive.content },
                expected = row.getValue("expected").jsonArray.toList(),
            )
        }
    }
}

class SweepStopped(message: String) : Exception(message)

/**
 * A port of `mcp/test-support/vehicle-sweep.ts` that calls the app's MCP tools instead of the old
 * bridge. Only the tools the server offers (the catalog's adopted ones) can be swept; the report
 * names the others under `notSwept`, and their properties keep whatever the catalog records. One
 * call at a time, no retries.
 */
class Sweep(
    private val catalog: Catalog,
    private val caller: ToolCaller,
    private val offered: Set<String>,
    private val repeat: Int = 5,
    private val expectedSource: String = "pleos_emulator",
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
) {
    private class ToolReport(
        val name: String,
        val classified: Map<String, Boolean>,
        val json: JsonObject,
    )

    suspend fun run(
        console: VirtualConsole? = null,
        consoleMap: List<ConsoleRow> = emptyList(),
    ): JsonObject {
        val swept = catalog.tools.filter { it.name in offered }
        val reports = swept.map { sweepTool(it) }

        val consoleResults = mutableMapOf<String, Boolean>()
        if (consoleMap.isNotEmpty()) {
            val console = checkNotNull(console) { "a console map needs the console" }
            val state = console.state()
            val clients = state.path("browser.clients")?.jsonPrimitive?.doubleOrNull ?: 0.0
            val available = state.path("availability.available")?.jsonPrimitive?.booleanOrNull
            if (clients < 1 || available != true) {
                throw SweepStopped("the Virtual Console page is not open and active")
            }
            for (row in consoleMap) {
                val tool = swept.firstOrNull { tool ->
                    catalog.propertiesOf(tool).contains(row.property)
                }
                if (tool == null) {
                    log("console ${row.property} skipped: no offered tool reads it")
                    continue
                }
                val matched = matchRow(tool, row, console)
                consoleResults[row.property] = (consoleResults[row.property] ?: true) && matched
                log(
                    "console ${row.property} ${row.field} ${if (matched) "matched" else "mismatch"}"
                )
            }
        }

        val sweptQueries = swept.map { it.query }.toSet()
        return buildJsonObject {
            putJsonArray("tools") {
                for (report in reports) {
                    val tool = swept.single { it.name == report.name }
                    val counted = catalog.propertiesOf(tool).map { consoleResults[it] }
                    add(
                        JsonObject(
                            report.json +
                                ("console" to
                                    buildJsonObject {
                                        put("matched", counted.count { it == true })
                                        put("mismatch", counted.count { it == false })
                                        put("unmapped", counted.count { it == null })
                                    })
                        )
                    )
                }
            }
            putJsonObject("verification") {
                for (property in catalog.properties.filter { it.query in sweptQueries }) {
                    val classified = reports.any { it.classified[property.id] == true }
                    put(property.id, verificationOf(classified, consoleResults[property.id]))
                }
            }
            putJsonArray("notSwept") {
                catalog.tools.filter { it.name !in offered }.forEach { add(JsonPrimitive(it.name)) }
            }
        }
    }

    private suspend fun call(tool: Catalog.Tool, arguments: Map<String, Any?>): Call {
        val started = clock()
        val structured = caller.call(tool.name, arguments)
        val result = Call.of(tool, structured, clock() - started)
        if (result.source != null && result.source != expectedSource) {
            throw SweepStopped("source ${result.source} is not $expectedSource")
        }
        return result
    }

    private suspend fun sweepTool(tool: Catalog.Tool): ToolReport {
        val calls = mutableListOf<Call>()
        var lastItems = emptyList<Item>()
        for (run in 1..repeat) {
            val result = call(tool, emptyMap())
            if (result.items.isNotEmpty()) lastItems = result.items
            calls += result
            log("${tool.name} $run/$repeat ${result.outcome} ${result.elapsedMs}ms")
        }

        // A property a whole call never reached may still answer alone, which tells a slow tool
        // from a silent property.
        val properties = catalog.propertiesOf(tool)
        val classified = properties.associateWith { id ->
            calls.all { result ->
                val items = result.items.filter { it.property == id }
                items.isNotEmpty() && items.all { it.status in ANSWERED }
            }
        }
        val alone = mutableMapOf<String, String>()
        if (!tool.legacy) {
            for (id in properties) {
                if (classified.getValue(id)) continue
                alone[id] = call(tool, mapOf("properties" to listOf(id))).outcome
            }
        }

        val statuses = lastItems.groupingBy { it.status }.eachCount()
        val okItems = statuses["OK"] ?: 0
        val times = calls.map { it.elapsedMs }.sorted()
        val reachedAll = calls.all { it.finished }
        val hasOk = calls.all { result -> result.items.any { it.status == "OK" } }
        val allClassified = classified.all { (id, answered) ->
            answered || catalog.properties.first { it.id == id }.noCapabilityApi
        }
        val json = buildJsonObject {
            put("name", tool.name)
            put("query", tool.query)
            put("legacy", tool.legacy)
            putJsonArray("outcomes") { calls.forEach { add(JsonPrimitive(it.outcome)) } }
            put("items", lastItems.size)
            putJsonObject("statuses") { statuses.forEach { (key, count) -> put(key, count) } }
            put(
                "okRatio",
                if (lastItems.isEmpty()) 0.0
                else Math.round(okItems * 1000.0 / lastItems.size) / 1000.0,
            )
            putJsonArray("unsupported") {
                lastItems
                    .filter { it.status in UNSUPPORTED }
                    .forEach {
                        add(
                            JsonPrimitive(
                                "${it.property}${it.area?.let { area -> "/$area" } ?: ""}:${it.status}"
                            )
                        )
                    }
            }
            putJsonObject("timeMs") {
                put("min", times.first())
                put("median", times[(times.size - 1) / 2])
                put("max", times.last())
            }
            put("timeouts", calls.count { it.outcome == "TIMEOUT" })
            put("responseBytes", calls.maxOf { it.bytes })
            putJsonObject("classified") { classified.forEach { (id, value) -> put(id, value) } }
            putJsonObject("alone") { alone.forEach { (id, value) -> put(id, value) } }
            put(
                "finalItems",
                buildJsonArray {
                    lastItems.forEach {
                        add(
                            buildJsonObject {
                                put("property", it.property)
                                put("area", it.area)
                                put("status", it.status)
                                put("rawValue", it.rawValue)
                            }
                        )
                    }
                },
            )
            putJsonObject("r19") {
                put("allClassified", allClassified)
                put("hasOk", hasOk)
                put("noTimeout", reachedAll)
                put("passed", allClassified && hasOk && reachedAll)
            }
        }
        return ToolReport(tool.name, classified, json)
    }

    private suspend fun matchRow(
        tool: Catalog.Tool,
        row: ConsoleRow,
        console: VirtualConsole,
    ): Boolean {
        fun put(value: String) = console.put(row.endpoint, row.params + (row.field to value))
        val baseline = console.state().path(row.statePath)?.let(::plain)
        var matched = true
        try {
            for (index in 0..1) {
                put(row.values[index])
                val consoleValue = console.state().path(row.statePath)?.let(::plain)
                if (consoleValue != row.values[index]) {
                    throw SweepStopped(
                        "console value did not change for ${row.property} (${row.field})"
                    )
                }
                val result = call(tool, emptyMap())
                if (!result.finished) {
                    throw SweepStopped("read did not finish while comparing ${row.property}")
                }
                val item =
                    result.items.firstOrNull {
                        it.property == row.property &&
                            (row.area == null ||
                                it.area == row.area ||
                                it.area?.split('+')?.contains(row.area) == true)
                    }
                if (item?.status != "OK" || !sameJson(item.rawValue, row.expected[index])) {
                    matched = false
                }
            }
        } finally {
            put(baseline ?: "")
        }
        return matched
    }

    private fun verificationOf(classified: Boolean, consoleResult: Boolean?) =
        when {
            !classified -> "none"
            consoleResult == null -> "executed"
            consoleResult -> "console_matched"
            else -> "console_mismatch"
        }

    private companion object {
        /** Statuses the SDK itself answered with; the rest did not execute the read. */
        val ANSWERED =
            setOf(
                "OK",
                "NO_VALUE",
                "UNKNOWN_VALUE",
                "READ_UNAVAILABLE",
                "AREA_UNSUPPORTED",
                "PROPERTY_UNSUPPORTED",
                "SUPPORT_UNCONFIRMED",
            )
        val UNSUPPORTED =
            setOf(
                "PROPERTY_UNSUPPORTED",
                "AREA_UNSUPPORTED",
                "READ_UNAVAILABLE",
                "CAPABILITY_FAILED",
            )
    }
}

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.path(dotted: String): JsonElement? =
    dotted.split('.').fold(this as JsonElement?) { value, key -> (value as? JsonObject)?.get(key) }

/** What JavaScript's String(value) gives for a Console state value. */
internal fun plain(element: JsonElement): String {
    val primitive = element as? JsonPrimitive ?: return element.toString()
    if (primitive.isString) return primitive.content
    val number = primitive.doubleOrNull ?: return primitive.content
    return if (number == Math.floor(number) && Math.abs(number) < 1e15) number.toLong().toString()
    else number.toString()
}

/** JSON equality that treats 1 and 1.0 as the same number. */
internal fun sameJson(left: JsonElement, right: JsonElement): Boolean {
    val a = left as? JsonPrimitive
    val b = right as? JsonPrimitive
    if (a != null && b != null && !a.isString && !b.isString) {
        val x = a.doubleOrNull
        val y = b.doubleOrNull
        if (x != null && y != null) return x == y
    }
    return left == right
}
