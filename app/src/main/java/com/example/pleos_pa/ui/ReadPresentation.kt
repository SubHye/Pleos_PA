package com.example.pleos_pa.ui

import com.example.pleos_pa.contract.ItemStatus
import com.example.pleos_pa.contract.SmartGlassConfigValue
import com.example.pleos_pa.contract.VehicleReadRequest
import com.example.pleos_pa.contract.VehicleReadResponse
import java.time.Instant

/**
 * Receives only the already validated request/response; never the original body or exception text.
 */
interface ReadPresentation {
    fun begin(request: VehicleReadRequest): RequestHistory.Token?

    fun finish(token: RequestHistory.Token?, code: String, response: VehicleReadResponse?)

    object None : ReadPresentation {
        override fun begin(request: VehicleReadRequest): RequestHistory.Token? = null

        override fun finish(
            token: RequestHistory.Token?,
            code: String,
            response: VehicleReadResponse?,
        ) {}
    }
}

class HistoryPresentation(
    private val history: RequestHistory,
    private val toolName: (String) -> String,
    private val now: () -> Long = System::currentTimeMillis,
) : ReadPresentation {
    override fun begin(request: VehicleReadRequest): RequestHistory.Token =
        history.begin(
            request.requestId,
            toolName(request.query),
            "속성: ${request.properties.sorted().joinToString().ifEmpty { "도구 기본 선택" }}\n영역: ${request.areas.sorted().joinToString().ifEmpty { "도구 기본 선택" }}",
            Instant.ofEpochMilli(now()).toString(),
        )

    override fun finish(
        token: RequestHistory.Token?,
        code: String,
        response: VehicleReadResponse?,
    ) {
        if (token == null) return
        val details =
            response?.items?.joinToString("\n") { item ->
                buildString {
                    append(item.property)
                    item.area?.let { append(" / ").append(it) }
                    append(": ").append(item.status.name)
                    if (item.status == ItemStatus.OK) {
                        val value = item.rawValue
                        append(" · ")
                            .append(
                                if (value is SmartGlassConfigValue)
                                    "구역 ${value.zoneCount}, 단계 ${value.levelCount}"
                                else value.toString()
                            )
                        item.unit?.let { append(" ").append(it) }
                        item.meaning?.let { append(" (").append(it).append(")") }
                        item.capability?.minimum?.let { minimum ->
                            append(" [범위 ")
                                .append(minimum)
                                .append(" ~ ")
                                .append(item.capability.maximum)
                                .append("]")
                        }
                    }
                    item.receivedAt?.let { append(" · SDK 콜백 수신: ").append(it) }
                    item.errorCode?.let { append(" · ").append(it) }
                }
            } ?: "조회 결과를 확인하지 못했습니다."
        history.finish(
            token,
            code,
            response?.source,
            details,
            response?.let(::batteryHeadline),
            response?.items?.count { it.status == ItemStatus.OK },
        )
    }

    /** The one value the screen names by itself: a confirmed battery percentage. */
    private fun batteryHeadline(response: VehicleReadResponse): String? {
        if (response.query != "energy_level") return null
        val percent =
            response.items
                .firstOrNull { it.property == "EV_BATTERY_PERCENT" && it.status == ItemStatus.OK }
                ?.rawValue as? Number ?: return null
        val value = percent.toDouble()
        return "배터리 ${if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()}%"
    }
}

object DriverHistory {
    val history = RequestHistory()
}
