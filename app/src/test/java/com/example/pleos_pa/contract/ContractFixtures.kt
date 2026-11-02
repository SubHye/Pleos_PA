package com.example.pleos_pa.contract

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Reads the language-neutral contract files. Gradle runs unit tests from the `app` directory. */
internal object ContractFixtures {
    private val root = File("../contracts/vehicle-read")

    val catalogText: String = File(root, "catalog.json").readText()
    val catalog: VehicleReadCatalog = VehicleReadCatalog.parse(catalogText)

    fun cases(name: String): List<JSONObject> {
        val array = JSONArray(File(root, "fixtures/$name.json").readText())
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
}

/** Deep equality that treats 1 and 1.0 as the same JSON number. */
internal fun jsonEquivalent(left: Any?, right: Any?): Boolean =
    when {
        left is JSONObject && right is JSONObject -> {
            val keys = left.keys().asSequence().toSet()
            keys == right.keys().asSequence().toSet() &&
                keys.all { jsonEquivalent(left.get(it), right.get(it)) }
        }
        left is JSONArray && right is JSONArray ->
            left.length() == right.length() &&
                (0 until left.length()).all { jsonEquivalent(left.get(it), right.get(it)) }
        left is Number && right is Number -> left.toDouble() == right.toDouble()
        else -> left == right
    }

internal fun JSONObject.stringList(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return (0 until array.length()).map { array.getString(it) }
}
