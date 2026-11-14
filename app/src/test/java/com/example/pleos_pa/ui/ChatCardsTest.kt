package com.example.pleos_pa.ui

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What one request shows on the screen. Pure presentation; no SDK or Android widget runs here. */
class ChatCardsTest {
    private val history = RequestHistory()
    private val cards =
        ChatCards(
            titles =
                mapOf(
                    "get_energy_level" to "에너지 잔량",
                    "get_door_status" to "문 상태",
                    "set_navigation_destination" to "내비 목적지 설정",
                ),
            navigation = setOf("set_navigation_destination"),
            zone = ZoneOffset.ofHours(9),
        )

    private fun record(
        tool: String,
        state: String? = null,
        source: String? = null,
        headline: String? = null,
        items: Int? = null,
    ): RequestHistory.Record {
        val token =
            history.begin("request-id-1", tool, "EV_BATTERY_PERCENT", "2026-11-14T05:02:09Z")
        if (state != null) history.finish(token, state, source, "raw detail 72 %", headline, items)
        return history.snapshot().first()
    }

    @Test
    fun aRequestStillWorkingReadsAsWorkingUnderItsCatalogTitle() {
        val read = cards.of(record("get_door_status"), showValues = true)
        assertEquals("문 상태", read.title)
        assertEquals(ChatCard.Phase.WORKING, read.phase)
        assertEquals("문 상태 읽는 중…", read.line)
        assertEquals("14:02", read.time)
        assertEquals(ChatCard.Kind.VEHICLE, read.kind)
        val change = cards.of(record("set_navigation_destination"), showValues = true)
        assertEquals(ChatCard.Kind.NAVIGATION, change.kind)
        assertEquals("내비 목적지 설정 처리 중…", change.line)
    }

    @Test
    fun aValueShowsOnlyWhenTheScreenPermitsIt() {
        val row =
            record(
                "get_energy_level",
                "COMPLETE",
                "pleos_emulator",
                headline = "배터리 72%",
                items = 2,
            )
        val open = cards.of(row, showValues = true)
        assertEquals(ChatCard.Kind.ENERGY, open.kind)
        assertEquals(ChatCard.Phase.DONE, open.phase)
        assertEquals("배터리 72%", open.line)
        val hidden = cards.of(row, showValues = false)
        assertEquals("에너지 잔량 · 2개 항목 확인", hidden.line)
        assertFalse(hidden.line.contains("72"))
    }

    @Test
    fun aSimulatedValueIsLabelledAndNeverPassesAsAVehicleValue() {
        val row =
            record("get_energy_level", "PARTIAL", "simulation", headline = "배터리 42%", items = 1)
        assertEquals("배터리 42% · 일부만 확인 · 모의 값", cards.of(row, showValues = true).line)
        assertEquals("에너지 잔량 · 일부만 확인", cards.of(row, showValues = false).line)
    }

    @Test
    fun theCardNeverCarriesTheRequestIdArgumentsOrRawDetails() {
        for (state in listOf(null, "COMPLETE", "FAILED", "BUSY")) {
            val card = cards.of(record("get_door_status", state, items = 3), showValues = true)
            val text = card.title + card.line + card.time
            assertFalse(text.contains("request-id-1"))
            assertFalse(text.contains("EV_BATTERY_PERCENT"))
            assertFalse(text.contains("raw detail"))
        }
    }

    @Test
    fun endStatesSayWhatHappenedWithoutClaimingSuccess() {
        fun line(state: String) = cards.of(record("set_navigation_destination", state), true)
        assertEquals("내비 목적지 설정 · 완료", line("COMPLETE").line)
        assertEquals(ChatCard.Phase.DONE, line("COMPLETE").phase)
        for (state in
            listOf(
                "FAILED",
                "BUSY",
                "BLOCKED",
                "REJECTED",
                "RESULT_UNKNOWN",
                "CONNECTION_STOPPED",
                "TIMEOUT",
                "SOMETHING_NEW",
            )) {
            val card = line(state)
            assertEquals(state, ChatCard.Phase.FAILED, card.phase)
            assertFalse(state, card.line.contains("완료"))
        }
        assertTrue(line("RESULT_UNKNOWN").line.contains("결과 불명"))
        assertTrue(line("BLOCKED").line.contains("실행 안 함"))
    }

    @Test
    fun aReadThatConfirmedNothingDoesNotReadAsDone() {
        val card = cards.of(record("get_door_status", "COMPLETE", items = 0), showValues = true)
        assertEquals("문 상태 · 확인된 항목 없음", card.line)
    }

    @Test
    fun anUnknownToolFallsBackToItsNameAndAnUnreadableTimeIsLeftOut() {
        val token = history.begin("id", "navigation_task", "", "synthetic_time")
        history.finish(token, "COMPLETE", null, "")
        val card = cards.of(history.snapshot().first(), showValues = false)
        assertEquals("navigation_task", card.title)
        assertEquals("", card.time)
    }
}
