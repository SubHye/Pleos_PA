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
    private val entry = NavigationFixtures.catalog.tool("get_navigation_destination")!!
    private val cases = NavigationFixtures.cases("tool-results")
    private val destinationCases = NavigationFixtures.cases("destination-tool-results")

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

    /** `fixtures/destination-tool-results.json`: the place and set-destination tools. */
    @Test
    fun destinationToolResultsMatchTheFixture() {
        val catalog = NavigationFixtures.catalog
        val find = catalog.tool("find_navigation_place")!!
        val set = catalog.tool("set_navigation_destination")!!
        val orderedCases =
            Json.parseToJsonElement(NavigationFixtures.text("destination-tool-results")).jsonArray
        for ((index, case) in destinationCases.withIndex()) {
            val id = case.getString("id")
            val given = case.getJSONObject("given")
            val requestId = given.getString("requestId")
            val result =
                when (given.getString("kind")) {
                    "places" ->
                        NavigationToolResult.places(
                            find,
                            requestId,
                            NavigationFixtures.places.find(given.getString("query")),
                        )
                    "set" ->
                        NavigationToolResult.destinationSet(
                            set,
                            requestId,
                            given.getString("source"),
                            catalog.sdk,
                            given.getString("status"),
                            NavigationFixtures.places.place(given.getString("candidateId"))!!,
                            given.getLong("elapsedMs"),
                        )
                    else ->
                        NavigationToolResult.notSet(
                            given.getString("code"),
                            requestId,
                            given.getBoolean("sent"),
                        )
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
                orderedCases[index]
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

    /** A result that was sent is never worded as a failure, and one not sent never as unknown. */
    @Test
    fun onlyASentRequestIsReportedAsUnknown() {
        for (case in destinationCases) {
            val given = case.getJSONObject("given")
            if (given.getString("kind") != "notSet") continue
            val text = case.getJSONObject("expect").getString("text")
            assertEquals(
                case.getString("id"),
                given.getBoolean("sent"),
                text.contains("단정하지 마세요"),
            )
            assertEquals(
                case.getString("id"),
                !given.getBoolean("sent"),
                text.contains("순정 내비는 바뀌지 않았습니다"),
            )
        }
    }

    @Test
    fun noResultCarriesCoordinates() {
        for (case in cases + destinationCases) {
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
