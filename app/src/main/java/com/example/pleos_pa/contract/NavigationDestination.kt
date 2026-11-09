package com.example.pleos_pa.contract

import java.time.Instant

/**
 * The fields of one NaviHelper `DestinationInfo` this contract uses, in SDK units: metres and
 * seconds. Coordinates only tell "no destination" apart and never reach a result.
 */
data class DestinationFields(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val remainDistance: Int,
    val remainTime: Int,
)

enum class DestinationStatus {
    COMPLETE,
    PARTIAL,
    NO_DESTINATION,
}

/**
 * One judged destination read (`contracts/navigation/CONTRACT.md`). [missing] names the result
 * fields that are null although guidance is on.
 */
data class DestinationResult(
    val status: DestinationStatus,
    val receivedAt: String,
    val name: String?,
    val address: String?,
    val remainDistanceMeters: Int?,
    val remainTimeSeconds: Int?,
    val estimatedArrivalAt: String?,
    val missing: List<String>,
) {
    init {
        require((remainTimeSeconds == null) == (estimatedArrivalAt == null))
        if (status == DestinationStatus.NO_DESTINATION) {
            require(name == null && address == null && remainDistanceMeters == null)
            require(remainTimeSeconds == null && missing.isEmpty())
        } else {
            require((status == DestinationStatus.COMPLETE) == missing.isEmpty())
        }
    }
}

/** Pure: no SDK, no Android, no clock. */
object NavigationDestination {
    const val NAME = "name"
    const val ADDRESS = "address"
    const val REMAIN_DISTANCE = "remainDistanceMeters"
    const val REMAIN_TIME = "remainTimeSeconds"
    const val ESTIMATED_ARRIVAL = "estimatedArrivalAt"

    /**
     * An empty name at (0, 0) is "no destination", as observed on the Pleos emulator (COM-8). A
     * zero or negative time or distance is not a value: 0 seconds never means "arriving now".
     */
    fun judge(fields: DestinationFields, receivedAtMillis: Long): DestinationResult {
        val receivedAt = iso(receivedAtMillis)
        if (fields.name.isBlank() && fields.latitude == 0.0 && fields.longitude == 0.0) {
            return DestinationResult(
                DestinationStatus.NO_DESTINATION,
                receivedAt,
                null,
                null,
                null,
                null,
                null,
                emptyList(),
            )
        }
        val name = fields.name.takeUnless { it.isBlank() }
        val address = fields.address.takeUnless { it.isBlank() }
        val distance = fields.remainDistance.takeIf { it > 0 }
        val time = fields.remainTime.takeIf { it > 0 }
        val arrival = time?.let { iso(receivedAtMillis + it * 1_000L) }
        val missing = buildList {
            if (name == null) add(NAME)
            if (address == null) add(ADDRESS)
            if (distance == null) add(REMAIN_DISTANCE)
            if (time == null) {
                add(REMAIN_TIME)
                add(ESTIMATED_ARRIVAL)
            }
        }
        return DestinationResult(
            if (missing.isEmpty()) DestinationStatus.COMPLETE else DestinationStatus.PARTIAL,
            receivedAt,
            name,
            address,
            distance,
            time,
            arrival,
            missing,
        )
    }

    /** UTC ISO 8601, the form the vehicle contract uses for `receivedAt`. */
    fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()
}
