package com.example.pleos_pa.contract

import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins `contracts/navigation/places.json`: only places verified on the Pleos emulator are listed,
 * and each one carries its own evidence. Reads recorded evidence; nothing here runs the SDK.
 */
class PlaceCatalogTest {
    private val catalog = NavigationFixtures.places
    private val raw = JSONObject(NavigationFixtures.placesText).getJSONArray("places")
    private val entries = (0 until raw.length()).map { raw.getJSONObject(it) }

    @Test
    fun placesAreNamedAndLocatedConsistently() {
        assertTrue(catalog.places.size in 3..5)
        for (place in catalog.places) {
            assertTrue(place.key, Regex("[a-z][a-z0-9_]*").matches(place.key))
            assertTrue(place.key, place.displayName.isNotBlank() && place.address.isNotBlank())
            assertTrue(place.key, place.aliases.isNotEmpty() && place.aliases.none { it.isBlank() })
            assertTrue(place.key, place.latitude in -90.0..90.0 && place.latitude != 0.0)
            assertTrue(place.key, place.longitude in -180.0..180.0 && place.longitude != 0.0)
        }
    }

    @Test
    fun aPlaceIdComesOnlyFromTheStockNavigationApp() {
        for (entry in entries) {
            val key = entry.getString("key")
            val source = entry.getString("coordinateSource")
            assertTrue(key, source == NAVI_CAPTURE || source.startsWith("external:"))
            if (entry.getString("poiId").isNotEmpty()) assertEquals(key, NAVI_CAPTURE, source)
            else assertEquals(key, "", entry.getString("poiSubId"))
        }
        // Both forms of RouteInfo are exercised: with the stock app's ID and with an empty one.
        assertTrue(catalog.places.any { it.poiId.isNotEmpty() })
        assertTrue(catalog.places.any { it.poiId.isEmpty() })
    }

    @Test
    fun everyPlaceWasSetAtLeastTwiceAndReadBackAtItsListedCoordinates() {
        for (entry in entries) {
            val key = entry.getString("key")
            val place = catalog.place(key)!!
            val evidence = entry.getJSONObject("evidence")
            assertEquals(key, "sdk_emulator", evidence.getString("status"))
            val runs = evidence.getJSONArray("runs")
            assertTrue(key, runs.length() >= 2)
            for (i in 0 until runs.length()) {
                val run = runs.getJSONObject(i)
                for (field in listOf("date", "build", "environment")) {
                    assertTrue("$key $field", run.getString(field).isNotBlank())
                }
                assertEquals(key, place.poiId, run.getString("requestedPoiId"))
                assertEquals(key, "PASS", run.getString("verdict"))
                val reread = run.getJSONObject("reread")
                assertTrue(key, abs(reread.getDouble("latitude") - place.latitude) <= TOLERANCE)
                assertTrue(key, abs(reread.getDouble("longitude") - place.longitude) <= TOLERANCE)
            }
        }
    }

    private companion object {
        const val NAVI_CAPTURE = "navi_capture"
        // About 0.1 m; the same bound tools/nav-place-check judges with.
        const val TOLERANCE = 1e-6
    }
}
