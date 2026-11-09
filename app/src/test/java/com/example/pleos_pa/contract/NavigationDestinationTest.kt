package com.example.pleos_pa.contract

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every case of `destination-cases.json`. Pure judgment; nothing here runs the SDK. */
class NavigationDestinationTest {
    private val cases = NavigationFixtures.cases("destination-cases")

    @Test
    fun everyCaseIsJudgedAsTheFixtureSays() {
        for (case in cases) {
            val id = case.getString("id")
            val expect = case.getJSONObject("expect")
            val result =
                NavigationDestination.judge(
                    NavigationFixtures.fieldsOf(case.getJSONObject("input")),
                    NavigationFixtures.millis(case.getString("receivedAt")),
                )
            assertEquals(id, expect.getString("status"), result.status.name)
            assertEquals(id, expect.getString("receivedAt"), result.receivedAt)
            assertEquals(id, expect.nullableString("name"), result.name)
            assertEquals(id, expect.nullableString("address"), result.address)
            assertEquals(
                id,
                expect.nullableInt("remainDistanceMeters"),
                result.remainDistanceMeters,
            )
            assertEquals(id, expect.nullableInt("remainTimeSeconds"), result.remainTimeSeconds)
            assertEquals(id, expect.nullableString("estimatedArrivalAt"), result.estimatedArrivalAt)
            assertEquals(id, expect.stringList("missing"), result.missing)
        }
    }

    @Test
    fun theFixtureCoversEveryStatusAndTheAgreedEdges() {
        val ids = cases.map { it.getString("id") }.toSet()
        assertEquals(
            setOf("COMPLETE", "PARTIAL", "NO_DESTINATION"),
            cases.map { it.getJSONObject("expect").getString("status") }.toSet(),
        )
        for (needed in
            listOf(
                "utc_midnight",
                "kst_midnight",
                "time_zero",
                "time_negative",
                "distance_zero",
                "name_blank_with_coordinates",
                "no_destination",
            )) {
            assertTrue(needed, needed in ids)
        }
        assertTrue(cases.all { it.getString("evidence") in setOf("mock", "sdk_emulator") })
    }

    @Test
    fun aZeroTimeNeverBecomesAnArrival() {
        val zero =
            NavigationDestination.judge(
                DestinationFields("A", "B", 37.0, 127.0, remainDistance = 10, remainTime = 0),
                0L,
            )
        assertEquals(null, zero.estimatedArrivalAt)
        assertEquals(DestinationStatus.PARTIAL, zero.status)
    }

    @Test
    fun theLargestIntTimeDoesNotOverflow() {
        val result =
            NavigationDestination.judge(
                DestinationFields("A", "B", 37.0, 127.0, 1, Int.MAX_VALUE),
                0L,
            )
        assertEquals("2038-01-19T03:14:07Z", result.estimatedArrivalAt)
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else getString(name)

    private fun JSONObject.nullableInt(name: String): Int? =
        if (isNull(name)) null else getInt(name)
}
