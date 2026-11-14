package com.example.pleos_pa.mcp

import com.example.pleos_pa.bridge.BridgeEvents
import com.example.pleos_pa.contract.NavigationCatalog
import com.example.pleos_pa.contract.NavigationToolEntry
import com.example.pleos_pa.contract.NavigationWaypoints
import com.example.pleos_pa.contract.PlaceCatalog
import com.example.pleos_pa.navigation.WaypointRead
import com.example.pleos_pa.navigation.WaypointReader
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * `get_navigation_waypoints`: one MCP call is one read of the waypoint slot. The slot, the
 * deadline, the stale-callback hold and the stop order are [WaypointReader]'s. No retry.
 */
class NavigationWaypointsTool(
    private val catalog: NavigationCatalog,
    private val places: PlaceCatalog,
    private val reader: WaypointReader,
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
                ?: WaypointRead.Failed(WaypointReader.TIMEOUT)
        val (result, label) =
            when (read) {
                is WaypointRead.Received -> {
                    val judged =
                        NavigationWaypoints.judge(read.waypoints, read.receivedAtMillis, places)
                    val shown = checkNotNull(source) { "the reader refuses reads without a source" }
                    NavigationToolResult.waypoints(tool, requestId, shown, catalog.sdk, judged) to
                        judged.status.name
                }
                is WaypointRead.Failed ->
                    NavigationToolResult.failure(read.code, requestId) to read.code
            }
        // Only fixed fields reach the log, never a waypoint value.
        events.finished(requestId, QUERY, label, clock() - started)
        return result
    }

    private suspend fun submit(): WaypointRead = suspendCancellableCoroutine { continuation ->
        reader.read(catalog.deadlineMs) { read ->
            if (continuation.isActive) continuation.resume(read)
        }
    }

    companion object {
        const val NAME = "get_navigation_waypoints"
        const val SDK_CALL = "getWaypointInfo"
        private const val QUERY = "navigation_waypoints"
        private const val GUARD_MILLIS = 1_000L
    }
}
