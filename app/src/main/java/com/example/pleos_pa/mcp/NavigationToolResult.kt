package com.example.pleos_pa.mcp

import com.example.pleos_pa.contract.DestinationResult
import com.example.pleos_pa.contract.DestinationStatus
import com.example.pleos_pa.contract.NavigationToolEntry
import com.example.pleos_pa.contract.Place
import com.example.pleos_pa.contract.PlaceMatch
import com.example.pleos_pa.contract.PlaceMatchStatus
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Text and structuredContent of the navigation tools, both from one judged read or one failure.
 * Coordinates never appear: [DestinationResult] has none.
 */
object NavigationToolResult {
    private const val ORIGIN = "android"
    private const val RECEIPT_NOTE = "수신 시각은 앱이 콜백을 받은 시각이며 내비가 값을 계산한 시각이나 값의 최신성을 보장하지 않습니다."
    private const val ARRIVAL_NOTE =
        "추정 도착 시각은 차량 앱이 수신 시각에 남은 시간을 더해 계산한 값이며 교통 변화를 반영한 보장값이 아닙니다. 내비 화면 표시와 반올림이 다를 수 있습니다."
    private const val NO_GUESS = "확인하지 못한 값을 추정하지 마세요. 자동 재시도하지 않았으며, 필요하면 사용자가 새로 요청할 수 있습니다."
    private const val SIMULATION_NOTE = "시뮬레이션 결과이며 실제 차량 값이 아닙니다."

    private val FAILURE_HINTS =
        mapOf(
            "NAVI_NOT_CONNECTED" to "차량 앱이 아직 내비 서비스에 연결되지 않았거나 연결에 실패했습니다.",
            "PREVIOUS_RESPONSE_PENDING" to "이전 조회의 응답을 아직 받지 못해 새 조회를 보내지 않았습니다.",
            "BUSY" to "다른 내비 조회가 진행 중이라 이번 조회를 시작하지 않았습니다.",
        )

    fun of(
        tool: NavigationToolEntry,
        requestId: String,
        source: String,
        sdk: String,
        result: DestinationResult,
    ): CallToolResult {
        val lines = buildList {
            add("${tool.name} 결과 ${result.status}. 실행 환경 $source, $sdk.")
            if (result.status == DestinationStatus.NO_DESTINATION) {
                add("안내 중인 목적지가 없습니다. 남은 거리, 남은 시간, 추정 도착 시각은 없습니다.")
            } else {
                add("목적지 ${shown(result.name)}, 주소 ${shown(result.address)}.")
                add(
                    "남은 거리 ${shown(result.remainDistanceMeters, "m")}, " +
                        "남은 시간 ${shown(result.remainTimeSeconds, "초")}, " +
                        "추정 도착 ${shown(result.estimatedArrivalAt)}."
                )
            }
            add("콜백 수신 ${result.receivedAt}.")
            add(RECEIPT_NOTE)
            if (result.estimatedArrivalAt != null) add(ARRIVAL_NOTE)
            if (result.status == DestinationStatus.PARTIAL) {
                add("확인하지 못한 값: ${result.missing.joinToString(", ")}.")
                add(NO_GUESS)
            }
            if (source == "simulation") add(SIMULATION_NOTE)
        }
        val structured = buildJsonObject {
            put("ok", true)
            put("requestId", requestId)
            put("source", source)
            put("sdkVersion", sdk)
            put("status", result.status.name)
            put("receivedAt", result.receivedAt)
            if (result.status == DestinationStatus.NO_DESTINATION) {
                put("destination", JsonNull)
            } else {
                putJsonObject("destination") {
                    put("name", result.name)
                    put("address", result.address)
                }
            }
            put("remainDistanceMeters", result.remainDistanceMeters)
            put("remainTimeSeconds", result.remainTimeSeconds)
            put("estimatedArrivalAt", result.estimatedArrivalAt)
            putJsonArray("missing") { result.missing.forEach { add(it) } }
        }
        return result(lines, structured, isError = result.status == DestinationStatus.PARTIAL)
    }

    /** A read that produced no SDK value: a refusal, a stop, a timeout or a failed call. */
    fun failure(code: String, requestId: String): CallToolResult =
        result(
            buildList {
                add("확인하지 못했습니다 [$code, $ORIGIN].")
                FAILURE_HINTS[code]?.let(::add)
                add(NO_GUESS)
            },
            buildJsonObject {
                put("ok", false)
                put("origin", ORIGIN)
                put("code", code)
                put("requestId", requestId)
            },
            isError = true,
        )

    /** `find_navigation_place`. No coordinates, and never the whole list when nothing matched. */
    fun places(tool: NavigationToolEntry, requestId: String, match: PlaceMatch): CallToolResult {
        val lines = buildList {
            add("${tool.name} 결과 ${match.status}.")
            when (match.status) {
                PlaceMatchStatus.NO_MATCH -> add(NO_MATCH_NOTE)
                PlaceMatchStatus.SINGLE -> {
                    add("후보 1개: ${candidate(match.candidates.single())}.")
                    add("이 candidateId로 set_navigation_destination을 호출할 수 있습니다.")
                }
                PlaceMatchStatus.MULTIPLE -> {
                    add(
                        "후보 ${match.candidates.size}개: ${match.candidates.joinToString("; ", transform = ::candidate)}."
                    )
                    add("어느 장소인지 사용자에게 물어본 뒤 선택한 candidateId로 설정하세요. 임의로 고르지 마세요.")
                }
            }
        }
        val structured = buildJsonObject {
            put("ok", true)
            put("requestId", requestId)
            put("status", match.status.name)
            putJsonArray("candidates") {
                match.candidates.forEach { place ->
                    add(
                        buildJsonObject {
                            put("candidateId", place.key)
                            put("name", place.displayName)
                            put("address", place.address)
                        }
                    )
                }
            }
        }
        return result(lines, structured, isError = false)
    }

    /** `set_navigation_destination` confirmed: [status] is `APPLIED` or `ALREADY_SET`. */
    fun destinationSet(
        tool: NavigationToolEntry,
        requestId: String,
        source: String,
        sdk: String,
        status: String,
        place: Place,
        elapsedMillis: Long,
    ): CallToolResult {
        val lines = buildList {
            add("${tool.name} 결과 $status. 실행 환경 $source, $sdk.")
            if (status == "ALREADY_SET") {
                add("요청 전 조회에서 이미 목적지가 이 장소(${place.displayName})였습니다. 이번 호출로 바꾼 것은 없습니다.")
            } else {
                add(
                    "목적지 ${place.displayName}(${place.address}) 설정이 순정 내비에 반영된 것을 " +
                        "재조회 좌표로 확인했습니다. 확인까지 ${elapsedMillis}ms."
                )
            }
            add("남은 시간과 도착 예상은 get_navigation_destination으로 조회하세요.")
            if (source == "simulation") add(SIMULATION_NOTE)
        }
        val structured = buildJsonObject {
            put("ok", true)
            put("requestId", requestId)
            put("source", source)
            put("sdkVersion", sdk)
            put("status", status)
            putJsonObject("destination") {
                put("candidateId", place.key)
                put("name", place.displayName)
                put("address", place.address)
            }
            put("elapsedMs", elapsedMillis)
        }
        return result(lines, structured, isError = false)
    }

    /**
     * `set_navigation_destination` not confirmed. [sent] true means the request reached the SDK and
     * its effect is unknown: neither a success nor a failure.
     */
    fun notSet(code: String, requestId: String, sent: Boolean): CallToolResult =
        result(
            buildList {
                if (sent) {
                    add("목적지 설정 결과를 확인하지 못했습니다 [$code, $ORIGIN].")
                    add("요청은 순정 내비에 보냈지만 반영을 확인하지 못했습니다. 성공이나 실패로 단정하지 마세요.")
                    add(
                        "자동으로 다시 실행하지 않았습니다. 다시 호출하지 말고 get_navigation_destination으로 현재 목적지를 확인하세요."
                    )
                } else {
                    add("목적지를 설정하지 않았습니다 [$code, $ORIGIN].")
                    (SET_HINTS[code] ?: FAILURE_HINTS[code])?.let(::add)
                    add("순정 내비는 바뀌지 않았습니다. 자동 재시도하지 않았으며, 필요하면 사용자가 새로 요청할 수 있습니다.")
                }
            },
            buildJsonObject {
                put("ok", false)
                put("origin", ORIGIN)
                put("code", code)
                put("requestId", requestId)
                put("sent", sent)
            },
            isError = true,
        )

    private fun candidate(place: Place) =
        "${place.displayName} (${place.address}) [candidateId ${place.key}]"

    private const val NO_MATCH_NOTE =
        "고정 장소 목록에 일치하는 장소가 없습니다. 목록에 없는 장소는 목적지로 설정하지 않습니다. 목적지를 설정하지 말고 사용자에게 알리세요."

    private val SET_HINTS =
        mapOf(
            "UNKNOWN_CANDIDATE" to
                "find_navigation_place가 돌려준 candidateId가 아닙니다. 목록에 없는 장소는 목적지로 설정하지 않습니다.",
            "BUSY" to "다른 내비 변경 작업이 진행 중이라 이번 요청을 시작하지 않았습니다. 대기열에 넣지 않았습니다.",
            "BASELINE_UNAVAILABLE" to "요청 전 현재 목적지를 확인하지 못했습니다.",
        )

    /** Arguments outside the tool's schema: an error with no structuredContent. */
    fun invalidInput(tool: NavigationToolEntry, reason: String): CallToolResult =
        CallToolResult(
            content =
                listOf(
                    TextContent(
                        "Input validation error: Invalid arguments for tool ${tool.name}: $reason"
                    )
                ),
            isError = true,
        )

    private fun shown(value: String?) = value ?: "확인 못 함"

    private fun shown(value: Int?, unit: String) = value?.let { "$it$unit" } ?: "확인 못 함"

    private fun result(lines: List<String>, structured: JsonObject, isError: Boolean) =
        CallToolResult(
            content = listOf(TextContent(lines.joinToString("\n"))),
            isError = if (isError) true else null,
            structuredContent = structured,
        )
}
