package com.example.pleos_pa.mcp

import com.example.pleos_pa.contract.ContractFixtures
import com.example.pleos_pa.contract.ReadSelection
import com.example.pleos_pa.contract.VehicleReadRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Tool arguments get the same verdicts as Node's zod schemas over `tool-inputs.json`. */
class ToolInputsTest {
    private val catalog = ContractFixtures.catalog

    private fun inputs(name: String) =
        ToolInputs(catalog, catalog.tools.values.single { it.name == name })

    private fun select(name: String, args: String) =
        inputs(name).select(Json.parseToJsonElement(args).jsonObject)

    @Test
    fun argumentsMatchTheNodeVerdicts() {
        for (case in ContractFixtures.cases("tool-inputs")) {
            val selected = select(case.getString("tool"), case.getJSONObject("args").toString())
            assertEquals(
                case.getString("name"),
                case.getBoolean("accept"),
                selected is ToolInputs.Selected.Accepted,
            )
        }
    }

    @Test
    fun acceptedArgumentsMakeRequestsTheBridgeContractAccepts() {
        for (case in ContractFixtures.cases("tool-inputs")) {
            if (!case.getBoolean("accept")) continue
            val tool = catalog.tools.values.single { it.name == case.getString("tool") }
            val selection =
                (select(tool.name, case.getJSONObject("args").toString())
                        as ToolInputs.Selected.Accepted)
                    .selection
            val request =
                JSONObject()
                    .put("requestId", "11111111-1111-4111-8111-111111111111")
                    .put("remainingMs", 10_000)
                    .put("query", tool.query)
            if (selection.properties.isNotEmpty()) request.put("properties", selection.properties)
            if (selection.areas.isNotEmpty()) request.put("areas", selection.areas)
            assertNotNull(
                case.getString("name"),
                VehicleReadRequest.parse(catalog, request.toString()),
            )
        }
    }

    @Test
    fun omittedOrNullArgumentsReadTheWholeTool() {
        assertEquals(
            ToolInputs.Selected.Accepted(ReadSelection()),
            inputs("get_seat_status").select(null),
        )
        assertEquals(ToolInputs.Selected.Accepted(ReadSelection()), select("get_door_status", "{}"))
    }

    @Test
    fun selectorsMustBeStrings() {
        val refused = select("get_seat_status", """{"properties":[1]}""")
        assertEquals(true, refused is ToolInputs.Selected.Refused)
        assertEquals(
            true,
            select("get_seat_status", """{"areas":null}""") is ToolInputs.Selected.Refused,
        )
    }

    @Test
    fun theRefusalNamesAnUnknownKeyLikeNode() {
        assertEquals(
            ToolInputs.Selected.Refused("Unrecognized key: \"timeoutMs\""),
            select("get_seat_status", """{"timeoutMs":1000}"""),
        )
    }

    @Test
    fun everyCatalogToolAcceptsNoArguments() {
        for (tool in catalog.tools.values) {
            assertEquals(
                tool.name,
                true,
                inputs(tool.name).select(null) is ToolInputs.Selected.Accepted,
            )
        }
    }
}
