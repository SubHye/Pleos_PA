package com.example.pleos_pa.contract

import org.json.JSONArray
import org.json.JSONObject

/** One fixed or read item of a query. [area] is null for properties without an area. */
data class ItemKey(val property: String, val area: String?)

data class ToolEntry(
    val name: String,
    val query: String,
    /** What MCP clients read about the tool. */
    val title: String,
    val description: String,
    val legacy: Boolean,
    /** `items` or `scalar`: the shape of the MCP result. */
    val resultForm: String,
    /** Only `adopted` tools are offered to MCP clients. */
    val adoption: String,
    val fixedItems: Set<ItemKey>,
)

data class PropertyEntry(
    val id: String,
    val query: String,
    val areaType: String?,
    val valueType: String,
    val unit: String?,
)

/** Limits of one read through the app's gateway. */
data class BridgeLimits(
    val maxRequestBytes: Int,
    val maxResponseBytes: Int,
    val deadlineMs: Long,
)

/** The parsed `contracts/vehicle-read/catalog.json`. Holds only what the wire rules need. */
class VehicleReadCatalog
private constructor(
    val sdkVersion: String,
    val bridge: BridgeLimits,
    val areas: Map<String, Set<String>>,
    val enums: Map<String, Set<String>>,
    val tools: Map<String, ToolEntry>,
    val properties: Map<String, PropertyEntry>,
) {
    /** Properties a [query] may answer with, whether fixed or chosen by capability. */
    fun propertiesOf(query: String): Set<String> {
        val fixed = tools.getValue(query).fixedItems.map { it.property }.toSet()
        return fixed + dynamicPropertiesOf(query)
    }

    /** Properties of [query] whose items follow the areas capability reports. */
    fun dynamicPropertiesOf(query: String): Set<String> {
        val fixed = tools.getValue(query).fixedItems.map { it.property }.toSet()
        return properties.values
            .filter { it.query == query && it.id !in fixed }
            .map { it.id }
            .toSet()
    }

    /** Every area part a selector of [query] may name. */
    fun areaVocabularyOf(query: String): Set<String> =
        propertiesOf(query)
            .mapNotNull { properties.getValue(it).areaType }
            .flatMap { areas.getValue(it) }
            .toSet()

    /** True when [area] is one vocabulary value or several distinct ones joined by `+`. */
    fun isAreaOf(area: String, vocabulary: Set<String>): Boolean {
        val parts = area.split('+')
        return parts.all { it in vocabulary } && parts.distinct().size == parts.size
    }

    companion object {
        fun parse(text: String): VehicleReadCatalog {
            val root = JSONObject(text)
            val bridge = root.getJSONObject("bridge")
            return VehicleReadCatalog(
                sdkVersion = root.getJSONObject("sdk").getString("version"),
                bridge =
                    BridgeLimits(
                        maxRequestBytes = bridge.getInt("maxRequestBytes"),
                        maxResponseBytes = bridge.getInt("maxResponseBytes"),
                        deadlineMs = bridge.getLong("deadlineMs"),
                    ),
                areas = stringSets(root.getJSONObject("areas")),
                enums = stringSets(root.getJSONObject("enums")),
                tools =
                    objects(root.getJSONArray("tools")).associate { tool ->
                        val query = tool.getString("query")
                        query to
                            ToolEntry(
                                name = tool.getString("name"),
                                query = query,
                                title = tool.getString("title"),
                                description = tool.getString("description"),
                                legacy = tool.getBoolean("legacy"),
                                resultForm = tool.getString("resultForm"),
                                adoption = tool.getString("adoption"),
                                fixedItems =
                                    objects(tool.getJSONArray("fixedItems"))
                                        .map {
                                            ItemKey(
                                                it.getString("property"),
                                                it.stringOrNull("area"),
                                            )
                                        }
                                        .toSet(),
                            )
                    },
                properties =
                    objects(root.getJSONArray("properties")).associate { property ->
                        val id = property.getString("id")
                        id to
                            PropertyEntry(
                                id = id,
                                query = property.getString("query"),
                                areaType = property.stringOrNull("areaType"),
                                valueType = property.getString("valueType"),
                                unit = property.stringOrNull("unit"),
                            )
                    },
            )
        }

        private fun stringSets(source: JSONObject): Map<String, Set<String>> =
            source.keys().asSequence().associateWith { key ->
                val values = source.getJSONArray(key)
                (0 until values.length()).map { values.getString(it) }.toSet()
            }

        private fun objects(source: JSONArray): List<JSONObject> =
            (0 until source.length()).map { source.getJSONObject(it) }
    }
}

internal fun JSONObject.stringOrNull(name: String): String? =
    if (isNull(name)) null else getString(name)
