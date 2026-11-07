package com.example.pleos_pa.mcp

import com.example.pleos_pa.contract.ContractFixtures
import com.example.pleos_pa.contract.jsonEquivalent
import com.example.pleos_pa.contract.stringList
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin port must give the same MCP results as `mcp/src/tool-result.ts` for every case of the
 * shared fixture, text included: `expect.text` is what the Node server writes. Fixture values are
 * mock evidence, not vehicle results.
 */
class McpToolResultTest {
    private val catalog = ContractFixtures.catalog
    private val cases = ContractFixtures.cases("tool-results")

    /** The same cases read without org.json, which on the JVM does not keep key order. */
    private val ordered =
        Json.parseToJsonElement(
                File("../contracts/vehicle-read/fixtures/tool-results.json").readText()
            )
            .jsonArray

    @Test
    fun bothResultFormsAreCovered() {
        val forms = cases.map { catalog.tools.getValue(it.getString("query")).resultForm }
        assertEquals(23, cases.size)
        assertEquals(setOf("items", "scalar"), forms.toSet())
    }

    @Test
    fun resultsMatchTheNodeFixture() {
        for ((index, case) in cases.withIndex()) {
            val name = case.getString("name")
            val tool = catalog.tools.getValue(case.getString("query"))
            val bridge = case.getJSONObject("bridge")
            val result =
                if (bridge.getBoolean("ok")) {
                    val response =
                        ordered[index].jsonObject.getValue("bridge").jsonObject.getValue("response")
                    McpToolResult.of(tool, response.jsonObject)
                } else {
                    McpToolResult.failure(
                        tool,
                        bridge.getString("code"),
                        bridge.getString("origin"),
                        case.getString("requestId"),
                    )
                }
            val expect = case.getJSONObject("expect")

            assertEquals(name, expect.getBoolean("isError"), result.isError == true)
            val structured = JSONObject(result.structuredContent.toString())
            assertTrue(
                "$name\n$structured",
                jsonEquivalent(expect.getJSONObject("structuredContent"), structured),
            )
            assertEquals(name, 1, result.content.size)
            val text = (result.content.single() as TextContent).text
            assertEquals(name, expect.getString("text"), text)
            for (part in expect.stringList("textIncludes")) {
                assertTrue("$name: missing $part\n$text", text.contains(part))
            }
            for (part in expect.stringList("textExcludes")) {
                assertTrue("$name: unexpected $part\n$text", !text.contains(part))
            }
        }
    }

    @Test
    fun aWholeNumberPrintsWithoutAFraction() {
        val tool = catalog.tools.getValue("energy_level")
        val case = cases.first { it.getString("name") == "complete is not an error" }
        val response = case.getJSONObject("bridge").getJSONObject("response").toString()
        val asFloat = response.replace("\"rawValue\":62", "\"rawValue\":62.0")
        assertTrue(asFloat != response)

        val text =
            (McpToolResult.of(tool, Json.parseToJsonElement(asFloat).jsonObject).content.single()
                    as TextContent)
                .text
        assertTrue(text, text.contains("[FUEL_PERCENT]: 62 %"))
    }
}
