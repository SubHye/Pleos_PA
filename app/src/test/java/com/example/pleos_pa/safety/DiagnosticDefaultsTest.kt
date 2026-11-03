package com.example.pleos_pa.safety

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticDefaultsTest {
    @Test
    fun rawUnrestrictedEvidenceCannotEnableUnverifiedNavigation() {
        val boundary = ExecutionBoundary()
        boundary.resume("local-diagnostic")
        boundary.observeUx(ExecutionBoundary.UxState.PARKED_UNRESTRICTED)
        assertFalse(boundary.allows(ExecutionBoundary.Action.NAVIGATION_CHANGE))
        boundary.observeUx(ExecutionBoundary.UxState.UNKNOWN)
        boundary.stop()
        assertTrue(boundary.allows(ExecutionBoundary.Action.STOP))
        assertFalse(boundary.allows(ExecutionBoundary.Action.NAVIGATION_CHANGE))
    }
}
