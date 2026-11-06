package com.example.pleos_pa.mcp

import com.example.pleos_pa.bridge.BridgeEndpoint
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

/**
 * COM-13 experiment: an MCP Streamable HTTP endpoint inside the app, on loopback only. The Host
 * allow-list and the Origin refusal stop browser-style requests; like the bridge's checks, they do
 * not identify the caller and are not authentication. Nothing here is reachable off the device
 * without an `adb forward`.
 */
class EmbeddedMcpServer(
    private val tool: VehicleReadTool,
    private val port: Int = PORT,
) : BridgeEndpoint {
    @Volatile private var server: EmbeddedServer<CIOApplicationEngine, *>? = null

    /** The bound port, which differs from [PORT] only in tests. */
    val localPort: Int
        get() = server?.let { runBlocking { it.engine.resolvedConnectors().first().port } } ?: -1

    override fun start() {
        val created =
            embeddedServer(CIO, host = HOST, port = port) {
                // Stateless: no session survives a request, so clients that leave without a DELETE
                // cannot use up a session cap. Read-only tools need no server-to-client stream.
                // The stateful variant refused new clients after eight abandoned sessions (COM-13).
                mcpStatelessStreamableHttp(
                    path = PATH,
                    allowedHosts = listOf(HOST),
                    // An empty list refuses every request that carries an Origin header.
                    allowedOrigins = emptyList(),
                ) {
                    mcpServer()
                }
            }
        // Returns once the socket is bound, and throws when the bind fails.
        created.start(wait = false)
        server = created
    }

    override fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MILLIS)
        server = null
    }

    private fun mcpServer() =
        Server(
            Implementation(NAME, VERSION),
            ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools())),
        ) {
            addTool(
                name = "get_energy_level",
                title = "에너지 잔량",
                description =
                    "연료 잔량과 EV 배터리 잔량을 퍼센트로 조회합니다. 차량 동력원에 맞지 않는 항목은 미지원으로 나옵니다. " +
                        "주행 가능 거리로 환산하지 마세요.",
                inputSchema = ToolSchema(properties = JsonObject(emptyMap())),
                toolAnnotations =
                    ToolAnnotations(
                        readOnlyHint = true,
                        destructiveHint = false,
                        idempotentHint = false,
                        openWorldHint = false,
                    ),
            ) {
                tool.call("energy_level")
            }
        }

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 18766
        const val PATH = "/mcp"
        const val NAME = "pleos-pa-vehicle-embedded"
        const val VERSION = "0.0.1-com13"
        private const val STOP_TIMEOUT_MILLIS = 1_000L
    }
}
