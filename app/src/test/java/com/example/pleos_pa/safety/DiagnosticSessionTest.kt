package com.example.pleos_pa.safety

import com.example.pleos_pa.diagnostics.DiagnosticSession
import org.junit.Assert.*
import org.junit.Test

class DiagnosticSessionTest {
    @Test
    fun appAndNotificationStopCloseTheSameObserver() {
        val owner = Any()
        val reasons = mutableListOf<String>()
        DiagnosticSession.attachObserver(owner) { reasons.add(it) }
        try {
            DiagnosticSession.stop("app_stop")
            DiagnosticSession.stop("notification_stop")
            assertEquals(listOf("app_stop", "notification_stop"), reasons)
            assertFalse(DiagnosticSession.navigationAllowed())
        } finally {
            DiagnosticSession.detachObserver(owner)
        }
    }

    @Test
    fun aDestroyedOldActivityCannotDetachTheNewObserver() {
        val oldOwner = Any()
        val newOwner = Any()
        val oldReasons = mutableListOf<String>()
        val newReasons = mutableListOf<String>()
        DiagnosticSession.attachObserver(oldOwner) { oldReasons.add(it) }
        DiagnosticSession.attachObserver(newOwner) { newReasons.add(it) }
        try {
            assertEquals(listOf("observer_replaced"), oldReasons)
            DiagnosticSession.detachObserver(oldOwner)
            DiagnosticSession.stop("notification_stop")
            assertEquals(listOf("notification_stop"), newReasons)
        } finally {
            DiagnosticSession.detachObserver(newOwner)
        }
    }
}
