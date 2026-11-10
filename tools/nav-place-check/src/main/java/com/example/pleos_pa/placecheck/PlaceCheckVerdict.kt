package com.example.pleos_pa.placecheck

import kotlin.math.abs

/**
 * Whether the destination read back after `requestRoute` sits at the listed coordinates. Only the
 * coordinates decide: the stock app answers a district-level address (COM-34) and may rename the
 * place, so those are recorded but not judged. Pure.
 */
object PlaceCheckVerdict {
    /** About 0.1 m. `PlaceCatalogTest` checks recorded runs with the same bound. */
    const val TOLERANCE_DEGREES = 1e-6

    data class Result(val pass: Boolean, val deltaLatitude: Double, val deltaLongitude: Double)

    fun judge(
        listedLatitude: Double,
        listedLongitude: Double,
        readLatitude: Double,
        readLongitude: Double,
    ): Result {
        val dLat = readLatitude - listedLatitude
        val dLon = readLongitude - listedLongitude
        return Result(
            abs(dLat) <= TOLERANCE_DEGREES && abs(dLon) <= TOLERANCE_DEGREES,
            dLat,
            dLon,
        )
    }

    /** An empty name at (0, 0) is "no destination" (COM-8, `contracts/navigation/CONTRACT.md`). */
    fun isNoDestination(name: String, latitude: Double, longitude: Double): Boolean =
        name.isBlank() && latitude == 0.0 && longitude == 0.0
}
