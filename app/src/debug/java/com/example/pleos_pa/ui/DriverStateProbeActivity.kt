package com.example.pleos_pa.ui

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.example.pleos_pa.connection.ConnectionStatus
import com.example.pleos_pa.connection.ConnectionStatusBoard
import java.util.UUID
import org.json.JSONObject

/** Shared production journal in a real process, seeded only with explicit synthetic test data. */
class DriverStateProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Also guarded in the debug Manifest. Never seed the user's ordinary application.
        if (packageName != "com.example.pleos_pa.com5") {
            finish()
            return
        }
        val nonce =
            intent.getStringExtra("nonce")?.let {
                try {
                    UUID.fromString(it).toString()
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        val operation = intent.getStringExtra("operation")
        if (nonce == null || operation !in setOf("seed", "inspect")) {
            finish()
            return
        }
        if (operation == "seed") {
            val token =
                DriverHistory.history.begin(nonce, "synthetic_process_probe", "모의 인자", "모의 시각")
            DriverHistory.history.finish(token, "COMPLETE", "simulation", "모의 값 · 실제 차량 값 아님")
        }
        val rows = DriverHistory.history.snapshot()
        val result =
            JSONObject()
                .put("nonce", nonce)
                .put("operation", operation)
                .put("source", "synthetic_process_probe")
                .put("processId", Process.myPid())
                .put("records", rows.size)
                .put("payloadChars", rows.sumOf { it.arguments.length + it.details.length })
                .put("connectionStopped", ConnectionStatusBoard.status is ConnectionStatus.Stopped)
        // Counts only: never record journal contents, original requests or device-wide logcat.
        Log.i("COM5_STATE", result.toString())
        finish()
    }
}
