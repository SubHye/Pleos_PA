package com.example.pleos_pa.navigation

import com.example.pleos_pa.contract.DestinationFields
import com.example.pleos_pa.contract.NavigationFixtures
import com.example.pleos_pa.contract.Place
import com.example.pleos_pa.contract.WaypointFields
import com.example.pleos_pa.vehicle.ManualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The waypoint add and remove tasks with an SDK stand-in, by the catalog's limits. State and
 * call-count checks by the observations of COM-47; not a verification that the stock navigation app
 * took a waypoint.
 */
class WaypointChangerTest {
    private val catalog = NavigationFixtures.catalog
    private val places = NavigationFixtures.places
    private val scheduler = ManualScheduler()
    private val sdk = FakeNaviSdk()
    private val states = mutableListOf<NavTaskState>()
    private val outcomes = mutableListOf<WaypointChange>()
    private val destinationOutcomes = mutableListOf<SetDestination>()
    private val coordinator = NavTaskCoordinator.shared { _, state -> states += state }
    private var restricted: String? = null

    private lateinit var destinations: DestinationReader
    private val waypoints =
        WaypointReader(sdk, scheduler, catalog.staleHoldMs) {
            destinations.unavailable()
        }

    private lateinit var changer: WaypointChanger

    init {
        destinations =
            DestinationReader(
                sdk,
                scheduler,
                "simulation",
                catalog.staleHoldMs,
                waypoints::delivered,
            )
        changer = changerOn(destinations)
        destinations.open()
        sdk.connect()
        scheduler.runAll()
    }

    private fun changerOn(reader: DestinationReader) =
        WaypointChanger(
            sdk,
            reader,
            waypoints,
            scheduler,
            coordinator,
            catalog.baselineDeadlineMs,
            catalog.confirmTimeoutMs,
            catalog.confirmPollMs,
            catalog.matchToleranceDegrees,
            catalog.maxWaypoints,
            restriction = { restricted },
        )

    private fun place(key: String): Place = places.place(key)!!

    /** A listed place as the stock app reads it back: the ID if it has one, and no coordinates. */
    private fun one(key: String): WaypointFields {
        val place = place(key)
        val name = if (place.poiId.isNotEmpty()) "${place.displayName} KTX" else place.displayName
        return WaypointFields(name, "동", place.poiId)
    }

    private fun read(vararg keys: String) = keys.map(::one)

    private val elsewhere = WaypointFields("시청", "서울 중구", "city-hall")

    /** Runs a request through its checks: the destination is read, then the waypoints. */
    private fun begin(
        before: List<WaypointFields>,
        destination: DestinationFields = FakeNaviSdk.GUIDING,
        request: () -> Unit,
    ) {
        request()
        scheduler.runAll()
        sdk.answer(destination)
        scheduler.runAll()
        sdk.answerWaypoints(before)
        scheduler.runAll()
    }

    private fun add(key: String, id: String = "r1") = changer.add(id, place(key)) { outcomes += it }

    private fun remove(key: String, id: String = "r1") =
        changer.remove(id, place(key)) { outcomes += it }

    /** The stock app's answer after the change: the waypoints and the destination. */
    private fun readBack(
        list: List<WaypointFields>,
        destination: DestinationFields = FakeNaviSdk.GUIDING,
    ) {
        sdk.answerWaypoints(list)
        sdk.answer(destination)
        scheduler.runAll()
    }

    private fun notDone(code: String, sent: Boolean) = WaypointChange.NotDone(code, sent)

    @Test
    fun anAddIsAppliedOnlyWhenTheWaypointsAndTheDestinationAreReadBack() {
        begin(emptyList()) { add("coex") }

        val coex = place("coex")
        assertEquals(
            listOf(
                WaypointRequest(
                    coex.latitude,
                    coex.longitude,
                    coex.displayName,
                    coex.address,
                    coex.poiId,
                    WaypointSlot.FIRST,
                )
            ),
            sdk.addedWaypoints,
        )
        assertTrue(outcomes.isEmpty())

        // A request having been passed on proves nothing; neither does a list without the place.
        readBack(emptyList())
        assertTrue(outcomes.isEmpty())

        readBack(read("coex"))
        assertEquals(listOf<WaypointChange>(WaypointChange.Applied), outcomes)
        assertEquals(
            listOf(
                NavTaskState.RECEIVED,
                NavTaskState.CHECKING,
                NavTaskState.EXECUTING,
                NavTaskState.SUCCEEDED,
            ),
            states,
        )

        // Nothing goes on once the task has ended.
        val requests = sdk.requests
        scheduler.advance(catalog.confirmTimeoutMs)
        assertEquals(requests, sdk.requests)
        assertEquals(1, sdk.addedWaypoints.size)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun theSecondWaypointIsAppendedAtSecond() {
        begin(read("pangyo_station")) { add("coex") }

        assertEquals(listOf(WaypointSlot.SECOND), sdk.addedWaypoints.map { it.slot })
        readBack(read("pangyo_station", "coex"))
        assertEquals(listOf<WaypointChange>(WaypointChange.Applied), outcomes)
    }

    @Test
    fun theChangeIsConfirmedByPollingBothReads() {
        begin(emptyList()) { add("coex") }
        val destinationReads = sdk.requests
        val waypointReads = sdk.waypointRequests

        scheduler.advance(catalog.confirmPollMs)
        assertEquals(destinationReads + 1, sdk.requests)
        assertEquals(waypointReads + 1, sdk.waypointRequests)
    }

    @Test
    fun withoutADestinationNothingIsSent() {
        begin(emptyList(), destination = DestinationFields("", "", 0.0, 0.0, 0, 0)) {
            add("coex")
        }

        assertEquals(
            listOf<WaypointChange>(notDone(WaypointChanger.NO_DESTINATION, false)),
            outcomes,
        )
        assertEquals(NavTaskState.BLOCKED, states.last())
        assertEquals(0, sdk.waypointRequests)
        assertEquals(0, sdk.addedWaypoints.size)
    }

    @Test
    fun aPlaceAlreadyAWaypointIsNotAddedAgain() {
        // A place with an ID is read back by that ID, one without by the name that was sent.
        begin(read("coex")) { add("coex") }
        begin(read("pangyo_station"), FakeNaviSdk.GUIDING) { add("pangyo_station", "r2") }

        assertEquals(
            listOf<WaypointChange>(
                notDone(WaypointChanger.ALREADY_WAYPOINT, false),
                notDone(WaypointChanger.ALREADY_WAYPOINT, false),
            ),
            outcomes,
        )
        assertEquals(0, sdk.addedWaypoints.size)
    }

    @Test
    fun aFullListIsNotAddedTo() {
        begin(listOf(elsewhere, one("pangyo_station"))) { add("coex") }

        assertEquals(
            listOf<WaypointChange>(notDone(WaypointChanger.WAYPOINT_LIMIT, false)),
            outcomes,
        )
        assertEquals(0, sdk.addedWaypoints.size)
    }

    @Test
    fun anUnreadDestinationOrWaypointListBlocksTheChange() {
        add("coex")
        scheduler.runAll()
        scheduler.advance(catalog.baselineDeadlineMs)
        assertEquals(
            listOf<WaypointChange>(notDone(WaypointChanger.BASELINE_UNAVAILABLE, false)),
            outcomes,
        )

        // The late answer lifts the hold; the next request reads again and now gets no waypoints.
        sdk.answer(FakeNaviSdk.GUIDING)
        scheduler.runAll()
        add("coex", "r2")
        scheduler.runAll()
        sdk.answer(FakeNaviSdk.GUIDING)
        scheduler.runAll()
        scheduler.advance(catalog.baselineDeadlineMs)
        assertEquals(notDone(WaypointChanger.WAYPOINTS_UNAVAILABLE, false), outcomes.last())
        assertEquals(0, sdk.addedWaypoints.size)
    }

    @Test
    fun noReadBackLeavesTheOutcomeUnknownAndNothingRepeats() {
        begin(emptyList()) { add("coex") }
        scheduler.advance(catalog.confirmTimeoutMs)

        assertEquals(
            listOf<WaypointChange>(notDone(WaypointChanger.OUTCOME_UNKNOWN, true)),
            outcomes,
        )
        assertEquals(NavTaskState.FAILED, states.last())
        assertEquals(1, sdk.addedWaypoints.size)

        // A late match changes nothing.
        readBack(read("coex"))
        assertEquals(1, outcomes.size)
    }

    @Test
    fun aListThatLostAnOtherWaypointDoesNotConfirmAnAdd() {
        begin(read("pangyo_station")) { add("coex") }
        // Two entries, but the earlier waypoint is gone: not the change that was asked for.
        readBack(listOf(one("coex"), elsewhere))
        scheduler.advance(catalog.confirmTimeoutMs)

        assertEquals(
            listOf<WaypointChange>(notDone(WaypointChanger.OUTCOME_UNKNOWN, true)),
            outcomes,
        )
    }

    @Test
    fun aChangedDestinationDoesNotConfirmAnAddUntilItIsBack() {
        begin(emptyList()) { add("coex") }
        val empty = DestinationFields("", "", 0.0, 0.0, 0, 0)
        readBack(read("coex"), destination = empty)
        assertTrue(outcomes.isEmpty())

        readBack(read("coex"))
        assertEquals(listOf<WaypointChange>(WaypointChange.Applied), outcomes)
    }

    @Test
    fun aRemoveSendsThePositionOfThePlaceInTheListJustRead() {
        begin(read("coex", "pangyo_station")) { remove("coex") }
        assertEquals(listOf(WaypointSlot.FIRST), sdk.removedWaypoints)
        readBack(read("pangyo_station"))
        assertEquals(listOf<WaypointChange>(WaypointChange.Applied), outcomes)

        begin(read("coex", "pangyo_station")) { remove("pangyo_station", "r2") }
        assertEquals(listOf(WaypointSlot.FIRST, WaypointSlot.SECOND), sdk.removedWaypoints)
        readBack(read("coex"))
        assertEquals(WaypointChange.Applied, outcomes.last())
    }

    @Test
    fun aRemoveIsNotConfirmedWhileTheListIsUnchanged() {
        begin(read("coex", "pangyo_station")) { remove("coex") }
        readBack(read("coex", "pangyo_station"))
        // One fewer, but the other waypoint is not the one that should have stayed.
        readBack(listOf(elsewhere))
        scheduler.advance(catalog.confirmTimeoutMs)

        assertEquals(
            listOf<WaypointChange>(notDone(WaypointChanger.OUTCOME_UNKNOWN, true)),
            outcomes,
        )
        assertEquals(1, sdk.removedWaypoints.size)
    }

    @Test
    fun aRemoveOfAPlaceThatIsNotAWaypointSendsNothing() {
        begin(read("pangyo_station")) { remove("coex") }

        assertEquals(
            listOf<WaypointChange>(notDone(WaypointChanger.WAYPOINT_NOT_FOUND, false)),
            outcomes,
        )
        assertEquals(0, sdk.removedWaypoints.size)
    }

    @Test
    fun aPlaceListedTwiceIsNotGuessedAt() {
        begin(read("coex", "coex")) { remove("coex") }

        assertEquals(
            listOf<WaypointChange>(notDone(WaypointChanger.AMBIGUOUS_WAYPOINT, false)),
            outcomes,
        )
        assertEquals(0, sdk.removedWaypoints.size)
    }

    @Test
    fun aSecurityExceptionIsPermissionDeniedAndNotSent() {
        sdk.throwWaypointChange = SecurityException("NAVI_ROUTE_SEARCH is required")
        begin(emptyList()) { add("coex") }

        assertEquals(
            listOf<WaypointChange>(notDone(WaypointChanger.PERMISSION_DENIED, false)),
            outcomes,
        )
        assertEquals(NavTaskState.FAILED, states.last())
    }

    @Test
    fun anyOtherExceptionLeavesTheOutcomeUnknown() {
        sdk.throwWaypointChange = IllegalStateException()
        begin(read("coex")) { remove("coex") }

        assertEquals(
            listOf<WaypointChange>(notDone(WaypointChanger.OUTCOME_UNKNOWN, true)),
            outcomes,
        )
    }

    @Test
    fun oneChangeAtATimeAcrossWaypointsAndTheDestination() {
        begin(emptyList()) { add("coex") }
        remove("pangyo_station", "r2")
        val setter =
            DestinationSetter(
                sdk,
                destinations,
                scheduler,
                catalog.baselineDeadlineMs,
                catalog.confirmTimeoutMs,
                catalog.confirmPollMs,
                catalog.matchToleranceDegrees,
                shared = coordinator,
            )
        setter.set("r3", place("seoul_station")) { destinationOutcomes += it }
        scheduler.runAll()

        assertEquals(listOf<WaypointChange>(notDone(WaypointChanger.BUSY, false)), outcomes)
        assertEquals(
            listOf<SetDestination>(SetDestination.NotDone(DestinationSetter.BUSY, sent = false)),
            destinationOutcomes,
        )

        // The first change goes on and is the only one sent.
        readBack(read("coex"))
        assertEquals(WaypointChange.Applied, outcomes.last())
        assertEquals(1, sdk.addedWaypoints.size)
        assertEquals(0, sdk.routes.size)
        assertEquals(0, sdk.removedWaypoints.size)
    }

    @Test
    fun aRestrictionBlocksBeforeAnySdkCall() {
        restricted = "DRIVING_RESTRICTED"
        add("coex")
        scheduler.runAll()

        assertEquals(listOf<WaypointChange>(notDone("DRIVING_RESTRICTED", false)), outcomes)
        assertEquals(0, sdk.requests)
        assertEquals(0, sdk.waypointRequests)
        assertEquals(0, sdk.addedWaypoints.size)
    }

    @Test
    fun aStopWhileCheckingBlocksAndSendsNothing() {
        add("coex")
        scheduler.runAll()
        changer.close()
        destinations.closeAdmission()
        scheduler.runAll()
        sdk.answer(FakeNaviSdk.GUIDING)
        scheduler.runAll()

        assertEquals(
            listOf<WaypointChange>(notDone(WaypointChanger.CONNECTION_STOPPED, false)),
            outcomes,
        )
        assertEquals(NavTaskState.BLOCKED, states.last())
        assertEquals(0, sdk.addedWaypoints.size)
    }

    @Test
    fun aStopAfterTheRequestIsUnconfirmedAndNotACancellation() {
        begin(emptyList()) { add("coex") }
        changer.close()
        destinations.closeAdmission()
        scheduler.runAll()

        assertEquals(
            listOf<WaypointChange>(notDone(WaypointChanger.CONNECTION_STOPPED, true)),
            outcomes,
        )
        assertEquals(NavTaskState.FAILED, states.last())

        // A match that arrives later changes nothing, and no poll is sent.
        val requests = sdk.requests
        readBack(read("coex"))
        scheduler.advance(catalog.confirmTimeoutMs)
        assertEquals(1, outcomes.size)
        assertEquals(requests, sdk.requests)
    }

    @Test
    fun aRequestAfterAStopIsRefused() {
        changer.close()
        scheduler.runAll()
        add("coex")
        scheduler.runAll()

        assertEquals(
            listOf<WaypointChange>(notDone(WaypointChanger.CONNECTION_STOPPED, false)),
            outcomes,
        )
        assertEquals(0, sdk.requests)
    }

    @Test
    fun aSharedCoordinatorBlocksARequestThatBringsNoConditions() {
        val started = mutableListOf<String>()
        val action =
            object : NavAction {
                override val name = "unchecked"

                override fun start(done: (failure: String?) -> Unit) {
                    started += name
                    done(null)
                }
            }
        var result: NavTaskResult? = null
        coordinator.submit(NavTaskRequest("x", listOf(action))) { result = it }

        assertEquals(NavTaskState.BLOCKED, result!!.state)
        assertEquals(NavTaskCoordinator.NO_CONDITIONS, result!!.code)
        assertTrue(started.isEmpty())
    }
}
