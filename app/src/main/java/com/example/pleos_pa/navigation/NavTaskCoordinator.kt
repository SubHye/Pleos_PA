package com.example.pleos_pa.navigation

/** Where a task stands. [SUCCEEDED], [FAILED] and [BLOCKED] are final. */
enum class NavTaskState {
    RECEIVED,
    CHECKING,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    BLOCKED,
}

/**
 * One navigation change a task needs, such as setting the destination. [start] sends the change and
 * reports once through `done`: null when the change is confirmed, otherwise a fixed failure code.
 * What counts as confirmed belongs to each feature, not to a request having been sent.
 */
interface NavAction {
    val name: String

    fun start(done: (failure: String?) -> Unit)
}

/**
 * The one navigation result the user asked for, with every action it needs decided before it is
 * submitted. Nothing is added once the task runs.
 */
class NavTaskRequest(val requestId: String, actions: List<NavAction>) {
    val actions: List<NavAction> = actions.toList()
}

/**
 * The execution conditions: connection, arguments, feature support, driving restriction and the
 * current state. Reports once through `done`: null when the task may run, otherwise a fixed code.
 */
fun interface NavConditions {
    fun check(request: NavTaskRequest, done: (block: String?) -> Unit)
}

/**
 * The end of one task. [code] is the block or failure code, null on success. [failedAction] is the
 * action that reported failure. [unconfirmed] is the action already sent when the connection
 * stopped: its effect is unknown, and it is not reported as cancelled or undone.
 */
data class NavTaskResult(
    val requestId: String,
    val state: NavTaskState,
    val code: String?,
    val succeeded: List<String>,
    val failedAction: String?,
    val unconfirmed: String?,
    val notRun: List<String>,
)

sealed interface NavAdmission {
    data class Accepted(val requestId: String) : NavAdmission

    /** A task is in progress. It keeps running, and this request is not queued. */
    data class Busy(val activeRequestId: String) : NavAdmission

    /** The connection has stopped. */
    data object Unavailable : NavAdmission

    /** No request ID, or no action decided. */
    data object Incomplete : NavAdmission
}

/**
 * Runs at most one navigation change task at a time, for one connection. A task is checked once,
 * then its actions run in order, and the first failure ends it. Nothing retries or runs later on
 * its own: a blocked or failed request has to be submitted again as a new task.
 *
 * Single-thread use: [submit], [close] and every callback from conditions and actions must run on
 * the same thread. `complete` may run before [submit] returns.
 */
class NavTaskCoordinator(
    private val conditions: NavConditions,
    private val onState: (requestId: String, state: NavTaskState) -> Unit = { _, _ -> },
) {
    private class Task(val request: NavTaskRequest, val complete: (NavTaskResult) -> Unit) {
        var state = NavTaskState.RECEIVED
        val succeeded = mutableListOf<String>()
        var next = 0
        var inFlight: NavAction? = null

        /** The one callback still awaited, consumed by its first delivery. */
        var awaited: Any? = null
    }

    private var active: Task? = null
    private var closed = false

    fun submit(request: NavTaskRequest, complete: (NavTaskResult) -> Unit): NavAdmission {
        if (closed) return NavAdmission.Unavailable
        active?.let {
            return NavAdmission.Busy(it.request.requestId)
        }
        if (request.requestId.isBlank() || request.actions.isEmpty()) {
            return NavAdmission.Incomplete
        }
        val task = Task(request, complete)
        active = task
        onState(request.requestId, NavTaskState.RECEIVED)
        check(task)
        return NavAdmission.Accepted(request.requestId)
    }

    /**
     * The connection stopped: no action that has not started will start. A task still being checked
     * is blocked; a task executing fails, leaving the action in flight unconfirmed.
     */
    fun close() {
        if (closed) return
        closed = true
        val task = active ?: return
        if (task.state == NavTaskState.EXECUTING) {
            finish(task, NavTaskState.FAILED, CONNECTION_STOPPED, unconfirmed = task.inFlight)
        } else {
            finish(task, NavTaskState.BLOCKED, CONNECTION_STOPPED)
        }
    }

    private fun check(task: Task) {
        move(task, NavTaskState.CHECKING)
        val token = Any()
        task.awaited = token
        try {
            conditions.check(task.request) { block ->
                if (!consume(task, token)) return@check
                if (block == null) {
                    move(task, NavTaskState.EXECUTING)
                    runNext(task)
                } else {
                    finish(task, NavTaskState.BLOCKED, block)
                }
            }
        } catch (_: Exception) {
            if (consume(task, token)) finish(task, NavTaskState.BLOCKED, CHECK_FAILED)
        }
    }

    private fun runNext(task: Task) {
        val action = task.request.actions.getOrNull(task.next)
        if (action == null) {
            finish(task, NavTaskState.SUCCEEDED, null)
            return
        }
        val token = Any()
        task.awaited = token
        task.inFlight = action
        try {
            action.start { failure ->
                if (!consume(task, token)) return@start
                if (failure == null) {
                    task.succeeded += action.name
                    task.next++
                    runNext(task)
                } else {
                    finish(task, NavTaskState.FAILED, failure, failed = action)
                }
            }
        } catch (_: Exception) {
            if (consume(task, token)) {
                finish(task, NavTaskState.FAILED, ACTION_FAILED, failed = action)
            }
        }
    }

    private fun consume(task: Task, token: Any): Boolean {
        if (active !== task || task.awaited !== token) return false
        task.awaited = null
        task.inFlight = null
        return true
    }

    private fun move(task: Task, state: NavTaskState) {
        task.state = state
        onState(task.request.requestId, state)
    }

    private fun finish(
        task: Task,
        state: NavTaskState,
        code: String?,
        failed: NavAction? = null,
        unconfirmed: NavAction? = null,
    ) {
        if (active !== task) return
        active = null
        task.awaited = null
        task.inFlight = null
        val reached = if (failed != null || unconfirmed != null) task.next + 1 else task.next
        val result =
            NavTaskResult(
                requestId = task.request.requestId,
                state = state,
                code = code,
                succeeded = task.succeeded.toList(),
                failedAction = failed?.name,
                unconfirmed = unconfirmed?.name,
                notRun = task.request.actions.drop(reached).map { it.name },
            )
        move(task, state)
        task.complete(result)
    }

    companion object {
        const val CONNECTION_STOPPED = "CONNECTION_STOPPED"
        const val CHECK_FAILED = "CHECK_FAILED"
        const val ACTION_FAILED = "ACTION_FAILED"
    }
}
