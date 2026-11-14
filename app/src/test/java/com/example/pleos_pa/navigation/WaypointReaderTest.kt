package com.example.pleos_pa.navigation

import com.example.pleos_pa.contract.WaypointFields
import com.example.pleos_pa.vehicle.ManualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** State-machine checks of the waypoint read slot with an SDK stand-in. Not a navigation check. */
class WaypointReaderTest {
    private val scheduler = ManualScheduler()
    private val sdk = FakeNaviSdk()
    private val replies = mutableListOf<WaypointRead>()
    private lateinit var destinations: DestinationReader
    private val reader = WaypointReader(sdk, scheduler, STALE_HOLD) { destinations.unavailable() }

    private fun connect(source: String? = "simulation") {
        destinations = DestinationReader(sdk, scheduler, source, STALE_HOLD, reader::delivered)
        destinations.open()
        sdk.connect()
        scheduler.runAll()
    }

    private fun readNow(budget: Long = BUDGET) {
        reader.read(budget) { replies += it }
        scheduler.runAll()
    }

    private fun answer(waypoints: List<WaypointFields> = LISTED, at: Long = FakeNaviSdk.RECEIVED) {
        sdk.answerWaypoints(waypoints, at)
        scheduler.runAll()
    }

    @Test
    fun theFirstCallbackAfterTheCallIsTheAnswer() {
        connect()
        readNow()
        assertEquals(1, sdk.waypointRequests)
        assertTrue(replies.isEmpty())

        answer()
        assertEquals(
            listOf<WaypointRead>(WaypointRead.Received(LISTED, FakeNaviSdk.RECEIVED)),
            replies,
        )

        // Nothing asked for it: dropped, and no second reply.
        answer(emptyList())
        assertEquals(1, replies.size)
    }

    @Test
    fun anEmptyListIsAnAnswer() {
        connect()
        readNow()
        answer(emptyList())

        assertEquals(WaypointRead.Received(emptyList(), FakeNaviSdk.RECEIVED), replies.single())
    }

    @Test
    fun aSecondReadWhileOneRunsIsBusy() {
        connect()
        readNow()
        readNow()

        assertEquals(listOf<WaypointRead>(WaypointRead.Failed(WaypointReader.BUSY)), replies)
        assertEquals(1, sdk.waypointRequests)
        answer()
        assertEquals(2, replies.size)
    }

    @Test
    fun withoutAConnectionOrASourceNothingIsAsked() {
        destinations =
            DestinationReader(sdk, scheduler, "simulation", STALE_HOLD, reader::delivered)
        destinations.open()
        readNow()
        assertEquals(
            WaypointRead.Failed(DestinationReader.NAVI_NOT_CONNECTED),
            replies.last(),
        )

        destinations = DestinationReader(sdk, scheduler, null, STALE_HOLD, reader::delivered)
        readNow()
        assertEquals(WaypointRead.Failed(DestinationReader.APP_NOT_READY), replies.last())
        assertEquals(0, sdk.waypointRequests)
    }

    @Test
    fun aTimedOutReadHoldsNewOnesUntilItsLateAnswerOrTheHoldEnds() {
        connect()
        readNow()
        scheduler.advance(BUDGET)
        assertEquals(WaypointRead.Failed(WaypointReader.TIMEOUT), replies.last())

        readNow()
        assertEquals(
            WaypointRead.Failed(WaypointReader.PREVIOUS_RESPONSE_PENDING),
            replies.last(),
        )
        assertEquals(1, sdk.waypointRequests)

        // The late answer is dropped and lifts the hold.
        answer()
        assertEquals(2, replies.size)
        readNow()
        assertEquals(2, sdk.waypointRequests)
    }

    @Test
    fun theHoldEndsOnItsOwnAfterTheStaleTime() {
        connect()
        readNow()
        scheduler.advance(BUDGET)
        scheduler.advance(STALE_HOLD)

        readNow()
        assertEquals(2, sdk.waypointRequests)
    }

    @Test
    fun aCallThatThrowsEndsTheReadAsAFailureOrAPermissionDenial() {
        connect()
        sdk.throwWaypoints = IllegalStateException()
        readNow()
        assertEquals(WaypointRead.Failed(DestinationReader.READ_FAILED), replies.last())

        sdk.throwWaypoints = SecurityException("NAVI_ROUTE is required")
        readNow()
        assertEquals(WaypointRead.Failed(DestinationReader.PERMISSION_DENIED), replies.last())
    }

    @Test
    fun anObserverSeesEveryCallbackAndAProbeTakesNoSlot() {
        connect()
        val seen = mutableListOf<List<WaypointFields>>()
        val stop = reader.watch { seen += it }

        reader.probe()
        answer()
        assertEquals(listOf(LISTED), seen)
        assertTrue(replies.isEmpty())
        assertEquals(1, sdk.waypointRequests)

        stop()
        answer(emptyList())
        assertEquals(1, seen.size)
    }

    @Test
    fun aStopEndsTheReadAndRefusesTheNextOne() {
        connect()
        readNow()
        reader.close()
        scheduler.runAll()
        assertEquals(WaypointRead.Failed(WaypointReader.CONNECTION_STOPPED), replies.single())

        readNow()
        assertEquals(WaypointRead.Failed(WaypointReader.CONNECTION_STOPPED), replies.last())
        reader.probe()
        assertEquals(1, sdk.waypointRequests)
    }

    private companion object {
        const val BUDGET = 3_000L
        const val STALE_HOLD = 10_000L
        val LISTED = listOf(WaypointFields("코엑스", "동", "36khp5gns575cm5nqhe4"))
    }
}
