package com.example.pleos_pa.contract

import java.time.Instant

/**
 * The fields of one NaviHelper `WaypointInfo` this contract uses. The stock navigation app answers
 * its coordinates as (0, 0) and `poiSubId` as null (COM-47), so neither is kept: a waypoint is told
 * apart by [poiId] and [name] only.
 */
data class WaypointFields(val name: String, val address: String, val poiId: String)

/**
 * Whether waypoints read back are a listed place, and whether two reads show the same waypoint.
 * Pure.
 */
object WaypointMatch {
    /**
     * A listed place that has a `poiId` is read back with that ID (and the stock app's own name).
     * One without is read back with the name that was sent (COM-52). Coordinates cannot decide.
     */
    fun isPlace(place: Place, waypoint: WaypointFields): Boolean =
        if (place.poiId.isNotEmpty()) waypoint.poiId == place.poiId
        else sameName(waypoint.name, place.displayName)

    /** The same waypoint in two reads: by `poiId` when both have one, otherwise by name. */
    fun same(a: WaypointFields, b: WaypointFields): Boolean =
        if (a.poiId.isNotEmpty() && b.poiId.isNotEmpty()) a.poiId == b.poiId
        else sameName(a.name, b.name)

    private fun sameName(a: String, b: String): Boolean {
        val wanted = PlaceCatalog.normalize(b)
        return wanted.isNotEmpty() && PlaceCatalog.normalize(a) == wanted
    }
}

enum class WaypointStatus {
    NO_WAYPOINTS,
    HAS_WAYPOINTS,
}

/**
 * One waypoint as a result shows it. No coordinates (NFR-05, and they read back as 0/0).
 * [candidateId] is the fixed-list key when the waypoint is a listed place, so a client can name it
 * to `remove_navigation_waypoint`; it is null for a waypoint set elsewhere, such as on the stock
 * screen.
 */
data class WaypointEntry(
    val order: Int,
    val name: String?,
    val address: String?,
    val poiId: String?,
    val candidateId: String?,
)

/** One judged waypoint read. [receivedAt] is when the app got the callback, UTC ISO 8601. */
data class WaypointResult(
    val status: WaypointStatus,
    val receivedAt: String,
    val waypoints: List<WaypointEntry>,
) {
    init {
        require((status == WaypointStatus.NO_WAYPOINTS) == waypoints.isEmpty())
    }
}

/** Pure: no SDK, no Android, no clock. */
object NavigationWaypoints {
    fun judge(
        waypoints: List<WaypointFields>,
        receivedAtMillis: Long,
        places: PlaceCatalog,
    ): WaypointResult =
        WaypointResult(
            status =
                if (waypoints.isEmpty()) WaypointStatus.NO_WAYPOINTS
                else WaypointStatus.HAS_WAYPOINTS,
            receivedAt = Instant.ofEpochMilli(receivedAtMillis).toString(),
            waypoints =
                waypoints.mapIndexed { index, waypoint ->
                    WaypointEntry(
                        order = index + 1,
                        name = waypoint.name.ifBlank { null },
                        address = waypoint.address.ifBlank { null },
                        poiId = waypoint.poiId.ifBlank { null },
                        candidateId =
                            places.places.firstOrNull { WaypointMatch.isPlace(it, waypoint) }?.key,
                    )
                },
        )
}
