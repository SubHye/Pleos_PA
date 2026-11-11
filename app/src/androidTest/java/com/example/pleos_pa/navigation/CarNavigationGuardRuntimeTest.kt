package com.example.pleos_pa.navigation

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual public Car UX/driver mapping only. No navigation SDK, vehicle write or permission
 * adoption.
 */
@RunWith(AndroidJUnit4::class)
class CarNavigationGuardRuntimeTest {
    @Test
    fun readsActualExecutionPolicyWithoutChangingNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(
            "Separate diagnostic package required",
            "com.example.pleos_pa.com9guard",
            context.packageName,
        )
        val expected =
            InstrumentationRegistry.getArguments().getString("expectedDrivingPolicy")
                ?: error("Pass expectedDrivingPolicy=DRIVING_RESTRICTED or ALLOWED")
        require(expected in setOf("DRIVING_RESTRICTED", "ALLOWED"))
        val guard = CarNavigationGuard(context, Handler(Looper.getMainLooper()))
        var observed = "NOT_READ"
        try {
            instrumentation.runOnMainSync { guard.start() }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            do {
                instrumentation.runOnMainSync { observed = guard.blockCode() ?: "ALLOWED" }
                if (observed == expected) break
                SystemClock.sleep(100)
            } while (SystemClock.elapsedRealtime() < deadline)
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("com9DrivingPolicy", observed)
                    putString("source", "fresh_public_car_ux;driver_mapping_required_for_allow")
                    putString("scope", "Adapter read only; no navigation SDK call, no MCP write")
                },
            )
            assertEquals(
                "Actual service-side policy (UNKNOWN never counts as restricted)",
                expected,
                observed,
            )
        } finally {
            instrumentation.runOnMainSync { guard.close() }
        }
    }
}
