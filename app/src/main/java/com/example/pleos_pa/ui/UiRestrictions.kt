package com.example.pleos_pa.ui

/**
 * Display-scoped UX permission, never evidence of parked gear or permission to mutate a vehicle.
 */
data class UiRestrictions(val unrestricted: Boolean, val receivedAtMillis: Long) {
    fun permitsHistory(nowMillis: Long): Boolean =
        unrestricted && nowMillis - receivedAtMillis in 0 until MAX_AGE_MS

    companion object {
        const val MAX_AGE_MS = 3_000L
        val UNKNOWN = UiRestrictions(false, Long.MIN_VALUE)

        fun observed(
            requiresDo: Boolean,
            activeRestrictions: Int,
            nowMillis: Long,
            displayPolicyVerified: Boolean = false,
        ) =
            UiRestrictions(
                displayPolicyVerified && !requiresDo && activeRestrictions == 0,
                nowMillis,
            )
    }
}

interface UiRestrictionSource {
    fun start(changed: (UiRestrictions) -> Unit)

    fun stop()
}
