package com.example.pleos_pa.contract

import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Reads `contracts/navigation`. Gradle runs unit tests from the `app` directory. */
internal object NavigationFixtures {
    private val root = File("../contracts/navigation")

    val catalogText: String = File(root, "catalog.json").readText()
    val catalog: NavigationCatalog = NavigationCatalog.parse(catalogText)

    /** The catalog with every tool set to [adoption], for tests of what is offered. */
    fun catalogWith(adoption: String): NavigationCatalog {
        val root = JSONObject(catalogText)
        val tools = root.getJSONArray("tools")
        for (index in 0 until tools.length()) tools.getJSONObject(index).put("adoption", adoption)
        return NavigationCatalog.parse(root.toString())
    }

    fun cases(name: String): List<JSONObject> {
        val array = JSONArray(File(root, "fixtures/$name.json").readText())
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    fun text(name: String): String = File(root, "fixtures/$name.json").readText()

    fun fieldsOf(input: JSONObject) =
        DestinationFields(
            name = input.getString("name"),
            address = input.getString("address"),
            latitude = input.getDouble("latitude"),
            longitude = input.getDouble("longitude"),
            remainDistance = input.getInt("remainDistance"),
            remainTime = input.getInt("remainTime"),
        )

    fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

    /** The judged result of one `destination-cases.json` case. */
    fun judged(id: String): DestinationResult {
        val case = cases("destination-cases").single { it.getString("id") == id }
        return NavigationDestination.judge(
            fieldsOf(case.getJSONObject("input")),
            millis(case.getString("receivedAt")),
        )
    }
}
