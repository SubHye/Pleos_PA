package com.example.pleos_pa.contract

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleReadContractTest {
    private val catalog = ContractFixtures.catalog
    private val validator = VehicleReadValidator(catalog)

    @Test
    fun requestsMatchTheirFixtures() {
        val cases = ContractFixtures.cases("requests")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val parsed = VehicleReadRequest.parse(catalog, case.getJSONObject("body").toString())
            if (case.getBoolean("accept")) {
                assertNotNull(case.getString("name"), parsed)
            } else {
                assertNull(case.getString("name"), parsed)
            }
        }
    }

    @Test
    fun oversizedRequestIsRejected() {
        val padded =
            JSONObject()
                .put("requestId", "11111111-1111-4111-8111-111111111111")
                .put("remainingMs", 9000)
                .put("query", "seat_status")
                .put("properties", List(catalog.bridge.maxRequestBytes) { "X" })
        assertNull(VehicleReadRequest.parse(catalog, padded.toString()))
    }

    @Test
    fun responsesMatchTheirFixtures() {
        val cases = ContractFixtures.cases("responses")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val name = case.getString("name")
            val expect = case.getJSONObject("expect")
            val result = validate(case)
            if (expect.getBoolean("ok")) {
                val accepted = result as? BridgeResult.Accepted
                assertNotNull("$name: $result", accepted)
                assertEquals(name, expect.getString("outcome"), accepted!!.response.outcome.name)
            } else {
                val expected =
                    BridgeResult.Failed(
                        expect.getString("code"),
                        FailureOrigin.valueOf(expect.getString("origin").uppercase()),
                    )
                assertEquals(name, expected, result)
            }
        }
    }

    @Test
    fun acceptedResponsesSerializeBackToTheWireForm() {
        for (case in ContractFixtures.cases("responses")) {
            if (!case.getJSONObject("expect").getBoolean("ok")) continue
            val accepted = validate(case) as BridgeResult.Accepted
            val name = case.getString("name")
            assertTrue(name, jsonEquivalent(case.getJSONObject("body"), accepted.response.toJson()))
        }
    }

    @Test
    fun oversizedResponseIsInvalid() {
        val text = " ".repeat(catalog.bridge.maxResponseBytes + 1)
        assertEquals(
            BridgeResult.Failed("INVALID_RESPONSE", FailureOrigin.PC),
            validator.validate(200, text, "id", "door_status"),
        )
    }

    @Test
    fun malformedResponseIsInvalid() {
        assertEquals(
            BridgeResult.Failed("INVALID_RESPONSE", FailureOrigin.PC),
            validator.validate(200, "not json", "id", "door_status"),
        )
    }

    @Test
    fun nonFiniteValueIsInvalid() {
        val case =
            ContractFixtures.cases("responses").first {
                it.getString("name") == "float property keeps a value beyond 32 bits"
            }
        val text = case.getJSONObject("body").toString().replace("2147483648", "1e999")
        assertEquals(
            BridgeResult.Failed("INVALID_RESPONSE", FailureOrigin.PC),
            validator.validate(200, text, case.getString("requestId"), case.getString("query")),
        )
    }

    private fun validate(case: JSONObject): BridgeResult {
        val selection = case.optJSONObject("selection") ?: JSONObject()
        return validator.validate(
            http = case.getInt("http"),
            text = case.getJSONObject("body").toString(),
            requestId = case.getString("requestId"),
            query = case.getString("query"),
            selection =
                ReadSelection(
                    selection.stringList("properties").toSet(),
                    selection.stringList("areas").toSet(),
                ),
        )
    }
}
