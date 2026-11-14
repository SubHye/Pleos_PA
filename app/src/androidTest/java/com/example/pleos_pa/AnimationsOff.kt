package com.example.pleos_pa

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * Runs a device test with animators off and puts the device setting back afterwards. The screen
 * keeps a slow indicator ring moving while connected, and these tests check states, not motion.
 */
class AnimationsOff : ExternalResource() {
    private var previous: String? = null

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .executeShellCommand(command)
            )
            .use { it.readBytes().decodeToString() }

    override fun before() {
        previous = shell("settings get global $SETTING").trim()
        shell("settings put global $SETTING 0")
    }

    override fun after() {
        val value = previous?.takeIf { it.toFloatOrNull() != null }
        shell(
            if (value == null) "settings delete global $SETTING"
            else "settings put global $SETTING $value"
        )
    }

    private companion object {
        const val SETTING = "animator_duration_scale"
    }
}
