package com.example.pleos_pa.contract

import kotlin.math.abs

/**
 * Whether a destination read back from the stock navigation app is a listed place. Only the
 * coordinates decide: the stock app renames places and answers a district-level address, so those
 * prove nothing about the position (COM-36). Pure.
 */
object DestinationMatch {
    fun matches(place: Place, fields: DestinationFields, toleranceDegrees: Double): Boolean =
        abs(fields.latitude - place.latitude) <= toleranceDegrees &&
            abs(fields.longitude - place.longitude) <= toleranceDegrees

    /** Whether two reads show the same destination, by coordinates alone. */
    fun same(a: DestinationFields, b: DestinationFields, toleranceDegrees: Double): Boolean =
        abs(a.latitude - b.latitude) <= toleranceDegrees &&
            abs(a.longitude - b.longitude) <= toleranceDegrees
}
