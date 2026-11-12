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

/**
 * The block code for one change request. A missing guard blocks; a present guard answers for
 * itself, and its null means the change may run. Do not write this as `guard?.invoke() ?: UNKNOWN`:
 * that turns the guard's "may run" into a block.
 */
internal fun drivingBlockCode(guard: (() -> String?)?): String? =
    if (guard == null) DestinationSetter.DRIVING_STATE_UNKNOWN else guard()
