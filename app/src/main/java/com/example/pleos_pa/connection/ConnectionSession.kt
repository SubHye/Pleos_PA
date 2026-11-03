package com.example.pleos_pa.connection

import com.example.pleos_pa.bridge.BridgeEndpoint
import java.util.concurrent.Executor

sealed interface ConnectionStatus {
    data object Stopped : ConnectionStatus

    data object Starting : ConnectionStatus

    /**
     * The bridge can take requests. [interrupted] means a read lost its peer since the last one.
     */
    data class Connected(val interrupted: Boolean = false) : ConnectionStatus

    /** Only a fixed code is kept. */
    data class Failed(val code: String) : ConnectionStatus
}

/**
 * One start-to-stop span of the bridge. Single use: a resume builds a new session, so nothing the
 * old one still has in flight can reach the new one. [onStatus] may run on the bind executor.
 */
class ConnectionSession(
    private val closeAdmission: () -> Unit,
    private val endpoint: BridgeEndpoint,
    private val closeReader: (released: () -> Unit) -> Unit,
    private val bindExecutor: Executor,
    private val onStatus: (ConnectionSession, ConnectionStatus) -> Unit,
) {
    private val lock = Any()
    private var started = false
    private var stopping = false

    fun start() {
        synchronized(lock) {
            if (started || stopping) return
            started = true
        }
        onStatus(this, ConnectionStatus.Starting)
        bindExecutor.execute {
            if (synchronized(lock) { stopping }) return@execute
            val failure =
                try {
                    endpoint.start()
                    null
                } catch (_: Exception) {
                    BIND_FAILED
                }
            if (synchronized(lock) { stopping }) return@execute
            onStatus(
                this,
                if (failure == null) ConnectionStatus.Connected()
                else ConnectionStatus.Failed(failure),
            )
        }
    }

    /**
     * Admission, then sockets, then the request in progress and the SDK. [stopped] runs once the
     * SDK is released, which is when "no new SDK call" starts to hold.
     */
    fun stop(stopped: () -> Unit) {
        synchronized(lock) {
            if (stopping) return
            stopping = true
        }
        try {
            closeAdmission()
            endpoint.stop()
        } finally {
            closeReader(stopped)
        }
    }

    private companion object {
        const val BIND_FAILED = "BRIDGE_START_FAILED"
    }
}

/**
 * Holds at most one session and applies commands in order, the last one winning. Main-thread use.
 * [ended] tells the service the connection is down and nothing is queued, with the final status.
 */
class ConnectionController(
    private val create: () -> ConnectionSession,
    private val report: (ConnectionStatus) -> Unit,
    private val ended: (ConnectionStatus) -> Unit,
) {
    private var current: ConnectionSession? = null
    private var tearingDown = false
    private var resumeAfterTeardown = false

    fun start() {
        if (tearingDown) {
            resumeAfterTeardown = true
            return
        }
        if (current != null) return
        val session = create()
        current = session
        session.start()
    }

    fun stop() {
        resumeAfterTeardown = false
        if (tearingDown) return
        val session = current
        if (session == null) {
            ended(ConnectionStatus.Stopped)
            return
        }
        tearDown(session, ConnectionStatus.Stopped)
    }

    fun onStatus(session: ConnectionSession, status: ConnectionStatus) {
        if (session !== current || tearingDown) return
        if (status is ConnectionStatus.Failed) {
            tearDown(session, status)
        } else {
            report(status)
        }
    }

    /** [ConnectionSession.stop]'s callback must come back on the main thread. */
    private fun tearDown(session: ConnectionSession, finalStatus: ConnectionStatus) {
        tearingDown = true
        session.stop {
            tearingDown = false
            current = null
            if (resumeAfterTeardown) {
                resumeAfterTeardown = false
                start()
            } else {
                ended(finalStatus)
            }
        }
    }
}
