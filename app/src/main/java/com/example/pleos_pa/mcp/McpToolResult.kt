package com.example.pleos_pa.mcp

import com.example.pleos_pa.contract.ToolEntry
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.math.BigDecimal
import java.math.MathContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A Kotlin port of `mcp/src/tool-result.ts`. Text and structuredContent always come from one
 * validated response or one failure. Strings and numbers print the way JavaScript template literals
 * print them, so the text matches the Node server word for word.
 */
object McpToolResult {
    private const val RECEIPT_NOTE = "수신 시각은 앱이 콜백을 받은 시각이며 차량 측 측정 시각이나 값의 최신성을 보장하지 않습니다."
    private const val SNAPSHOT_NOTE = "항목들은 순차로 읽었으며 동시에 읽은 스냅샷이 아닙니다."
    private const val LOCK_NOTE = "잠김/잠금 해제는 SDK 불리언을 메서드 이름 기준(true=잠김)으로 해석한 것입니다."
    private const val NO_GUESS = "확인하지 못한 값을 추정하지 마세요. 자동 재시도하지 않았으며, 필요하면 사용자가 새로 요청할 수 있습니다."
    private const val SIMULATION_NOTE = "시뮬레이션 결과이며 실제 차량 값이 아닙니다."

    /** [response] is the response JSON after [VehicleReadValidator] accepted it for [tool]. */
    fun of(tool: ToolEntry, response: JsonObject): CallToolResult {
        val clean = normalized(response) as JsonObject
        return if (tool.resultForm == SCALAR) scalar(clean, scalarArea(tool)) else items(clean)
    }

    /** A call that produced no validated response: an admission refusal, a stop, or a failure. */
    fun failure(tool: ToolEntry, code: String, origin: String, requestId: String): CallToolResult =
        result(
            failureLines(code, origin),
            buildJsonObject {
                put("ok", false)
                put("origin", origin)
                put("code", code)
                put("requestId", requestId)
                if (tool.resultForm == SCALAR) put("area", scalarArea(tool))
                else put("query", tool.query)
            },
            isError = true,
        )

    /**
     * Arguments outside the tool's schema. Like the Node SDK: an error with no structuredContent.
     */
    fun invalidInput(tool: ToolEntry, reason: String): CallToolResult =
        CallToolResult(
            content =
                listOf(
                    TextContent(
                        "Input validation error: Invalid arguments for tool ${tool.name}: $reason"
                    )
                ),
            isError = true,
        )

    private fun items(response: JsonObject): CallToolResult {
        val outcome = response.text("outcome")
        val source = response.text("source")
        val items = response.getValue("items").jsonArray.map { it.jsonObject }
        val isError = outcome != "COMPLETE"
        val lines = buildList {
            add(
                "${response.text("query")} 결과 $outcome. 실행 환경 $source, " +
                    "Vehicle SDK ${response.text("sdkVersion")}."
            )
            items.forEach { add(itemLine(it)) }
            if (items.any { it.text("property") == "DOOR_LOCK" && it.text("status") == "OK" }) {
                add(LOCK_NOTE)
            }
            if (items.any { it["receivedAt"] !is JsonNull }) add(RECEIPT_NOTE)
            if (items.size > 1) add(SNAPSHOT_NOTE)
            sourceNote(source)?.let(::add)
            if (isError) add(NO_GUESS)
        }
        return result(lines, response, isError)
    }

    private fun scalar(response: JsonObject, area: String): CallToolResult {
        val outcome = response.text("outcome")
        val requestId = response.getValue("requestId")
        val source = response.text("source")
        val sdkVersion = response.getValue("sdkVersion")
        val item = response.getValue("items").jsonArray.firstOrNull()?.jsonObject
        if (item != null && item.text("status") == "OK") {
            val meaning = item.text("meaning")
            return result(
                listOfNotNull(
                    "$area: $meaning. 원시값 ${item.text("rawValue")}, 콜백 수신 ${item.text("receivedAt")}.",
                    RECEIPT_NOTE,
                    sourceNote(source),
                ),
                buildJsonObject {
                    put("ok", true)
                    put("requestId", requestId)
                    put("source", source)
                    put("area", area)
                    put("status", if (meaning == "open") "OPEN" else "CLOSED")
                    put("state", meaning)
                    put("rawValue", item.getValue("rawValue"))
                    put("receivedAt", item.getValue("receivedAt"))
                    put("sdkVersion", sdkVersion)
                    put("capability", item.getValue("capability"))
                },
                isError = false,
            )
        }
        val unfinished = outcome == "TIMEOUT" || outcome == "CANCELLED"
        // The validator only accepts an empty item list for an unfinished outcome.
        val code = if (unfinished || item == null) outcome else item.text("status")
        val observed = item != null && item["receivedAt"] !is JsonNull
        val observation =
            if (observed) {
                buildJsonObject {
                    put("rawValue", item.getValue("rawValue"))
                    put("receivedAt", item.getValue("receivedAt"))
                }
            } else {
                JsonNull
            }
        return result(
            buildList {
                addAll(failureLines(code, ORIGIN_ANDROID))
                add("$area 문 상태입니다.")
                if (observed) {
                    add("관측 원시값 ${item.text("rawValue")}, 콜백 수신 ${item.text("receivedAt")}.")
                    add(RECEIPT_NOTE)
                }
                sourceNote(source)?.let(::add)
            },
            buildJsonObject {
                put("ok", false)
                put("origin", ORIGIN_ANDROID)
                put("code", code)
                put("requestId", requestId)
                put("source", source)
                put("area", area)
                put("status", code)
                put("sdkVersion", sdkVersion)
                put("capability", item?.get("capability") ?: JsonNull)
                put("errorType", item?.get("errorType") ?: JsonNull)
                put("errorCode", item?.get("errorCode") ?: JsonNull)
                put("observation", observation)
            },
            isError = true,
        )
    }

    private fun result(lines: List<String>, structured: JsonObject, isError: Boolean) =
        CallToolResult(
            content = listOf(TextContent(lines.joinToString("\n"))),
            isError = if (isError) true else null,
            structuredContent = structured,
        )

    private fun failureLines(code: String, origin: String) =
        listOf("확인하지 못했습니다 [$code, $origin].", NO_GUESS)

    private fun sourceNote(source: String) = if (source == "simulation") SIMULATION_NOTE else null

    private fun scalarArea(tool: ToolEntry): String =
        checkNotNull(tool.fixedItems.first().area) { "a scalar tool reads one area" }

    private fun itemLine(item: JsonObject): String {
        val area = item.getValue("area")
        val name = "[${item.text("property")}]" + if (area is JsonNull) "" else " ${jsString(area)}"
        val receivedAt = item.getValue("receivedAt")
        if (item.text("status") == "OK") {
            return "$name: ${value(item)}, 콜백 수신 ${jsString(receivedAt)}."
        }
        val observed =
            if (receivedAt is JsonNull) ""
            else " 관측 원시값 ${item.getValue("rawValue")}, 콜백 수신 ${jsString(receivedAt)}."
        return "$name: 확인 실패 [${item.text("status")}].$observed"
    }

    private fun value(item: JsonObject): String {
        val unit = item.getValue("unit")
        val meaning = item.getValue("meaning")
        return jsString(item.getValue("rawValue")) +
            (if (truthy(unit)) " ${jsString(unit)}" else "") +
            if (truthy(meaning)) " (${jsString(meaning)})" else ""
    }

    /** What a JavaScript template literal prints: strings bare, objects and null as JSON. */
    private fun jsString(element: JsonElement): String =
        if (element is JsonPrimitive && element.isString) element.content else element.toString()

    private fun truthy(element: JsonElement): Boolean =
        element is JsonPrimitive && element !is JsonNull && element.content.isNotEmpty()

    private fun JsonObject.text(key: String): String = jsString(getValue(key))

    /**
     * Rewrites every number the way JavaScript writes it (`25.0` becomes `25`, `1.0E-4` becomes
     * `0.0001`), as Node's `JSON.parse` then `JSON.stringify` would. Text and structuredContent
     * then print the same digits the Node server did.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun normalized(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.mapValues { normalized(it.value) })
            is JsonArray -> JsonArray(element.map(::normalized))
            is JsonPrimitive ->
                if (element.isString || element is JsonNull) element
                else
                    element.content.toDoubleOrNull()?.let { JsonUnquotedLiteral(jsNumber(it)) }
                        ?: element
        }

    /**
     * ECMAScript Number::toString for a finite number: the shortest digits that round-trip, plain
     * from 1e-6 up to 1e21, exponent form (`1e-7`, `1e+21`) outside that range.
     */
    internal fun jsNumber(value: Double): String {
        if (value == 0.0) return "0"
        // The shortest digits that read back as the same double, found the same way on any JVM
        // or Android runtime (whose Double.toString is not always shortest).
        val magnitude = BigDecimal(Math.abs(value))
        val exact =
            (1..17)
                .asSequence()
                .map { magnitude.round(MathContext(it)) }
                .first { it.toDouble() == Math.abs(value) }
                .stripTrailingZeros()
        val digits = exact.unscaledValue().toString()
        val k = digits.length
        // value = 0.digits × 10^n
        val n = k - exact.scale()
        val body =
            when {
                n in k..21 -> digits + "0".repeat(n - k)
                n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
                n in -5..0 -> "0." + "0".repeat(-n) + digits
                else -> {
                    val exponent = n - 1
                    val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                    mantissa + "e" + (if (exponent >= 0) "+" else "-") + Math.abs(exponent)
                }
            }
        return if (value < 0) "-$body" else body
    }

    private const val SCALAR = "scalar"
    private const val ORIGIN_ANDROID = "android"
}
