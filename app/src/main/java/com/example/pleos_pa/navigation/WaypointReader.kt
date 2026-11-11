package com.example.pleos_pa.navigation

import com.example.pleos_pa.contract.WaypointFields
import com.example.pleos_pa.vehicle.ReadScheduler

/** How one waypoint read ended. */
sealed interface WaypointRead {
    data class Received(val waypoints: List<WaypointFields>, val receivedAtMillis: Long) :
        WaypointRead

    /**
     * A fixed code from [WaypointReader]'s companion or [DestinationReader]'s. No SDK value came.
     */
    data class Failed(val code: String) : WaypointRead
}

/**
 * The waypoint read slot, separate from the destination and vehicle slots: one read at a time and
 * no queue. It does the same as [DestinationReader] for `onWayPointInfo`, which also carries no
 * request identifier and reaches every client of the navigation service. A callback is therefore
 * only taken as the answer of the read in progress, and a read that timed out leaves a hold: until
 * its late callback arrives, or [staleHoldMillis] passes, no new call goes to the SDK and reads end
 * with [PREVIOUS_RESPONSE_PENDING]. A callback later than the hold can still be taken for a later
 * read.
 *
 * It does not own the connection. [unavailable] says why nothing can be asked right now (the
 * destination reader's connection state), and the destination reader hands over each waypoint
 * callback through [delivered]. Every state change runs on [scheduler]'s thread; [read] may be
 * called from any thread.
 */
class WaypointReader(
    private val sdk: NaviSdk,
    private val scheduler: ReadScheduler,
    private val staleHoldMillis: Long,
    private val unavailable: () -> String?,
) {
    private class Pending(val reply: (WaypointRead) -> Unit) {
        var cancelTimer: () -> Unit = {}
    }

    private var closed = false
    private var pending: Pending? = null
    // The read whose SDK call timed out unanswered, and the timer that lifts the hold.
    private var stale: Pending? = null
    private var cancelHold: () -> Unit = {}
    private var watcher: ((List<WaypointFields>) -> Unit)? = null

    /**
     * [reply] runs exactly once on the scheduler thread. [budgetMillis] is what the read may spend.
     */
    fun read(budgetMillis: Long, reply: (WaypointRead) -> Unit) {
        scheduler.execute { admit(budgetMillis, reply) }
    }

    /**
     * Shows [observer] every waypoint callback from now on, before the slot handles it, until the
     * returned function is called. Scheduler thread only; one observer at a time. A callback holds
     * the waypoints current when it was sent, whoever asked for it.
     */
    fun watch(observer: (List<WaypointFields>) -> Unit): () -> Unit {
        watcher = observer
        return { if (watcher === observer) watcher = null }
    }

    /**
     * Asks the SDK for the current waypoints without taking the read slot: no reply, no deadline
     * and no hold. The answer reaches [watch]'s observer. Scheduler thread only.
     */
    fun probe() {
        if (!closed && unavailable() == null) sdk.requestWaypoints {}
    }

    /** One waypoint callback, handed over by the destination reader. Scheduler thread only. */
    fun delivered(snapshot: List<WaypointFields>, receivedAtMillis: Long) {
        if (closed) return
        watcher?.invoke(snapshot)
        val current = pending
        when {
            current != null -> finish(current, WaypointRead.Received(snapshot, receivedAtMillis))
            // The late answer of the read that timed out: dropped, and the hold is lifted.
            stale != null -> liftHold()
            // No read asked for it. Dropped.
            else -> Unit
        }
    }

    /** Ends the read in progress with [CONNECTION_STOPPED]. The SDK is released by its owner. */
    fun close() {
        scheduler.execute {
            if (closed) return@execute
            closed = true
            pending?.let { finish(it, WaypointRead.Failed(CONNECTION_STOPPED)) }
            cancelHold()
            stale = null
            watcher = null
        }
    }

    private fun admit(budgetMillis: Long, reply: (WaypointRead) -> Unit) {
        val refusal =
            when {
                closed -> CONNECTION_STOPPED
                else -> unavailable() ?: refusalOfSlot(budgetMillis)
            }
        if (refusal != null) {
            reply(WaypointRead.Failed(refusal))
            return
        }
        val admitted = Pending(reply)
        pending = admitted
        admitted.cancelTimer = scheduler.after(budgetMillis) { timedOut(admitted) }
        sdk.requestWaypoints { error -> scheduler.execute { callFailed(admitted, error) } }
    }

    private fun refusalOfSlot(budgetMillis: Long): String? =
        when {
            pending != null -> BUSY
            stale != null -> PREVIOUS_RESPONSE_PENDING
            budgetMillis < 1 -> TIMEOUT
            else -> null
        }

    private fun callFailed(request: Pending, error: Exception) {
        when {
            pending === request ->
                finish(request, WaypointRead.Failed(DestinationReader.classify(error)))
            // The call of a read that already timed out threw, so no late answer will follow.
            stale === request -> liftHold()
        }
    }

    private fun timedOut(request: Pending) {
        if (pending !== request) return
        finish(request, WaypointRead.Failed(TIMEOUT))
        stale = request
        cancelHold = scheduler.after(staleHoldMillis) { if (stale === request) liftHold() }
    }

    private fun liftHold() {
        cancelHold()
        cancelHold = {}
        stale = null
    }

    private fun finish(request: Pending, result: WaypointRead) {
        if (pending !== request) return
        pending = null
        request.cancelTimer()
        request.reply(result)
    }

    companion object {
        const val BUSY = DestinationReader.BUSY
        const val CONNECTION_STOPPED = DestinationReader.CONNECTION_STOPPED
        const val PREVIOUS_RESPONSE_PENDING = DestinationReader.PREVIOUS_RESPONSE_PENDING
        const val TIMEOUT = DestinationReader.TIMEOUT
    }
}
