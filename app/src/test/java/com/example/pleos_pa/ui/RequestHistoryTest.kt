package com.example.pleos_pa.ui

import org.junit.Assert.*
import org.junit.Test

class RequestHistoryTest {
    private val history = RequestHistory(3)

    private fun begin(id: String) =
        history.begin(id, "get_door_status", "전체", "2026-11-04T00:00:00Z")

    @Test
    fun reverseAndLateResultsStayWithTheirOwnAttempts() {
        val first = begin("first")
        val second = begin("second")
        history.finish(second, "COMPLETE", "simulation", "second value")
        history.finish(first, "TIMEOUT", "simulation", "partial first")
        assertFalse(history.finish(first, "COMPLETE", "simulation", "late overwrite"))
        assertEquals(listOf("second", "first"), history.snapshot().map { it.requestId })
        assertEquals(listOf("second value", "partial first"), history.snapshot().map { it.details })
    }

    @Test
    fun duplicateIdsNeverShareTheCompletionToken() {
        val first = begin("same")
        val second = begin("same")
        history.finish(second, "BUSY", null, "")
        history.finish(first, "COMPLETE", "simulation", "first only")
        assertEquals(listOf("BUSY", "COMPLETE"), history.snapshot().map { it.state })
    }

    @Test
    fun clearedAndEvictedResponsesCannotRestorePrivateValues() {
        val cleared = begin("cleared")
        history.clear()
        val evicted = begin("evicted")
        repeat(3) { begin("new-$it") }
        assertFalse(history.finish(cleared, "COMPLETE", "simulation", "old private value"))
        assertFalse(history.finish(evicted, "COMPLETE", "simulation", "old private value"))
        assertEquals(listOf("new-2", "new-1", "new-0"), history.snapshot().map { it.requestId })
    }

    @Test
    fun snapshotsAreStableAndNewProcessStateStartsEmpty() {
        val token = begin("old")
        val before = history.snapshot()
        history.finish(token, "COMPLETE", "simulation", "value")
        assertEquals("RECEIVED", before.single().state)
        assertTrue(RequestHistory().snapshot().isEmpty())
    }

    @Test
    fun replacedActivityObserversCannotRemoveNewObservers() {
        var first = 0
        var second = 0
        val a = {
            first++
            Unit
        }
        val b = {
            second++
            Unit
        }
        history.observe(a)
        history.observe(b)
        history.remove(a)
        begin("new")
        assertEquals(1, first)
        assertEquals(2, second)
    }

    @Test
    fun simultaneousAttemptsWithTheSameIdKeepTheirOwnValues() {
        val concurrent = RequestHistory()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val jobs =
                (0 until 64).map { index ->
                    executor.submit {
                        val token = concurrent.begin("same-id", "tool", "$index", "time")
                        concurrent.finish(token, "COMPLETE", "simulation", "value-$index")
                    }
                }
            jobs.forEach { it.get(3, java.util.concurrent.TimeUnit.SECONDS) }
            val rows = concurrent.snapshot()
            assertEquals(64, rows.size)
            assertEquals(64, rows.map { it.token.sequence }.toSet().size)
            assertTrue(rows.all { it.details == "value-${it.arguments}" })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun aFloodRetainsBoundedPresentationPayload() {
        val bounded = RequestHistory()
        repeat(2_000) {
            val token = bounded.begin("$it", "tool", "x".repeat(8_000), "time")
            bounded.finish(token, "COMPLETE", "simulation", "z".repeat(32_000))
        }
        val rows = bounded.snapshot()
        assertEquals(100, rows.size)
        assertEquals("1999", rows.first().requestId)
        assertTrue(
            rows.all {
                it.arguments.length <= 4096 && it.details.length <= RequestHistory.MAX_DETAILS
            }
        )
        assertTrue(rows.sumOf { it.details.length + it.arguments.length } <= 100 * (16_384 + 4096))
        assertTrue(rows.all { it.details.endsWith("[일부 내용 생략]") })
    }
}
