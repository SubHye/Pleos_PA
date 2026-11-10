package com.example.pleos_pa.navigation

import com.example.pleos_pa.contract.DestinationMatch
import com.example.pleos_pa.contract.Place
import com.example.pleos_pa.vehicle.ReadScheduler

/** How one request to set the destination ended. */
sealed interface SetDestination {
    /** A destination read after the request sits at the place, and the one before did not. */
    data object Applied : SetDestination

    /** The place already was the destination, so `requestRoute` was not called. */
    data object AlreadySet : SetDestination

    /**
     * Not confirmed. [sent] false: `requestRoute` was not called and navigation is as it was.
     * [sent] true: the request was handed to the SDK and its effect is unknown; it is neither a
     * success nor a failure, and nothing was undone.
     */
    data class NotDone(val code: String, val sent: Boolean) : SetDestination
}

/**
 * Sets a listed place as the destination, as one [NavTaskCoordinator] task of one action.
 *
 * The stock navigation app sends no usable completion signal (COM-36), so the task itself reads the
 * destination: once before the request, as the execution condition, and then every [pollMillis]
 * until a callback sits at the place or [confirmMillis] passes. Only a match counts. An empty
 * destination, another place or a missing answer proves nothing: replacing a destination shows an
 * empty one for about five seconds.
 *
 * Every state change runs on [scheduler]'s thread; [set] and [close] may be called from any thread.
 * Nothing is retried, and no change runs later on its own.
 */
class DestinationSetter(
    private val sdk: NaviSdk,
    private val reader: DestinationReader,
    private val scheduler: ReadScheduler,
    private val baselineMillis: Long,
    private val confirmMillis: Long,
    private val pollMillis: Long,
    private val toleranceDegrees: Double,
    /**
     * The driving-restriction check: a fixed block code, or null when the change may run. Nothing
     * is wired to it yet, so a restriction does not stop a change (FR-UI-04 is open).
     */
    private val restriction: () -> String? = { null },
    onState: (requestId: String, state: NavTaskState) -> Unit = { _, _ -> },
) {
    private val coordinator = NavTaskCoordinator(::check, onState)
    @Volatile private var admissionClosed = false
    private var running: Route? = null

    /** [reply] runs exactly once on the scheduler thread. */
    fun set(requestId: String, place: Place, reply: (SetDestination) -> Unit) {
        scheduler.execute {
            if (admissionClosed) {
                reply(SetDestination.NotDone(CONNECTION_STOPPED, sent = false))
                return@execute
            }
            val request = NavTaskRequest(requestId, listOf(Route(place)))
            val refusal =
                when (coordinator.submit(request) { reply(outcomeOf(it)) }) {
                    is NavAdmission.Accepted -> null
                    is NavAdmission.Busy -> BUSY
                    NavAdmission.Unavailable -> CONNECTION_STOPPED
                    NavAdmission.Incomplete -> INVALID_REQUEST
                }
            if (refusal != null) reply(SetDestination.NotDone(refusal, sent = false))
        }
    }

    /**
     * The first step of a stop. Requests that arrive from now on are refused, a task still being
     * checked is blocked, and a task already sent ends unconfirmed: it is not reported as cancelled
     * or undone.
     */
    fun close() {
        admissionClosed = true
        scheduler.execute {
            running?.cancel()
            running = null
            coordinator.close()
        }
    }

    private fun check(request: NavTaskRequest, done: (String?) -> Unit) {
        val place = (request.actions.single() as Route).place
        val blocked = reader.unavailable() ?: restriction()
        if (blocked != null) {
            done(blocked)
            return
        }
        // Without the destination before the request, a match afterwards would prove nothing.
        reader.read(baselineMillis) { read ->
            done(
                when (read) {
                    is DestinationRead.Failed ->
                        if (read.code == CONNECTION_STOPPED) CONNECTION_STOPPED
                        else BASELINE_UNAVAILABLE
                    is DestinationRead.Received -> if (matches(place, read)) ALREADY_SET else null
                }
            )
        }
    }

    private fun matches(place: Place, read: DestinationRead.Received) =
        DestinationMatch.matches(place, read.snapshot, toleranceDegrees)

    /** `requestRoute`, then destination reads until one sits at [place]. */
    private inner class Route(val place: Place) : NavAction {
        override val name = ACTION

        private var over = false
        private var stopWatching: () -> Unit = {}
        private var cancelDeadline: () -> Unit = {}
        private var cancelPoll: () -> Unit = {}

        override fun start(done: (failure: String?) -> Unit) {
            running = this
            fun finish(failure: String?) {
                if (over) return
                cancel()
                if (running === this) running = null
                done(failure)
            }
            stopWatching = reader.watch { snapshot ->
                if (DestinationMatch.matches(place, snapshot, toleranceDegrees)) finish(null)
            }
            cancelDeadline = scheduler.after(confirmMillis) { finish(OUTCOME_UNKNOWN) }
            sdk.requestRoute(
                RouteRequest(
                    latitude = place.latitude,
                    longitude = place.longitude,
                    name = place.displayName,
                    address = place.address,
                    poiId = place.poiId,
                )
            ) { thrown ->
                scheduler.execute {
                    when {
                        over -> Unit
                        thrown == null -> poll()
                        DestinationReader.classify(thrown) == DestinationReader.PERMISSION_DENIED ->
                            finish(PERMISSION_DENIED)
                        // Whether the request reached the navigation app is not known.
                        else -> finish(OUTCOME_UNKNOWN)
                    }
                }
            }
        }

        fun cancel() {
            over = true
            stopWatching()
            cancelDeadline()
            cancelPoll()
        }

        private fun poll() {
            cancelPoll =
                scheduler.after(pollMillis) {
                    if (over) return@after
                    reader.probe()
                    poll()
                }
        }
    }

    private fun outcomeOf(result: NavTaskResult): SetDestination =
        when (result.state) {
            NavTaskState.SUCCEEDED -> SetDestination.Applied
            NavTaskState.BLOCKED ->
                if (result.code == ALREADY_SET) SetDestination.AlreadySet
                else SetDestination.NotDone(result.code ?: CHECK_FAILED, sent = false)
            else ->
                when (result.code) {
                    // Refused by the SDK before anything was sent.
                    PERMISSION_DENIED -> SetDestination.NotDone(PERMISSION_DENIED, sent = false)
                    CONNECTION_STOPPED -> SetDestination.NotDone(CONNECTION_STOPPED, sent = true)
                    else -> SetDestination.NotDone(OUTCOME_UNKNOWN, sent = true)
                }
        }

    companion object {
        const val ACTION = "set_destination"
        const val BUSY = "BUSY"
        const val ALREADY_SET = "ALREADY_SET"
        const val BASELINE_UNAVAILABLE = "BASELINE_UNAVAILABLE"
        const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"
        const val INVALID_REQUEST = "INVALID_REQUEST"
        const val PERMISSION_DENIED = DestinationReader.PERMISSION_DENIED
        const val CONNECTION_STOPPED = NavTaskCoordinator.CONNECTION_STOPPED
        const val CHECK_FAILED = NavTaskCoordinator.CHECK_FAILED
    }
}
