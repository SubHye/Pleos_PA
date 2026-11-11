package com.example.pleos_pa

import android.app.Activity
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail

/** ActivityScenario RESUMED alone does not mean a window is laid out and receives touch. */
fun awaitFocusedActivity(activity: Activity) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val end = SystemClock.uptimeMillis() + 30_000
    while (SystemClock.uptimeMillis() < end) {
        var ready = false
        instrumentation.runOnMainSync {
            ready =
                activity.hasWindowFocus() &&
                    activity.window.decorView.isLaidOut &&
                    !activity.window.decorView.isLayoutRequested
        }
        if (ready) return
        Thread.sleep(100)
    }
    val owner = instrumentation.uiAutomation.rootInActiveWindow?.packageName
    fail("Diagnostic activity never gained a laid-out focused window; active package=$owner")
}
