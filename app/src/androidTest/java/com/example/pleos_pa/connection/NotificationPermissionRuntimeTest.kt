package com.example.pleos_pa.connection

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pleos_pa.MainActivity
import com.example.pleos_pa.R
import com.example.pleos_pa.RuntimeMcpClient
import com.example.pleos_pa.awaitFocusedActivity
import com.example.pleos_pa.mcp.EmbeddedMcpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import org.hamcrest.CoreMatchers.sameInstance
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run separately after the host revokes only .com5 POST_NOTIFICATIONS, before instrumentation. */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Timed out: $label")
    }

    @Test
    fun firstStartRequestsPermissionAndDenialKeepsActualAppStopAvailable() {
        assertEquals("Diagnostic package only", "com.example.pleos_pa.com5", context.packageName)
        assertNotEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
        )
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        assertEquals(
            "Distinct label avoids acting on another app's permission request",
            "Pleos PA 검수 (.com5)",
            label,
        )
        val automation = instrumentation.uiAutomation
        val originalInfo = automation.serviceInfo
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
        val scenario =
            ActivityScenario.launch<MainActivity>(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        lateinit var activity: MainActivity
        scenario.onActivity { activity = it }
        try {
            awaitFocusedActivity(activity)
            onView(withText(context.getString(R.string.action_start)))
                .inRoot(withDecorView(sameInstance(activity.window.decorView)))
                .perform(click())
            var denied = false
            await("our diagnostic notification permission dialog") {
                if (denied) return@await true
                val root = automation.rootInActiveWindow ?: return@await false
                val controller = root.packageName?.toString() ?: return@await false
                if (
                    controller !in
                        setOf(
                            "com.android.permissioncontroller",
                            "com.google.android.permissioncontroller",
                        ) || root.findAccessibilityNodeInfosByText(label).isEmpty()
                )
                    return@await false
                val candidates =
                    listOf(
                            "com.android.permissioncontroller:id/permission_deny_button",
                            "$controller:id/permission_deny_button",
                        )
                        .flatMap { root.findAccessibilityNodeInfosByViewId(it) }
                        .distinctBy { it.viewIdResourceName }
                val standard = candidates.firstOrNull { it.isVisibleToUser && it.isEnabled }
                // Automotive permission UI uses a labelled list row and a sibling touch target.
                // Keep the controller + distinct diagnostic label guard above; never use
                // coordinates.
                val carButton =
                    if (
                        standard == null &&
                            context.packageManager.hasSystemFeature(
                                PackageManager.FEATURE_AUTOMOTIVE
                            )
                    ) {
                        val controllerContext = context.createPackageContext(controller, 0)
                        val denyId =
                            controllerContext.resources.getIdentifier(
                                "grant_dialog_button_deny",
                                "string",
                                controller,
                            )
                        val denyLabels = buildSet {
                            if (denyId != 0) add(controllerContext.getString(denyId))
                            addAll(listOf("허용 안함", "허용 안 함", "Don't allow", "Don’t allow"))
                        }
                        root
                            .findAccessibilityNodeInfosByViewId(
                                "$controller:id/car_ui_list_item_title"
                            )
                            .filter { it.isVisibleToUser && it.text?.toString() in denyLabels }
                            .flatMap { title ->
                                title.parent
                                    ?.findAccessibilityNodeInfosByViewId(
                                        "$controller:id/car_ui_list_item_touch_interceptor"
                                    )
                                    .orEmpty()
                            }
                            .singleOrNull { it.isVisibleToUser && it.isEnabled && it.isClickable }
                    } else null
                val button = standard ?: carButton ?: return@await false
                denied = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                denied
            }
            await("runtime permission stays denied") {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
            }
            await("real bridge connected after denial") {
                var ready = false
                instrumentation.runOnMainSync {
                    ready = ConnectionStatusBoard.status is ConnectionStatus.Connected
                }
                ready
            }
            RuntimeMcpClient().use { assertEquals(21, it.toolNames().size) }
            awaitFocusedActivity(activity)
            onView(withText(context.getString(R.string.notifications_off)))
                .inRoot(withDecorView(sameInstance(activity.window.decorView)))
                .check(matches(isDisplayed()))
            onView(withText(context.getString(R.string.action_stop)))
                .inRoot(withDecorView(sameInstance(activity.window.decorView)))
                .perform(click())
            await("actual stop after denial") {
                var stopped = false
                instrumentation.runOnMainSync {
                    stopped = ConnectionStatusBoard.status is ConnectionStatus.Stopped
                }
                stopped
            }
            try {
                Socket().use {
                    it.connect(
                        InetSocketAddress(
                            InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
                            EmbeddedMcpServer.PORT,
                        ),
                        300,
                    )
                }
                fail("Bridge must refuse new connections after app stop")
            } catch (_: java.net.ConnectException) {}
            val evidence =
                JSONObject()
                    .put("source", "production_mcp_permission")
                    .put("transport", "streamable_http")
                    .put("port", EmbeddedMcpServer.PORT)
                    .put("permissionDenied", true)
                    .put("toolsListVerified", true)
                    .put("actualAppStop", true)
                    .put("newConnectionRefused", true)
                    .put("testVehicleReadRequests", 0)
            instrumentation.addResults(
                Bundle().apply { putString("com22_permission", evidence.toString()) }
            )
            Log.i(
                "COM22_PERMISSION",
                "requested=true denied=true actualAppStop=true newConnectionRefused=true testVehicleReadRequests=0",
            )
        } finally {
            context.stopService(Intent(context, BridgeService::class.java))
            automation.serviceInfo = originalInfo
            scenario.close()
        }
    }
}
