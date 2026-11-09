package com.example.pleos_pa.mcp

import com.example.pleos_pa.bridge.BridgeEndpoint
import com.example.pleos_pa.contract.VehicleReadCatalog
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * The app's MCP Streamable HTTP endpoint, on loopback only. It offers the catalog's adopted tools.
 * The Host allow-list and the Origin refusal stop browser-style requests; they do not identify the
 * caller and are not authentication. Nothing here is reachable off the device without an `adb
 * forward`. Unlike the old bridge, a server that stops on its own is not detected: Ktor reports no
 * such event, so the status stays as it was until the next call fails.
 */
class EmbeddedMcpServer(
    catalog: VehicleReadCatalog,
    private val tool: VehicleReadTool,
    private val port: Int = PORT,
) : BridgeEndpoint {
    private val offered =
        catalog.tools.values
            .filter { it.adoption == "adopted" }
            .associate { it.name to (it to ToolInputs(catalog, it)) }

    private val lock = Any()
    // Guarded by lock. Single use: once stopped, this endpoint never serves again.
    private var server: EmbeddedServer<CIOApplicationEngine, *>? = null
    private var stopped = false

    /** The bound port, which differs from [PORT] only in tests. -1 when not serving. */
    val localPort: Int
        get() =
            synchronized(lock) { server }
                ?.let { runBlocking { it.engine.resolvedConnectors().first().port } } ?: -1

    /**
     * Runs on the bind executor while [stop] may run on the main thread. A stop that lands before
     * or during the bind wins: the server bound late is closed here instead of being kept.
     */
    override fun start() {
        if (synchronized(lock) { stopped }) return
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
        val late =
            synchronized(lock) {
                if (!stopped) server = created
                stopped
            }
        if (late) close(created)
    }

    /** Idempotent. Also makes a start still in progress close what it binds. */
    override fun stop() {
        val running =
            synchronized(lock) {
                stopped = true
                server.also { server = null }
            }
        running?.let(::close)
    }

    private fun close(server: EmbeddedServer<CIOApplicationEngine, *>) {
        server.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MILLIS)
    }

    /** A new server per request; the stateless transport gives it exactly one session. */
    private fun mcpServer(): Server {
        val mcp =
            Server(
                Implementation(NAME, VERSION),
                ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools())),
            )
        // Registered for tools/list. Calls go through dispatch() below.
        for ((entry, inputs) in offered.values) {
            mcp.addTool(
                name = entry.name,
                title = entry.title,
                description = entry.description,
                inputSchema = inputs.schema,
                toolAnnotations = ANNOTATIONS,
            ) {
                dispatch(it)
            }
        }
        // The SDK answers an unknown tool with an error result. The MCP specification, and the Node
        // server this replaced, answer with a JSON-RPC invalid-params error, so this session's
        // tools/call is replaced.
        mcp.onConnect {
            mcp.sessions.values.forEach { session ->
                session.setRequestHandler<CallToolRequest>(Method.Defined.ToolsCall) { request, _ ->
                    dispatch(request)
                }
            }
        }
        return mcp
    }

    private suspend fun dispatch(request: CallToolRequest): CallToolResult {
        val (entry, inputs) =
            offered[request.name]
                ?: throw McpException(
                    RPCError.ErrorCode.INVALID_PARAMS,
                    "Tool ${request.name} not found",
                )
        return try {
            when (val selected = inputs.select(request.arguments)) {
                is ToolInputs.Selected.Refused -> McpToolResult.invalidInput(entry, selected.reason)
                is ToolInputs.Selected.Accepted -> tool.call(entry, selected.selection)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never a vehicle value: the read did not finish, and no detail leaves the app.
            CallToolResult(
                content = listOf(TextContent("Error executing tool ${entry.name}")),
                isError = true,
            )
        }
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 18766
        const val PATH = "/mcp"
        const val NAME = "pleos-pa-vehicle"
        const val VERSION = "0.1.0"
        private const val STOP_TIMEOUT_MILLIS = 1_000L
        private val ANNOTATIONS =
            ToolAnnotations(
                readOnlyHint = true,
                destructiveHint = false,
                idempotentHint = false,
                openWorldHint = false,
            )
    }
}
