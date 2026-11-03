package com.example.pleos_pa.safety

/** A contract prototype. No vehicle API or remote approval endpoint is connected to this class. */
class ExecutionBoundary(
    private val capabilities: VerifiedCapabilities = VerifiedCapabilities(),
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val evidenceMaxAgeMillis: Long = 1_000,
    private val approvalLifetimeMillis: Long = 30_000,
    private val requestLimit: Int = 128,
) {
    enum class UxState {
        PARKED_UNRESTRICTED,
        RESTRICTED,
        UNKNOWN,
    }

    enum class Action {
        TEXT_INPUT,
        LONG_HISTORY,
        NAVIGATION_CHANGE,
        RESULT_SUMMARY,
        STOP,
    }

    enum class Decision {
        APPROVE,
        REJECT,
        CANCEL,
    }

    enum class Status {
        AWAITING_APPROVAL,
        APPROVED,
        REJECTED,
        CANCELLED,
        INVALIDATED,
        SENT,
        UNKNOWN,
        SUCCEEDED,
        FAILED,
    }

    enum class Result {
        BLOCKED,
        AWAITING_APPROVAL,
        APPROVED,
        REJECTED,
        CANCELLED,
        INVALIDATED,
        EXPIRED,
        ARGUMENT_MISMATCH,
        ROUTE_CHANGED,
        ROUTE_UNAVAILABLE,
        DUPLICATE,
        CAPACITY,
        SENT,
        UNKNOWN,
    }

    /**
     * Set only after target-platform evidence and trusted adapter/approval integration are
     * reviewed.
     */
    data class VerifiedCapabilities(
        val ux: Boolean = false,
        val navigation: Boolean = false,
        val stopPaths: Boolean = false,
        val resultSummaryInAllUxStates: Boolean = false,
    )

    /** Fully expanded immutable arguments; no caller-supplied JSON/hash or mutable collections. */
    data class DestinationChange(
        val requestId: String,
        val clientSessionId: String,
        val latitudeE7: Int,
        val longitudeE7: Int,
        val displayName: String,
        val expectedRouteVersion: String,
    ) {
        init {
            require(requestId.isNotBlank() && clientSessionId.isNotBlank())
            require(latitudeE7 in -900_000_000..900_000_000)
            require(longitudeE7 in -1_800_000_000..1_800_000_000)
            require(displayName.isNotBlank() && expectedRouteVersion.isNotBlank())
        }
    }

    private data class Record(
        val change: DestinationChange,
        var status: Status = Status.AWAITING_APPROVAL,
        var approvedAt: Long? = null,
    )

    private val records = mutableMapOf<String, Record>()
    private var uxState = UxState.UNKNOWN
    private var observedAt: Long? = null
    private var connectedSession: String? = null

    init {
        require(evidenceMaxAgeMillis > 0 && approvalLifetimeMillis > 0 && requestLimit > 0)
    }

    /** Must come from a live, display-correct platform adapter; UNKNOWN on disconnect/error. */
    @Synchronized
    fun observeUx(state: UxState) {
        if (state != uxState || !hasFreshParkedEvidence()) invalidateUnsent()
        uxState = state
        observedAt = clockMillis()
    }

    /** Explicit user action only. Reconnecting never revives an old approval or request. */
    @Synchronized
    fun resume(clientSessionId: String) {
        require(clientSessionId.isNotBlank())
        stop()
        connectedSession = clientSessionId
    }

    /** Both app and notification stop actions must call this same operation. */
    @Synchronized
    fun stop() {
        connectedSession = null
        records.values.forEach {
            it.status =
                when (it.status) {
                    Status.AWAITING_APPROVAL,
                    Status.APPROVED -> Status.CANCELLED
                    Status.SENT -> Status.UNKNOWN
                    else -> it.status
                }
        }
    }

    @Synchronized
    fun allows(action: Action): Boolean =
        when (action) {
            Action.STOP ->
                true // Logical stop is always accepted; UI reachability needs device evidence.
            Action.RESULT_SUMMARY -> capabilities.resultSummaryInAllUxStates
            Action.TEXT_INPUT,
            Action.LONG_HISTORY -> hasFreshParkedEvidence()
            Action.NAVIGATION_CHANGE ->
                capabilities.navigation &&
                    capabilities.stopPaths &&
                    connectedSession != null &&
                    hasFreshParkedEvidence()
        }

    @Synchronized
    fun prepare(change: DestinationChange): Result {
        if (records.containsKey(change.requestId)) return Result.DUPLICATE
        if (!canExecute(change)) return Result.BLOCKED
        // Keep consumed IDs for replay rejection; never evict and silently permit a replay.
        if (records.size >= requestLimit) return Result.CAPACITY
        records[change.requestId] = Record(change)
        return Result.AWAITING_APPROVAL
    }

    /** Trusted local confirmation only. Never expose this method as an AI/MCP tool or IPC route. */
    @Synchronized
    fun decide(requestId: String, decision: Decision): Result {
        val record = records[requestId] ?: return Result.BLOCKED
        if (record.status != Status.AWAITING_APPROVAL) return Result.BLOCKED
        if (!canExecute(record.change)) {
            record.status = Status.INVALIDATED
            return Result.INVALIDATED
        }
        return when (decision) {
            Decision.APPROVE -> {
                record.status = Status.APPROVED
                record.approvedAt = clockMillis()
                Result.APPROVED
            }
            Decision.REJECT -> {
                record.status = Status.REJECTED
                Result.REJECTED
            }
            Decision.CANCEL -> {
                record.status = Status.CANCELLED
                Result.CANCELLED
            }
        }
    }

    /** A trusted user cancellation may also withdraw an approval before SDK handoff. */
    @Synchronized
    fun cancel(requestId: String): Boolean {
        val record = records[requestId] ?: return false
        if (record.status != Status.AWAITING_APPROVAL && record.status != Status.APPROVED)
            return false
        record.status = Status.CANCELLED
        return true
    }

    /**
     * The same monitor serializes stop and the actual SDK handoff, not merely a permission check.
     * readRouteVersion performs a trusted fresh SDK read inside the same boundary, never remote
     * data. send must hand off synchronously and promptly, with no hidden queue/retry or approval
     * UI. A returned send is only SENT. It is not evidence that navigation changed successfully.
     */
    @Synchronized
    fun dispatch(
        change: DestinationChange,
        readRouteVersion: () -> String?,
        send: (DestinationChange) -> Unit,
    ): Result {
        val record = records[change.requestId] ?: return Result.BLOCKED
        if (record.status != Status.APPROVED) return Result.BLOCKED
        if (record.change != change) {
            record.status = Status.INVALIDATED
            return Result.ARGUMENT_MISMATCH
        }
        if (!canExecute(change)) {
            record.status = Status.INVALIDATED
            return Result.INVALIDATED
        }
        val approvedAt = record.approvedAt ?: return Result.BLOCKED
        if (clockMillis() - approvedAt !in 0 until approvalLifetimeMillis) {
            record.status = Status.INVALIDATED
            return Result.EXPIRED
        }
        val currentRouteVersion =
            try {
                readRouteVersion()
            } catch (_: Exception) {
                null
            }
        if (currentRouteVersion == null) {
            record.status = Status.INVALIDATED
            return Result.ROUTE_UNAVAILABLE
        }
        if (currentRouteVersion != change.expectedRouteVersion) {
            record.status = Status.INVALIDATED
            return Result.ROUTE_CHANGED
        }
        // Even a synchronous adapter read can take time; do not extend evidence/approval validity.
        if (
            record.status != Status.APPROVED ||
                !canExecute(change) ||
                clockMillis() - approvedAt !in 0 until approvalLifetimeMillis
        ) {
            record.status = Status.INVALIDATED
            return Result.INVALIDATED
        }
        record.status =
            Status.SENT // Consume before crossing the SDK boundary, including exceptions.
        try {
            send(change)
        } catch (_: Exception) {
            record.status = Status.UNKNOWN
        }
        return if (record.status == Status.UNKNOWN) Result.UNKNOWN else Result.SENT
    }

    /**
     * Transport timeout/disconnect cannot prove rollback. A later callback cannot overwrite
     * UNKNOWN.
     */
    @Synchronized
    fun markResultUnknown(requestId: String) {
        records[requestId]?.takeIf { it.status == Status.SENT }?.status = Status.UNKNOWN
    }

    /** Use only after an authoritative SDK result; request acceptance is not success. */
    @Synchronized
    fun complete(requestId: String, succeeded: Boolean): Boolean {
        val record = records[requestId] ?: return false
        if (record.status != Status.SENT) return false
        record.status = if (succeeded) Status.SUCCEEDED else Status.FAILED
        return true
    }

    @Synchronized fun status(requestId: String): Status? = records[requestId]?.status

    private fun canExecute(change: DestinationChange): Boolean =
        allows(Action.NAVIGATION_CHANGE) && change.clientSessionId == connectedSession

    private fun hasFreshParkedEvidence(): Boolean {
        val at = observedAt ?: return false
        return capabilities.ux &&
            uxState == UxState.PARKED_UNRESTRICTED &&
            clockMillis() - at in 0 until evidenceMaxAgeMillis
    }

    private fun invalidateUnsent() {
        records.values
            .filter { it.status == Status.AWAITING_APPROVAL || it.status == Status.APPROVED }
            .forEach { it.status = Status.INVALIDATED }
    }
}
