package com.example.pleos_pa.navigation

import com.example.pleos_pa.contract.DestinationFields
import com.example.pleos_pa.vehicle.ReadScheduler

/** How one destination read ended. */
sealed interface DestinationRead {
    data class Received(val snapshot: DestinationFields, val receivedAtMillis: Long) :
        DestinationRead

    /** A fixed code from [DestinationReader]'s companion. No SDK value came with it. */
    data class Failed(val code: String) : DestinationRead
}

/**
 * The app-wide navigation read slot, separate from the vehicle read slot. One read at a time and no
 * queue. Every state change runs on [scheduler]'s thread; [read] and [close] may be called from any
 * thread.
 *
 * `onDestinationInfo` carries no request identifier. A callback is therefore only taken as the
 * answer of the read in progress, and a read that timed out leaves a hold: until its late callback
 * arrives, or [staleHoldMillis] passes, no new call goes to the SDK and reads end with
 * [PREVIOUS_RESPONSE_PENDING]. A callback later than the hold can still be taken for a later read;
 * the contract records that residual risk.
 */
class DestinationReader(
    private val sdk: NaviSdk,
    private val scheduler: ReadScheduler,
    private val source: String?,
    private val staleHoldMillis: Long,
    private val onConnection: (connected: Boolean) -> Unit = {},
) {
    private enum class Connection {
        CONNECTING,
        READY,
        FAILED,
    }

    private class Pending(val reply: (DestinationRead) -> Unit) {
        var cancelTimer: () -> Unit = {}
    }

    private var connection = Connection.CONNECTING
    private var closed = false
    // Set from any thread by the stop sequence, before the server closes.
    @Volatile private var admissionClosed = false
    private var pending: Pending? = null
    // The read whose SDK call timed out unanswered, and the timer that lifts the hold.
    private var stale: Pending? = null
    private var cancelHold: () -> Unit = {}
    private var watcher: ((DestinationFields) -> Unit)? = null

    /** Connects the SDK. Once per reader; the session calls it when the connection starts. */
    fun open() {
        sdk.open(
            object : NaviSdkListener {
                override fun connected() {
                    scheduler.execute { if (!closed) onConnected(Connection.READY) }
                }

                override fun connectionFailed() {
                    scheduler.execute { if (!closed) onConnected(Connection.FAILED) }
                }

                override fun destination(snapshot: DestinationFields, receivedAtMillis: Long) {
                    scheduler.execute { delivered(snapshot, receivedAtMillis) }
                }
            }
        )
    }

    /**
     * [reply] runs exactly once on the scheduler thread. [budgetMillis] is what the read may spend
     * before it ends with [TIMEOUT].
     */
    fun read(budgetMillis: Long, reply: (DestinationRead) -> Unit) {
        scheduler.execute { admit(budgetMillis, reply) }
    }

    /**
     * Why nothing can be asked of the SDK right now, or null. Scheduler thread only. A destination
     * change checks this before it starts.
     */
    fun unavailable(): String? =
        when {
            closed || admissionClosed -> CONNECTION_STOPPED
            source == null -> APP_NOT_READY
            connection != Connection.READY -> NAVI_NOT_CONNECTED
            else -> null
        }

    /**
     * Shows [observer] every destination callback from now on, before the slot handles it, until
     * the returned function is called. Scheduler thread only; one observer at a time. A callback
     * holds the destination current when it was sent, whoever asked for it, so an observer may
     * judge its content but not which request it answers.
     */
    fun watch(observer: (DestinationFields) -> Unit): () -> Unit {
        watcher = observer
        return { if (watcher === observer) watcher = null }
    }

    /**
     * Asks the SDK for the current destination without taking the read slot: no reply, no deadline
     * and no hold. The answer reaches [watch]'s observer, and a read in progress may take it as its
     * own, which is the destination current at that moment either way. It also ends a hold, as any
     * callback does. Scheduler thread only.
     */
    fun probe() {
        if (unavailable() == null) sdk.requestDestination {}
    }

    /** The first step of a stop: reads that arrive from now on end with [CONNECTION_STOPPED]. */
    fun closeAdmission() {
        admissionClosed = true
    }

    /** Ends the read in progress with [CONNECTION_STOPPED], then releases the SDK. */
    fun close(released: () -> Unit) {
        scheduler.execute {
            if (closed) return@execute
            closed = true
            pending?.let { finish(it, DestinationRead.Failed(CONNECTION_STOPPED)) }
            cancelHold()
            stale = null
            watcher = null
            sdk.close(released)
        }
    }

    private fun onConnected(state: Connection) {
        // Only the first report counts: a failure after a connection does not undo it, and the
        // SDK is not asked to connect again.
        if (connection != Connection.CONNECTING) return
        connection = state
        onConnection(state == Connection.READY)
    }

    private fun admit(budgetMillis: Long, reply: (DestinationRead) -> Unit) {
        val refusal =
            when {
                closed || admissionClosed -> CONNECTION_STOPPED
                source == null -> APP_NOT_READY
                pending != null -> BUSY
                connection != Connection.READY -> NAVI_NOT_CONNECTED
                stale != null -> PREVIOUS_RESPONSE_PENDING
                budgetMillis < 1 -> TIMEOUT
                else -> null
            }
        if (refusal != null) {
            reply(DestinationRead.Failed(refusal))
            return
        }
        val admitted = Pending(reply)
        pending = admitted
        admitted.cancelTimer = scheduler.after(budgetMillis) { timedOut(admitted) }
        sdk.requestDestination { error ->
            scheduler.execute { callFailed(admitted, error) }
        }
    }

    private fun delivered(snapshot: DestinationFields, receivedAtMillis: Long) {
        if (closed) return
        watcher?.invoke(snapshot)
        val current = pending
        when {
            current != null -> finish(current, DestinationRead.Received(snapshot, receivedAtMillis))
            // The late answer of the read that timed out: dropped, and the hold is lifted.
            stale != null -> liftHold()
            // No read asked for it. Dropped.
            else -> Unit
        }
    }

    private fun callFailed(request: Pending, error: Exception) {
        when {
            pending === request -> finish(request, DestinationRead.Failed(classify(error)))
            // The call of a read that already timed out threw, so no late answer will follow.
            stale === request -> liftHold()
        }
    }

    private fun timedOut(request: Pending) {
        if (pending !== request) return
        finish(request, DestinationRead.Failed(TIMEOUT))
        stale = request
        cancelHold = scheduler.after(staleHoldMillis) { if (stale === request) liftHold() }
    }

    private fun liftHold() {
        cancelHold()
        cancelHold = {}
        stale = null
    }

    private fun finish(request: Pending, result: DestinationRead) {
        if (pending !== request) return
        pending = null
        request.cancelTimer()
        request.reply(result)
    }

    companion object {
        const val BUSY = "BUSY"
        const val APP_NOT_READY = "APP_NOT_READY"
        const val CONNECTION_STOPPED = "CONNECTION_STOPPED"
        const val NAVI_NOT_CONNECTED = "NAVI_NOT_CONNECTED"
        const val PREVIOUS_RESPONSE_PENDING = "PREVIOUS_RESPONSE_PENDING"
        const val PERMISSION_DENIED = "PERMISSION_DENIED"
        const val TIMEOUT = "TIMEOUT"
        const val READ_FAILED = "READ_FAILED"

        /** The whole cause chain, as the vehicle classifier does; messages are never exported. */
        fun classify(error: Throwable): String {
            var cause: Throwable? = error
            var depth = 0
            while (cause != null && depth < MAX_CAUSE_DEPTH) {
                if (cause is SecurityException) return PERMISSION_DENIED
                cause = cause.cause
                depth++
            }
            return READ_FAILED
        }

        private const val MAX_CAUSE_DEPTH = 16
    }
}
