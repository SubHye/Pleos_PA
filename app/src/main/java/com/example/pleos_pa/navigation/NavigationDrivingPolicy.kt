package com.example.pleos_pa.navigation

/** A fresh public Car UX read and verified driver-main-display mapping are both required. */
internal fun navigationRestrictionCode(
    requiresDo: Boolean?,
    activeRestrictions: Int?,
    driverDisplayVerified: Boolean,
): String? =
    when {
        requiresDo == null || activeRestrictions == null -> DestinationSetter.DRIVING_STATE_UNKNOWN
        requiresDo || activeRestrictions != 0 -> DestinationSetter.DRIVING_RESTRICTED
        !driverDisplayVerified -> DestinationSetter.DRIVING_STATE_UNKNOWN
        else -> null
    }
