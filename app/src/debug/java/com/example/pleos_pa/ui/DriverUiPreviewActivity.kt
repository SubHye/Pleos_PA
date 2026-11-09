package com.example.pleos_pa.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import com.example.pleos_pa.MainActivity
import com.example.pleos_pa.connection.ConnectionStatus

/** Debug-only, isolated UI fixture. Never calls a Vehicle SDK or populates the real journal. */
class DriverUiPreviewActivity : MainActivity() {
    override val history = RequestHistory()
    override val previewNotice = "모의 화면 검증 · 아래 기록은 실제 차량 값이 아닙니다."
    private val fixture = FixtureSource()
    private var previewConnection: ConnectionStatus = ConnectionStatus.Stopped

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only the synthetic diagnostic screen stays awake; no device setting is changed.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun startConnection() {
        previewConnection = ConnectionStatus.Connected
        renderConnection(previewConnection)
    }

    override fun stopConnection() {
        previewConnection = ConnectionStatus.Stopped
        renderConnection(previewConnection)
    }

    override fun renderConnection(current: ConnectionStatus) {
        super.renderConnection(previewConnection)
    }

    override fun createRestrictionSource(): UiRestrictionSource = fixture

    override fun addPreviewControls(container: LinearLayout) {
        for ((text, mode) in
            listOf("모의: 제한 없음" to 0, "모의: 규제 활성" to 1, "모의: 정보 상실" to 2, "모의: 갱신 중단" to 3)) {
            container.addView(
                Button(this).apply {
                    this.text = text
                    setOnClickListener { fixture.mode(mode) }
                }
            )
        }
        container.addView(
            Button(this).apply {
                text = "모의 기록 생성"
                setOnClickListener { seed() }
            }
        )
    }

    private fun seed() {
        history.clear()
        val first =
            history.begin(
                "first-fixture",
                "get_energy_level",
                "EV_BATTERY_PERCENT",
                "2026-11-04T00:00:00Z",
            )
        val second =
            history.begin(
                "second-fixture",
                "get_door_status",
                "DOOR_POSITION",
                "2026-11-04T00:00:01Z",
            )
        history.finish(second, "COMPLETE", "simulation", "DOOR_POSITION: OK · fixture-door-value")
        history.finish(
            first,
            "PARTIAL",
            "simulation",
            "EV_BATTERY_PERCENT: OK · fixture-energy-value 42 %\nFUEL_PERCENT: PROPERTY_UNSUPPORTED",
        )
    }

    private class FixtureSource : UiRestrictionSource {
        private val handler = Handler(Looper.getMainLooper())
        private var changed: ((UiRestrictions) -> Unit)? = null
        private var mode = 0
        private val refresh =
            object : Runnable {
                override fun run() {
                    emit()
                    if (changed != null) handler.postDelayed(this, 1_000)
                }
            }

        fun mode(next: Int) {
            mode = next
            emit()
        }

        private fun emit() {
            if (mode == 3) return // Let the actual UI freshness timer expire.
            changed?.invoke(
                if (mode == 2) UiRestrictions.UNKNOWN
                else
                    UiRestrictions.observed(
                        mode == 1,
                        if (mode == 1) 0x50 else 0,
                        SystemClock.elapsedRealtime(),
                        displayPolicyVerified = true,
                    )
            )
        }

        override fun start(changed: (UiRestrictions) -> Unit) {
            stop()
            this.changed = changed
            handler.post(refresh)
        }

        override fun stop() {
            handler.removeCallbacks(refresh)
            changed = null
        }
    }
}
