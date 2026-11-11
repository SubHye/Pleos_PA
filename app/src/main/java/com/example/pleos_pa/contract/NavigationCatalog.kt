package com.example.pleos_pa.contract

import org.json.JSONObject

/**
 * One navigation tool. Only `adopted` tools are offered to MCP clients. [kind] is `read` or
 * `change`; a change tool alters the stock navigation app. [sdkCall] and [permission] are null for
 * a tool that never reaches the SDK.
 */
data class NavigationToolEntry(
    val name: String,
    val title: String,
    val description: String,
    val kind: String,
    val sdkCall: String?,
    val permission: String?,
    val adoption: String,
)

/** The parsed `contracts/navigation/catalog.json`. */
class NavigationCatalog
private constructor(
    val sdk: String,
    val verifiedOn: String?,
    val deadlineMs: Long,
    val staleHoldMs: Long,
    /** What the read before a destination change may spend. */
    val baselineDeadlineMs: Long,
    /** How long a destination change waits for the destination read back to match. */
    val confirmTimeoutMs: Long,
    val confirmPollMs: Long,
    /**
     * How many waypoints the stock navigation app keeps; a further one is ignored without a word.
     */
    val maxWaypoints: Int,
    val matchToleranceDegrees: Double,
    val tools: List<NavigationToolEntry>,
) {
    fun tool(name: String): NavigationToolEntry? = tools.firstOrNull { it.name == name }

    companion object {
        const val ASSET = "navigation-catalog.json"
        const val KIND_READ = "read"
        const val KIND_CHANGE = "change"

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
                        kind = tool.getString("kind"),
                        sdkCall = tool.stringOrNull("sdkCall"),
                        permission = tool.stringOrNull("permission"),
                        adoption = tool.getString("adoption"),
                    )
                }
            return NavigationCatalog(
                sdk = root.getString("sdk"),
                verifiedOn = root.stringOrNull("verifiedOn"),
                deadlineMs = limits.getLong("deadlineMs"),
                staleHoldMs = limits.getLong("staleHoldMs"),
                baselineDeadlineMs = limits.getLong("baselineDeadlineMs"),
                confirmTimeoutMs = limits.getLong("confirmTimeoutMs"),
                confirmPollMs = limits.getLong("confirmPollMs"),
                maxWaypoints = limits.getInt("maxWaypoints"),
                matchToleranceDegrees = limits.getDouble("matchToleranceDegrees"),
                tools = tools,
            )
        }

        private fun JSONObject.stringOrNull(name: String): String? =
            if (isNull(name)) null else getString(name)
    }
}
