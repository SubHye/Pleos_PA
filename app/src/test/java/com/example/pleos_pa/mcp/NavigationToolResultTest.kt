package com.example.pleos_pa.mcp

import com.example.pleos_pa.contract.NavigationFixtures
import com.example.pleos_pa.contract.jsonEquivalent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `contracts/navigation/fixtures/tool-results.json`, text included. Mock evidence only. */
class NavigationToolResultTest {
    private val entry = NavigationFixtures.catalog.tools.single()
    private val cases = NavigationFixtures.cases("tool-results")

    /** The same cases with key order kept, to compare structuredContent key for key. */
    private val ordered = Json.parseToJsonElement(NavigationFixtures.text("tool-results")).jsonArray

    @Test
    fun resultsMatchTheFixture() {
        for ((index, case) in cases.withIndex()) {
            val id = case.getString("id")
            val given = case.getJSONObject("given")
            val requestId = given.getString("requestId")
            val result =
                if (given.getString("kind") == "received") {
                    NavigationToolResult.of(
                        entry,
                        requestId,
                        given.getString("source"),
                        NavigationFixtures.catalog.sdk,
                        NavigationFixtures.judged(given.getString("destinationCase")),
                    )
                } else {
                    NavigationToolResult.failure(given.getString("code"), requestId)
                }
            val expect = case.getJSONObject("expect")
            assertEquals(
                id,
                expect.getString("text"),
                (result.content.single() as TextContent).text,
            )
            assertEquals(id, if (expect.getBoolean("isError")) true else null, result.isError)
            val structured = JSONObject(result.structuredContent.toString())
            assertTrue(id, jsonEquivalent(expect.getJSONObject("structuredContent"), structured))
            val keys =
                ordered[index]
                    .jsonObject
                    .getValue("expect")
                    .jsonObject
                    .getValue("structuredContent")
                    .jsonObject
                    .keys
                    .toList()
            assertEquals(id, keys, result.structuredContent!!.keys.toList())
        }
    }

    @Test
    fun noResultCarriesCoordinates() {
        for (case in cases) {
            val structured = case.getJSONObject("expect").getJSONObject("structuredContent")
            val text = structured.toString()
            assertTrue(!text.contains("latitude") && !text.contains("longitude"))
        }
    }

    @Test
    fun invalidInputHasNoStructuredContent() {
        val result = NavigationToolResult.invalidInput(entry, "Unrecognized key: \"x\"")
        assertEquals(true, result.isError)
        assertEquals(null, result.structuredContent)
        assertTrue(
            (result.content.single() as TextContent).text.startsWith("Input validation error:")
        )
    }
}
