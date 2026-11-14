package com.example.pleos_pa.ui

import com.example.pleos_pa.contract.*
import org.junit.Assert.*
import org.junit.Test

class HistoryPresentationTest {
    private val history = RequestHistory()
    private val presentation = HistoryPresentation(history, { "get_$it" }, { 0L })
    private val request =
        VehicleReadRequest("11111111-1111-4111-8111-111111111111", 9000, "energy_level")

    @Test
    fun partialValuesAreNotReplacedByFalseOrZeroAndIdentityIsNotInferred() {
        val token = presentation.begin(request)
        val response =
            VehicleReadResponse(
                request.requestId,
                request.query,
                Outcome.PARTIAL,
                "simulation",
                "2.3.2",
                listOf(
                    VehicleReadItem(
                        "EV_BATTERY_PERCENT",
                        null,
                        ItemStatus.OK,
                        rawValue = 42,
                        unit = "%",
                        receivedAt = "2026-11-04T00:00:00Z",
                    ),
                    VehicleReadItem(
                        "FUEL_PERCENT",
                        null,
                        ItemStatus.PROPERTY_UNSUPPORTED,
                        errorCode = "ERROR_NOT_SUPPORTED_PROPERTY",
                    ),
                ),
            )
        presentation.finish(token, "PARTIAL", response)
        val row = history.snapshot().single()
        assertEquals("simulation", row.source)
        assertEquals("1970-01-01T00:00:00Z", row.receivedAt)
        assertTrue(row.details.contains("OK · 42 %"))
        assertTrue(row.details.contains("PROPERTY_UNSUPPORTED"))
        assertFalse(row.details.contains("FUEL_PERCENT: OK"))
        assertFalse(row.details.contains("ChatGPT"))
        assertEquals("get_energy_level", row.tool)
    }

    @Test
    fun unknownRawValuesAreNotPresentedAsConfirmedFacts() {
        val token = presentation.begin(request)
        presentation.finish(
            token,
            "FAILED",
            VehicleReadResponse(
                request.requestId,
                request.query,
                Outcome.FAILED,
                "simulation",
                "2.3.2",
                listOf(
                    VehicleReadItem(
                        "FUEL_PERCENT",
                        null,
                        ItemStatus.UNKNOWN_VALUE,
                        rawValue = "untrusted secret",
                    )
                ),
            ),
        )
        assertFalse(history.snapshot().single().details.contains("untrusted secret"))
        assertTrue(history.snapshot().single().details.contains("UNKNOWN_VALUE"))
    }

    @Test
    fun admissionFailureStaysASeparateRequestWithoutInventingValues() {
        val first = presentation.begin(request)
        val second =
            presentation.begin(request.copy(requestId = "22222222-2222-4222-8222-222222222222"))
        presentation.finish(second, "BUSY", null)
        presentation.finish(first, "CANCELLED", null)
        assertEquals(listOf("BUSY", "CANCELLED"), history.snapshot().map { it.state })
        assertTrue(history.snapshot().all { it.source == null })
    }
}
