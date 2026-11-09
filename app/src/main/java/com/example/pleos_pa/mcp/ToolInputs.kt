package com.example.pleos_pa.mcp

import com.example.pleos_pa.contract.ReadSelection
import com.example.pleos_pa.contract.ToolEntry
import com.example.pleos_pa.contract.VehicleReadCatalog
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The input side of one tool, ported from `toolInputSchema` in `mcp/src/tool-result.ts`. Legacy
 * tools take no argument. Others may narrow by `properties`, and by `areas` only where a property
 * of the tool has areas. The schema and [select] accept the same arguments.
 */
class ToolInputs(private val catalog: VehicleReadCatalog, private val tool: ToolEntry) {
    private val ids: List<String> = catalog.propertiesOf(tool.query).toList()
    private val vocabulary: Set<String> = catalog.areaVocabularyOf(tool.query)
    private val keys: Set<String> =
        when {
            tool.legacy -> emptySet()
            vocabulary.isEmpty() -> setOf(PROPERTIES)
            else -> setOf(PROPERTIES, AREAS)
        }

    /**
     * The JSON Schema clients see. The SDK's [ToolSchema] has no `additionalProperties`, so unlike
     * Node the listed schema does not say `false` there; [select] still refuses unknown keys.
     */
    val schema: ToolSchema =
        ToolSchema(
            schema = JSON_SCHEMA_2020_12,
            properties =
                buildJsonObject {
                    if (PROPERTIES in keys) {
                        putJsonObject(PROPERTIES) {
                            put("description", "읽을 속성. 생략하면 이 도구의 모든 속성을 읽습니다.")
                            put("minItems", 1)
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "string")
                                putJsonArray("enum") { ids.forEach { add(it) } }
                            }
                        }
                    }
                    if (AREAS in keys) {
                        putJsonObject(AREAS) {
                            put("description", areasDescription())
                            put("minItems", 1)
                            put("type", "array")
                            putJsonObject("items") { put("type", "string") }
                        }
                    }
                },
        )

    /** The selection the arguments ask for, or why they are refused. */
    fun select(arguments: JsonObject?): Selected {
        val args = arguments ?: JsonObject(emptyMap())
        args.keys
            .firstOrNull { it !in keys }
            ?.let {
                return Selected.Refused("Unrecognized key: \"$it\"")
            }
        val properties =
            args[PROPERTIES]?.let { value ->
                strings(value) { it in ids }
                    ?: return Selected.Refused("$PROPERTIES: not a list of distinct properties")
            } ?: emptySet()
        val areas =
            args[AREAS]?.let { value ->
                strings(value) { catalog.isAreaOf(it, vocabulary) }
                    ?: return Selected.Refused("$AREAS: not a list of distinct areas")
            } ?: emptySet()
        return Selected.Accepted(ReadSelection(properties, areas))
    }

    private fun strings(value: Any, allowed: (String) -> Boolean): Set<String>? {
        val array = value as? JsonArray ?: return null
        if (array.isEmpty()) return null
        val items = array.map { element ->
            val primitive = element as? JsonPrimitive
            if (primitive == null || !primitive.isString) return null
            primitive.content
        }
        if (items.distinct().size != items.size || !items.all(allowed)) return null
        return items.toSet()
    }

    private fun areasDescription(): String {
        val (first, second) = vocabulary.take(2).let { it[0] to it.getOrElse(1) { "undefined" } }
        return "읽을 영역. 값은 ${vocabulary.joinToString(", ")} 중 하나이고, 묶음 영역은 $first+${second}처럼 " +
            "차량이 보고한 묶음 그대로 +로 잇습니다. 묶음의 일부만 고르면 AREA_UNSUPPORTED가 될 수 있으니, " +
            "차량이 보고하는 영역을 모르면 생략하세요."
    }

    sealed interface Selected {
        data class Accepted(val selection: ReadSelection) : Selected

        /** [reason] names the first problem only, as diagnostics for the client. */
        data class Refused(val reason: String) : Selected
    }

    private companion object {
        const val PROPERTIES = "properties"
        const val AREAS = "areas"
        const val JSON_SCHEMA_2020_12 = "https://json-schema.org/draft/2020-12/schema"
    }
}
