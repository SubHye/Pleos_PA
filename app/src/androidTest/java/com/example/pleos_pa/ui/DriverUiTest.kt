package com.example.pleos_pa.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android widgets with an explicitly fake UX source and values; no vehicle evidence. */
@RunWith(AndroidJUnit4::class)
class DriverUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun exercise(test: (DriverUiPreviewActivity) -> Unit) {
        val intent =
            Intent()
                .setClassName(
                    instrumentation.targetContext.packageName,
                    "com.example.pleos_pa.ui.DriverUiPreviewActivity",
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(intent) as DriverUiPreviewActivity
        instrumentation.waitForIdleSync()
        try {
            test(activity)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun views(root: View): List<View> =
        listOf(root) +
            if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) }
            else emptyList()

    private fun all(activity: DriverUiPreviewActivity) = views(activity.window.decorView)

    private fun click(activity: DriverUiPreviewActivity, text: String) {
        instrumentation.runOnMainSync {
            all(activity)
                .filterIsInstance<Button>()
                .single { it.text.toString() == text }
                .performClick()
        }
        instrumentation.waitForIdleSync()
    }

    private fun text(activity: DriverUiPreviewActivity): String {
        var result = ""
        instrumentation.runOnMainSync {
            result = all(activity).filterIsInstance<TextView>().joinToString("\n") { it.text }
        }
        return result
    }

    private fun selectEnergy(activity: DriverUiPreviewActivity) {
        instrumentation.runOnMainSync {
            all(activity)
                .filterIsInstance<Button>()
                .single { it.text.startsWith("get_energy_level ·") }
                .performClick()
        }
    }

    @Test
    fun restrictedAndUnknownModesRemoveDetailsWithoutRemovingStop() = exercise { activity ->
        click(activity, "시작")
        click(activity, "모의 기록 생성")
        selectEnergy(activity)
        assertTrue(text(activity).contains("fixture-energy-value"))
        var selectedRow: Button? = null
        instrumentation.runOnMainSync {
            selectedRow =
                all(activity).filterIsInstance<Button>().single {
                    it.text.startsWith("get_energy_level ·")
                }
        }
        Thread.sleep(1_100) // An unchanged UX refresh must not remove the selected row.
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertNotNull(selectedRow?.parent) }
        for (mode in listOf("모의: 규제 활성", "모의: 정보 상실")) {
            click(activity, mode)
            assertFalse(text(activity).contains("fixture-energy-value"))
            assertFalse(text(activity).contains("first-fixture"))
            instrumentation.runOnMainSync {
                assertTrue(
                    all(activity).filterIsInstance<Button>().any {
                        it.text.toString() == "중지" && it.visibility == View.VISIBLE && it.isEnabled
                    }
                )
                assertFalse(
                    all(activity)
                        .filterIsInstance<Button>()
                        .single { it.text.toString() == "이전 기록" }
                        .isEnabled
                )
            }
            // An off-screen view with VISIBLE set is insufficient: check touch reachability.
            onView(withText("중지")).check(matches(isDisplayed())).check(matches(isEnabled()))
        }
        onView(withText("중지")).perform(click())
        assertTrue(text(activity).contains("중지됨. AI가 차량 값을 읽을 수 없습니다."))
        click(activity, "모의: 제한 없음")
        selectEnergy(activity)
        assertTrue(text(activity).contains("fixture-energy-value"))
    }

    @Test
    fun stalePermissionHidesAnAlreadyOpenedDetail() = exercise { activity ->
        click(activity, "모의 기록 생성")
        selectEnergy(activity)
        click(activity, "모의: 갱신 중단")
        Thread.sleep(3_500)
        instrumentation.waitForIdleSync()
        assertFalse(text(activity).contains("fixture-energy-value"))
        assertTrue(text(activity).contains("최근 조회: 조회 완료"))
    }

    @Test
    fun identityUndoAndClearDoNotInventCapabilitiesOrRestoreValues() = exercise { activity ->
        assertTrue(text(activity).contains("연결된 AI: 알 수 없음"))
        instrumentation.runOnMainSync {
            assertFalse(
                all(activity)
                    .filterIsInstance<Button>()
                    .single { it.text.toString() == "내비 변경 되돌리기" }
                    .isEnabled
            )
        }
        click(activity, "모의 기록 생성")
        selectEnergy(activity)
        assertTrue(text(activity).contains("모의 값 · 실제 차량 값 아님"))
        click(activity, "메모리 기록 지우기")
        assertFalse(text(activity).contains("fixture-energy-value"))
        assertFalse(text(activity).contains("first-fixture"))
        assertTrue(text(activity).contains("아직 받은 요청이 없습니다."))
    }

    @Test
    fun workingHistoryLimitHasBoundedPayloadOnAndroid() {
        val start = SystemClock.elapsedRealtime()
        val journal = RequestHistory()
        val arguments = "x".repeat(8_000)
        val result = "z".repeat(32_000)
        repeat(2_000) {
            val token = journal.begin("$it", "synthetic_load", arguments, "synthetic_time")
            journal.finish(token, "COMPLETE", "simulation", result)
        }
        val rows = journal.snapshot()
        assertEquals(100, rows.size)
        assertEquals("1999", rows.first().requestId)
        assertTrue(
            rows.all {
                it.arguments.length <= 4096 && it.details.length <= RequestHistory.MAX_DETAILS
            }
        )
        val metrics =
            Bundle().apply {
                putString("scope", "synthetic Android journal; not a final Pleos UI/heap limit")
                putInt("retainedRecords", rows.size)
                putInt(
                    "retainedPayloadChars",
                    rows.sumOf { it.arguments.length + it.details.length },
                )
                putLong("elapsedMs", SystemClock.elapsedRealtime() - start)
                putLong(
                    "processJavaHeapUsedBytes",
                    Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                )
            }
        instrumentation.addResults(metrics)
        val json = JSONObject()
        for (key in metrics.keySet()) {
            @Suppress("DEPRECATION") json.put(key, metrics.get(key))
        }
        Log.i("COM5_METRICS", json.toString())
    }
}
