package com.example.pleos_pa.ui

import com.example.pleos_pa.navigation.*
import org.junit.Assert.*
import org.junit.Test

class NavTaskHistoryTest {
    private class Action : NavAction {
        override val name = "private destination"
        lateinit var reply: (String?) -> Unit
        var calls = 0

        override fun start(done: (String?) -> Unit) {
            calls++
            reply = done
        }
    }

    @Test
    fun busyAndLateCallbacksNeverOverwriteAnotherAttempt() {
        val history = RequestHistory()
        val ui = NavTaskHistory(NavConditions { _, done -> done(null) }, history)
        val first = Action()
        ui.submit(NavTaskRequest("same", listOf(first))) {}
        assertEquals("EXECUTING", history.snapshot().single().state)
        assertTrue(ui.submit(NavTaskRequest("same", listOf(Action()))) {} is NavAdmission.Busy)
        first.reply(null)
        first.reply("private failure")
        assertEquals(listOf("BUSY", "SUCCEEDED"), history.snapshot().map { it.state })
        assertFalse(history.snapshot().toString().contains("private"))
    }

    @Test
    fun stopInFlightShowsUnknownAndNeverRunsTheNextAction() {
        val h = RequestHistory()
        val first = Action()
        val next = Action()
        val ui = NavTaskHistory(NavConditions { _, done -> done(null) }, h)
        ui.submit(NavTaskRequest("request", listOf(first, next))) {}
        ui.close()
        first.reply(null)
        assertEquals("RESULT_UNKNOWN", h.snapshot().single().state)
        assertEquals(0, next.calls)
    }

    @Test
    fun clearDuringCheckingDoesNotResurrectOnCompletion() {
        val h = RequestHistory()
        lateinit var check: (String?) -> Unit
        val ui = NavTaskHistory(NavConditions { _, done -> check = done }, h)
        ui.submit(NavTaskRequest("request", listOf(Action()))) {}
        assertEquals("CHECKING", h.snapshot().single().state)
        h.clear()
        check("private block")
        assertTrue(h.snapshot().isEmpty())
    }

    @Test
    fun blockAndFailureRemainSeparateFromSuccessAndDoNotRetry() {
        val h = RequestHistory()
        val a = Action()
        var allowed = false
        val ui =
            NavTaskHistory(NavConditions { _, done -> done(if (allowed) null else "BLOCK") }, h)
        ui.submit(NavTaskRequest("blocked", listOf(a))) {}
        allowed = true
        assertEquals(0, a.calls)
        assertEquals("BLOCKED", h.snapshot().single().state)
        ui.submit(NavTaskRequest("new", listOf(a))) {}
        a.reply("private failure")
        assertEquals("FAILED", h.snapshot().first().state)
    }
}
