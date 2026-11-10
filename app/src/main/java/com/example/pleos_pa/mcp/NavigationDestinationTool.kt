package com.example.pleos_pa.mcp

import com.example.pleos_pa.bridge.BridgeEvents
import com.example.pleos_pa.contract.NavigationCatalog
import com.example.pleos_pa.contract.NavigationDestination
import com.example.pleos_pa.contract.NavigationToolEntry
import com.example.pleos_pa.navigation.DestinationRead
import com.example.pleos_pa.navigation.DestinationReader
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * `get_navigation_destination`: one MCP call is one read of the navigation slot. The slot, the
 * deadline, the stale-callback hold and the stop order are [DestinationReader]'s. No retry.
 */
class NavigationDestinationTool(
    private val catalog: NavigationCatalog,
    private val reader: DestinationReader,
    private val source: String?,
    private val events: BridgeEvents,
    private val clock: () -> Long,
) : NavigationTool {
    /** Only adopted tools are offered. */
    override val entry: NavigationToolEntry? =
        catalog.tool(NAME)?.takeIf { it.adoption == "adopted" && it.sdkCall == SDK_CALL }

    override val inputSchema =
        ToolSchema(
            schema = StringArgument.JSON_SCHEMA_2020_12,
            properties = JsonObject(emptyMap()),
        )

    /** The tool takes no arguments; any key is refused before the SDK is asked. */
    fun refusal(arguments: JsonObject?): String? =
        arguments?.keys?.firstOrNull()?.let { "Unrecognized key: \"$it\"" }

    override suspend fun call(tool: NavigationToolEntry, arguments: JsonObject?): CallToolResult =
        refusal(arguments)?.let { NavigationToolResult.invalidInput(tool, it) } ?: call(tool)

    suspend fun call(tool: NavigationToolEntry): CallToolResult {
        val requestId = UUID.randomUUID().toString()
        val started = clock()
        events.requestReceived()
        // The reader answers TIMEOUT at the deadline; this only guards against a lost reply.
        val read =
            withTimeoutOrNull(catalog.deadlineMs + GUARD_MILLIS) { submit() }
                ?: DestinationRead.Failed(DestinationReader.TIMEOUT)
        val (result, label) =
            when (read) {
                is DestinationRead.Received -> {
                    val judged = NavigationDestination.judge(read.snapshot, read.receivedAtMillis)
                    val shown = checkNotNull(source) { "the reader refuses reads without a source" }
                    NavigationToolResult.of(tool, requestId, shown, catalog.sdk, judged) to
                        judged.status.name
                }
                is DestinationRead.Failed ->
                    NavigationToolResult.failure(read.code, requestId) to read.code
            }
        // Only fixed fields reach the log, never a destination value.
        events.finished(requestId, QUERY, label, clock() - started)
        return result
    }

    private suspend fun submit(): DestinationRead = suspendCancellableCoroutine { continuation ->
        reader.read(catalog.deadlineMs) { read ->
            if (continuation.isActive) continuation.resume(read)
        }
    }

    companion object {
        const val NAME = "get_navigation_destination"
        const val SDK_CALL = "getDestinationInfo"
        private const val QUERY = "navigation_destination"
        private const val GUARD_MILLIS = 1_000L
    }
}
