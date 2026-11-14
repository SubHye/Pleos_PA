package com.example.pleos_pa.ui

import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import com.example.pleos_pa.MainActivity
import com.example.pleos_pa.R
import com.example.pleos_pa.connection.ConnectionStatusBoard
import java.util.UUID
import org.json.JSONObject

/** Actual public Car UX reads and real service controls. Never injects a gear/UX value. */
class Com22InspectionActivity : MainActivity() {
    override val previewNotice = "COM-22 실제 플랫폼 관찰 · 주행 상태 주입 없음 · 상세 기록 기본 차단"
    private val sessionId by lazy {
        intent.getStringExtra("com22SessionId")?.let {
            try {
                UUID.fromString(it).toString()
            } catch (_: IllegalArgumentException) {
                null
            }
        } ?: UUID.randomUUID().toString()
    }
    private var listener: ((UiRestrictions) -> Unit)? = null
    private var platform: UiRestrictionSource? = null
    private var sequence = 0L

    private fun report(observation: CarUxObservation) {
        Log.i(
            "COM22_LIVE",
            JSONObject()
                .put("sessionId", sessionId)
                .put("sequence", ++sequence)
                .put("processId", Process.myPid())
                .put("elapsedMillis", observation.elapsedMillis)
                .put("event", observation.event)
                .put("origin", observation.origin)
                .put("displayId", observation.displayId)
                .put("requiresDo", observation.requiresDo ?: JSONObject.NULL)
                .put("activeRestrictions", observation.activeRestrictions ?: JSONObject.NULL)
                .put("cleanupCallsSucceeded", observation.cleanupCallsSucceeded ?: JSONObject.NULL)
                .put("maxStringLength", observation.maxStringLength ?: JSONObject.NULL)
                .put("maxContentItems", observation.maxContentItems ?: JSONObject.NULL)
                .put("maxContentDepth", observation.maxContentDepth ?: JSONObject.NULL)
                .put(
                    "automotive",
                    packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE),
                )
                .put("fingerprint", Build.FINGERPRINT)
                .put("driverDisplayVerified", false)
                .put("historyAllowed", false)
                .put("connectionStatus", ConnectionStatusBoard.status.javaClass.simpleName)
                .toString(),
        )
    }

    override fun createRestrictionSource(): UiRestrictionSource =
        object : UiRestrictionSource {
            override fun start(changed: (UiRestrictions) -> Unit) {
                stop()
                listener = changed
                changed(UiRestrictions.UNKNOWN)
                if (packageName != "com.example.pleos_pa.com5") {
                    report(
                        CarUxObservation(
                            "UNAVAILABLE",
                            "diagnostic_package_required",
                            -1,
                            SystemClock.elapsedRealtime(),
                        )
                    )
                    return
                }
                if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                    report(
                        CarUxObservation(
                            "UNAVAILABLE",
                            "not_automotive",
                            -1,
                            SystemClock.elapsedRealtime(),
                        )
                    )
                    return
                }
                try {
                    platform = CarUiRestrictionSource(this@Com22InspectionActivity, ::report)
                    platform?.start(changed)
                } catch (_: LinkageError) {
                    report(
                        CarUxObservation(
                            "UNAVAILABLE",
                            "car_library_missing",
                            -1,
                            SystemClock.elapsedRealtime(),
                        )
                    )
                }
            }

            override fun stop() {
                platform?.stop()
                platform = null
                listener = null
            }
        }

    override fun addPreviewControls(container: LinearLayout) {
        container.addView(
            Button(this).apply {
                setText(R.string.com22_disconnect_observer)
                setOnClickListener {
                    platform?.stop()
                    platform = null
                    listener?.invoke(UiRestrictions.UNKNOWN)
                    report(
                        CarUxObservation(
                            "MANUAL_DISCONNECT",
                            "local_observer_disconnect",
                            -1,
                            SystemClock.elapsedRealtime(),
                        )
                    )
                }
            }
        )
    }
}
