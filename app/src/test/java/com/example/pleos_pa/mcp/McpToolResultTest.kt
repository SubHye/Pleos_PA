package com.example.pleos_pa.mcp

import com.example.pleos_pa.contract.ContractFixtures
import com.example.pleos_pa.contract.jsonEquivalent
import com.example.pleos_pa.contract.stringList
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COM-13: the Kotlin port must give the same MCP results as `mcp/src/tool-result.ts` for every
 * items-form case in the shared fixture. Fixture values are mock evidence, not vehicle results.
 */
class McpToolResultTest {
    private val itemsQueries: Set<String> =
        JSONObject(ContractFixtures.catalogText).getJSONArray("tools").let { tools ->
            (0 until tools.length())
                .map { tools.getJSONObject(it) }
                .filter { it.getString("resultForm") == "items" }
                .map { it.getString("query") }
                .toSet()
        }

    private val cases =
        ContractFixtures.cases("tool-results").filter { it.getString("query") in itemsQueries }

    @Test
    fun everyItemsFormCaseIsCovered() {
        assertEquals(14, cases.size)
    }

    @Test
    fun itemsFormResultsMatchTheNodeFixture() {
        for (case in cases) {
            val name = case.getString("name")
            val bridge = case.getJSONObject("bridge")
            val result =
                if (bridge.getBoolean("ok")) {
                    val response = bridge.getJSONObject("response").toString()
                    McpToolResult.items(Json.parseToJsonElement(response).jsonObject)
                } else {
                    McpToolResult.failure(
                        bridge.getString("code"),
                        bridge.getString("origin"),
                        case.getString("requestId"),
                        case.getString("query"),
                    )
                }
            val expect = case.getJSONObject("expect")

            assertEquals(name, expect.getBoolean("isError"), result.isError == true)
            val structured = JSONObject(result.structuredContent.toString())
            assertTrue(name, jsonEquivalent(expect.getJSONObject("structuredContent"), structured))
            assertEquals(name, 1, result.content.size)
            val text = (result.content.single() as TextContent).text
            for (part in expect.stringList("textIncludes")) {
                assertTrue("$name: missing $part\n$text", text.contains(part))
            }
            for (part in expect.stringList("textExcludes")) {
                assertTrue("$name: unexpected $part\n$text", !text.contains(part))
            }
        }
    }
}
