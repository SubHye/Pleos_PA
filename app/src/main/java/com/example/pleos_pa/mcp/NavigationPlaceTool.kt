package com.example.pleos_pa.mcp

import com.example.pleos_pa.bridge.BridgeEvents
import com.example.pleos_pa.contract.NavigationCatalog
import com.example.pleos_pa.contract.NavigationToolEntry
import com.example.pleos_pa.contract.PlaceCatalog
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import java.util.UUID
import kotlinx.serialization.json.JsonObject

/**
 * `find_navigation_place`: matches a place string against the fixed place list. No SDK call and no
 * navigation change. The query may come from a calendar entry, so it reaches neither the log nor
 * the result.
 */
class NavigationPlaceTool(
    catalog: NavigationCatalog,
    private val places: PlaceCatalog,
    private val events: BridgeEvents,
    private val clock: () -> Long,
) : NavigationTool {
    override val entry: NavigationToolEntry? =
        catalog.tool(NAME)?.takeIf { it.adoption == "adopted" }

    private val argument = StringArgument(QUERY, "찾을 장소 이름. 층·회의실 같은 말은 빼고 장소 부분만 넣습니다.")

    override val inputSchema = argument.schema

    override suspend fun call(tool: NavigationToolEntry, arguments: JsonObject?): CallToolResult {
        val query =
            when (val read = argument.read(arguments)) {
                is StringArgument.Read.Refused ->
                    return NavigationToolResult.invalidInput(tool, read.reason)
                is StringArgument.Read.Accepted -> read.value
            }
        val requestId = UUID.randomUUID().toString()
        val started = clock()
        events.requestReceived()
        val match = places.find(query)
        // Only fixed fields reach the log, never the query or a place.
        events.finished(requestId, LABEL, match.status.name, clock() - started)
        return NavigationToolResult.places(tool, requestId, match)
    }

    companion object {
        const val NAME = "find_navigation_place"
        const val QUERY = "query"
        private const val LABEL = "navigation_place"
    }
}
