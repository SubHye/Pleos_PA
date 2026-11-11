package com.example.pleos_pa.navigation

import com.example.pleos_pa.contract.DestinationFields
import com.example.pleos_pa.contract.DestinationMatch
import com.example.pleos_pa.contract.DestinationStatus
import com.example.pleos_pa.contract.NavigationDestination
import com.example.pleos_pa.contract.Place
import com.example.pleos_pa.contract.WaypointFields
import com.example.pleos_pa.contract.WaypointMatch
import com.example.pleos_pa.vehicle.ReadScheduler

/** How one request to add or remove a waypoint ended. */
sealed interface WaypointChange {
    /** The waypoints read after the request show the change, and the destination is as before. */
    data object Applied : WaypointChange

    /**
     * Not confirmed. [sent] false: no waypoint call was made and navigation is as it was. [sent]
     * true: the request was handed to the SDK and its effect is unknown; it is neither a success
     * nor a failure, and nothing was undone.
     */
    data class NotDone(val code: String, val sent: Boolean) : WaypointChange
}

/**
 * Adds a listed place as a waypoint, or removes one, as one [NavTaskCoordinator] task of one action
 * (COM-41). The coordinator is shared with the destination change, so only one change runs at a
 * time across both.
 *
 * The stock navigation app sends no usable completion signal. `onWaypointChanged` comes about 6 to
 * 7 seconds late and always names FIRST, and `requestRouteWithWaypoints` sends none (COM-47). So
 * the task reads the waypoints itself, once before the request as the execution condition, and then
 * every [pollMillis] until a callback shows the change or [confirmMillis] passes. Only a match
 * counts, and it counts only when the destination and the other waypoints are as they were: a
 * missing answer proves nothing, and it ends as an unknown outcome, never as a failure.
 *
 * An add is refused before any call when there is no destination (the stock app ignores it without
 * a word), when the place already is a waypoint (the SDK allows a duplicate), and when
 * [maxWaypoints] are set (the next one is ignored without a word). A remove names the place, finds
 * its position in the waypoints read just before, and sends that position. Between that read and
 * the call the waypoints could change; the confirmation then does not match and the outcome is
 * unknown.
 *
 * Every state change runs on [scheduler]'s thread; [add], [remove] and [close] may be called from
 * any thread. Nothing is retried, and no change runs later on its own.
 */
class WaypointChanger(
    private val sdk: NaviSdk,
    private val destinations: DestinationReader,
    private val waypoints: WaypointReader,
    private val scheduler: ReadScheduler,
    private val coordinator: NavTaskCoordinator,
    private val baselineMillis: Long,
    private val confirmMillis: Long,
    private val pollMillis: Long,
    private val toleranceDegrees: Double,
    private val maxWaypoints: Int,
    /**
     * The driving-restriction check: a fixed block code, or null when the change may run. Nothing
     * is wired to it yet, so a restriction does not stop a change (FR-UI-04 is open).
     */
    private val restriction: () -> String? = { null },
) {
    @Volatile private var admissionClosed = false
    private var running: Change? = null

    /** [reply] runs exactly once on the scheduler thread. */
    fun add(requestId: String, place: Place, reply: (WaypointChange) -> Unit) =
        submit(requestId, Change(Kind.ADD, place), reply)

    /** [reply] runs exactly once on the scheduler thread. */
    fun remove(requestId: String, place: Place, reply: (WaypointChange) -> Unit) =
        submit(requestId, Change(Kind.REMOVE, place), reply)

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

    private fun submit(requestId: String, change: Change, reply: (WaypointChange) -> Unit) {
        scheduler.execute {
            if (admissionClosed) {
                reply(WaypointChange.NotDone(CONNECTION_STOPPED, sent = false))
                return@execute
            }
            val request = NavTaskRequest(requestId, listOf(change), change)
            val refusal =
                when (coordinator.submit(request) { reply(outcomeOf(it)) }) {
                    is NavAdmission.Accepted -> null
                    is NavAdmission.Busy -> BUSY
                    NavAdmission.Unavailable -> CONNECTION_STOPPED
                    NavAdmission.Incomplete -> INVALID_REQUEST
                }
            if (refusal != null) reply(WaypointChange.NotDone(refusal, sent = false))
        }
    }

    private enum class Kind(val action: String) {
        ADD(ADD_ACTION),
        REMOVE(REMOVE_ACTION),
    }

    /** One change: its execution conditions first, then the one action. */
    private inner class Change(private val kind: Kind, private val place: Place) :
        NavAction, NavConditions {
        override val name = kind.action

        private var destination: DestinationFields? = null
        private var before: List<WaypointFields> = emptyList()
        private var slot = WaypointSlot.FIRST
        private var removedAt = -1

        private var over = false
        private var latestWaypoints: List<WaypointFields>? = null
        private var latestDestination: DestinationFields? = null
        private var stopWatchingWaypoints: () -> Unit = {}
        private var stopWatchingDestination: () -> Unit = {}
        private var cancelDeadline: () -> Unit = {}
        private var cancelPoll: () -> Unit = {}

        override fun check(request: NavTaskRequest, done: (block: String?) -> Unit) {
            val blocked = destinations.unavailable() ?: restriction()
            if (blocked != null) {
                done(blocked)
                return
            }
            // Without the destination before the request, "it is as before" proves nothing. The
            // stock app ignores a waypoint without one.
            destinations.read(baselineMillis) { read ->
                // close() shuts admission synchronously; this reply may precede
                // coordinator.close().
                if (admissionClosed) {
                    done(CONNECTION_STOPPED)
                    return@read
                }
                when (read) {
                    is DestinationRead.Failed ->
                        done(
                            if (read.code == CONNECTION_STOPPED) CONNECTION_STOPPED
                            else BASELINE_UNAVAILABLE
                        )
                    is DestinationRead.Received -> {
                        val judged =
                            NavigationDestination.judge(read.snapshot, read.receivedAtMillis)
                        if (judged.status == DestinationStatus.NO_DESTINATION) {
                            done(NO_DESTINATION)
                        } else {
                            destination = read.snapshot
                            readWaypoints(done)
                        }
                    }
                }
            }
        }

        private fun readWaypoints(done: (block: String?) -> Unit) {
            waypoints.read(baselineMillis) { read ->
                if (admissionClosed) {
                    done(CONNECTION_STOPPED)
                    return@read
                }
                when (read) {
                    is WaypointRead.Failed ->
                        done(
                            if (read.code == CONNECTION_STOPPED) CONNECTION_STOPPED
                            else WAYPOINTS_UNAVAILABLE
                        )
                    is WaypointRead.Received -> {
                        before = read.waypoints
                        done(if (kind == Kind.ADD) refusalOfAdd() else refusalOfRemove())
                    }
                }
            }
        }

        private fun refusalOfAdd(): String? =
            when {
                before.any { WaypointMatch.isPlace(place, it) } -> ALREADY_WAYPOINT
                before.size >= maxWaypoints -> WAYPOINT_LIMIT
                else -> {
                    // Appended: the only two placements observed to work (COM-47).
                    slot = if (before.isEmpty()) WaypointSlot.FIRST else WaypointSlot.SECOND
                    null
                }
            }

        private fun refusalOfRemove(): String? {
            val positions = before.indices.filter { WaypointMatch.isPlace(place, before[it]) }
            return when {
                positions.isEmpty() -> WAYPOINT_NOT_FOUND
                positions.size > 1 -> AMBIGUOUS_WAYPOINT
                else -> {
                    removedAt = positions.single()
                    val found = WaypointSlot.entries.getOrNull(removedAt)
                    if (found == null) {
                        WAYPOINT_POSITION_UNSUPPORTED
                    } else {
                        slot = found
                        null
                    }
                }
            }
        }

        override fun start(done: (failure: String?) -> Unit) {
            running = this
            fun finish(failure: String?) {
                if (over) return
                cancel()
                if (running === this) running = null
                done(failure)
            }
            stopWatchingWaypoints = waypoints.watch { list ->
                latestWaypoints = list
                if (confirmed()) finish(null)
            }
            stopWatchingDestination = destinations.watch { fields ->
                latestDestination = fields
                if (confirmed()) finish(null)
            }
            cancelDeadline = scheduler.after(confirmMillis) { finish(OUTCOME_UNKNOWN) }
            val returned: (Exception?) -> Unit = { thrown ->
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
            if (kind == Kind.ADD) {
                sdk.addWaypoint(
                    WaypointRequest(
                        latitude = place.latitude,
                        longitude = place.longitude,
                        name = place.displayName,
                        address = place.address,
                        poiId = place.poiId,
                        slot = slot,
                    ),
                    returned,
                )
            } else {
                sdk.removeWaypoint(slot, returned)
            }
        }

        fun cancel() {
            over = true
            stopWatchingWaypoints()
            stopWatchingDestination()
            cancelDeadline()
            cancelPoll()
        }

        private fun poll() {
            cancelPoll =
                scheduler.after(pollMillis) {
                    if (over) return@after
                    destinations.probe()
                    waypoints.probe()
                    poll()
                }
        }

        /** The latest waypoints show the change and the latest destination is the one before. */
        private fun confirmed(): Boolean {
            val list = latestWaypoints ?: return false
            val now = latestDestination ?: return false
            val start = destination ?: return false
            return DestinationMatch.same(start, now, toleranceDegrees) && reflected(list)
        }

        private fun reflected(list: List<WaypointFields>): Boolean {
            val kept =
                if (kind == Kind.ADD) before else before.filterIndexed { i, _ -> i != removedAt }
            val changed =
                if (kind == Kind.ADD) {
                    list.size == before.size + 1 && list.any { WaypointMatch.isPlace(place, it) }
                } else {
                    list.size == before.size - 1 && list.none { WaypointMatch.isPlace(place, it) }
                }
            return changed && kept.all { old -> list.any { WaypointMatch.same(old, it) } }
        }
    }

    private fun outcomeOf(result: NavTaskResult): WaypointChange =
        when (result.state) {
            NavTaskState.SUCCEEDED -> WaypointChange.Applied
            NavTaskState.BLOCKED ->
                WaypointChange.NotDone(result.code ?: CHECK_FAILED, sent = false)
            else ->
                when (result.code) {
                    // Refused by the SDK before anything was sent.
                    PERMISSION_DENIED -> WaypointChange.NotDone(PERMISSION_DENIED, sent = false)
                    CONNECTION_STOPPED -> WaypointChange.NotDone(CONNECTION_STOPPED, sent = true)
                    else -> WaypointChange.NotDone(OUTCOME_UNKNOWN, sent = true)
                }
        }

    companion object {
        const val ADD_ACTION = "add_waypoint"
        const val REMOVE_ACTION = "remove_waypoint"
        const val BUSY = "BUSY"
        const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"
        const val INVALID_REQUEST = "INVALID_REQUEST"
        const val BASELINE_UNAVAILABLE = DestinationSetter.BASELINE_UNAVAILABLE
        const val PERMISSION_DENIED = DestinationReader.PERMISSION_DENIED
        const val CONNECTION_STOPPED = NavTaskCoordinator.CONNECTION_STOPPED
        const val CHECK_FAILED = NavTaskCoordinator.CHECK_FAILED
        const val NO_DESTINATION = "NO_DESTINATION"
        const val WAYPOINTS_UNAVAILABLE = "WAYPOINTS_UNAVAILABLE"
        const val ALREADY_WAYPOINT = "ALREADY_WAYPOINT"
        const val WAYPOINT_LIMIT = "WAYPOINT_LIMIT"
        const val WAYPOINT_NOT_FOUND = "WAYPOINT_NOT_FOUND"
        const val AMBIGUOUS_WAYPOINT = "AMBIGUOUS_WAYPOINT"
        const val WAYPOINT_POSITION_UNSUPPORTED = "WAYPOINT_POSITION_UNSUPPORTED"
    }
}
