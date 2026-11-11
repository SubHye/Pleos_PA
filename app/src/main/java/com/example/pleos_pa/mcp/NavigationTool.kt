package com.example.pleos_pa.mcp

import com.example.pleos_pa.contract.NavigationToolEntry
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One tool of the navigation catalog as the MCP endpoint offers it. */
interface NavigationTool {
    /** The catalog entry, or null when the tool is not adopted and must not be offered. */
    val entry: NavigationToolEntry?

    val inputSchema: ToolSchema

    /** Refuses arguments outside [inputSchema] before anything else happens. */
    suspend fun call(tool: NavigationToolEntry, arguments: JsonObject?): CallToolResult
}

/** The input of a tool that takes exactly one required string. */
internal class StringArgument(private val name: String, description: String) {
    val schema: ToolSchema =
        ToolSchema(
            schema = JSON_SCHEMA_2020_12,
            properties =
                buildJsonObject {
                    putJsonObject(name) {
                        put("type", "string")
                        put("description", description)
                    }
                },
            required = listOf(name),
        )

    /** The value, or why the arguments are refused. The reason never repeats a value. */
    fun read(arguments: JsonObject?): Read {
        val args = arguments ?: JsonObject(emptyMap())
        args.keys
            .firstOrNull { it != name }
            ?.let {
                return Read.Refused("Unrecognized key: \"$it\"")
            }
        val value = args[name] ?: return Read.Refused("$name: Required")
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString) return Read.Refused("$name: Expected string")
        return Read.Accepted(primitive.content)
    }

    sealed interface Read {
        data class Accepted(val value: String) : Read

        data class Refused(val reason: String) : Read
    }

    companion object {
        const val JSON_SCHEMA_2020_12 = "https://json-schema.org/draft/2020-12/schema"
    }
}
