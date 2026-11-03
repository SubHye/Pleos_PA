package com.example.pleos_pa.contract

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the catalog to the Vehicle SDK classes on the test classpath. Nothing here runs the SDK. */
class CatalogCompletenessTest {
    private val root = JSONObject(ContractFixtures.catalogText)
    private val properties = objects(root.getJSONArray("properties"))
    private val excluded = objects(root.getJSONArray("excluded"))

    private val sdkReads: Map<String, java.lang.reflect.Method> =
        API_NAMES.flatMap { api ->
                Class.forName("ai.pleos.playground.vehicle.api.$api")
                    .declaredMethods
                    .filter { !it.isSynthetic && READ_PREFIXES.any(it.name::startsWith) }
                    .map { "$api.${it.name}/${it.parameterCount}" to it }
            }
            .toMap()

    @Test
    fun everySdkReadIsCatalogedOrExcluded() {
        val cataloged = (properties + excluded).map(::key).toSet()
        assertEquals(sdkReads.keys.sorted(), cataloged.sorted())
    }

    @Test
    fun noEntryIsBothCatalogedAndExcluded() {
        assertEquals(
            emptySet<String>(),
            properties.map(::key).intersect(excluded.map(::key).toSet()),
        )
    }

    @Test
    fun deprecatedReadsAreExcluded() {
        val deprecated =
            sdkReads.filterValues { it.isAnnotationPresent(Deprecated::class.java) }.keys
        val offered = properties.map(::key).toSet()
        assertEquals(emptySet<String>(), deprecated.intersect(offered))
    }

    @Test
    fun theVehicleIdentifierIsNotOffered() {
        assertTrue(properties.none { it.getString("method") == "getVINInfo" })
        assertTrue(excluded.any { it.getString("method") == "getVINInfo" })
    }

    @Test
    fun privatePermissionsAreNotOffered() {
        assertTrue(properties.none { it.getString("permission").startsWith("PRIVATE_") })
    }

    @Test
    fun nothingIsMarkedVerifiedWithoutEvidence() {
        assertTrue(properties.all { it.getString("verification") == "none" })
    }

    @Test
    fun toolsAreConsistent() {
        val catalog = ContractFixtures.catalog
        val legacy = catalog.tools.values.filter { it.legacy }.map { it.name }.toSet()
        assertEquals(
            setOf(
                "get_door_status",
                "get_window_status",
                "get_energy_level",
                "get_front_left_door_position",
            ),
            legacy,
        )
        for (property in catalog.properties.values) {
            assertTrue(property.id, property.query in catalog.tools)
        }
        for (tool in catalog.tools.values) {
            assertTrue(tool.query, catalog.propertiesOf(tool.query).isNotEmpty())
            assertEquals(tool.query, "get_${tool.query}", tool.name)
        }
    }

    @Test
    fun declaredEnumsAndAreasExist() {
        val catalog = ContractFixtures.catalog
        for (property in catalog.properties.values) {
            property.areaType?.let { assertTrue(property.id, it in catalog.areas) }
            if (property.valueType.startsWith("enum:")) {
                assertTrue(property.id, property.valueType.removePrefix("enum:") in catalog.enums)
            }
        }
    }

    private fun key(entry: JSONObject) =
        "${entry.getString("api")}.${entry.getString("method")}/${entry.getInt("arity")}"

    private fun objects(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }

    private companion object {
        val READ_PREFIXES = listOf("get", "is", "has")
        val API_NAMES =
            listOf(
                "Brake",
                "CarInfo",
                "Display",
                "Door",
                "DrivingMode",
                "EvBattery",
                "Fuel",
                "Gear",
                "HVAC",
                "Light",
                "Odometer",
                "Safety",
                "Seat",
                "SideMirror",
                "SteeringWheel",
                "Tire",
                "Toll",
                "TurnSignal",
                "Window",
                "Wiper",
            )
    }
}
