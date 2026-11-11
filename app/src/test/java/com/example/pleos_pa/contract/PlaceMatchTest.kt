package com.example.pleos_pa.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every case of `place-cases.json` against `places.json`. Pure matching; no SDK runs here. */
class PlaceMatchTest {
    private val catalog = NavigationFixtures.places
    private val cases = NavigationFixtures.cases("place-cases")

    @Test
    fun everyCaseMatchesAsRecorded() {
        for (case in cases) {
            val id = case.getString("id")
            val expect = case.getJSONObject("expect")
            val keys =
                expect.getJSONArray("keys").let { a -> (0 until a.length()).map(a::getString) }
            val match = catalog.find(case.getString("query"))
            assertEquals(id, expect.getString("status"), match.status.name)
            assertEquals(id, keys, match.candidates.map { it.key })
        }
    }

    @Test
    fun casesNameOnlyListedPlacesAndCoverEveryStatus() {
        val listed = catalog.places.map { it.key }.toSet()
        val statuses = mutableSetOf<String>()
        for (case in cases) {
            val expect = case.getJSONObject("expect")
            val keys = expect.getJSONArray("keys")
            for (i in 0 until keys.length()) {
                assertTrue(case.getString("id"), keys.getString(i) in listed)
            }
            statuses += expect.getString("status")
        }
        assertEquals(PlaceMatchStatus.entries.map { it.name }.toSet(), statuses)
        assertEquals(cases.size, cases.map { it.getString("id") }.toSet().size)
    }

    @Test
    fun normalizingDropsEveryWhitespaceAndCase() {
        assertEquals("seoulstation", PlaceCatalog.normalize(" Seoul\tSTATION\n"))
        assertEquals("판교현대백화점", PlaceCatalog.normalize("판교 현대백화점"))
    }
}
