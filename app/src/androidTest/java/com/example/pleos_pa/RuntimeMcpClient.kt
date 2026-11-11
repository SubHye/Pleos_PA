package com.example.pleos_pa

import com.example.pleos_pa.mcp.EmbeddedMcpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.io.Closeable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Real MCP initialization and tool requests to the production loopback endpoint. No SDK fake. */
class RuntimeMcpClient : Closeable {
    private val http = HttpClient(CIO)
    private val client = Client(Implementation("com5-runtime-verification", "1"))

    init {
        try {
            runBlocking {
                withTimeout(15_000) {
                    client.connect(
                        StreamableHttpClientTransport(
                            http,
                            "http://${EmbeddedMcpServer.HOST}:${EmbeddedMcpServer.PORT}${EmbeddedMcpServer.PATH}",
                        )
                    )
                }
            }
        } catch (error: Exception) {
            http.close()
            throw error
        }
    }

    fun toolNames(): Set<String> = runBlocking {
        withTimeout(15_000) { client.listTools().tools.map { it.name }.toSet() }
    }

    fun call(name: String, arguments: Map<String, Any?>) = runBlocking {
        withTimeout(15_000) { client.callTool(name, arguments) }
    }

    override fun close() {
        try {
            runBlocking { withTimeout(5_000) { client.close() } }
        } finally {
            http.close()
        }
    }
}
