package com.example.pleos_pa.ui

import org.junit.Assert.*
import org.junit.Test

class UiRestrictionsTest {
    @Test
    fun platformUxAloneCannotEnableAnUnverifiedDisplayPolicy() {
        assertFalse(UiRestrictions.observed(false, 0, 100).permitsHistory(100))
    }

    @Test
    fun unknownDoOrAnyRestrictionBlocksHistory() {
        assertFalse(UiRestrictions.UNKNOWN.permitsHistory(100))
        assertFalse(
            UiRestrictions.observed(true, 0, 100, displayPolicyVerified = true).permitsHistory(100)
        )
        for (flags in listOf(1, 8, 16, 64, 0x1ff, -1)) {
            assertFalse(
                UiRestrictions.observed(false, flags, 100, displayPolicyVerified = true)
                    .permitsHistory(100)
            )
        }
    }

    @Test
    fun displayPermissionExpiresAndBackwardsClockCannotExtendIt() {
        val display = UiRestrictions.observed(false, 0, 100, displayPolicyVerified = true)
        assertTrue(display.permitsHistory(100))
        assertTrue(display.permitsHistory(3_099))
        assertFalse(display.permitsHistory(3_100))
        assertFalse(display.permitsHistory(99))
    }

    @Test
    fun freshObservationCanRecoverUiButUnknownImmediatelyClosesIt() {
        var display = UiRestrictions.observed(false, 0, 100, displayPolicyVerified = true)
        assertTrue(display.permitsHistory(101))
        display = UiRestrictions.UNKNOWN
        assertFalse(display.permitsHistory(101))
        display = UiRestrictions.observed(false, 0, 102, displayPolicyVerified = true)
        assertTrue(display.permitsHistory(102))
    }
}
