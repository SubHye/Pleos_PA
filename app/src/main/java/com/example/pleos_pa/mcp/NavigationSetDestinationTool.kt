package com.example.pleos_pa.mcp

import com.example.pleos_pa.bridge.BridgeEvents
import com.example.pleos_pa.contract.NavigationCatalog
import com.example.pleos_pa.contract.NavigationToolEntry
import com.example.pleos_pa.contract.PlaceCatalog
import com.example.pleos_pa.navigation.DestinationSetter
import com.example.pleos_pa.navigation.SetDestination
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * `set_navigation_destination`: one MCP call is one navigation change task. It takes only a
 * candidate ID that `find_navigation_place` returned; the place values come from the fixed list,
 * never from the client. An unknown ID is refused before anything reaches the SDK. The conditions,
 * the confirmation and the stop order are [DestinationSetter]'s. No retry.
 */
class NavigationSetDestinationTool(
    private val catalog: NavigationCatalog,
    private val places: PlaceCatalog,
    private val setter: DestinationSetter,
    private val source: String?,
    private val events: BridgeEvents,
    private val clock: () -> Long,
) : NavigationTool {
    override val entry: NavigationToolEntry? =
        catalog.tool(NAME)?.takeIf { it.adoption == "adopted" && it.sdkCall == SDK_CALL }

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
        val outcome: SetDestination =
            if (place == null) {
                SetDestination.NotDone(UNKNOWN_CANDIDATE, sent = false)
            } else {
                // The setter answers at its own limits; this only guards against a lost reply,
                // after which what the request did is not known.
                withTimeoutOrNull(
                    catalog.baselineDeadlineMs + catalog.confirmTimeoutMs + GUARD_MILLIS
                ) {
                    suspendCancellableCoroutine<SetDestination> { continuation ->
                        setter.set(requestId, place) {
                            if (continuation.isActive) continuation.resume(it)
                        }
                    }
                } ?: SetDestination.NotDone(DestinationSetter.OUTCOME_UNKNOWN, sent = true)
            }
        val elapsed = clock() - started
        val (result, label) =
            when (outcome) {
                SetDestination.Applied,
                SetDestination.AlreadySet -> {
                    val status = if (outcome == SetDestination.Applied) APPLIED else ALREADY_SET
                    val shown = checkNotNull(source) { "nothing is set without a source" }
                    NavigationToolResult.destinationSet(
                        tool,
                        requestId,
                        shown,
                        catalog.sdk,
                        status,
                        checkNotNull(place),
                        elapsed,
                    ) to status
                }
                is SetDestination.NotDone ->
                    NavigationToolResult.notSet(outcome.code, requestId, outcome.sent) to
                        outcome.code
            }
        // Only fixed fields reach the log, never the candidate or a place value.
        events.finished(requestId, LABEL, label, elapsed)
        return result
    }

    companion object {
        const val NAME = "set_navigation_destination"
        const val SDK_CALL = "requestRoute"
        const val CANDIDATE_ID = "candidateId"
        const val APPLIED = "APPLIED"
        const val ALREADY_SET = "ALREADY_SET"
        const val UNKNOWN_CANDIDATE = "UNKNOWN_CANDIDATE"
        private const val LABEL = "navigation_set_destination"
        private const val GUARD_MILLIS = 1_000L
    }
}
