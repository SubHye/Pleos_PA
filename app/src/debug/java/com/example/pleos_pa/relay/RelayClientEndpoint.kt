package com.example.pleos_pa.relay

import com.example.pleos_pa.bridge.BridgeEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSocketException
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * The car's side of the COM-55 relay experiment: it dials out to the relay and answers each request
 * frame with one POST to an MCP endpoint on this device's loopback. Nothing listens for the relay;
 * the link exists only while this endpoint is started.
 *
 * A dropped link is redialled a few times and then left failed until the session is restarted. A
 * request is never sent twice, so a redial is not a retry of a vehicle read.
 */
class RelayClientEndpoint(
    private val config: RelayConfig,
    private val localPort: () -> Int,
    private val onState: (state: String, code: String) -> Unit,
    private val redialDelaysMillis: List<Long> = listOf(2_000, 5_000, 10_000),
    private val localDeadlineMillis: Long = LOCAL_DEADLINE_MILLIS,
) : BridgeEndpoint {
    private val lock = Any()
    // Guarded by lock. Single use, like the endpoint it rides beside.
    private var scope: CoroutineScope? = null
    private var http: HttpClient? = null
    private var stopped = false

    /** Returns at once; the dial runs on its own scope and never fails the session. */
    override fun start() {
        val client: HttpClient
        val running: CoroutineScope
        synchronized(lock) {
            if (stopped || scope != null) return
            client = HttpClient(CIO) { install(WebSockets) { pingIntervalMillis = PING_MILLIS } }
            running = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            http = client
            scope = running
        }
        running.launch { dial(client) }
    }

    /** Idempotent. Closes the link and abandons what was being forwarded. */
    override fun stop() {
        val (running, client) =
            synchronized(lock) {
                stopped = true
                (scope to http).also {
                    scope = null
                    http = null
                }
            }
        running?.cancel()
        client?.close()
        if (running != null) onState("STOPPED", "-")
    }

    private suspend fun dial(client: HttpClient) {
        var redials = 0
        while (true) {
            onState("CONNECTING", "-")
            val code =
                try {
                    client.webSocket(
                        config.url,
                        { header(HttpHeaders.Authorization, "Bearer ${config.token}") },
                    ) {
                        onState("LINKED", "-")
                        serve(client, this)
                    }
                    "CLOSED"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    codeOf(e)
                }
            if (redials >= redialDelaysMillis.size) {
                onState("FAILED", code)
                return
            }
            onState("REDIAL", code)
            delay(redialDelaysMillis[redials++])
        }
    }

    private suspend fun serve(client: HttpClient, session: DefaultClientWebSocketSession) =
        coroutineScope {
            val gate = Semaphore(MAX_CONCURRENT)
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                // A frame without a usable id cannot be answered; the relay's deadline covers it.
                val request = RelayFrames.parseRequest(frame.readText()) ?: continue
                launch {
                    val reply = gate.withPermit { forward(client, request) }
                    session.send(Frame.Text(reply))
                }
            }
        }

    /**
     * The headers are this client's own; nothing the relay sent becomes a header but the version.
     */
    private suspend fun forward(client: HttpClient, request: RelayFrames.Request): String {
        val port = localPort()
        if (port <= 0) return RelayFrames.failure(request.id, "LOCAL_MCP_FAILED")
        return try {
            withTimeout(localDeadlineMillis) {
                val reply =
                    client.post("http://127.0.0.1:$port/mcp") {
                        header(HttpHeaders.Accept, "application/json, text/event-stream")
                        request.protocolVersion?.let { header(PROTOCOL_VERSION_HEADER, it) }
                        setBody(TextContent(request.body, ContentType.Application.Json))
                    }
                RelayFrames.response(
                    request.id,
                    reply.status.value,
                    reply.contentType()?.toString(),
                    reply.bodyAsText(),
                )
            }
        } catch (e: TimeoutCancellationException) {
            RelayFrames.failure(request.id, "LOCAL_TIMEOUT")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RelayFrames.failure(request.id, "LOCAL_MCP_FAILED")
        }
    }

    /** A fixed word and the exception's class. Never its message, which may name a host. */
    private fun codeOf(e: Exception): String {
        val kind =
            when (e) {
                is WebSocketException -> "HANDSHAKE_REFUSED"
                is IOException -> "IO"
                else -> "ERROR"
            }
        return "$kind:${e.javaClass.simpleName}"
    }

    companion object {
        const val LOCAL_DEADLINE_MILLIS = 12_000L
        private const val PROTOCOL_VERSION_HEADER = "mcp-protocol-version"
        private const val PING_MILLIS = 20_000L
        private const val MAX_CONCURRENT = 8
    }
}
