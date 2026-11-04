package com.example.pleos_pa.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceIdentityTest {
    private val fingerprint = "Pleos/sdk_car/emu:14/UE1A/1:userdebug/test-keys"

    @Test
    fun thePleosEmulatorIsNamed() {
        assertEquals("pleos_emulator", vehicleSourceOf("Pleos", fingerprint, "ranchu", true))
    }

    @Test
    fun aPleosDeviceThatIsNotAnEmulatorHasNoSource() {
        assertNull(vehicleSourceOf("Pleos", fingerprint, "vehicle_soc", true))
    }

    @Test
    fun anyOtherDeviceHasNoSource() {
        assertNull(vehicleSourceOf("google", fingerprint, "ranchu", true))
        assertNull(vehicleSourceOf("Pleos", "google/sdk/emu", "ranchu", true))
        assertNull(vehicleSourceOf("Pleos", fingerprint, "ranchu", false))
    }
}
