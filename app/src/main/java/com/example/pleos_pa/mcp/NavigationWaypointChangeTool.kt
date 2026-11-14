package com.example.pleos_pa.mcp

import com.example.pleos_pa.bridge.BridgeEvents
import com.example.pleos_pa.contract.NavigationCatalog
import com.example.pleos_pa.contract.NavigationToolEntry
import com.example.pleos_pa.contract.Place
import com.example.pleos_pa.contract.PlaceCatalog
import com.example.pleos_pa.navigation.WaypointChange
import com.example.pleos_pa.navigation.WaypointChanger
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * `add_navigation_waypoint` and `remove_navigation_waypoint`: one MCP call is one navigation change
 * task. Both take only a candidate ID that `find_navigation_place` returned; the place values come
 * from the fixed list, never from the client. An unknown ID is refused before anything reaches the
 * SDK. The conditions, the confirmation and the stop order are [WaypointChanger]'s. No retry.
 */
class NavigationWaypointChangeTool
private constructor(
    private val name: String,
    private val sdkCall: String,
    private val added: Boolean,
    private val catalog: NavigationCatalog,
    private val places: PlaceCatalog,
    private val change: (requestId: String, place: Place, reply: (WaypointChange) -> Unit) -> Unit,
    private val source: String?,
    private val events: BridgeEvents,
    private val clock: () -> Long,
) : NavigationTool {
    override val entry: NavigationToolEntry? =
        catalog.tool(name)?.takeIf { it.adoption == "adopted" && it.sdkCall == sdkCall }

    private val argument =
        StringArgument(CANDIDATE_ID, "find_navigation_place가 돌려준 candidateId. 다른 값은 받지 않습니다.")

    override val inputSchema = argument.schema

    override suspend fun call(tool: NavigationToolEntry, arguments: JsonObject?): CallToolResult {
        val candidateId =
            when (val read = argument.read(arguments)) {
                is StringArgument.Read.Refused ->
                    return NavigationToolResult.invalidInput(tool, read.reason)
                is StringArgument.Read.Accepted -> read.value
            }
        val requestId = UUID.randomUUID().toString()
        val started = clock()
        events.requestReceived()
        val place = places.place(candidateId)
        val outcome: WaypointChange =
            if (place == null) {
                WaypointChange.NotDone(UNKNOWN_CANDIDATE, sent = false)
            } else {
                // The changer answers at its own limits; this only guards against a lost reply,
                // after which what the request did is not known. Two baseline reads come first.
                withTimeoutOrNull(
                    2 * catalog.baselineDeadlineMs + catalog.confirmTimeoutMs + GUARD_MILLIS
                ) {
                    suspendCancellableCoroutine<WaypointChange> { continuation ->
                        change(requestId, place) {
                            if (continuation.isActive) continuation.resume(it)
                        }
                    }
                } ?: WaypointChange.NotDone(WaypointChanger.OUTCOME_UNKNOWN, sent = true)
            }
        val elapsed = clock() - started
        val (result, label) =
            when (outcome) {
                WaypointChange.Applied -> {
                    val shown = checkNotNull(source) { "nothing is changed without a source" }
                    NavigationToolResult.waypointChanged(
                        tool,
                        requestId,
                        shown,
                        catalog.sdk,
                        added,
                        checkNotNull(place),
                        elapsed,
                    ) to APPLIED
                }
                is WaypointChange.NotDone ->
                    NavigationToolResult.waypointNotChanged(
                        outcome.code,
                        requestId,
                        outcome.sent,
                    ) to outcome.code
            }
        // Only fixed fields reach the log, never the candidate or a place value.
        events.finished(requestId, if (added) ADD_LABEL else REMOVE_LABEL, label, elapsed)
        return result
    }

    companion object {
        const val ADD_NAME = "add_navigation_waypoint"
        const val ADD_SDK_CALL = "addWaypoint"
        const val REMOVE_NAME = "remove_navigation_waypoint"
        const val REMOVE_SDK_CALL = "removeWaypoint"
        const val CANDIDATE_ID = "candidateId"
        const val APPLIED = "APPLIED"
        const val UNKNOWN_CANDIDATE = "UNKNOWN_CANDIDATE"
        private const val ADD_LABEL = "navigation_add_waypoint"
        private const val REMOVE_LABEL = "navigation_remove_waypoint"
        private const val GUARD_MILLIS = 1_000L

        fun add(
            catalog: NavigationCatalog,
            places: PlaceCatalog,
            changer: WaypointChanger,
            source: String?,
            events: BridgeEvents,
            clock: () -> Long,
        ) =
            NavigationWaypointChangeTool(
                ADD_NAME,
                ADD_SDK_CALL,
                added = true,
                catalog,
                places,
                changer::add,
                source,
                events,
                clock,
            )

        fun remove(
            catalog: NavigationCatalog,
            places: PlaceCatalog,
            changer: WaypointChanger,
            source: String?,
            events: BridgeEvents,
            clock: () -> Long,
        ) =
            NavigationWaypointChangeTool(
                REMOVE_NAME,
                REMOVE_SDK_CALL,
                added = false,
                catalog,
                places,
                changer::remove,
                source,
                events,
                clock,
            )
    }
}
