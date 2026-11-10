package com.example.pleos_pa.navigation

import com.example.pleos_pa.contract.DestinationFields
import com.example.pleos_pa.contract.NavigationFixtures
import com.example.pleos_pa.contract.Place
import com.example.pleos_pa.vehicle.ManualScheduler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The destination change task with an SDK stand-in, by the catalog's limits and
 * `fixtures/set-destination-cases.json`. State and call-count checks; not a verification that the
 * stock navigation app took a destination.
 */
class DestinationSetterTest {
    private val catalog = NavigationFixtures.catalog
    private val places = NavigationFixtures.places
    private val scheduler = ManualScheduler()
    private val sdk = FakeNaviSdk()
    private val states = mutableListOf<NavTaskState>()
    private val outcomes = mutableListOf<SetDestination>()
    private var restricted: String? = null

    private fun reader(source: String? = "simulation") =
        DestinationReader(sdk, scheduler, source, catalog.staleHoldMs)

    private fun setter(reader: DestinationReader) =
        DestinationSetter(
            sdk,
            reader,
            scheduler,
            catalog.baselineDeadlineMs,
            catalog.confirmTimeoutMs,
            catalog.confirmPollMs,
            catalog.matchToleranceDegrees,
            { restricted },
        ) { _, state ->
            states += state
        }

    private fun connected(): Pair<DestinationReader, DestinationSetter> {
        val reader = reader()
        reader.open()
        sdk.connect()
        scheduler.runAll()
        return reader to setter(reader)
    }

    private fun place(key: String): Place = places.place(key)!!

    private fun at(place: Place) =
        DestinationFields(place.displayName, "동", place.latitude, place.longitude, 12000, 1500)

    private fun DestinationSetter.request(key: String, requestId: String = "r1") {
        set(requestId, place(key)) { outcomes += it }
        scheduler.runAll()
    }

    private fun answer(fields: DestinationFields) {
        sdk.answer(fields)
        scheduler.runAll()
    }

    private fun fieldsOf(json: JSONObject) =
        DestinationFields(
            json.getString("name"),
            "",
            json.getDouble("latitude"),
            json.getDouble("longitude"),
            0,
            0,
        )

    @Test
    fun outcomesMatchTheFixture() {
        for (case in NavigationFixtures.cases("set-destination-cases")) {
            val id = case.getString("id")
            val test = DestinationSetterTest()
            val (_, setter) = test.connected()
            test.run {
                setter.request(case.getString("place"))
                val baseline = case.getJSONObject("baseline")
                if (baseline.has("failure")) scheduler.advance(catalog.baselineDeadlineMs)
                else answer(fieldsOf(baseline))
                val observations = case.getJSONArray("observations")
                var next = 0
                var elapsed = 0L
                while (outcomes.isEmpty() && elapsed <= catalog.confirmTimeoutMs) {
                    scheduler.advance(catalog.confirmPollMs)
                    elapsed += catalog.confirmPollMs
                    while (next < observations.length()) {
                        val observation = observations.getJSONObject(next)
                        if (observation.getLong("atMs") > elapsed) break
                        answer(fieldsOf(observation))
                        next++
                    }
                }
                val expect = case.getJSONObject("expect")
                val expected =
                    when (expect.getString("outcome")) {
                        "APPLIED" -> SetDestination.Applied
                        "ALREADY_SET" -> SetDestination.AlreadySet
                        else ->
                            SetDestination.NotDone(
                                expect.getString("code"),
                                expect.getBoolean("sent"),
                            )
                    }
                assertEquals(id, listOf(expected), outcomes)
                assertEquals(id, expect.getInt("routeRequests"), sdk.routes.size)
            }
        }
    }

    @Test
    fun theRouteCarriesOnlyTheListedPlace() {
        val (_, setter) = connected()
        setter.request("seoul_station")
        answer(FakeNaviSdk.OTHER)

        val seoul = place("seoul_station")
        assertEquals(
            listOf(
                RouteRequest(
                    seoul.latitude,
                    seoul.longitude,
                    seoul.displayName,
                    seoul.address,
                    seoul.poiId,
                )
            ),
            sdk.routes,
        )
    }

    @Test
    fun aMatchIsConfirmedByPollingAndPollingStopsAfterwards() {
        val (_, setter) = connected()
        setter.request("pangyo_station")
        answer(FakeNaviSdk.OTHER)
        assertEquals(1, sdk.requests)

        scheduler.advance(catalog.confirmPollMs)
        scheduler.advance(catalog.confirmPollMs)
        assertEquals(3, sdk.requests)
        assertTrue(outcomes.isEmpty())

        answer(at(place("pangyo_station")))
        assertEquals(listOf<SetDestination>(SetDestination.Applied), outcomes)
        assertEquals(
            listOf(
                NavTaskState.RECEIVED,
                NavTaskState.CHECKING,
                NavTaskState.EXECUTING,
                NavTaskState.SUCCEEDED,
            ),
            states,
        )

        // Nothing goes on once the task has ended: no poll, no second request, no late timeout.
        scheduler.advance(catalog.confirmTimeoutMs)
        assertEquals(3, sdk.requests)
        assertEquals(1, sdk.routes.size)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun aSecondRequestWhileOneRunsIsBusyAndTheFirstGoesOn() {
        val (_, setter) = connected()
        setter.request("pangyo_station")
        setter.request("coex", "r2")

        assertEquals(
            listOf<SetDestination>(SetDestination.NotDone(DestinationSetter.BUSY, sent = false)),
            outcomes,
        )
        answer(FakeNaviSdk.OTHER)
        scheduler.advance(catalog.confirmPollMs)
        answer(at(place("pangyo_station")))

        assertEquals(SetDestination.Applied, outcomes.last())
        assertEquals(1, sdk.routes.size)
        assertEquals(place("pangyo_station").latitude, sdk.routes.single().latitude, 0.0)
    }

    @Test
    fun withoutANaviConnectionOrASourceNothingIsAsked() {
        val unconnected = reader()
        unconnected.open()
        setter(unconnected).request("coex")
        val elsewhere = reader(source = null)
        setter(elsewhere).request("coex")

        assertEquals(
            listOf<SetDestination>(
                SetDestination.NotDone(DestinationReader.NAVI_NOT_CONNECTED, sent = false),
                SetDestination.NotDone(DestinationReader.APP_NOT_READY, sent = false),
            ),
            outcomes,
        )
        assertEquals(0, sdk.requests)
        assertEquals(0, sdk.routes.size)
    }

    @Test
    fun aRestrictionBlocksBeforeAnySdkCall() {
        val (_, setter) = connected()
        restricted = "DRIVING_RESTRICTED"
        setter.request("coex")

        assertEquals(
            listOf<SetDestination>(SetDestination.NotDone("DRIVING_RESTRICTED", sent = false)),
            outcomes,
        )
        assertEquals(0, sdk.requests)
        assertEquals(0, sdk.routes.size)

        // Lifting it runs nothing on its own.
        restricted = null
        scheduler.advance(catalog.confirmTimeoutMs)
        assertEquals(0, sdk.routes.size)
    }

    @Test
    fun aBusyBaselineReadBlocksTheChange() {
        val (reader, setter) = connected()
        reader.read(catalog.deadlineMs) {}
        scheduler.runAll()
        setter.request("coex")

        assertEquals(
            listOf<SetDestination>(
                SetDestination.NotDone(DestinationSetter.BASELINE_UNAVAILABLE, sent = false)
            ),
            outcomes,
        )
        assertEquals(0, sdk.routes.size)
    }

    @Test
    fun aSecurityExceptionIsPermissionDeniedAndNotSent() {
        val (_, setter) = connected()
        sdk.throwRoute = SecurityException("NAVI_ROUTE_SEARCH is required")
        setter.request("coex")
        answer(FakeNaviSdk.OTHER)

        assertEquals(
            listOf<SetDestination>(
                SetDestination.NotDone(DestinationSetter.PERMISSION_DENIED, sent = false)
            ),
            outcomes,
        )
        assertEquals(NavTaskState.FAILED, states.last())
        scheduler.advance(catalog.confirmTimeoutMs)
        assertEquals(1, sdk.routes.size)
    }

    @Test
    fun anyOtherExceptionLeavesTheOutcomeUnknown() {
        val (_, setter) = connected()
        sdk.throwRoute = IllegalStateException()
        setter.request("coex")
        answer(FakeNaviSdk.OTHER)

        assertEquals(
            listOf<SetDestination>(
                SetDestination.NotDone(DestinationSetter.OUTCOME_UNKNOWN, sent = true)
            ),
            outcomes,
        )
        assertEquals(1, sdk.routes.size)
    }

    @Test
    fun aStopWhileCheckingBlocksAndSendsNothing() {
        val (reader, setter) = connected()
        setter.request("coex")
        setter.close()
        reader.closeAdmission()
        scheduler.runAll()
        answer(FakeNaviSdk.OTHER)

        assertEquals(
            listOf<SetDestination>(
                SetDestination.NotDone(DestinationSetter.CONNECTION_STOPPED, sent = false)
            ),
            outcomes,
        )
        assertEquals(NavTaskState.BLOCKED, states.last())
        assertEquals(0, sdk.routes.size)
    }

    @Test
    fun aQueuedBaselineReplyCannotStartARouteAfterStop() {
        val (reader, setter) = connected()
        setter.request("coex")
        sdk.answer(FakeNaviSdk.OTHER)
        setter.close()
        reader.closeAdmission()
        scheduler.runAll()

        assertEquals(
            listOf<SetDestination>(
                SetDestination.NotDone(
                    DestinationSetter.CONNECTION_STOPPED,
                    sent = false,
                )
            ),
            outcomes,
        )
        assertEquals(NavTaskState.BLOCKED, states.last())
        assertTrue(NavTaskState.EXECUTING !in states)
        assertEquals(0, sdk.routes.size)
        scheduler.advance(catalog.confirmTimeoutMs)
        assertEquals(1, outcomes.size)
        assertEquals(0, sdk.routes.size)
    }

    @Test
    fun aStopAfterTheRequestIsUnconfirmedAndNotACancellation() {
        val (reader, setter) = connected()
        setter.request("coex")
        answer(FakeNaviSdk.OTHER)
        setter.close()
        reader.closeAdmission()
        scheduler.runAll()

        assertEquals(
            listOf<SetDestination>(
                SetDestination.NotDone(DestinationSetter.CONNECTION_STOPPED, sent = true)
            ),
            outcomes,
        )
        assertEquals(NavTaskState.FAILED, states.last())

        // A match that arrives later changes nothing, and no poll is sent.
        answer(at(place("coex")))
        scheduler.advance(catalog.confirmTimeoutMs)
        assertEquals(1, outcomes.size)
        assertEquals(1, sdk.requests)
    }

    @Test
    fun aRequestAfterAStopIsRefused() {
        val (_, setter) = connected()
        setter.close()
        setter.request("coex")

        assertEquals(
            listOf<SetDestination>(
                SetDestination.NotDone(DestinationSetter.CONNECTION_STOPPED, sent = false)
            ),
            outcomes,
        )
        assertEquals(0, sdk.requests)
    }

    @Test
    fun aBlockedRequestHasToBeSubmittedAgain() {
        val (_, setter) = connected()
        setter.request("coex")
        scheduler.advance(catalog.baselineDeadlineMs)
        assertEquals(
            DestinationSetter.BASELINE_UNAVAILABLE,
            (outcomes.single() as SetDestination.NotDone).code,
        )

        // The late answer of the timed-out read lifts the hold; nothing runs by itself.
        answer(FakeNaviSdk.OTHER)
        scheduler.advance(catalog.confirmTimeoutMs)
        assertEquals(0, sdk.routes.size)

        setter.request("coex", "r2")
        answer(FakeNaviSdk.OTHER)
        scheduler.advance(catalog.confirmPollMs)
        answer(at(place("coex")))
        assertEquals(SetDestination.Applied, outcomes.last())
    }
}
