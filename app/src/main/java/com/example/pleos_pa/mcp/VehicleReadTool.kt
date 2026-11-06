package com.example.pleos_pa.mcp

import com.example.pleos_pa.bridge.BridgeEvents
import com.example.pleos_pa.bridge.BridgeGateway
import com.example.pleos_pa.bridge.BridgeReply
import com.example.pleos_pa.contract.BridgeResult
import com.example.pleos_pa.contract.FailureOrigin
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
 * COM-13 experiment: one MCP tool call is one read through the same gateway the ADB bridge uses, so
 * the app-wide read slot, the deadline and the stop order stay as they are. No retry.
 */
class VehicleReadTool(
    catalog: VehicleReadCatalog,
    private val gateway: BridgeGateway,
    private val events: BridgeEvents,
    private val clock: () -> Long,
) {
    private val validator = VehicleReadValidator(catalog)
    private val deadlineMs = catalog.bridge.deadlineMs

    suspend fun call(query: String): CallToolResult {
        val requestId = UUID.randomUUID().toString()
        events.requestReceived()
        val request = VehicleReadRequest(requestId, deadlineMs, query)
        // The reader answers TIMEOUT within the deadline; this only guards against a lost reply.
        val reply =
            withTimeoutOrNull(deadlineMs + GUARD_MILLIS) { submit(request) }
                ?: return McpToolResult.failure("TIMEOUT", ORIGIN, requestId, query)
        val text = reply.reply?.json
        if (reply.reply == null || text == null) {
            return McpToolResult.failure("INVALID_RESPONSE", ORIGIN, requestId, query)
        }
        return when (val verdict = validator.validate(reply.reply.status, text, requestId, query)) {
            is BridgeResult.Accepted ->
                McpToolResult.items(Json.parseToJsonElement(text).jsonObject)
            is BridgeResult.Failed ->
                McpToolResult.failure(verdict.code, verdict.origin.label(), requestId, query)
        }
    }

    private class Answer(val reply: BridgeReply?)

    private suspend fun submit(request: VehicleReadRequest): Answer =
        suspendCancellableCoroutine { continuation ->
            val giveUp =
                gateway.submit(request, clock()) { reply ->
                    if (continuation.isActive) continuation.resume(Answer(reply))
                }
            continuation.invokeOnCancellation { giveUp() }
        }

    private fun FailureOrigin.label() = name.lowercase()

    private companion object {
        /** Failures made here have no PC layer to blame; the whole call ran inside the app. */
        const val ORIGIN = "android"
        const val GUARD_MILLIS = 1_000L
    }
}
