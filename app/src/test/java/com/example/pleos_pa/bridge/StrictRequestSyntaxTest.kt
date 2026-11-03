package com.example.pleos_pa.bridge

import com.example.pleos_pa.contract.ContractFixtures
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictRequestSyntaxTest {
    private val base = """"requestId":"1","remainingMs":9000,"query":"door_status""""

    @Test
    fun everyAcceptedFixtureIsWellFormed() {
        for (case in ContractFixtures.cases("requests")) {
            if (!case.getBoolean("accept")) continue
            val text = case.getJSONObject("body").toString()
            assertTrue(case.getString("name"), StrictRequestSyntax.isWellFormed(text))
        }
    }

    @Test
    fun aRequestWithSelectorsIsWellFormed() {
        val text = """ { $base , "properties" : [ "A", "B" ], "areas": ["C+D"] } """
        assertTrue(StrictRequestSyntax.isWellFormed(text))
    }

    @Test
    fun whatAndroidsParserWouldForgiveIsRejected() {
        val forgiven =
            listOf(
                "{$base} trailing",
                "{$base}{}",
                """{$base,"query":"window_status"}""",
                "{$base,}",
                "{requestId:\"1\"}",
                "{'requestId':'1'}",
                "{$base /* note */}",
                "{$base,\"remainingMs\":9000.5}",
                "{$base,\"remainingMs\":1e3}",
                "{\"query\":\"door\nstatus\"}",
                """{"query":"door\"status"}""",
                """{"areas":[1]}""",
                """{"areas":["A",]}""",
                "",
                "[]",
                "null",
            )
        for (text in forgiven) assertFalse(text, StrictRequestSyntax.isWellFormed(text))
    }

    @Test
    fun theDocumentedBodyParsesAfterTheCheck() {
        val text = "{$base}"
        assertTrue(StrictRequestSyntax.isWellFormed(text))
        assertTrue(JSONObject(text).has("query"))
    }
}
