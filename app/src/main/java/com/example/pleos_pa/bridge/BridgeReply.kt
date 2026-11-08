package com.example.pleos_pa.bridge

/**
 * The gateway's answer to one read, in the shape of the vehicle read contract: an HTTP-style status
 * and a JSON body. Since COM-12 nothing sends it over a socket; the embedded MCP server validates
 * it in process with VehicleReadValidator.
 */
class BridgeReply(val status: Int, val json: String)

/** The admission refusals the gateway gives without reading. */
object BridgeReplies {
    fun busy(requestId: String) = BridgeReply(409, admission(requestId, "BUSY"))

    fun notReady(requestId: String) = BridgeReply(503, admission(requestId, "APP_NOT_READY"))

    fun stopped(requestId: String) = BridgeReply(503, admission(requestId, "CONNECTION_STOPPED"))

    fun invalid(status: Int) = BridgeReply(status, """{"ok":false,"code":"INVALID_REQUEST"}""")

    /** [requestId] passed the contract's UUID check, so it needs no escaping. */
    private fun admission(requestId: String, code: String) =
        """{"requestId":"$requestId","ok":false,"code":"$code"}"""
}
