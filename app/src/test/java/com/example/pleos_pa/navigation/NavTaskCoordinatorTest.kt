package com.example.pleos_pa.navigation

import com.example.pleos_pa.navigation.NavTaskState.BLOCKED
import com.example.pleos_pa.navigation.NavTaskState.CHECKING
import com.example.pleos_pa.navigation.NavTaskState.EXECUTING
import com.example.pleos_pa.navigation.NavTaskState.FAILED
import com.example.pleos_pa.navigation.NavTaskState.RECEIVED
import com.example.pleos_pa.navigation.NavTaskState.SUCCEEDED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A state machine check with stand-in actions. It does not touch the navigation SDK. */
class NavTaskCoordinatorTest {
    private val log = mutableListOf<String>()
    private val states = mutableListOf<Pair<String, NavTaskState>>()
    private val results = mutableListOf<NavTaskResult>()

    /** The conditions answer only when the test says so, unless [answer] is set. */
    private var answer: (() -> String?)? = { null }
    private var pendingCheck: ((String?) -> Unit)? = null
    private var checkThrows = false

    private val coordinator =
        NavTaskCoordinator(
            conditions = { request, done ->
                log += "check:${request.requestId}"
                if (checkThrows) throw IllegalStateException("check")
                val now = answer
                if (now != null) done(now()) else pendingCheck = done
            },
            onState = { id, state -> states += id to state },
        )

    /** Answers only when the test calls [succeed] or [fail], unless it was built to answer. */
    private inner class FakeAction(
        override val name: String,
        private val outcome: (() -> String?)? = null,
        private val throws: Boolean = false,
    ) : NavAction {
        var done: ((String?) -> Unit)? = null

        override fun start(done: (failure: String?) -> Unit) {
            log += "start:$name"
            if (throws) throw IllegalStateException(name)
            if (outcome != null) done(outcome.invoke()) else this.done = done
        }

        fun succeed() = done!!(null)

        fun fail(code: String) = done!!(code)
    }

    private fun ok(name: String) = FakeAction(name, outcome = { null })

    private fun submit(id: String, vararg actions: NavAction) =
        coordinator.submit(NavTaskRequest(id, actions.toList())) { results += it }

    private fun statesOf(id: String) = states.filter { it.first == id }.map { it.second }

    @Test
    fun allActionsSucceedingIsTheOnlySuccess() {
        val admission = submit("r1", ok("destination"), ok("waypoint"), ok("startGuidance"))

        assertEquals(NavAdmission.Accepted("r1"), admission)
        assertEquals(
            listOf("check:r1", "start:destination", "start:waypoint", "start:startGuidance"),
            log,
        )
        assertEquals(listOf(RECEIVED, CHECKING, EXECUTING, SUCCEEDED), statesOf("r1"))
        assertEquals(
            NavTaskResult(
                requestId = "r1",
                state = SUCCEEDED,
                code = null,
                succeeded = listOf("destination", "waypoint", "startGuidance"),
                failedAction = null,
                unconfirmed = null,
                notRun = emptyList(),
            ),
            results.single(),
        )
    }

    @Test
    fun acceptingIsNotCompleting() {
        val destination = FakeAction("destination")

        assertEquals(NavAdmission.Accepted("r1"), submit("r1", destination))

        assertTrue(results.isEmpty())
        assertEquals(listOf(RECEIVED, CHECKING, EXECUTING), statesOf("r1"))
        destination.succeed()
        assertEquals(SUCCEEDED, results.single().state)
    }

    @Test
    fun theFirstFailureEndsTheTaskWhereverItHappens() {
        val names = listOf("destination", "waypoint", "startGuidance")
        for (failing in names.indices) {
            log.clear()
            results.clear()
            val actions = names.mapIndexed { index, name ->
                FakeAction(name, outcome = { if (index == failing) "NAV_REJECTED" else null })
            }

            submit("r$failing", *actions.toTypedArray())

            assertEquals(
                listOf("check:r$failing") + names.take(failing + 1).map { "start:$it" },
                log,
            )
            val result = results.single()
            assertEquals(FAILED, result.state)
            assertEquals("NAV_REJECTED", result.code)
            assertEquals(names[failing], result.failedAction)
            assertEquals(names.take(failing), result.succeeded)
            assertEquals(names.drop(failing + 1), result.notRun)
            assertNull(result.unconfirmed)
            assertEquals(
                listOf(RECEIVED, CHECKING, EXECUTING, FAILED),
                statesOf("r$failing"),
            )
        }
    }

    @Test
    fun anActionThatThrowsFailsTheTask() {
        submit("r1", ok("destination"), FakeAction("waypoint", throws = true), ok("startGuidance"))

        val result = results.single()
        assertEquals(FAILED, result.state)
        assertEquals(NavTaskCoordinator.ACTION_FAILED, result.code)
        assertEquals("waypoint", result.failedAction)
        assertEquals(listOf("startGuidance"), result.notRun)
        assertEquals(listOf("check:r1", "start:destination", "start:waypoint"), log)
    }

    @Test
    fun anUnmetConditionBlocksBeforeAnyChange() {
        answer = { "DRIVING_RESTRICTED" }

        assertEquals(NavAdmission.Accepted("r1"), submit("r1", ok("destination")))

        assertEquals(listOf("check:r1"), log)
        assertEquals(listOf(RECEIVED, CHECKING, BLOCKED), statesOf("r1"))
        val result = results.single()
        assertEquals(BLOCKED, result.state)
        assertEquals("DRIVING_RESTRICTED", result.code)
        assertEquals(listOf("destination"), result.notRun)
        assertTrue(result.succeeded.isEmpty())
    }

    @Test
    fun aRecoveredConditionDoesNotRunTheBlockedTask() {
        answer = null
        val destination = FakeAction("destination")
        submit("r1", destination)
        val blocked = pendingCheck!!
        blocked("DRIVING_RESTRICTED")

        blocked(null)

        assertEquals(listOf("check:r1"), log)
        assertEquals(BLOCKED, results.single().state)

        answer = { null }
        assertEquals(NavAdmission.Accepted("r2"), submit("r2", ok("destination")))
        assertEquals(listOf("check:r1", "check:r2", "start:destination"), log)
        assertEquals(SUCCEEDED, results.last().state)
        assertEquals("r2", results.last().requestId)
    }

    @Test
    fun aCheckThatThrowsBlocks() {
        checkThrows = true

        submit("r1", ok("destination"))

        assertEquals(BLOCKED, results.single().state)
        assertEquals(NavTaskCoordinator.CHECK_FAILED, results.single().code)
        assertEquals(listOf("check:r1"), log)
    }

    @Test
    fun aNewRequestWhileExecutingIsBusyAndNotQueued() {
        val destination = FakeAction("destination")
        submit("r1", destination, ok("startGuidance"))

        assertEquals(NavAdmission.Busy("r1"), submit("r2", ok("other")))

        destination.succeed()
        assertEquals(listOf("check:r1", "start:destination", "start:startGuidance"), log)
        assertEquals(listOf("r1"), results.map { it.requestId })
        assertEquals(SUCCEEDED, results.single().state)
        assertTrue(statesOf("r2").isEmpty())

        assertEquals(NavAdmission.Accepted("r3"), submit("r3", ok("other")))
        assertEquals(SUCCEEDED, results.last().state)
    }

    @Test
    fun aNewRequestWhileCheckingIsBusy() {
        answer = null
        submit("r1", ok("destination"))

        assertEquals(NavAdmission.Busy("r1"), submit("r2", ok("other")))

        pendingCheck!!(null)
        assertEquals(listOf("check:r1", "start:destination"), log)
        assertEquals(listOf("r1"), results.map { it.requestId })
    }

    @Test
    fun stoppingWhileCheckingBlocksAndRunsNothing() {
        answer = null
        submit("r1", ok("destination"))

        coordinator.close()
        pendingCheck!!(null)

        assertEquals(listOf("check:r1"), log)
        val result = results.single()
        assertEquals(BLOCKED, result.state)
        assertEquals(NavTaskCoordinator.CONNECTION_STOPPED, result.code)
        assertEquals(listOf("destination"), result.notRun)
    }

    @Test
    fun stoppingWhileExecutingRunsNothingMoreAndClaimsNoCancel() {
        val destination = ok("destination")
        val waypoint = FakeAction("waypoint")
        submit("r1", destination, waypoint, ok("startGuidance"))

        coordinator.close()
        waypoint.succeed()

        assertEquals(listOf("check:r1", "start:destination", "start:waypoint"), log)
        assertEquals(
            NavTaskResult(
                requestId = "r1",
                state = FAILED,
                code = NavTaskCoordinator.CONNECTION_STOPPED,
                succeeded = listOf("destination"),
                failedAction = null,
                unconfirmed = "waypoint",
                notRun = listOf("startGuidance"),
            ),
            results.single(),
        )
        assertEquals(listOf(RECEIVED, CHECKING, EXECUTING, FAILED), statesOf("r1"))
    }

    @Test
    fun anObserverStoppingTheConnectionOnAnyStateStartsNothingAfterward() {
        val cases =
            mapOf(
                RECEIVED to Pair(BLOCKED, emptyList<String>()),
                CHECKING to Pair(BLOCKED, emptyList()),
                EXECUTING to Pair(FAILED, listOf("check:r1")),
            )
        for ((stopAt, expected) in cases) {
            val coordinatorLog = mutableListOf<String>()
            val seen = mutableListOf<NavTaskState>()
            val ended = mutableListOf<NavTaskResult>()
            lateinit var stopping: NavTaskCoordinator
            stopping =
                NavTaskCoordinator(
                    conditions = { request, done ->
                        coordinatorLog += "check:${request.requestId}"
                        done(null)
                    },
                    onState = { _, state ->
                        seen += state
                        if (state == stopAt) stopping.close()
                    },
                )
            val action =
                object : NavAction {
                    override val name = "destination"

                    override fun start(done: (failure: String?) -> Unit) {
                        coordinatorLog += "start:$name"
                        done(null)
                    }
                }

            stopping.submit(NavTaskRequest("r1", listOf(action))) { ended += it }

            val (finalState, calls) = expected
            assertEquals("stop on $stopAt", calls, coordinatorLog)
            assertEquals(
                "stop on $stopAt",
                NavTaskResult(
                    requestId = "r1",
                    state = finalState,
                    code = NavTaskCoordinator.CONNECTION_STOPPED,
                    succeeded = emptyList(),
                    failedAction = null,
                    unconfirmed = null,
                    notRun = listOf("destination"),
                ),
                ended.single(),
            )
            assertEquals("stop on $stopAt", finalState, seen.last())
            assertEquals("stop on $stopAt", seen.distinct(), seen)
        }
    }

    @Test
    fun anObserverThatThrowsChangesNeitherTheTaskNorTheNextOne() {
        for (throwAt in NavTaskState.entries) {
            val calls = mutableListOf<String>()
            val seen = mutableListOf<NavTaskState>()
            val ended = mutableListOf<NavTaskResult>()
            val failing = throwAt == FAILED
            val throwing =
                NavTaskCoordinator(
                    conditions = { request, done ->
                        calls += "check:${request.requestId}"
                        done(if (throwAt == BLOCKED) "DRIVING_RESTRICTED" else null)
                    },
                    onState = { id, state ->
                        seen += state
                        if (id == "r1" && state == throwAt) throw IllegalStateException("ui")
                    },
                )
            val destination =
                object : NavAction {
                    override val name = "destination"

                    override fun start(done: (failure: String?) -> Unit) {
                        calls += "start:$name"
                        done(if (failing) "NAV_REJECTED" else null)
                    }
                }

            val admission =
                throwing.submit(NavTaskRequest("r1", listOf(destination))) { ended += it }

            val expected =
                when (throwAt) {
                    BLOCKED -> Triple(BLOCKED, "DRIVING_RESTRICTED", listOf("check:r1"))
                    FAILED ->
                        Triple(FAILED, "NAV_REJECTED", listOf("check:r1", "start:destination"))
                    else -> Triple(SUCCEEDED, null, listOf("check:r1", "start:destination"))
                }
            val (finalState, code, expectedCalls) = expected
            assertEquals("throw on $throwAt", NavAdmission.Accepted("r1"), admission)
            assertEquals("throw on $throwAt", expectedCalls, calls)
            assertEquals("throw on $throwAt", finalState, ended.single().state)
            assertEquals("throw on $throwAt", code, ended.single().code)
            assertEquals("throw on $throwAt", finalState, seen.last())
            assertEquals(
                "throw on $throwAt",
                NavAdmission.Accepted("r2"),
                throwing.submit(NavTaskRequest("r2", listOf(destination))) { ended += it },
            )
            assertEquals("throw on $throwAt", 2, ended.size)
        }
    }

    @Test
    fun nothingIsAdmittedAfterTheConnectionStops() {
        coordinator.close()

        assertEquals(NavAdmission.Unavailable, submit("r1", ok("destination")))
        assertTrue(log.isEmpty())
        assertTrue(states.isEmpty())
    }

    @Test
    fun aRequestWithoutIdOrActionsIsNotAdmitted() {
        assertEquals(NavAdmission.Incomplete, submit("r1"))
        assertEquals(NavAdmission.Incomplete, submit(" ", ok("destination")))

        assertTrue(log.isEmpty())
        assertTrue(states.isEmpty())
        assertEquals(NavAdmission.Accepted("r2"), submit("r2", ok("destination")))
    }

    @Test
    fun aSecondAnswerFromTheSameActionIsIgnored() {
        val destination = FakeAction("destination")
        submit("r1", destination, FakeAction("startGuidance"))

        destination.succeed()
        destination.fail("LATE")

        assertEquals(listOf("check:r1", "start:destination", "start:startGuidance"), log)
        assertTrue(results.isEmpty())
    }

    @Test
    fun theActionsAreFixedWhenTheRequestIsMade() {
        val actions = mutableListOf<NavAction>(ok("destination"))
        val request = NavTaskRequest("r1", actions)
        actions += ok("unrequested")

        coordinator.submit(request) { results += it }

        assertEquals(listOf("destination"), results.single().succeeded)
        assertEquals(listOf("check:r1", "start:destination"), log)
    }
}
