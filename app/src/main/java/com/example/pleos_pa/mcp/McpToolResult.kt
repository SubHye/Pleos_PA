package com.example.pleos_pa.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * COM-13 experiment: a Kotlin port of the items form of `mcp/src/tool-result.ts`. Text and
 * structuredContent always come from one validated bridge response or one failure, as in Node. The
 * scalar form is left out because no embedded tool uses it yet.
 */
object McpToolResult {
    private const val RECEIPT_NOTE = "수신 시각은 앱이 콜백을 받은 시각이며 차량 측 측정 시각이나 값의 최신성을 보장하지 않습니다."
    private const val SNAPSHOT_NOTE = "항목들은 순차로 읽었으며 동시에 읽은 스냅샷이 아닙니다."
    private const val LOCK_NOTE = "잠김/잠금 해제는 SDK 불리언을 메서드 이름 기준(true=잠김)으로 해석한 것입니다."
    private const val NO_GUESS = "확인하지 못한 값을 추정하지 마세요. 자동 재시도하지 않았으며, 필요하면 사용자가 새로 요청할 수 있습니다."
    private const val SIMULATION_NOTE = "시뮬레이션 결과이며 실제 차량 값이 아닙니다."

    /** [response] is the bridge response JSON after [VehicleReadValidator] accepted it. */
    fun items(response: JsonObject): CallToolResult {
        val query = response.text("query")
        val outcome = response.text("outcome")
        val source = response.text("source")
        val items = response.getValue("items").jsonArray.map { it.jsonObject }
        val isError = outcome != "COMPLETE"
        val lines = buildList {
            add("$query 결과 $outcome. 실행 환경 $source, Vehicle SDK ${response.text("sdkVersion")}.")
            items.forEach { add(itemLine(it)) }
            if (items.any { it.text("property") == "DOOR_LOCK" && it.text("status") == "OK" }) {
                add(LOCK_NOTE)
            }
            if (items.any { it["receivedAt"] !is JsonNull }) add(RECEIPT_NOTE)
            if (items.size > 1) add(SNAPSHOT_NOTE)
            if (source == "simulation") add(SIMULATION_NOTE)
            if (isError) add(NO_GUESS)
        }
        return result(lines, response, isError)
    }

    /** A call that produced no validated response: an admission refusal, a stop, or a failure. */
    fun failure(code: String, origin: String, requestId: String, query: String): CallToolResult =
        result(
            listOf("확인하지 못했습니다 [$code, $origin].", NO_GUESS),
            buildJsonObject {
                put("ok", false)
                put("origin", origin)
                put("code", code)
                put("requestId", requestId)
                put("query", query)
            },
            isError = true,
        )

    private fun result(lines: List<String>, structured: JsonObject, isError: Boolean) =
        CallToolResult(
            content = listOf(TextContent(lines.joinToString("\n"))),
            isError = if (isError) true else null,
            structuredContent = structured,
        )

    private fun itemLine(item: JsonObject): String {
        val area = item["area"]
        val name = "[${item.text("property")}]" + if (area is JsonNull) "" else " ${jsString(area)}"
        val receivedAt = item.getValue("receivedAt")
        if (item.text("status") == "OK")
            return "$name: ${value(item)}, 콜백 수신 ${jsString(receivedAt)}."
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
    private fun jsString(element: JsonElement?): String =
        when {
            element == null -> "undefined"
            element is JsonPrimitive && element.isString -> element.content
            else -> element.toString()
        }

    private fun truthy(element: JsonElement): Boolean =
        element is JsonPrimitive && element !is JsonNull && element.content.isNotEmpty()

    private fun JsonObject.text(key: String): String = jsString(getValue(key))
}
