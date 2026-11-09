package com.example.pleos_pa.contract

import org.json.JSONObject

/** One navigation tool. Only `adopted` tools are offered to MCP clients. */
data class NavigationToolEntry(
    val name: String,
    val title: String,
    val description: String,
    val sdkCall: String,
    val permission: String,
    val adoption: String,
)

/** The parsed `contracts/navigation/catalog.json`. */
class NavigationCatalog
private constructor(
    val sdk: String,
    val verifiedOn: String?,
    val deadlineMs: Long,
    val staleHoldMs: Long,
    val tools: List<NavigationToolEntry>,
) {
    fun tool(name: String): NavigationToolEntry? = tools.firstOrNull { it.name == name }

    companion object {
        const val ASSET = "navigation-catalog.json"

        fun parse(text: String): NavigationCatalog {
            val root = JSONObject(text)
            val limits = root.getJSONObject("limits")
            val array = root.getJSONArray("tools")
            val tools =
                (0 until array.length()).map {
                    val tool = array.getJSONObject(it)
                    NavigationToolEntry(
                        name = tool.getString("name"),
                        title = tool.getString("title"),
                        description = tool.getString("description"),
                        sdkCall = tool.getString("sdkCall"),
                        permission = tool.getString("permission"),
                        adoption = tool.getString("adoption"),
                    )
                }
            return NavigationCatalog(
                sdk = root.getString("sdk"),
                verifiedOn = if (root.isNull("verifiedOn")) null else root.getString("verifiedOn"),
                deadlineMs = limits.getLong("deadlineMs"),
                staleHoldMs = limits.getLong("staleHoldMs"),
                tools = tools,
            )
        }
    }
}
