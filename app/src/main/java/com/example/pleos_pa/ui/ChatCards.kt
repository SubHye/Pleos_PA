package com.example.pleos_pa.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** One request as the screen shows it: what the AI asked for and how the app answered. */
data class ChatCard(
    val sequence: Long,
    val title: String,
    val kind: Kind,
    val phase: Phase,
    /** The answer line. A vehicle value appears only when the screen permits it. */
    val line: String,
    /** Receipt time as HH:mm, or empty when the record has no readable time. */
    val time: String,
) {
    enum class Kind {
        ENERGY,
        NAVIGATION,
        VEHICLE,
    }

    enum class Phase {
        WORKING,
        DONE,
        FAILED,
    }
}

/**
 * Turns a history record into a [ChatCard]. Only the catalog title, the end state and, when
 * permitted, the record's headline are used: never the request ID, the arguments or the details.
 */
class ChatCards(
    private val titles: Map<String, String>,
    private val navigation: Set<String>,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun of(record: RequestHistory.Record, showValues: Boolean): ChatCard {
        val title = titles[record.tool] ?: record.tool
        val navigates = record.tool in navigation
        val value =
            record.headline
                ?.takeIf { showValues }
                ?.let { shown ->
                    listOfNotNull(
                            shown,
                            "일부만 확인".takeIf { record.state == "PARTIAL" },
                            "모의 값".takeIf { record.source == "simulation" },
                        )
                        .joinToString(" · ")
                }
        val phase =
            when (record.state) {
                in RequestHistory.UNFINISHED -> ChatCard.Phase.WORKING
                "COMPLETE",
                "PARTIAL",
                "SUCCEEDED" -> ChatCard.Phase.DONE
                else -> ChatCard.Phase.FAILED
            }
        val line =
            when (phase) {
                ChatCard.Phase.WORKING -> "$title ${if (navigates) "처리 중…" else "읽는 중…"}"
                ChatCard.Phase.DONE -> value ?: "$title · ${done(record)}"
                ChatCard.Phase.FAILED -> "$title · ${failed(record.state)}"
            }
        return ChatCard(
            record.token.sequence,
            title,
            when {
                record.tool == "get_energy_level" -> ChatCard.Kind.ENERGY
                navigates -> ChatCard.Kind.NAVIGATION
                else -> ChatCard.Kind.VEHICLE
            },
            phase,
            line,
            time(record.receivedAt),
        )
    }

    private fun done(record: RequestHistory.Record): String =
        when {
            record.state == "PARTIAL" -> "일부만 확인"
            (record.items ?: 0) > 0 -> "${record.items}개 항목 확인"
            else -> "완료"
        }

    private fun failed(state: String): String =
        when (state) {
            "BUSY" -> "다른 요청 처리 중이라 실행 안 함"
            "BLOCKED" -> "주행 제한 또는 상태 미확인으로 실행 안 함"
            "REJECTED",
            "INCOMPLETE" -> "요청 형식 오류로 실행 안 함"
            "RESULT_UNKNOWN" -> "결과 불명 · 반영 여부 미확인"
            "CONNECTION_STOPPED",
            "UNAVAILABLE" -> "연결 중지로 실행 안 함"
            "APP_NOT_READY" -> "준비 안 됨"
            "TIMEOUT" -> "시간 초과"
            "CANCELLED" -> "취소됨 · 결과 미확인"
            "FAILED" -> "실패"
            else -> "결과 미확인"
        }

    private fun time(receivedAt: String): String =
        try {
            FORMAT.format(Instant.parse(receivedAt).atZone(zone))
        } catch (_: DateTimeParseException) {
            ""
        }

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
