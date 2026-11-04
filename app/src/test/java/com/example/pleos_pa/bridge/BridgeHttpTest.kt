package com.example.pleos_pa.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeHttpTest {
    private val http = BridgeHttp("/v3/vehicle-read", "127.0.0.1:18765", 4096)
    private val line = "POST /v3/vehicle-read HTTP/1.1"
    private val host = "Host: 127.0.0.1:18765"
    private val type = "Content-Type: application/json"
    private val length = "Content-Length: 10"

    private fun head(vararg lines: String) = lines.joinToString("\r\n")

    private fun statusOf(head: String): Int =
        when (val check = http.checkHead(head)) {
            is HeadCheck.Accepted -> 200
            is HeadCheck.Rejected -> check.reply.status
        }

    @Test
    fun theOneExactShapeIsAccepted() {
        assertEquals(HeadCheck.Accepted(10), http.checkHead(head(line, host, type, length)))
    }

    @Test
    fun aQuotedCharsetIsAccepted() {
        val quoted = "Content-Type: application/json; charset=\"utf-8\""
        assertEquals(HeadCheck.Accepted(10), http.checkHead(head(line, host, quoted, length)))
    }

    @Test
    fun ambiguousFramingIsRejected() {
        val rejected =
            listOf(
                head(line, host, type, length, "Transfer-Encoding: chunked"),
                head(line, host, type, length, "Content-Encoding: gzip"),
                head(line, host, type, length, "Expect: 100-continue"),
                head(line, host, type, length, "Upgrade: websocket"),
                head(line, host, type, length, "Content-Length: 10"),
                head(line, host, type, type, length),
                head(line, host, type),
                head(line, host, type, "Content-Length: ten"),
                head(line, host, type, "Content-Length: -1"),
                head(line, host, type, "Content-Length:"),
                head(line, host, type, "Content-Length: 0"),
                head(line, host, type, length, "Bad Header: x"),
                head(line, host, type, length, ": no name"),
                head(line, host, type, length, "X-Note: a\u0001b"),
                head("POST /v2/vehicle-read HTTP/1.1", host, type, length),
                head("POST /v3/vehicle-read HTTP/1.0", host, type, length),
                head("GET /v3/vehicle-read HTTP/1.1", host, type, length),
            )
        for (shape in rejected) assertEquals(shape, 400, statusOf(shape))
    }

    @Test
    fun browserStyleRequestsAreForbidden() {
        val foreign =
            listOf(
                head(line, type, length),
                head(line, "Host: localhost:18765", type, length),
                head(line, host, host, type, length),
                head(line, host, type, length, "Origin: null"),
            )
        for (shape in foreign) assertEquals(shape, 403, statusOf(shape))
    }

    @Test
    fun otherMediaTypesAreRefused() {
        val others =
            listOf(
                head(line, host, length),
                head(line, host, "Content-Type: text/plain", length),
                head(line, host, "Content-Type: application/json; charset=latin1", length),
            )
        for (shape in others) assertEquals(shape, 415, statusOf(shape))
    }

    @Test
    fun theSizeLimitIsExact() {
        assertEquals(
            HeadCheck.Accepted(4096),
            http.checkHead(head(line, host, type, "Content-Length: 4096")),
        )
        assertEquals(413, statusOf(head(line, host, type, "Content-Length: 4097")))
        assertEquals(413, statusOf(head(line, host, type, "Content-Length: 99999999999")))
    }

    @Test
    fun rejectionsNeverEchoTheRequest() {
        val secret = "Authorization: Bearer token-value"
        val check = http.checkHead(head(line, host, type, length, secret, "Origin: x"))
        assertEquals(
            """{"ok":false,"code":"REQUEST_FORBIDDEN"}""",
            (check as HeadCheck.Rejected).reply.json,
        )
    }
}
