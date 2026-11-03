package com.example.pleos_pa.contract

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class ItemStatus {
    OK,
    NO_VALUE,
    UNKNOWN_VALUE,
    CAPABILITY_FAILED,
    READ_UNAVAILABLE,
    AREA_UNSUPPORTED,
    SUPPORT_UNCONFIRMED,
    READ_FAILED,
    PERMISSION_DENIED,
    PROPERTY_UNSUPPORTED,
    DRIVING_RESTRICTED,
    SERVICE_UNAVAILABLE,
    NOT_READ,
}

enum class Outcome {
    COMPLETE,
    PARTIAL,
    FAILED,
    TIMEOUT,
    CANCELLED,
}

enum class FailureOrigin {
    ANDROID,
    PC,
}

data class ItemCapability(
    val access: String,
    val matchingAreas: Int?,
    val reportedAreas: String?,
    val minimum: Double?,
    val maximum: Double?,
)

data class SmartGlassConfigValue(val zoneCount: Int, val levelCount: Int)

data class VehicleReadItem(
    val property: String,
    val area: String?,
    val status: ItemStatus,
    val capability: ItemCapability? = null,
    /**
     * Boolean, Int, Double, enum name or [SmartGlassConfigValue]. Null when nothing was received.
     */
    val rawValue: Any? = null,
    val meaning: String? = null,
    val unit: String? = null,
    /** Callback receipt time, not a vehicle measurement time. */
    val receivedAt: String? = null,
    val errorType: String? = null,
    val errorCode: String? = null,
)

data class VehicleReadResponse(
    val requestId: String,
    val query: String,
    val outcome: Outcome,
    val source: String,
    val sdkVersion: String,
    val items: List<VehicleReadItem>,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("requestId", requestId)
            .put("query", query)
            .put("outcome", outcome.name)
            .put("source", source)
            .put("sdkVersion", sdkVersion)
            .put("items", JSONArray().also { array -> items.forEach { array.put(it.toJson()) } })

    private fun VehicleReadItem.toJson(): JSONObject =
        JSONObject()
            .put("property", property)
            .put("area", area.orJsonNull())
            .put("status", status.name)
            .put("capability", capability?.toJson().orJsonNull())
            .put("rawValue", rawJson().orJsonNull())
            .put("meaning", meaning.orJsonNull())
            .put("unit", unit.orJsonNull())
            .put("receivedAt", receivedAt.orJsonNull())
            .put("errorType", errorType.orJsonNull())
            .put("errorCode", errorCode.orJsonNull())

    private fun VehicleReadItem.rawJson(): Any? =
        when (val value = rawValue) {
            is SmartGlassConfigValue ->
                JSONObject().put("zoneCount", value.zoneCount).put("levelCount", value.levelCount)
            else -> value
        }

    private fun ItemCapability.toJson(): JSONObject =
        JSONObject()
            .put("access", access)
            .put("matchingAreas", matchingAreas.orJsonNull())
            .put("reportedAreas", reportedAreas.orJsonNull())
            .put("minimum", minimum.orJsonNull())
            .put("maximum", maximum.orJsonNull())

    private fun Any?.orJsonNull(): Any = this ?: JSONObject.NULL
}

sealed interface BridgeResult {
    data class Accepted(val response: VehicleReadResponse) : BridgeResult

    data class Failed(val code: String, val origin: FailureOrigin) : BridgeResult
}

/** What a request narrowed its answer to. Empty means no narrowing. */
data class ReadSelection(
    val properties: Set<String> = emptySet(),
    val areas: Set<String> = emptySet(),
)

/**
 * The same verdicts as `mcp/src/contract.ts`, over the same fixtures. Android runs it on its own
 * output so a contract violation fails here rather than at the Node validator.
 */
class VehicleReadValidator(private val catalog: VehicleReadCatalog) {
    fun validate(
        http: Int,
        text: String,
        requestId: String,
        query: String,
        selection: ReadSelection = ReadSelection(),
    ): BridgeResult {
        if (text.toByteArray().size > catalog.bridge.maxResponseBytes) return INVALID
        val body =
            try {
                JSONObject(text)
            } catch (_: JSONException) {
                return INVALID
            }
        return when (http) {
            200 ->
                try {
                    success(body, requestId, query, selection)?.let { BridgeResult.Accepted(it) }
                        ?: INVALID
                } catch (_: JSONException) {
                    INVALID
                }
            409 -> admission(body, requestId, setOf("BUSY"))
            503 -> admission(body, requestId, setOf("APP_NOT_READY", "CONNECTION_STOPPED"))
            400,
            413,
            415 -> rejection(body, "INVALID_REQUEST")
            403 -> rejection(body, "REQUEST_FORBIDDEN")
            else -> INVALID
        }
    }

    private fun admission(body: JSONObject, requestId: String, codes: Set<String>): BridgeResult {
        if (body.keySetOf() != setOf("requestId", "ok", "code")) return INVALID
        if (body.opt("requestId") != requestId || body.opt("ok") != false) return INVALID
        val code = body.opt("code") as? String ?: return INVALID
        return if (code in codes) BridgeResult.Failed(code, FailureOrigin.ANDROID) else INVALID
    }

    private fun rejection(body: JSONObject, code: String): BridgeResult {
        if (body.keySetOf() != setOf("ok", "code")) return INVALID
        val matches = body.opt("ok") == false && body.opt("code") == code
        return if (matches) BridgeResult.Failed(code, FailureOrigin.ANDROID) else INVALID
    }

    private fun success(
        body: JSONObject,
        requestId: String,
        query: String,
        selection: ReadSelection,
    ): VehicleReadResponse? {
        if (body.keySetOf() != RESPONSE_KEYS) return null
        val tool = catalog.tools[query] ?: return null
        if (body.opt("requestId") != requestId || body.opt("query") != query) return null
        if (body.opt("sdkVersion") != catalog.sdkVersion) return null
        val source = body.opt("source") as? String ?: return null
        if (source !in SOURCES) return null
        val outcome = (body.opt("outcome") as? String)?.let(::outcomeOf) ?: return null
        val array = body.opt("items") as? JSONArray ?: return null
        val items =
            (0 until array.length()).map { index ->
                val raw = array.opt(index) as? JSONObject ?: return null
                item(raw, query, selection) ?: return null
            }

        val keys = items.map { ItemKey(it.property, it.area) }
        if (keys.distinct().size != keys.size) return null
        val fixedProperties = tool.fixedItems.map { it.property }.toSet()
        if (keys.any { it.property in fixedProperties && it !in tool.fixedItems }) return null
        val unfinished = outcome == Outcome.TIMEOUT || outcome == Outcome.CANCELLED
        if (!(unfinished && items.isEmpty()) && !keys.containsAll(tool.fixedItems)) return null
        if (!unfinished) {
            if (items.isEmpty() || items.any { it.status == ItemStatus.NOT_READ }) return null
            val successes = items.count { it.status == ItemStatus.OK }
            val derived =
                when (successes) {
                    items.size -> Outcome.COMPLETE
                    0 -> Outcome.FAILED
                    else -> Outcome.PARTIAL
                }
            if (derived != outcome) return null
        }
        return VehicleReadResponse(requestId, query, outcome, source, catalog.sdkVersion, items)
    }

    private fun item(raw: JSONObject, query: String, selection: ReadSelection): VehicleReadItem? {
        if (raw.keySetOf() != ITEM_KEYS) return null
        val property = raw.opt("property") as? String ?: return null
        if (property !in catalog.propertiesOf(query)) return null
        if (selection.properties.isNotEmpty() && property !in selection.properties) return null
        val entry = catalog.properties[property] ?: return null

        val area = raw.optionalString("area") ?: return null
        val areaValue = area.value
        if (entry.areaType == null) {
            if (areaValue != null) return null
        } else {
            if (areaValue == null) return null
            if (!catalog.isAreaOf(areaValue, catalog.areas.getValue(entry.areaType))) return null
        }
        if (selection.areas.isNotEmpty() && areaValue !in selection.areas) return null

        val status = (raw.opt("status") as? String)?.let(::statusOf) ?: return null
        val capability =
            if (raw.isNull("capability")) {
                null
            } else {
                capability(raw.opt("capability") as? JSONObject ?: return null, entry)
                    ?: return null
            }
        val rawValue =
            if (raw.isNull("rawValue")) null else (rawValue(raw.opt("rawValue")) ?: return null)
        val meaning = (raw.optionalString("meaning") ?: return null).value
        val unit = (raw.optionalString("unit") ?: return null).value
        val receivedAt = (raw.optionalString("receivedAt") ?: return null).value
        if (receivedAt != null && !RECEIVED_AT.matches(receivedAt)) return null
        val errorType = (raw.optionalString("errorType") ?: return null).value
        if (errorType != null && !ERROR_TYPE.matches(errorType)) return null
        val errorCode = (raw.optionalString("errorCode") ?: return null).value

        val item =
            VehicleReadItem(
                property,
                areaValue,
                status,
                capability,
                rawValue,
                meaning,
                unit,
                receivedAt,
                errorType,
                errorCode,
            )
        return if (statusFits(item, entry)) item else null
    }

    private fun capability(raw: JSONObject, entry: PropertyEntry): ItemCapability? {
        if (raw.keySetOf() != CAPABILITY_KEYS) return null
        val access = raw.opt("access") as? String ?: return null
        val matching =
            if (raw.isNull("matchingAreas")) null
            else wholeNumber(raw.opt("matchingAreas")) ?: return null
        val reported = (raw.optionalString("reportedAreas") ?: return null).value
        val minimum =
            if (raw.isNull("minimum")) null
            else (raw.opt("minimum") as? Number ?: return null).toDouble()
        val maximum =
            if (raw.isNull("maximum")) null
            else (raw.opt("maximum") as? Number ?: return null).toDouble()
        if (minimum?.isFinite() == false || maximum?.isFinite() == false) return null
        if ((minimum == null) != (maximum == null)) return null
        if (minimum != null && maximum != null && minimum > maximum) return null
        if (entry.areaType == null && (matching != null || reported != null)) return null
        if (entry.areaType != null && (matching == null || reported == null)) return null
        return ItemCapability(access, matching, reported, minimum, maximum)
    }

    private fun rawValue(raw: Any?): Any? =
        when (raw) {
            is Boolean,
            is String -> raw
            is Number -> wholeNumber(raw) ?: raw.toDouble().takeIf { it.isFinite() }
            is JSONObject -> {
                val zones = wholeNumber(raw.opt("zoneCount"))
                val levels = wholeNumber(raw.opt("levelCount"))
                if (raw.keySetOf() == SMART_GLASS_KEYS && zones != null && levels != null) {
                    SmartGlassConfigValue(zones, levels)
                } else {
                    null
                }
            }
            else -> null
        }

    private fun statusFits(item: VehicleReadItem, entry: PropertyEntry): Boolean {
        val capability = item.capability
        val readable = capability != null && capability.access in READABLE
        val noValue =
            item.rawValue == null &&
                item.meaning == null &&
                item.unit == null &&
                item.receivedAt == null
        val noError = item.errorType == null && item.errorCode == null
        val typedCode =
            item.errorType != null && item.errorCode in (CODES_BY_STATUS.getValue(item.status))
        val received = item.receivedAt != null
        return when (item.status) {
            ItemStatus.OK ->
                readable &&
                    received &&
                    noError &&
                    (entry.areaType == null || capability?.matchingAreas == 1) &&
                    confirmed(item, entry, capability) &&
                    item.meaning == expectedMeaning(entry.id, item.rawValue) &&
                    item.unit == entry.unit
            ItemStatus.NO_VALUE ->
                capability != null &&
                    received &&
                    item.rawValue == null &&
                    item.meaning == null &&
                    item.unit == null &&
                    (noError || typedCode)
            ItemStatus.UNKNOWN_VALUE ->
                capability != null &&
                    received &&
                    item.rawValue != null &&
                    !confirmed(item, entry, capability) &&
                    item.meaning == null &&
                    item.unit == null &&
                    noError
            ItemStatus.CAPABILITY_FAILED -> capability == null && noValue && typedCode
            ItemStatus.READ_UNAVAILABLE -> capability != null && !readable && noValue && noError
            ItemStatus.AREA_UNSUPPORTED ->
                readable && noValue && (noError && capability?.matchingAreas == 0 || typedCode)
            ItemStatus.SUPPORT_UNCONFIRMED -> readable && noValue && noError
            ItemStatus.READ_FAILED -> readable && noValue && typedCode
            ItemStatus.PERMISSION_DENIED,
            ItemStatus.PROPERTY_UNSUPPORTED,
            ItemStatus.DRIVING_RESTRICTED,
            ItemStatus.SERVICE_UNAVAILABLE -> noValue && typedCode
            ItemStatus.NOT_READ -> noValue && noError
        }
    }

    /** Whether the value's type, range and unit are confirmed for this property. */
    private fun confirmed(
        item: VehicleReadItem,
        entry: PropertyEntry,
        capability: ItemCapability?,
    ): Boolean {
        val raw = item.rawValue
        val typed = isValueType(raw, entry.valueType) && withinBounds(raw, capability)
        return when (entry.id) {
            "DOOR_POSITION" ->
                capability?.minimum == 0.0 && capability.maximum == 1.0 && (raw == 0 || raw == 1)
            "WINDOW_POSITION" -> typed && capability?.minimum != null
            "FUEL_PERCENT",
            "EV_BATTERY_PERCENT" -> typed && raw is Int && raw in 0..100
            else -> typed
        }
    }

    /** A value outside the range capability reports is not a confirmed reading. */
    private fun withinBounds(raw: Any?, capability: ItemCapability?): Boolean {
        val value = (raw as? Number)?.toDouble() ?: return true
        val minimum = capability?.minimum ?: return true
        val maximum = capability.maximum ?: return true
        return value in minimum..maximum
    }

    private fun isValueType(raw: Any?, valueType: String): Boolean =
        when {
            valueType == "int" -> raw is Int
            valueType == "float" -> raw is Int || raw is Double
            valueType == "boolean" -> raw is Boolean
            valueType == "smart_glass_config" -> raw is SmartGlassConfigValue
            valueType.startsWith(ENUM_PREFIX) ->
                raw is String && raw in catalog.enums.getValue(valueType.removePrefix(ENUM_PREFIX))
            else -> false
        }

    private fun expectedMeaning(property: String, raw: Any?): String? =
        when (property) {
            "DOOR_POSITION" -> if (raw == 0) "closed" else "open"
            "DOOR_LOCK" -> if (raw == true) "locked" else "unlocked"
            else -> null
        }

    /** A JSON field that is either null or a string; null means the field is neither. */
    private class OptionalString(val value: String?)

    private fun JSONObject.optionalString(name: String): OptionalString? =
        if (isNull(name)) OptionalString(null) else (opt(name) as? String)?.let(::OptionalString)

    private fun JSONObject.keySetOf(): Set<String> = keys().asSequence().toSet()

    private fun wholeNumber(raw: Any?): Int? {
        val number = raw as? Number ?: return null
        val value = number.toDouble()
        val fits = value >= Int.MIN_VALUE && value <= Int.MAX_VALUE
        return if (fits && value == Math.rint(value)) value.toInt() else null
    }

    private fun outcomeOf(name: String): Outcome? = Outcome.entries.firstOrNull { it.name == name }

    private fun statusOf(name: String): ItemStatus? =
        ItemStatus.entries.firstOrNull { it.name == name }

    private companion object {
        val INVALID = BridgeResult.Failed("INVALID_RESPONSE", FailureOrigin.PC)
        const val ENUM_PREFIX = "enum:"
        val SOURCES = setOf("pleos_emulator", "real_vehicle", "simulation")
        val READABLE = setOf("READ", "READ_WRITE")
        val RESPONSE_KEYS = setOf("requestId", "query", "outcome", "source", "sdkVersion", "items")
        val ITEM_KEYS =
            setOf(
                "property",
                "area",
                "status",
                "capability",
                "rawValue",
                "meaning",
                "unit",
                "receivedAt",
                "errorType",
                "errorCode",
            )
        val CAPABILITY_KEYS =
            setOf("access", "matchingAreas", "reportedAreas", "minimum", "maximum")
        val SMART_GLASS_KEYS = setOf("zoneCount", "levelCount")
        val RECEIVED_AT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z""")
        val ERROR_TYPE = Regex("""[A-Za-z_$][A-Za-z0-9_$]{0,99}""")
        val CODES_BY_STATUS: Map<ItemStatus, Set<String?>> =
            mapOf(
                ItemStatus.NO_VALUE to setOf("ERROR_EMPTY_RESULT"),
                ItemStatus.UNKNOWN_VALUE to emptySet(),
                ItemStatus.OK to emptySet(),
                ItemStatus.READ_UNAVAILABLE to emptySet(),
                ItemStatus.SUPPORT_UNCONFIRMED to emptySet(),
                ItemStatus.NOT_READ to emptySet(),
                ItemStatus.CAPABILITY_FAILED to
                    setOf(
                        null,
                        "ERROR_INTERNAL_SERVICE",
                        "ERROR_UNDEFINED",
                        "ERROR_NOT_SUPPORTED_AREA",
                        "ERROR_EMPTY_RESULT",
                    ),
                ItemStatus.READ_FAILED to setOf(null, "ERROR_INTERNAL_SERVICE", "ERROR_UNDEFINED"),
                ItemStatus.AREA_UNSUPPORTED to setOf(null, "ERROR_NOT_SUPPORTED_AREA"),
                ItemStatus.PERMISSION_DENIED to setOf(null, "ERROR_SECURITY_PERMISSION"),
                ItemStatus.PROPERTY_UNSUPPORTED to
                    setOf("ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_VALUE_TYPE"),
                ItemStatus.DRIVING_RESTRICTED to setOf("ERROR_DRIVING_RESTRICTED"),
                ItemStatus.SERVICE_UNAVAILABLE to setOf("ERROR_SERVICE_CONNECTION_FAILURE"),
            )
    }
}
