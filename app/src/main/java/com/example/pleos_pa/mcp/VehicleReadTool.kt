package com.example.pleos_pa.mcp

import com.example.pleos_pa.bridge.BridgeEvents
import com.example.pleos_pa.bridge.BridgeGateway
import com.example.pleos_pa.bridge.BridgeReply
import com.example.pleos_pa.contract.BridgeResult
import com.example.pleos_pa.contract.ReadSelection
import com.example.pleos_pa.contract.ToolEntry
import com.example.pleos_pa.contract.VehicleReadCatalog
import com.example.pleos_pa.contract.VehicleReadRequest
import com.example.pleos_pa.contract.VehicleReadValidator
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * One MCP tool call is one read through the app's gateway, so the app-wide read slot, the deadline
 * and the stop order are the gateway's. No retry.
 */
class VehicleReadTool(
    catalog: VehicleReadCatalog,
    private val gateway: BridgeGateway,
    private val events: BridgeEvents,
    private val clock: () -> Long,
) {
    private val validator = VehicleReadValidator(catalog)
    private val deadlineMs = catalog.bridge.deadlineMs

    suspend fun call(tool: ToolEntry, selection: ReadSelection): CallToolResult {
        val requestId = UUID.randomUUID().toString()
        events.requestReceived()
        val request =
            VehicleReadRequest(
                requestId,
                deadlineMs,
                tool.query,
                selection.properties,
                selection.areas,
            )
        // The reader answers TIMEOUT within the deadline; this only guards against a lost reply.
        val answer =
            withTimeoutOrNull(deadlineMs + GUARD_MILLIS) { submit(request) }
                ?: return McpToolResult.failure(tool, "TIMEOUT", ORIGIN, requestId)
        val reply = answer.reply
        // No reply: the app's own response broke the contract and was withheld.
        val text =
            reply?.json ?: return McpToolResult.failure(tool, "INVALID_RESPONSE", ORIGIN, requestId)
        return when (
            val verdict = validator.validate(reply.status, text, requestId, tool.query, selection)
        ) {
            is BridgeResult.Accepted ->
                McpToolResult.of(tool, Json.parseToJsonElement(text).jsonObject)
            is BridgeResult.Failed ->
                McpToolResult.failure(
                    tool,
                    verdict.code,
                    verdict.origin.name.lowercase(),
                    requestId,
                )
        }
    }

    private class Answer(val reply: BridgeReply?)

    private suspend fun submit(request: VehicleReadRequest): Answer =
        suspendCancellableCoroutine { continuation ->
            val giveUp =
                gateway.submit(request, clock()) { reply ->
                    if (continuation.isActive) continuation.resume(Answer(reply))
                }
            // Ktor CIO does not cancel a call when its client disconnects, so this runs only when
            // the server itself stops. A client that leaves mid-read keeps the slot until the
            // read ends (EmbeddedMcpServerTest).
            continuation.invokeOnCancellation { giveUp() }
        }

    private companion object {
        /** Failures made here have no PC layer to blame; the whole call ran inside the app. */
        const val ORIGIN = "android"
        const val GUARD_MILLIS = 1_000L
    }
}
