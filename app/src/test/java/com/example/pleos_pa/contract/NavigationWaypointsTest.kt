package com.example.pleos_pa.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure waypoint rules of `contracts/navigation/CONTRACT.md`. No SDK, no Android. */
class NavigationWaypointsTest {
    private val places = NavigationFixtures.places
    private val coex = places.place("coex")!!
    private val pangyo = places.place("pangyo_station")!!

    @Test
    fun aPlaceWithAnIdIsReadBackByThatIdWhateverTheStockAppCallsIt() {
        assertTrue(WaypointMatch.isPlace(coex, WaypointFields("코엑스몰", "동", coex.poiId)))
        assertFalse(WaypointMatch.isPlace(coex, WaypointFields("코엑스", "동", "another-id")))
        assertFalse(WaypointMatch.isPlace(coex, WaypointFields("코엑스", "동", "")))
    }

    @Test
    fun aPlaceWithoutAnIdIsReadBackByTheNameThatWasSent() {
        assertTrue(WaypointMatch.isPlace(pangyo, WaypointFields("판교역", "동", "")))
        assertTrue(WaypointMatch.isPlace(pangyo, WaypointFields(" 판교 역 ", "동", "")))
        assertFalse(WaypointMatch.isPlace(pangyo, WaypointFields("판교", "동", "")))
        assertFalse(WaypointMatch.isPlace(pangyo, WaypointFields("", "동", "")))
    }

    @Test
    fun twoReadsShowTheSameWaypointByIdOrElseByName() {
        val a = WaypointFields("코엑스", "동", "id-1")
        assertTrue(WaypointMatch.same(a, WaypointFields("다른 이름", "동", "id-1")))
        assertFalse(WaypointMatch.same(a, WaypointFields("코엑스", "동", "id-2")))
        assertTrue(
            WaypointMatch.same(WaypointFields("판교역", "", ""), WaypointFields("판교역", "x", ""))
        )
        assertFalse(WaypointMatch.same(WaypointFields("", "", ""), WaypointFields("", "", "")))
    }

    @Test
    fun noWaypointsIsNotAnEmptyResultWithAGuessedStatus() {
        val result = NavigationWaypoints.judge(emptyList(), 0L, places)

        assertEquals(WaypointStatus.NO_WAYPOINTS, result.status)
        assertTrue(result.waypoints.isEmpty())
        assertEquals("1970-01-01T00:00:00Z", result.receivedAt)
    }

    @Test
    fun waypointsKeepTheirOrderAndNameAListedPlaceByItsKey() {
        val result =
            NavigationWaypoints.judge(
                listOf(
                    WaypointFields("코엑스 몰", "동", coex.poiId),
                    WaypointFields("시청", "", ""),
                    WaypointFields("판교역", "동", ""),
                ),
                1_794_272_400_000L,
                places,
            )

        assertEquals(WaypointStatus.HAS_WAYPOINTS, result.status)
        assertEquals(listOf(1, 2, 3), result.waypoints.map { it.order })
        assertEquals(
            listOf("coex", null, "pangyo_station"),
            result.waypoints.map { it.candidateId },
        )
        val unlisted = result.waypoints[1]
        assertEquals("시청", unlisted.name)
        assertNull(unlisted.address)
        assertNull(unlisted.poiId)
    }
}
