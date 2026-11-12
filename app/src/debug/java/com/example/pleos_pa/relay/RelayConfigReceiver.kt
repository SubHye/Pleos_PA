package com.example.pleos_pa.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Takes the relay address and car token of one COM-55 run from `adb shell am broadcast`. The
 * manifest lets only a holder of DUMP send to it, which the adb shell is and an installed app is
 * not. The values stay in memory, apply from the next connection start, and are gone when the
 * process ends. Only fixed words reach the log.
 */
class RelayConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_CLEAR, false)) {
            RelayExperiment.clear()
            Log.i(TAG, "RELAY_CONFIG=CLEARED")
            return
        }
        val config =
            RelayConfig.parse(intent.getStringExtra(EXTRA_URL), intent.getStringExtra(EXTRA_TOKEN))
        if (config == null) {
            Log.w(TAG, "RELAY_CONFIG=REFUSED")
            return
        }
        RelayExperiment.install(config) { state, code ->
            Log.i(TAG, "RELAY_LINK=$state code=$code")
        }
        Log.i(TAG, "RELAY_CONFIG=SET")
    }

    private companion object {
        const val TAG = "RelayExperiment"
        const val EXTRA_URL = "url"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_CLEAR = "clear"
    }
}
