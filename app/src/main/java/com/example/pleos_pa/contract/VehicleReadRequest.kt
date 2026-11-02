package com.example.pleos_pa.contract

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A validated bridge request. Empty selectors mean the whole query. */
data class VehicleReadRequest(
    val requestId: String,
    val remainingMs: Long,
    val query: String,
    val properties: Set<String> = emptySet(),
    val areas: Set<String> = emptySet(),
) {
    companion object {
        private val REQUIRED = setOf("requestId", "remainingMs", "query")
        private val ALLOWED = REQUIRED + setOf("properties", "areas")
        private val UUID =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /**
         * Returns null for anything outside the contract. Reasons are never echoed to the caller.
         */
        fun parse(catalog: VehicleReadCatalog, text: String): VehicleReadRequest? {
            if (text.toByteArray().size > catalog.bridge.maxRequestBytes) return null
            val body =
                try {
                    JSONObject(text)
                } catch (_: JSONException) {
                    return null
                }
            val keys = body.keys().asSequence().toSet()
            if (!keys.containsAll(REQUIRED) || !ALLOWED.containsAll(keys)) return null

            val requestId = body.get("requestId") as? String ?: return null
            val remaining = body.get("remainingMs") as? Number ?: return null
            val query = body.get("query") as? String ?: return null
            if (!UUID.matches(requestId)) return null
            if (remaining !is Int && remaining !is Long) return null
            if (remaining.toLong() !in 1..catalog.bridge.deadlineMs) return null
            val tool = catalog.tools[query] ?: return null
            if (tool.legacy && (body.has("properties") || body.has("areas"))) return null

            val properties = selector(body, "properties") ?: return null
            val areas = selector(body, "areas") ?: return null
            if (!catalog.propertiesOf(query).containsAll(properties)) return null
            val vocabulary = catalog.areaVocabularyOf(query)
            if (!areas.all { catalog.isAreaOf(it, vocabulary) }) return null
            return VehicleReadRequest(requestId, remaining.toLong(), query, properties, areas)
        }

        private fun selector(body: JSONObject, name: String): Set<String>? {
            if (!body.has(name)) return emptySet()
            val values = body.get(name) as? JSONArray ?: return null
            if (values.length() == 0) return null
            val items = (0 until values.length()).map { values.get(it) as? String ?: return null }
            return if (items.distinct().size == items.size) items.toSet() else null
        }
    }
}
