package com.example.pleos_pa.bridge

class BridgeReply(val status: Int, val json: String) {
    fun toBytes(): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        val head =
            "HTTP/1.1 $status ${REASONS.getValue(status)}\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
        return head.toByteArray(Charsets.US_ASCII) + body
    }

    private companion object {
        val REASONS =
            mapOf(
                200 to "OK",
                400 to "Bad Request",
                403 to "Forbidden",
                409 to "Conflict",
                413 to "Content Too Large",
                415 to "Unsupported Media Type",
                503 to "Service Unavailable",
            )
    }
}

sealed interface HeadCheck {
    data class Accepted(val contentLength: Int) : HeadCheck

    data class Rejected(val reply: BridgeReply) : HeadCheck
}

/**
 * The one route's HTTP shape. Only one exact HTTP/1.1 form is accepted, and anything ambiguous is
 * rejected rather than resynchronized. Internal messages are never echoed. The Host and Origin
 * checks stop browser-style requests; they do not identify the calling process.
 */
class BridgeHttp(path: String, private val expectedHost: String, private val maxBodyBytes: Int) {
    private val requestLine = "POST $path HTTP/1.1"

    /** [head] is the request line and header lines, without the terminating blank line. */
    fun checkHead(head: String): HeadCheck {
        val lines = head.split("\r\n")
        if (lines.first() != requestLine) return HeadCheck.Rejected(invalid(400))
        val headers = mutableMapOf<String, MutableList<String>>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0 || !TOKEN_NAME.matches(line.substring(0, colon))) {
                return HeadCheck.Rejected(invalid(400))
            }
            val value = line.substring(colon + 1).trim(' ', '\t')
            if (value.any { (it < ' ' && it != '\t') || it.code == 0x7F || it.code > 0xFF }) {
                return HeadCheck.Rejected(invalid(400))
            }
            headers.getOrPut(line.substring(0, colon).lowercase()) { mutableListOf() }.add(value)
        }
        if (headers.keys.any { it in FORBIDDEN } || SINGLE.any { (headers[it]?.size ?: 0) > 1 }) {
            return HeadCheck.Rejected(invalid(400))
        }
        val length = headers["content-length"]?.single()
        if (length.isNullOrEmpty() || !length.all { it in '0'..'9' }) {
            return HeadCheck.Rejected(invalid(400))
        }
        if (headers["host"] != listOf(expectedHost) || "origin" in headers) {
            return HeadCheck.Rejected(forbidden())
        }
        if (headers["content-type"]?.single()?.let(JSON_TYPE::matches) != true) {
            return HeadCheck.Rejected(invalid(415))
        }
        val size = if (length.length > 7) Int.MAX_VALUE else length.toInt()
        if (size > maxBodyBytes) return HeadCheck.Rejected(invalid(413))
        if (size == 0) return HeadCheck.Rejected(invalid(400))
        return HeadCheck.Accepted(size)
    }

    companion object {
        private val TOKEN_NAME = Regex("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+")
        private val JSON_TYPE =
            Regex("""application/json(\s*;\s*charset="?utf-8"?)?""", RegexOption.IGNORE_CASE)
        private val FORBIDDEN = setOf("transfer-encoding", "content-encoding", "expect", "upgrade")
        private val SINGLE = setOf("content-length", "content-type")

        fun busy(requestId: String) = BridgeReply(409, admission(requestId, "BUSY"))

        fun notReady(requestId: String) = BridgeReply(503, admission(requestId, "APP_NOT_READY"))

        fun stopped(requestId: String) =
            BridgeReply(503, admission(requestId, "CONNECTION_STOPPED"))

        fun invalid(status: Int) = BridgeReply(status, """{"ok":false,"code":"INVALID_REQUEST"}""")

        fun forbidden() = BridgeReply(403, """{"ok":false,"code":"REQUEST_FORBIDDEN"}""")

        /** [requestId] passed the contract's UUID check, so it needs no escaping. */
        private fun admission(requestId: String, code: String) =
            """{"requestId":"$requestId","ok":false,"code":"$code"}"""
    }
}
