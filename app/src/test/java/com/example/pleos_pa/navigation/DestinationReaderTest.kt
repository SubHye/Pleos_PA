package com.example.pleos_pa.navigation

import com.example.pleos_pa.contract.DestinationFields
import com.example.pleos_pa.contract.WaypointFields
import com.example.pleos_pa.vehicle.ManualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A hand-driven NaviHelper stand-in: answers only when the test says so. */
internal class FakeNaviSdk : NaviSdk {
    var listener: NaviSdkListener? = null
    @Volatile var requests = 0
    var closed = 0
    /** Thrown by the next [requestDestination], as the SDK throws without `NAVI_ROUTE`. */
    var throwNext: Exception? = null
    val failures = mutableListOf<(Exception) -> Unit>()

    override fun open(listener: NaviSdkListener) {
        this.listener = listener
    }

    override fun requestDestination(failed: (Exception) -> Unit) {
        requests++
        failures += failed
        throwNext?.let {
            throwNext = null
            failed(it)
        }
    }

    /** Every `requestRoute` handed over, in order. */
    val routes = mutableListOf<RouteRequest>()

    /** What the next [requestRoute] throws instead of returning. */
    var throwRoute: Exception? = null

    override fun requestRoute(route: RouteRequest, returned: (Exception?) -> Unit) {
        routes += route
        returned(throwRoute.also { throwRoute = null })
    }

    /** Every `requestWaypoints` call, and what the next one throws. */
    @Volatile var waypointRequests = 0
    var throwWaypoints: Exception? = null

    override fun requestWaypoints(failed: (Exception) -> Unit) {
        waypointRequests++
        throwWaypoints?.let {
            throwWaypoints = null
            failed(it)
        }
    }

    /** Every `addWaypoint` and `removeWaypoint` handed over, in order. */
    val addedWaypoints = mutableListOf<WaypointRequest>()
    val removedWaypoints = mutableListOf<WaypointSlot>()

    /** What the next `addWaypoint` or `removeWaypoint` throws instead of returning. */
    var throwWaypointChange: Exception? = null

    override fun addWaypoint(waypoint: WaypointRequest, returned: (Exception?) -> Unit) {
        addedWaypoints += waypoint
        returned(throwWaypointChange.also { throwWaypointChange = null })
    }

    override fun removeWaypoint(slot: WaypointSlot, returned: (Exception?) -> Unit) {
        removedWaypoints += slot
        returned(throwWaypointChange.also { throwWaypointChange = null })
    }

    override fun close(closed: () -> Unit) {
        this.closed++
        closed()
    }

    fun connect() = listener!!.connected()

    fun answerWaypoints(waypoints: List<WaypointFields>, at: Long = RECEIVED) =
        listener!!.waypoints(waypoints, at)

    fun failConnection() = listener!!.connectionFailed()

    fun answer(fields: DestinationFields = GUIDING, at: Long = RECEIVED) =
        listener!!.destination(fields, at)

    companion object {
        const val RECEIVED = 1_794_272_400_000L
        val GUIDING = DestinationFields("서울역", "서울 중구 한강대로 405", 37.55, 126.97, 12300, 1620)
        val OTHER = DestinationFields("시청", "서울 중구 세종대로 110", 37.56, 126.97, 900, 180)
    }
}

/** State-machine checks with an SDK stand-in. Not a navigation function verification. */
class DestinationReaderTest {
    private val scheduler = ManualScheduler()
    private val sdk = FakeNaviSdk()
    private val replies = mutableListOf<DestinationRead>()

    private fun reader(source: String? = "simulation") =
        DestinationReader(sdk, scheduler, source, STALE_HOLD)

    private fun connected(): DestinationReader =
        reader().also {
            it.open()
            sdk.connect()
            scheduler.runAll()
        }

    private fun DestinationReader.readNow() {
        read(BUDGET) { replies += it }
        scheduler.runAll()
    }

    private fun failed(code: String) = DestinationRead.Failed(code)

    @Test
    fun aConnectedReadReturnsTheAnswer() {
        val reader = connected()
        reader.readNow()
        assertEquals(1, sdk.requests)
        assertTrue(replies.isEmpty())

        sdk.answer()
        scheduler.runAll()

        assertEquals(
            listOf(DestinationRead.Received(FakeNaviSdk.GUIDING, FakeNaviSdk.RECEIVED)),
            replies,
        )
    }

    @Test
    fun aReadBeforeTheConnectionDoesNotCallTheSdk() {
        val reader = reader()
        reader.open()
        reader.readNow()

        assertEquals(listOf(failed(DestinationReader.NAVI_NOT_CONNECTED)), replies)
        assertEquals(0, sdk.requests)
    }

    @Test
    fun aFailedConnectionStaysFailedAndIsNotRetried() {
        val reader = reader()
        reader.open()
        sdk.failConnection()
        scheduler.runAll()
        // A later success report does not undo the first result.
        sdk.connect()
        scheduler.runAll()
        reader.readNow()

        assertEquals(listOf(failed(DestinationReader.NAVI_NOT_CONNECTED)), replies)
        assertEquals(0, sdk.requests)
    }

    @Test
    fun withoutASourceNothingIsRead() {
        val reader = reader(source = null)
        reader.open()
        sdk.connect()
        scheduler.runAll()
        reader.readNow()

        assertEquals(listOf(failed(DestinationReader.APP_NOT_READY)), replies)
        assertEquals(0, sdk.requests)
    }

    @Test
    fun aSecondReadWhileOneIsInProgressIsBusyAndNotQueued() {
        val reader = connected()
        reader.readNow()
        reader.readNow()

        assertEquals(listOf(failed(DestinationReader.BUSY)), replies)
        assertEquals(1, sdk.requests)

        sdk.answer()
        scheduler.runAll()
        assertEquals(2, replies.size)
        assertEquals(1, sdk.requests)
    }

    @Test
    fun aTimedOutReadHoldsNewReadsUntilItsLateAnswerArrives() {
        val reader = connected()
        reader.readNow()
        scheduler.advance(BUDGET)
        assertEquals(listOf(failed(DestinationReader.TIMEOUT)), replies)

        reader.readNow()
        assertEquals(failed(DestinationReader.PREVIOUS_RESPONSE_PENDING), replies.last())
        assertEquals(1, sdk.requests)

        // The late answer of the timed-out read is dropped, not given to anyone.
        sdk.answer(FakeNaviSdk.OTHER)
        scheduler.runAll()
        assertEquals(2, replies.size)

        reader.readNow()
        assertEquals(2, sdk.requests)
        sdk.answer()
        scheduler.runAll()
        assertEquals(
            DestinationRead.Received(FakeNaviSdk.GUIDING, FakeNaviSdk.RECEIVED),
            replies.last(),
        )
    }

    @Test
    fun theHoldIsLiftedAfterStaleHoldWithoutALateAnswer() {
        val reader = connected()
        reader.readNow()
        scheduler.advance(BUDGET)
        scheduler.advance(STALE_HOLD - 1)
        reader.readNow()
        assertEquals(failed(DestinationReader.PREVIOUS_RESPONSE_PENDING), replies.last())

        scheduler.advance(1)
        reader.readNow()
        assertEquals(2, sdk.requests)
    }

    /** The residual risk the contract records: a callback later than the hold is not told apart. */
    @Test
    fun aCallbackLaterThanTheHoldIsTakenByTheNextRead() {
        val reader = connected()
        reader.readNow()
        scheduler.advance(BUDGET)
        scheduler.advance(STALE_HOLD)
        reader.readNow()

        sdk.answer(FakeNaviSdk.OTHER)
        scheduler.runAll()

        assertEquals(
            DestinationRead.Received(FakeNaviSdk.OTHER, FakeNaviSdk.RECEIVED),
            replies.last(),
        )
    }

    @Test
    fun aThrowingCallOfATimedOutReadLiftsTheHold() {
        val reader = connected()
        reader.readNow()
        scheduler.advance(BUDGET)
        // The executor ran the call only now, and it threw: no late answer will follow.
        sdk.failures.single()(IllegalStateException())
        scheduler.runAll()

        reader.readNow()
        assertEquals(2, sdk.requests)
    }

    @Test
    fun aSecurityExceptionIsPermissionDeniedAndNotRetried() {
        val reader = connected()
        sdk.throwNext = SecurityException("NAVI_ROUTE is required")
        reader.readNow()

        assertEquals(listOf(failed(DestinationReader.PERMISSION_DENIED)), replies)
        assertEquals(1, sdk.requests)
        // Nothing is held after a call that threw.
        reader.readNow()
        assertEquals(2, sdk.requests)
    }

    @Test
    fun failuresAreClassifiedByTheirCauseChain() {
        assertEquals(
            DestinationReader.PERMISSION_DENIED,
            DestinationReader.classify(RuntimeException(SecurityException())),
        )
        assertEquals(DestinationReader.READ_FAILED, DestinationReader.classify(RuntimeException()))
    }

    @Test
    fun aCallbackWithNoReadInProgressIsDropped() {
        val reader = connected()
        sdk.answer(FakeNaviSdk.OTHER)
        scheduler.runAll()
        reader.readNow()

        assertTrue(replies.isEmpty())
        assertEquals(1, sdk.requests)
    }

    @Test
    fun aWatcherSeesEveryCallbackUntilItStops() {
        val reader = connected()
        val seen = mutableListOf<DestinationFields>()
        val stop = reader.watch { seen += it }
        sdk.answer(FakeNaviSdk.OTHER)
        scheduler.runAll()
        reader.readNow()
        sdk.answer()
        scheduler.runAll()

        // The read in progress still gets its answer.
        assertEquals(listOf(FakeNaviSdk.OTHER, FakeNaviSdk.GUIDING), seen)
        assertEquals(1, replies.size)

        stop()
        sdk.answer()
        scheduler.runAll()
        assertEquals(2, seen.size)
    }

    @Test
    fun aProbeAsksTheSdkWithoutTakingTheSlotOrLeavingAHold() {
        val reader = connected()
        reader.probe()
        assertEquals(1, sdk.requests)

        // Unanswered, it blocks nothing.
        scheduler.advance(BUDGET + STALE_HOLD)
        reader.readNow()
        assertEquals(2, sdk.requests)
        assertTrue(replies.isEmpty())
    }

    @Test
    fun aProbeIsNotSentBeforeTheConnectionOrAfterAStop() {
        val unconnected = reader()
        unconnected.open()
        unconnected.probe()
        assertEquals(DestinationReader.NAVI_NOT_CONNECTED, unconnected.unavailable())

        val reader = connected()
        assertEquals(null, reader.unavailable())
        reader.closeAdmission()
        reader.probe()

        assertEquals(DestinationReader.CONNECTION_STOPPED, reader.unavailable())
        assertEquals(0, sdk.requests)
    }

    @Test
    fun aReadIsAnsweredOnlyOnce() {
        val reader = connected()
        reader.readNow()
        sdk.answer()
        sdk.answer(FakeNaviSdk.OTHER)
        scheduler.runAll()
        scheduler.advance(BUDGET)

        assertEquals(1, replies.size)
    }

    @Test
    fun closingEndsTheReadInProgressAndReleasesTheSdk() {
        val reader = connected()
        reader.readNow()
        var released = false
        reader.close { released = true }
        scheduler.runAll()

        assertEquals(listOf(failed(DestinationReader.CONNECTION_STOPPED)), replies)
        assertTrue(released)
        assertEquals(1, sdk.closed)

        sdk.answer()
        scheduler.runAll()
        reader.readNow()
        assertEquals(failed(DestinationReader.CONNECTION_STOPPED), replies.last())
        assertEquals(2, replies.size)
        assertEquals(1, sdk.requests)
    }

    @Test
    fun aClosedAdmissionRefusesWithoutCallingTheSdk() {
        val reader = connected()
        reader.closeAdmission()
        reader.readNow()

        assertEquals(listOf(failed(DestinationReader.CONNECTION_STOPPED)), replies)
        assertEquals(0, sdk.requests)
    }

    private companion object {
        const val BUDGET = 10_000L
        const val STALE_HOLD = 10_000L
    }
}
