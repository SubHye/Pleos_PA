package com.example.pleos_pa.vehicle

import ai.pleos.playground.vehicle.api.model.capability.SmartGlassConfig
import ai.pleos.playground.vehicle.constant.control.LightState
import com.example.pleos_pa.contract.ContractFixtures
import com.example.pleos_pa.contract.SmartGlassConfigValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the SDK call table to the catalog. Nothing here runs the SDK. */
class VehiclePropertiesTest {
    @Test
    fun everyCatalogPropertyHasAnSdkCall() {
        assertEquals(ContractFixtures.catalog.properties.keys, SDK_PROPERTIES.keys)
    }

    @Test
    fun valuesTakeTheContractsForm() {
        assertEquals(1, contractValue(1))
        assertEquals(true, contractValue(true))
        assertEquals(0.1, contractValue(0.1f))
        assertEquals("ON", contractValue(LightState.ON))
        assertEquals(SmartGlassConfigValue(2, 3), contractValue(SmartGlassConfig(2, 3)))
    }

    @Test
    fun aMissingOrNonFiniteValueIsNoValue() {
        assertNull(contractValue(null))
        assertNull(contractValue(Float.NaN))
        assertNull(contractValue(Float.POSITIVE_INFINITY))
        assertNull(contractValue(Double.NaN))
    }
}
