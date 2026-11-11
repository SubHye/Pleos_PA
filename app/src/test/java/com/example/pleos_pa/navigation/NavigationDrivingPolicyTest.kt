package com.example.pleos_pa.navigation

import org.junit.Assert.*
import org.junit.Test

class NavigationDrivingPolicyTest {
    @Test
    fun absentOrUnverifiedPolicyNeverPermitsAChange() {
        assertEquals(
            DestinationSetter.DRIVING_STATE_UNKNOWN,
            navigationRestrictionCode(null, null, true),
        )
        assertEquals(
            DestinationSetter.DRIVING_STATE_UNKNOWN,
            navigationRestrictionCode(false, 0, false),
        )
    }

    @Test
    fun anyRestrictionOrDoBlocksEvenWithDriverMapping() {
        for (flags in listOf(0, 1, 16, 64, 80, 511, -1)) {
            assertEquals(
                DestinationSetter.DRIVING_RESTRICTED,
                navigationRestrictionCode(true, flags, true),
            )
            if (flags != 0)
                assertEquals(
                    DestinationSetter.DRIVING_RESTRICTED,
                    navigationRestrictionCode(false, flags, true),
                )
        }
    }

    @Test
    fun onlyCurrentUnrestrictedDriverPolicyPermitsHandoff() {
        assertNull(navigationRestrictionCode(false, 0, true))
    }
}
