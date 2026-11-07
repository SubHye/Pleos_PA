package com.example.pleos_pa.mcp

import com.example.pleos_pa.bridge.BridgeEndpoint

/**
 * Starts and stops several endpoints as one, so the embedded MCP server lives and dies with the
 * bridge session. A failed start closes whatever already started.
 */
class EndpointGroup(private val endpoints: List<BridgeEndpoint>) : BridgeEndpoint {
    override fun start() {
        val started = mutableListOf<BridgeEndpoint>()
        try {
            endpoints.forEach {
                it.start()
                started += it
            }
        } catch (e: Exception) {
            started.asReversed().forEach { runCatching { it.stop() } }
            throw e
        }
    }

    override fun stop() {
        var failure: Throwable? = null
        endpoints.asReversed().forEach { endpoint ->
            try {
                endpoint.stop()
            } catch (e: Exception) {
                failure = failure ?: e
            }
        }
        failure?.let { throw it }
    }
}
