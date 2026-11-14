package com.example.pleos_pa.mcp

import com.example.pleos_pa.contract.NavigationToolEntry
import com.example.pleos_pa.ui.RequestHistory
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shows a navigation tool call on the screen: which tool, and how it ended. The arguments, the
 * place and the answer never reach the record.
 */
class RecordedNavigationTool(
    private val tool: NavigationTool,
    private val history: RequestHistory,
    private val now: () -> Long = System::currentTimeMillis,
) : NavigationTool {
    override val entry: NavigationToolEntry?
        get() = tool.entry

    override val inputSchema: ToolSchema
        get() = tool.inputSchema

    override suspend fun call(tool: NavigationToolEntry, arguments: JsonObject?): CallToolResult {
        val token = history.begin("", tool.name, "", Instant.ofEpochMilli(now()).toString())
        val result =
            try {
                this.tool.call(tool, arguments)
            } catch (e: Throwable) {
                history.finish(token, "FAILED", null, "")
                throw e
            }
        history.finish(token, stateOf(result.structuredContent), null, "")
        return result
    }

    private fun stateOf(structured: JsonObject?): String {
        // Refused input is answered before any tool logic, with text only.
        if (structured == null) return "REJECTED"
        fun text(key: String) = (structured[key] as? JsonPrimitive)?.content
        if (text("ok") == "true") return if (text("status") == "PARTIAL") "PARTIAL" else "COMPLETE"
        // Already handed to the stock navigation: neither applied nor failed is known.
        if (text("sent") == "true") return "RESULT_UNKNOWN"
        return when (text("code")) {
            "BUSY" -> "BUSY"
            "CONNECTION_STOPPED" -> "CONNECTION_STOPPED"
            "DRIVING_RESTRICTED",
            "DRIVING_STATE_UNKNOWN" -> "BLOCKED"
            else -> "FAILED"
        }
    }
}
