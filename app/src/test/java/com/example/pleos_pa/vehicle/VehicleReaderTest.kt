package com.example.pleos_pa.vehicle

import com.example.pleos_pa.contract.BridgeResult
import com.example.pleos_pa.contract.ContractFixtures
import com.example.pleos_pa.contract.ItemStatus
import com.example.pleos_pa.contract.Outcome
import com.example.pleos_pa.contract.ReadSelection
import com.example.pleos_pa.contract.VehicleReadRequest
import com.example.pleos_pa.contract.VehicleReadResponse
import com.example.pleos_pa.contract.VehicleReadValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ID = "11111111-1111-4111-8111-111111111111"

class VehicleReaderTest {
    private val catalog = ContractFixtures.catalog
    private val scheduler = ManualScheduler()
    private val sdk = FakeVehicleSdk()
    private val reader = VehicleReader(catalog, sdk, scheduler, "simulation")
    private val finished = mutableListOf<VehicleReadResponse>()

    private fun request(
        query: String,
        properties: Set<String> = emptySet(),
        areas: Set<String> = emptySet(),
    ) = VehicleReadRequest(ID, 9000, query, properties, areas)

    private fun read(request: VehicleReadRequest, budget: Long = 9000) =
        reader.read(request, budget) { finished += it }

    private fun assertValid(response: VehicleReadResponse, selection: ReadSelection) {
        val verdict =
            VehicleReadValidator(catalog)
                .validate(
                    200,
                    response.toJson().toString(),
                    response.requestId,
                    response.query,
                    selection,
                )
        assertTrue(response.toJson().toString(), verdict is BridgeResult.Accepted)
    }

    private fun answerDoorCapability() {
        sdk.answerCapability(
            PropertyCapability(
                "READ",
                listOf(
                    ReportedArea("ROW_1_LEFT", 0.0, 1.0),
                    ReportedArea("ROW_1_RIGHT", 0.0, 1.0),
                    ReportedArea("ROW_2_LEFT", 0.0, 1.0),
                    ReportedArea("ROW_2_RIGHT", 0.0, 1.0),
                ),
            )
        )
        scheduler.runAll()
    }

    @Test
    fun oneDoorReadsToAValidatedAnswer() {
        read(request("front_left_door_position"))
        answerDoorCapability()
        sdk.answerRead(1)
        scheduler.runAll()

        val response = finished.single()
        assertEquals(Outcome.COMPLETE, response.outcome)
        val item = response.items.single()
        assertEquals(ItemStatus.OK, item.status)
        assertEquals("open", item.meaning)
        assertEquals(1, item.capability?.matchingAreas)
        assertValid(response, ReadSelection())
    }

    @Test
    fun aSecondRequestIsBusyAndTheFirstContinues() {
        read(request("front_left_door_position"))
        assertEquals(ReadAdmission.Busy, read(request("front_left_door_position")))

        answerDoorCapability()
        sdk.answerRead(0)
        scheduler.runAll()
        assertEquals(Outcome.COMPLETE, finished.single().outcome)
    }

    @Test
    fun theDeadlineKeepsWhatWasReadAndMarksTheRest() {
        read(request("door_status", setOf()), 1000)
        // DOOR_LOCK has no fixed items and is asked first, so answer it and move to the positions.
        sdk.failCapability(Exception("99999"))
        scheduler.runAll()
        answerDoorCapability()
        sdk.answerRead(0)
        scheduler.runAll()

        scheduler.advance(1000)

        val response = finished.single()
        assertEquals(Outcome.TIMEOUT, response.outcome)
        val positions = response.items.filter { it.property == "DOOR_POSITION" }
        assertEquals(1, positions.count { it.status == ItemStatus.OK })
        assertEquals(3, positions.count { it.status == ItemStatus.NOT_READ })
        assertValid(response, ReadSelection())
    }

    @Test
    fun aLateCallbackCannotChangeTheNextRequest() {
        read(request("front_left_door_position"), 1000)
        answerDoorCapability()
        val late = sdk.pendingRead()
        scheduler.advance(1000)
        assertEquals(Outcome.TIMEOUT, finished.single().outcome)

        read(request("front_left_door_position"))
        answerDoorCapability()
        late(1)
        scheduler.runAll()
        assertEquals(1, finished.size)

        sdk.answerRead(0)
        scheduler.runAll()
        assertEquals("closed", finished[1].items.single().meaning)
    }

    @Test
    fun theSameCallbackDeliveredTwiceCountsOnce() {
        read(request("front_left_door_position"))
        answerDoorCapability()
        val once = sdk.pendingRead()
        once(1)
        once(0)
        scheduler.runAll()
        assertEquals("open", finished.single().items.single().meaning)
    }

    @Test
    fun closingEndsTheRequestWithoutFurtherSdkCalls() {
        read(request("door_status"))
        val callsBefore = sdk.calls.size
        val pendingCapability = sdk.pendingCapability()
        var released = false

        reader.close { released = true }
        assertTrue(released)
        assertEquals(Outcome.CANCELLED, finished.single().outcome)

        pendingCapability(PropertyCapability("READ", listOf(ReportedArea("ROW_1_LEFT"))))
        scheduler.runAll()
        assertEquals(callsBefore, sdk.calls.size)
        assertEquals(1, finished.size)
    }

    @Test
    fun nothingIsReadAfterClosing() {
        reader.close {}
        assertEquals(ReadAdmission.Unavailable, read(request("front_left_door_position")))
        assertTrue(sdk.calls.isEmpty())
    }

    @Test
    fun anUnidentifiedDeviceReadsNothing() {
        val unknown = VehicleReader(catalog, sdk, scheduler, null)
        val admission = unknown.read(request("front_left_door_position"), 9000) { finished += it }
        assertEquals(ReadAdmission.Unavailable, admission)
        assertTrue(sdk.calls.isEmpty())
    }

    @Test
    fun aBudgetUsedUpTimesOutWithoutCallingTheSdk() {
        read(request("front_left_door_position"), 0)
        assertEquals(Outcome.TIMEOUT, finished.single().outcome)
        assertTrue(sdk.calls.isEmpty())
        assertValid(finished.single(), ReadSelection())
    }

    @Test
    fun aWriteOnlyCapabilityIsNotRead() {
        read(request("front_left_door_position"))
        sdk.answerCapability(PropertyCapability("WRITE", listOf(ReportedArea("ROW_1_LEFT"))))
        scheduler.runAll()

        assertEquals(ItemStatus.READ_UNAVAILABLE, finished.single().items.single().status)
        assertEquals(1, sdk.calls.size)
        assertValid(finished.single(), ReadSelection())
    }

    @Test
    fun aFailedCapabilityOnAreaPropertiesAnswersEveryCatalogArea() {
        read(request("side_mirror_status"))
        sdk.failCapability(Exception("60001"))
        scheduler.runAll()

        val response = finished.single()
        assertEquals(Outcome.FAILED, response.outcome)
        assertEquals(
            catalog.areas.getValue("MirrorArea"),
            response.items.mapNotNull { it.area }.toSet(),
        )
        assertTrue(response.items.all { it.status == ItemStatus.PROPERTY_UNSUPPORTED })
        assertValid(response, ReadSelection())
    }

    @Test
    fun aFailedCapabilityWithChosenAreasAnswersOnlyThose() {
        val areas = setOf("DRIVER_LEFT")
        read(request("side_mirror_status", areas = areas))
        sdk.failCapability(Exception("99999"))
        scheduler.runAll()

        val response = finished.single()
        assertEquals(areas, response.items.mapNotNull { it.area }.toSet())
        assertEquals(ItemStatus.CAPABILITY_FAILED, response.items.single().status)
        assertValid(response, ReadSelection(areas = areas))
    }

    @Test
    fun areasOutsideTheCatalogAreLeftOutButStayInTheReport() {
        read(request("side_mirror_status"))
        sdk.answerCapability(
            PropertyCapability("READ", listOf(ReportedArea("DRIVER_LEFT"), ReportedArea("NEW")))
        )
        scheduler.runAll()
        sdk.answerRead(1)
        scheduler.runAll()

        val response = finished.single()
        assertEquals(listOf("DRIVER_LEFT"), response.items.map { it.area })
        assertEquals("DRIVER_LEFT, NEW", response.items.single().capability?.reportedAreas)
        assertValid(response, ReadSelection())
    }

    @Test
    fun aGroupedAreaStaysGrouped() {
        read(request("door_status"))
        sdk.answerCapability(PropertyCapability("READ", listOf(ReportedArea("ROW_1_LEFT+HOOD"))))
        scheduler.runAll()
        sdk.answerRead(true)
        scheduler.runAll()

        assertEquals(listOf("door_lock:ROW_1_LEFT+HOOD"), sdk.reads)
    }

    @Test
    fun aValueOutsideTheReportedRangeIsNotConfirmed() {
        read(request("front_left_door_position"))
        sdk.answerCapability(
            PropertyCapability("READ", listOf(ReportedArea("ROW_1_LEFT", 0.0, 1.0)))
        )
        scheduler.runAll()
        sdk.answerRead(7)
        scheduler.runAll()

        val item = finished.single().items.single()
        assertEquals(ItemStatus.UNKNOWN_VALUE, item.status)
        assertNull(item.meaning)
        assertValid(finished.single(), ReadSelection())
    }

    @Test
    fun aReadFailureKeepsTheOtherItems() {
        read(request("door_status"))
        sdk.failCapability(Exception("99999"))
        scheduler.runAll()
        answerDoorCapability()
        sdk.answerRead(0)
        scheduler.runAll()
        sdk.failRead(Exception("area ID: 0x4 not supported for property ID: DOOR_POS"))
        scheduler.runAll()
        sdk.answerRead(1)
        scheduler.runAll()
        sdk.answerRead(0)
        scheduler.runAll()

        val response = finished.single()
        assertEquals(Outcome.PARTIAL, response.outcome)
        assertEquals(3, response.items.count { it.status == ItemStatus.OK })
        assertEquals(1, response.items.count { it.status == ItemStatus.AREA_UNSUPPORTED })
        assertValid(response, ReadSelection())
    }

    @Test
    fun aMissingValueIsNotAReading() {
        read(request("front_left_door_position"))
        answerDoorCapability()
        sdk.answerRead(null)
        scheduler.runAll()

        val item = finished.single().items.single()
        assertEquals(ItemStatus.NO_VALUE, item.status)
        assertNull(item.rawValue)
        assertTrue(item.receivedAt != null)
        assertValid(finished.single(), ReadSelection())
    }

    @Test
    fun anSdkCallThatThrowsFailsThatItemAndFreesTheSlot() {
        sdk.throwNext = Exception("99999")
        read(request("front_left_door_position"))
        assertEquals(ItemStatus.CAPABILITY_FAILED, finished.single().items.single().status)
        assertValid(finished.single(), ReadSelection())

        read(request("front_left_door_position"))
        answerDoorCapability()
        sdk.answerRead(1)
        scheduler.runAll()
        assertEquals(Outcome.COMPLETE, finished[1].outcome)
    }

    @Test
    fun aSelectionWithNothingToReadIsReported() {
        val property = catalog.propertiesOf("turn_signal_status").first()
        val admission = read(request("turn_signal_status", setOf(property), setOf("ROW_1_LEFT")))
        assertEquals(ReadAdmission.NothingSelected, admission)
    }

    @Test
    fun aPropertyWithoutAnSdkCallIsUnavailable() {
        sdk.unsupported += "DOOR_LOCK"
        assertEquals(ReadAdmission.Unavailable, read(request("door_status")))
        assertTrue(sdk.calls.isEmpty())
    }
}
