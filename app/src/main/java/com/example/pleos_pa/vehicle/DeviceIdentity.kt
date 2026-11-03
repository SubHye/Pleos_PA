package com.example.pleos_pa.vehicle

/**
 * The contract's `source` for this device, or null when it cannot be stated. Mirrors the checks of
 * the emulator test skill's preflight. The app cannot see the adb serial that preflight uses to
 * tell an emulator apart, so the emulator's hardware name takes that place. There is no evidence
 * yet for recognizing a real vehicle, so `real_vehicle` is never reported.
 */
fun vehicleSourceOf(
    brand: String,
    fingerprint: String,
    hardware: String,
    automotive: Boolean,
): String? {
    val pleos = brand == "Pleos" && fingerprint.startsWith("Pleos/") && automotive
    return if (pleos && hardware in EMULATOR_HARDWARE) "pleos_emulator" else null
}

private val EMULATOR_HARDWARE = setOf("ranchu", "goldfish")
