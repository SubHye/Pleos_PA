package com.example.pleos_pa.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RelayConfigTest {
    @Test
    fun aTlsRelayOrTheDevicesOwnLoopbackIsAccepted() {
        assertNotNull(RelayConfig.parse("wss://example.trycloudflare.com/car", TOKEN))
        assertNotNull(RelayConfig.parse("ws://127.0.0.1:18767/car", TOKEN))
    }

    @Test
    fun anythingElseIsRefused() {
        val refused =
            listOf(
                "ws://example.trycloudflare.com/car",
                "ws://10.0.2.2:18767/car",
                "ws://localhost:18767/car",
                "ws://127.0.0.1/car",
                "http://127.0.0.1:18767/car",
                "https://example.trycloudflare.com/car",
                "wss://example.trycloudflare.com/",
                "wss://example.trycloudflare.com/car/extra",
                "wss://example.trycloudflare.com/car?x=1",
                "wss://example.trycloudflare.com/car#x",
                "wss://user@example.trycloudflare.com/car",
                "wss:///car",
                "not a url",
            )
        for (url in refused) assertNull(url, RelayConfig.parse(url, TOKEN))
        assertNull(RelayConfig.parse(null, TOKEN))
        assertNull(RelayConfig.parse(URL, null))
        assertNull(RelayConfig.parse(URL, "short"))
        assertNull(RelayConfig.parse(URL, "$TOKEN\r\nX-Injected: 1"))
    }

    @Test
    fun theTokenNeverAppearsInText() {
        val config = RelayConfig.parse(URL, TOKEN)!!
        assertFalse(config.toString().contains(TOKEN))
        assertEquals(TOKEN, config.token)
    }

    @Test
    fun onlyAWellFormedRequestFrameIsRead() {
        val frame = """{"v":1,"t":"req","id":"7","protocolVersion":"2025-06-18","body":"{}"}"""
        val request = RelayFrames.parseRequest(frame)!!
        assertEquals("7", request.id)
        assertEquals("2025-06-18", request.protocolVersion)
        assertEquals("{}", request.body)

        val odd = frame.replace("2025-06-18", "x\\r\\nHost: evil")
        assertNull(RelayFrames.parseRequest(odd)!!.protocolVersion)
        assertNull(RelayFrames.parseRequest(frame.replace("\"v\":1", "\"v\":2")))
        assertNull(RelayFrames.parseRequest(frame.replace("\"req\"", "\"res\"")))
        assertNull(RelayFrames.parseRequest(frame.replace("\"7\"", "\"../x\"")))
        assertNull(RelayFrames.parseRequest(frame.replace("\"body\":\"{}\"", "\"body\":{}")))
        assertNull(RelayFrames.parseRequest("not json"))
    }

    private companion object {
        const val URL = "wss://example.trycloudflare.com/car"
        const val TOKEN = "car-token-car-token-car-token-car-0002"
    }
}
