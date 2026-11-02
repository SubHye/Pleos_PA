package com.example.pleos_pa.contract

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkFailureClassifierTest {
    @Test
    fun failuresMatchTheirFixtures() {
        val cases = ContractFixtures.cases("failures")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val name = case.getString("name")
            val stage = ReadStage.valueOf(case.getString("stage"))
            val failure =
                SdkFailureClassifier.classify(stage, exception(case.getJSONObject("exception")))
            val expect = case.getJSONObject("expect")
            assertEquals(name, expect.getString("status"), failure.status.name)
            val code = if (expect.isNull("errorCode")) null else expect.getString("errorCode")
            assertEquals(name, code, failure.errorCode)
        }
    }

    @Test
    fun theFixturesCoverObservedSdkFailures() {
        val observed =
            ContractFixtures.cases("failures").count { it.getString("evidence") == "sdk_emulator" }
        assertTrue(observed >= 2)
    }

    @Test
    fun errorTypeNeverCarriesTheMessage() {
        val failure = SdkFailureClassifier.classify(ReadStage.READ, Exception("secret detail"))
        assertEquals("Exception", failure.errorType)
    }

    @Test
    fun aCauseCycleEnds() {
        val first = Exception("first")
        val second = Exception("second", first)
        first.initCause(second)
        assertEquals(
            ItemStatus.READ_FAILED,
            SdkFailureClassifier.classify(ReadStage.READ, first).status,
        )
    }

    private fun exception(spec: JSONObject): Throwable {
        val message = if (spec.isNull("message")) null else spec.getString("message")
        val cause = spec.optJSONObject("cause")?.let(::exception)
        return when (spec.getString("type")) {
            "security" -> SecurityException(message, cause)
            "vehicle" -> publishedVehicleException(spec.getString("error"))
            else -> Exception(message ?: cause?.toString(), cause)
        }
    }

    /** The published AAR renames VehicleException to `a`, so only reflection can reach it. */
    private fun publishedVehicleException(error: String): Throwable =
        Class.forName("ai.pleos.playground.vehicle.callback.exception.a")
            .getConstructor(String::class.java)
            .newInstance(error) as Throwable
}
