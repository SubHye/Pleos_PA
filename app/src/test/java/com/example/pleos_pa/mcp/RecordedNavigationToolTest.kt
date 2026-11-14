package com.example.pleos_pa.mcp

import com.example.pleos_pa.contract.NavigationFixtures
import com.example.pleos_pa.contract.NavigationToolEntry
import com.example.pleos_pa.ui.RequestHistory
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The screen record of a navigation tool call. No SDK runs here. */
class RecordedNavigationToolTest {
    private val history = RequestHistory()
    private val entry = NavigationFixtures.catalog.tool("set_navigation_destination")!!

    private class Fake(
        override val entry: NavigationToolEntry,
        private val answer: (JsonObject?) -> CallToolResult,
    ) : NavigationTool {
        override val inputSchema = ToolSchema()

        override suspend fun call(tool: NavigationToolEntry, arguments: JsonObject?) =
            answer(arguments)
    }

    private fun recorded(answer: (JsonObject?) -> CallToolResult) =
        RecordedNavigationTool(Fake(entry, answer), history) { 0L }

    private fun stateAfter(result: CallToolResult): String {
        history.clear()
        runBlocking { recorded { result }.call(entry, null) }
        return history.snapshot().single().state
    }

    @Test
    fun theRecordKeepsTheToolAndItsStateButNeverTheArgumentsOrTheAnswer() {
        val place = "private-place-판교역"
        val answer =
            CallToolResult(
                content = listOf(TextContent("목적지를 $place(으)로 설정했습니다.")),
                structuredContent =
                    buildJsonObject {
                        put("ok", true)
                        put("status", "APPLIED")
                        put("name", place)
                    },
            )
        val tool = recorded { arguments ->
            // While the tool works the screen already shows the request, unfinished.
            assertEquals("RECEIVED", history.snapshot().single().state)
            assertEquals(place, arguments!!.getValue("placeId").toString().trim('"'))
            answer
        }
        val returned = runBlocking { tool.call(entry, buildJsonObject { put("placeId", place) }) }
        assertSame("The caller gets the tool's own result", answer, returned)
        val row = history.snapshot().single()
        assertEquals("set_navigation_destination", row.tool)
        assertEquals("COMPLETE", row.state)
        assertEquals("1970-01-01T00:00:00Z", row.receivedAt)
        assertFalse(
            "No argument or place reaches the record",
            listOf(row.requestId, row.arguments, row.details, row.headline.orEmpty()).any {
                it.contains("private-place") || it.contains("판교역")
            },
        )
    }

    @Test
    fun statesFollowTheStructuredResult() {
        assertEquals(
            "PARTIAL",
            stateAfter(
                CallToolResult(
                    content = emptyList(),
                    isError = true,
                    structuredContent =
                        buildJsonObject {
                            put("ok", true)
                            put("status", "PARTIAL")
                        },
                )
            ),
        )
        assertEquals("BUSY", stateAfter(NavigationToolResult.notSet("BUSY", "id", false)))
        assertEquals(
            "BLOCKED",
            stateAfter(NavigationToolResult.notSet("DRIVING_RESTRICTED", "id", false)),
        )
        assertEquals(
            "BLOCKED",
            stateAfter(NavigationToolResult.notSet("DRIVING_STATE_UNKNOWN", "id", false)),
        )
        assertEquals(
            "CONNECTION_STOPPED",
            stateAfter(NavigationToolResult.failure("CONNECTION_STOPPED", "id")),
        )
        assertEquals("FAILED", stateAfter(NavigationToolResult.failure("TIMEOUT", "id")))
        assertEquals(
            "A sent change whose outcome is unknown is not a failure",
            "RESULT_UNKNOWN",
            stateAfter(NavigationToolResult.waypointNotChanged("OUTCOME_UNKNOWN", "id", true)),
        )
    }

    @Test
    fun refusedInputLeavesNoRecord() {
        runBlocking {
            recorded { NavigationToolResult.invalidInput(entry, "placeId: Required") }
                .call(entry, null)
        }
        assertTrue(history.snapshot().isEmpty())
    }

    @Test
    fun aToolThatThrowsLeavesAFailedRecordAndStillThrows() {
        val tool = recorded { throw IllegalStateException("private detail") }
        try {
            runBlocking { tool.call(entry, null) }
            fail("The failure must reach the server")
        } catch (_: IllegalStateException) {}
        val row = history.snapshot().single()
        assertEquals("FAILED", row.state)
        assertFalse(row.details.contains("private detail"))
    }
}
