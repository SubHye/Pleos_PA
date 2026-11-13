package com.example.pleos_pa.ui

import com.example.pleos_pa.navigation.NavAdmission
import com.example.pleos_pa.navigation.NavConditions
import com.example.pleos_pa.navigation.NavTaskCoordinator
import com.example.pleos_pa.navigation.NavTaskRequest
import com.example.pleos_pa.navigation.NavTaskResult
import java.time.Instant

/**
 * Presentation adapter for a connection-owned coordinator, on its single callback thread. No SDK
 * calls or new execution permissions. The future navigation adapter must own/close this with its
 * connection. Inputs, destination values, action names and failure text are not retained.
 */
class NavTaskHistory(
    conditions: NavConditions,
    private val history: RequestHistory,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var token: RequestHistory.Token? = null
    private val coordinator =
        NavTaskCoordinator(conditions) { _, state ->
            if (state.name in setOf("RECEIVED", "CHECKING", "EXECUTING")) {
                token?.let { history.progress(it, state.name) }
            }
        }

    fun submit(request: NavTaskRequest, complete: (NavTaskResult) -> Unit): NavAdmission {
        // A BUSY request gets its own attempt; restore the active attempt's observer token.
        val previous = token
        val attempt =
            history.begin(
                request.requestId,
                "navigation_task",
                "동작 수: ${request.actions.size}",
                Instant.ofEpochMilli(now()).toString(),
            )
        token = attempt
        val admission =
            coordinator.submit(request) { result ->
                val unknown = result.unconfirmed != null
                history.finish(
                    attempt,
                    if (unknown) "RESULT_UNKNOWN" else result.state.name,
                    null,
                    if (unknown) "이미 전송한 동작의 반영 여부를 확인하지 못했습니다. 취소·복원 완료가 아닙니다."
                    else "확인된 성공 ${result.succeeded.size} · 미실행 ${result.notRun.size} · 복원 미지원",
                )
                complete(result)
            }
        when (admission) {
            is NavAdmission.Accepted -> Unit
            else -> {
                history.finish(
                    attempt,
                    when (admission) {
                        is NavAdmission.Busy -> "BUSY"
                        NavAdmission.Unavailable -> "UNAVAILABLE"
                        else -> "INCOMPLETE"
                    },
                    null,
                    "요청이 실행되지 않았습니다. 대기열·자동 재실행·복원은 제공하지 않습니다.",
                )
                token = previous
            }
        }
        return admission
    }

    fun close() = coordinator.close()
}
