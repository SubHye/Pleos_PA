package com.example.pleos_pa.safety

import com.example.pleos_pa.safety.ExecutionBoundary.Action
import com.example.pleos_pa.safety.ExecutionBoundary.Decision
import com.example.pleos_pa.safety.ExecutionBoundary.DestinationChange
import com.example.pleos_pa.safety.ExecutionBoundary.Result
import com.example.pleos_pa.safety.ExecutionBoundary.Status
import com.example.pleos_pa.safety.ExecutionBoundary.UxState
import com.example.pleos_pa.safety.ExecutionBoundary.VerifiedCapabilities
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ExecutionBoundaryTest {
    private var now = 0L

    private fun change(id: String = "request-1") =
        DestinationChange(
            id,
            "session-a",
            375_000_000,
            1_270_000_000,
            "Test destination",
            "route-1",
        )

    private fun ready(maxAge: Long = 60_000, limit: Int = 128): ExecutionBoundary =
        ExecutionBoundary(
                VerifiedCapabilities(ux = true, navigation = true, stopPaths = true),
                { now },
                maxAge,
                requestLimit = limit,
            )
            .apply {
                resume("session-a")
                observeUx(UxState.PARKED_UNRESTRICTED)
            }

    private fun approve(gate: ExecutionBoundary, request: DestinationChange = change()) {
        assertEquals(Result.AWAITING_APPROVAL, gate.prepare(request))
        assertEquals(Result.APPROVED, gate.decide(request.requestId, Decision.APPROVE))
    }

    @Test
    fun unverifiedPlatformDoesNotEnableMutationEvenWhenCallerReportsParked() {
        val gate = ExecutionBoundary()
        gate.resume("session-a")
        gate.observeUx(UxState.PARKED_UNRESTRICTED)
        assertEquals(Result.BLOCKED, gate.prepare(change()))
        assertFalse(gate.allows(Action.TEXT_INPUT))
        assertFalse(gate.allows(Action.RESULT_SUMMARY))
        assertTrue(gate.allows(Action.STOP))
    }

    @Test
    fun policyMatrixBlocksInputHistoryAndMutationWhenRestrictedOrUnknown() {
        for (state in UxState.entries) {
            val gate = ready().apply { observeUx(state) }
            for (action in
                listOf(Action.TEXT_INPUT, Action.LONG_HISTORY, Action.NAVIGATION_CHANGE)) {
                assertEquals(
                    "$state / $action",
                    state == UxState.PARKED_UNRESTRICTED,
                    gate.allows(action),
                )
            }
            assertTrue(gate.allows(Action.STOP))
            assertFalse(gate.allows(Action.RESULT_SUMMARY))
        }
    }

    @Test
    fun summaryRequiresIndependentEvidenceCoveringAllUxStates() {
        val gate = ExecutionBoundary(VerifiedCapabilities(resultSummaryInAllUxStates = true))
        for (state in UxState.entries) {
            gate.observeUx(state)
            assertTrue(gate.allows(Action.RESULT_SUMMARY))
            assertFalse(gate.allows(Action.NAVIGATION_CHANGE))
        }
    }

    @Test
    fun connectionStartsStopped() {
        val gate =
            ExecutionBoundary(VerifiedCapabilities(ux = true, navigation = true, stopPaths = true))
        gate.observeUx(UxState.PARKED_UNRESTRICTED)
        assertEquals(Result.BLOCKED, gate.prepare(change()))
    }

    @Test
    fun navigationStaysDisabledWithoutVerifiedStopPaths() {
        val gate = ExecutionBoundary(VerifiedCapabilities(ux = true, navigation = true))
        gate.resume("session-a")
        gate.observeUx(UxState.PARKED_UNRESTRICTED)
        assertEquals(Result.BLOCKED, gate.prepare(change()))
    }

    @Test
    fun missingOrPendingApprovalNeverCallsSender() {
        val gate = ready()
        assertEquals(
            Result.BLOCKED,
            gate.dispatch(change(), { "route-1" }) { fail("Unapproved send") },
        )
        gate.prepare(change())
        assertEquals(
            Result.BLOCKED,
            gate.dispatch(change(), { "route-1" }) { fail("Unapproved send") },
        )
        assertEquals(Result.BLOCKED, gate.decide("missing", Decision.APPROVE))
    }

    @Test
    fun rejectionAndCancellationCannotBeChangedBackToApproval() {
        for (decision in listOf(Decision.REJECT, Decision.CANCEL)) {
            val gate = ready()
            gate.prepare(change())
            assertEquals(
                if (decision == Decision.REJECT) Result.REJECTED else Result.CANCELLED,
                gate.decide("request-1", decision),
            )
            assertEquals(Result.BLOCKED, gate.decide("request-1", Decision.APPROVE))
            assertEquals(
                Result.BLOCKED,
                gate.dispatch(change(), { "route-1" }) { fail("Rejected send") },
            )
        }
    }

    @Test
    fun exactApprovalSendsOnceAndAcceptanceIsNotCompletion() {
        val gate = ready()
        approve(gate)
        var calls = 0
        assertEquals(Result.SENT, gate.dispatch(change(), { "route-1" }) { calls++ })
        assertEquals(Status.SENT, gate.status("request-1"))
        assertEquals(Result.BLOCKED, gate.dispatch(change(), { "route-1" }) { calls++ })
        assertEquals(Result.DUPLICATE, gate.prepare(change()))
        assertEquals(1, calls)
        assertTrue(gate.complete("request-1", true))
        assertEquals(Status.SUCCEEDED, gate.status("request-1"))
    }

    @Test
    fun changedArgumentsAndSessionInvalidateApproval() {
        val variants =
            listOf(
                change().copy(latitudeE7 = 374_000_000),
                change().copy(longitudeE7 = 1_271_000_000),
                change().copy(displayName = "Different destination"),
                change().copy(expectedRouteVersion = "route-2"),
                change().copy(clientSessionId = "session-b"),
            )
        for (variant in variants) {
            val gate = ready()
            approve(gate)
            assertEquals(
                Result.ARGUMENT_MISMATCH,
                gate.dispatch(variant, { "route-1" }) { fail("Changed arguments") },
            )
            assertEquals(
                Result.BLOCKED,
                gate.dispatch(change(), { "route-1" }) { fail("Revived approval") },
            )
        }
    }

    @Test
    fun copiedApprovalCannotBeUsedWithDifferentRequestId() {
        val gate = ready()
        approve(gate)
        assertEquals(
            Result.BLOCKED,
            gate.dispatch(change("request-2"), { "route-1" }) { fail("Copied approval") },
        )
    }

    @Test
    fun changedLiveRouteRequiresNewRequestAndConfirmation() {
        val gate = ready()
        approve(gate)
        assertEquals(
            Result.ROUTE_CHANGED,
            gate.dispatch(change(), { "route-2" }) { fail("Stale route") },
        )
        assertEquals(Status.INVALIDATED, gate.status("request-1"))
    }

    @Test
    fun unavailableRouteCannotBeAssumedUnchanged() {
        val gate = ready()
        approve(gate)
        assertEquals(
            Result.ROUTE_UNAVAILABLE,
            gate.dispatch(change(), { error("No SDK route data") }) { fail("Unknown route") },
        )
    }

    @Test
    fun evidenceExpiringDuringRouteReadCannotReachSender() {
        val gate = ready(maxAge = 1_000)
        approve(gate)
        assertEquals(
            Result.INVALIDATED,
            gate.dispatch(
                change(),
                {
                    now = 1_000
                    "route-1"
                },
            ) {
                fail("Expired while reading")
            },
        )
    }

    @Test
    fun userCanWithdrawAlreadyGrantedApprovalBeforeHandoff() {
        val gate = ready()
        approve(gate)
        assertTrue(gate.cancel("request-1"))
        assertEquals(Status.CANCELLED, gate.status("request-1"))
        assertEquals(
            Result.BLOCKED,
            gate.dispatch(change(), { "route-1" }) { fail("Withdrawn approval") },
        )
    }

    @Test
    fun cancellationAfterHandoffCannotPretendToUndoIt() {
        val gate = ready()
        approve(gate)
        gate.dispatch(change(), { "route-1" }) {}
        assertFalse(gate.cancel("request-1"))
        assertEquals(Status.SENT, gate.status("request-1"))
    }

    @Test
    fun approvalExpiresAtItsBoundary() {
        val gate = ready()
        approve(gate)
        now = 30_000
        assertEquals(
            Result.EXPIRED,
            gate.dispatch(change(), { "route-1" }) { fail("Expired approval") },
        )
    }

    @Test
    fun staleUxEvidenceBlocksEvenAnUnexpiredApproval() {
        val gate = ready(maxAge = 1_000)
        approve(gate)
        now = 1_000
        assertEquals(
            Result.INVALIDATED,
            gate.dispatch(change(), { "route-1" }) { fail("Stale UX") },
        )
        assertFalse(gate.allows(Action.TEXT_INPUT))
    }

    @Test
    fun backwardsClockCannotExtendUxEvidence() {
        val gate = ready()
        approve(gate)
        now = -1
        assertEquals(
            Result.INVALIDATED,
            gate.dispatch(change(), { "route-1" }) { fail("Future evidence") },
        )
    }

    @Test
    fun restrictionOrLossRevokesApprovalAndReturningToParkDoesNotRestoreIt() {
        for (state in listOf(UxState.RESTRICTED, UxState.UNKNOWN)) {
            val gate = ready()
            approve(gate)
            gate.observeUx(state)
            gate.observeUx(UxState.PARKED_UNRESTRICTED)
            assertEquals(Status.INVALIDATED, gate.status("request-1"))
            assertEquals(
                Result.BLOCKED,
                gate.dispatch(change(), { "route-1" }) { fail("Restored approval") },
            )
        }
    }

    @Test
    fun refreshingEvidenceAfterGapDoesNotReviveAnApproval() {
        val gate = ready(maxAge = 1_000)
        approve(gate)
        now = 1_000
        gate.observeUx(UxState.PARKED_UNRESTRICTED)
        assertEquals(Status.INVALIDATED, gate.status("request-1"))
    }

    @Test
    fun continuousParkedUpdatesPreserveTheExactUnexpiredApproval() {
        val gate = ready(maxAge = 1_000)
        approve(gate)
        now = 500
        gate.observeUx(UxState.PARKED_UNRESTRICTED)
        assertEquals(Result.SENT, gate.dispatch(change(), { "route-1" }) {})
    }

    @Test
    fun stopCancelsBothPendingAndApprovedRequestsAndResumeDoesNotReplayThem() {
        val gate = ready()
        gate.prepare(change("pending"))
        approve(gate)
        gate.stop()
        assertEquals(Status.CANCELLED, gate.status("pending"))
        assertEquals(Status.CANCELLED, gate.status("request-1"))
        gate.resume("session-a")
        assertEquals(
            Result.BLOCKED,
            gate.dispatch(change(), { "route-1" }) { fail("Stopped request") },
        )
        assertEquals(Result.DUPLICATE, gate.prepare(change()))
    }

    @Test
    fun changingConnectionSessionInvalidatesItsApprovals() {
        val gate = ready()
        approve(gate)
        gate.resume("session-b")
        assertEquals(Result.BLOCKED, gate.prepare(change("request-2")))
        assertEquals(
            Result.BLOCKED,
            gate.dispatch(change(), { "route-1" }) { fail("Wrong session") },
        )
    }

    @Test
    fun stopAfterHandoffIsUnknownAndLateSuccessDoesNotOverwriteIt() {
        val gate = ready()
        approve(gate)
        gate.dispatch(change(), { "route-1" }) {}
        gate.stop()
        assertEquals(Status.UNKNOWN, gate.status("request-1"))
        assertFalse(gate.complete("request-1", true))
        assertEquals(Status.UNKNOWN, gate.status("request-1"))
    }

    @Test
    fun stopDoesNotRewriteAnAlreadyConfirmedOutcome() {
        val gate = ready()
        approve(gate)
        gate.dispatch(change(), { "route-1" }) {}
        gate.complete("request-1", true)
        gate.stop()
        assertEquals(Status.SUCCEEDED, gate.status("request-1"))
    }

    @Test
    fun exceptionAfterPossibleHandoffIsUnknownAndCannotRetry() {
        val gate = ready()
        approve(gate)
        assertEquals(
            Result.UNKNOWN,
            gate.dispatch(change(), { "route-1" }) { error("Transport uncertain") },
        )
        assertEquals(Status.UNKNOWN, gate.status("request-1"))
        assertEquals(
            Result.BLOCKED,
            gate.dispatch(change(), { "route-1" }) { fail("Unsafe retry") },
        )
    }

    @Test
    fun timeoutAndReverseOrderCallbacksStayWithTheirOwnRequest() {
        val gate = ready()
        approve(gate)
        approve(gate, change("request-2"))
        gate.dispatch(change(), { "route-1" }) {}
        gate.dispatch(change("request-2"), { "route-1" }) {}
        assertTrue(gate.complete("request-2", false))
        gate.markResultUnknown("request-1")
        assertFalse(gate.complete("request-1", true))
        assertEquals(Status.FAILED, gate.status("request-2"))
        assertEquals(Status.UNKNOWN, gate.status("request-1"))
    }

    @Test
    fun capacityFailsClosedWithoutEvictingReplayHistory() {
        val gate = ready(limit = 1)
        approve(gate)
        gate.dispatch(change(), { "route-1" }) {}
        assertEquals(Result.CAPACITY, gate.prepare(change("request-2")))
        gate.resume("session-a")
        assertEquals(Result.DUPLICATE, gate.prepare(change()))
    }

    @Test
    fun concurrentDuplicateRequestsCannotSendTwice() {
        val gate = ready()
        approve(gate)
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val count = AtomicInteger()
        try {
            val tasks =
                (1..2).map {
                    pool.submit<Result> {
                        assertTrue(start.await(5, TimeUnit.SECONDS))
                        gate.dispatch(change(), { "route-1" }) { count.incrementAndGet() }
                    }
                }
            start.countDown()
            assertEquals(
                setOf(Result.SENT, Result.BLOCKED),
                tasks.map { it.get(5, TimeUnit.SECONDS) }.toSet(),
            )
            assertEquals(1, count.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun acknowledgedStopPreventsAnyLaterHandoffIncludingAlreadyApprovedWork() {
        val gate = ready()
        approve(gate)
        approve(gate, change("request-2"))
        val pool = Executors.newFixedThreadPool(2)
        val enteredSender = CountDownLatch(1)
        val releaseSender = CountDownLatch(1)
        val count = AtomicInteger()
        try {
            val send =
                pool.submit<Result> {
                    gate.dispatch(change(), { "route-1" }) {
                        enteredSender.countDown()
                        assertTrue(releaseSender.await(5, TimeUnit.SECONDS))
                        count.incrementAndGet()
                    }
                }
            assertTrue(enteredSender.await(5, TimeUnit.SECONDS))
            val stopped = pool.submit { gate.stop() }
            releaseSender.countDown()
            send.get(5, TimeUnit.SECONDS)
            stopped.get(5, TimeUnit.SECONDS)
            assertEquals(
                Result.BLOCKED,
                gate.dispatch(change("request-2"), { "route-1" }) { count.incrementAndGet() },
            )
            assertEquals(1, count.get())
            assertEquals(Status.UNKNOWN, gate.status("request-1"))
            assertEquals(Status.CANCELLED, gate.status("request-2"))
        } finally {
            releaseSender.countDown()
            pool.shutdownNow()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidCoordinatesAreRejectedBeforeApproval() {
        change().copy(latitudeE7 = 900_000_001)
    }
}
