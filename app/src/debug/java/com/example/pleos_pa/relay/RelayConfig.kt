package com.example.pleos_pa.relay

import java.net.URI
import org.json.JSONObject

/**
 * Where the car dials and what it presents there (COM-55 experiment). Only a TLS relay, or a
 * cleartext one on this device's loopback reached through `adb reverse`, is accepted: the client
 * engine does not consult the platform's cleartext policy, so the rule is enforced here.
 */
class RelayConfig private constructor(val url: String, val token: String) {
    override fun toString() = "RelayConfig(redacted)"

    companion object {
        private val TOKEN_SHAPE = Regex("[A-Za-z0-9_-]{32,128}")

        fun parse(url: String?, token: String?): RelayConfig? {
            if (url == null || token == null || !TOKEN_SHAPE.matches(token)) return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val plain =
                uri.path == "/car" &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null &&
                    uri.rawUserInfo == null &&
                    !uri.host.isNullOrEmpty()
            val allowed =
                when (uri.scheme) {
                    "wss" -> true
                    "ws" -> uri.host == "127.0.0.1" && uri.port in 1..65535
                    else -> false
                }
            return if (plain && allowed) RelayConfig(url, token) else null
        }
    }
}

/** The frames between the relay and the car: one request, one response, matched by id. */
internal object RelayFrames {
    const val VERSION = 1
    private val ID_SHAPE = Regex("[0-9]{1,19}")
    private val PROTOCOL_VERSION_SHAPE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    class Request(val id: String, val protocolVersion: String?, val body: String)

    /** Null for anything that is not a well-formed request frame. */
    fun parseRequest(text: String): Request? {
        val frame = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (frame.optInt("v") != VERSION || frame.optString("t") != "req") return null
        val id = frame.optString("id")
        val body = frame.opt("body") as? String
        if (!ID_SHAPE.matches(id) || body == null) return null
        // Becomes a header of the local request, so only the dated form passes.
        val version = frame.optString("protocolVersion").takeIf(PROTOCOL_VERSION_SHAPE::matches)
        return Request(id, version, body)
    }

    fun response(id: String, status: Int, contentType: String?, body: String): String =
        JSONObject()
            .put("v", VERSION)
            .put("t", "res")
            .put("id", id)
            .put("status", status)
            .put("contentType", contentType ?: JSONObject.NULL)
            .put("body", body)
            .toString()

    fun failure(id: String, code: String): String =
        JSONObject().put("v", VERSION).put("t", "res").put("id", id).put("error", code).toString()
}
