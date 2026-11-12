package com.example.pleos_pa.relayserver

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.readAvailable
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The relay of the COM-55 experiment. The car app dials `GET /car` as a WebSocket; an AI client
 * posts MCP requests to `/r/{secret}/mcp`, and each one travels to the car as one frame and comes
 * back as one frame. The relay never opens a connection toward the car.
 *
 * It listens on loopback only; a tunnel in front of it is what makes it reachable, and only while
 * that tunnel runs. The secret in the path is the only thing an AI client presents, so this is not
 * authentication of a user. The tool allow-list here is a second layer: what the car serves to the
 * relay is decided in the car.
 */
class RelayServer(
    private val mcpSecret: String,
    private val carToken: String,
    private val allowedTools: Set<String>,
    private val port: Int = DEFAULT_PORT,
    private val carDeadlineMillis: Long = CAR_DEADLINE_MILLIS,
    private val log: (String) -> Unit = ::println,
) {
    private class Car(val session: DefaultWebSocketServerSession) {
        val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    }

    private class CarGone : Exception()

    private val car = AtomicReference<Car?>(null)
    private val inFlight = AtomicInteger(0)
    private val frameIds = AtomicLong(0)
    private val lock = Any()
    private var server: EmbeddedServer<CIOApplicationEngine, *>? = null

    /** The bound port, or -1 when not serving. */
    val localPort: Int
        get() =
            synchronized(lock) { server }
                ?.let { runBlocking { it.engine.resolvedConnectors().first().port } } ?: -1

    fun start() {
        val created =
            embeddedServer(CIO, host = HOST, port = port) {
                install(WebSockets) {
                    pingPeriod = PING_SECONDS.seconds
                    timeout = PING_TIMEOUT_SECONDS.seconds
                    maxFrameSize = MAX_FRAME_BYTES
                }
                // Before the upgrade, so a caller without the token never gets a socket.
                intercept(ApplicationCallPipeline.Plugins) {
                    val call = context
                    if (call.request.path() != CAR_PATH) return@intercept
                    val refusal =
                        when {
                            !carAuthorized(call) -> HttpStatusCode.NotFound
                            car.get() != null -> HttpStatusCode.Conflict
                            else -> null
                        }
                    if (refusal != null) {
                        log("event=car_refused status=${refusal.value}")
                        call.respond(refusal)
                        finish()
                    }
                }
                routing {
                    webSocket(CAR_PATH) { serveCar(this) }
                    route("/r/{secret}/mcp") { handle { mcp(call) } }
                }
            }
        created.start(wait = false)
        synchronized(lock) { server = created }
    }

    fun stop() {
        synchronized(lock) { server.also { server = null } }
            ?.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MILLIS)
    }

    private fun carAuthorized(call: ApplicationCall): Boolean {
        val presented = call.request.header(HttpHeaders.Authorization) ?: return false
        return sameText(presented, "Bearer $carToken")
    }

    private suspend fun serveCar(session: DefaultWebSocketServerSession) {
        val mine = Car(session)
        if (!car.compareAndSet(null, mine)) {
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "BUSY"))
            return
        }
        log("event=car_connected")
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val reply = runCatching { JSONObject(frame.readText()) }.getOrNull() ?: continue
                if (reply.optInt("v") != FRAME_VERSION || reply.optString("t") != "res") continue
                mine.pending.remove(reply.optString("id"))?.complete(reply)
            }
        } finally {
            car.compareAndSet(mine, null)
            mine.pending.values.forEach { it.completeExceptionally(CarGone()) }
            mine.pending.clear()
            log("event=car_disconnected")
        }
    }

    private suspend fun mcp(call: ApplicationCall) {
        if (!sameText(call.parameters["secret"].orEmpty(), mcpSecret)) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        if (call.request.httpMethod != HttpMethod.Post) {
            call.respond(HttpStatusCode.MethodNotAllowed)
            return
        }
        if (!call.request.contentType().match(ContentType.Application.Json)) {
            call.respond(HttpStatusCode.UnsupportedMediaType)
            return
        }
        val body = readBody(call)
        if (body == null) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return
        }
        // A batch could carry a tools/call past the name check below.
        val rpc =
            if (body.trimStart().startsWith("[")) null
            else runCatching { JSONObject(body) }.getOrNull()
        if (rpc == null) {
            call.respond(HttpStatusCode.BadRequest)
            return
        }
        val method = rpc.optString("method")
        val shownMethod = if (METHOD_SHAPE.matches(method)) method else "other"
        val tool =
            if (method == "tools/call") rpc.optJSONObject("params")?.optString("name").orEmpty()
            else null
        if (tool != null && tool !in allowedTools) {
            log("event=mcp method=$shownMethod tool=denied status=200")
            val error = JSONObject().put("code", INVALID_PARAMS).put("message", "Tool not found")
            val reply = JSONObject().put("jsonrpc", "2.0").put("error", error)
            reply.put("id", rpc.opt("id") ?: JSONObject.NULL)
            call.respondText(reply.toString(), ContentType.Application.Json)
            return
        }
        val shownTool = tool ?: "-"
        val linked = car.get()
        if (linked == null) {
            log("event=mcp method=$shownMethod tool=$shownTool status=503 code=CAR_NOT_CONNECTED")
            call.respondText("CAR_NOT_CONNECTED", status = HttpStatusCode.ServiceUnavailable)
            return
        }
        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
            inFlight.decrementAndGet()
            log("event=mcp method=$shownMethod tool=$shownTool status=503 code=TOO_MANY")
            call.respondText("TOO_MANY", status = HttpStatusCode.ServiceUnavailable)
            return
        }
        val id = frameIds.incrementAndGet().toString()
        val started = System.nanoTime()
        var status = HttpStatusCode.BadGateway
        var code = "-"
        try {
            val frame =
                JSONObject().put("v", FRAME_VERSION).put("t", "req").put("id", id).put("body", body)
            call.request.header(PROTOCOL_VERSION_HEADER)?.let { frame.put("protocolVersion", it) }
            val waiter = CompletableDeferred<JSONObject>()
            linked.pending[id] = waiter
            val reply =
                try {
                    linked.session.send(Frame.Text(frame.toString()))
                    withTimeoutOrNull(carDeadlineMillis) { waiter.await() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    code = "CAR_GONE"
                    call.respondText(code, status = status)
                    return
                } finally {
                    linked.pending.remove(id)
                }
            if (reply == null) {
                status = HttpStatusCode.GatewayTimeout
                code = "CAR_TIMEOUT"
                call.respondText(code, status = status)
                return
            }
            val failure = reply.optString("error")
            if (failure.isNotEmpty() || !reply.has("status")) {
                code = if (ERROR_SHAPE.matches(failure)) failure else "BAD_FRAME"
                call.respondText(code, status = status)
                return
            }
            status = HttpStatusCode.fromValue(reply.getInt("status"))
            val text = reply.optString("body")
            val type = runCatching { ContentType.parse(reply.optString("contentType")) }.getOrNull()
            if (text.isEmpty() || type == null) call.respond(status)
            else call.respondText(text, type, status)
        } finally {
            inFlight.decrementAndGet()
            val millis = (System.nanoTime() - started) / 1_000_000
            log(
                "event=mcp id=$id method=$shownMethod tool=$shownTool " +
                    "status=${status.value} code=$code ms=$millis"
            )
        }
    }

    /** The body as text, or null when it is larger than [MAX_BODY_BYTES]. */
    private suspend fun readBody(call: ApplicationCall): String? {
        val channel = call.receiveChannel()
        val buffer = ByteArray(MAX_BODY_BYTES + 1)
        var size = 0
        while (size < buffer.size) {
            val read = channel.readAvailable(buffer, size, buffer.size - size)
            if (read < 0) break
            size += read
        }
        return if (size > MAX_BODY_BYTES) null else String(buffer, 0, size, Charsets.UTF_8)
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val DEFAULT_PORT = 18767
        const val CAR_PATH = "/car"
        const val FRAME_VERSION = 1
        const val MAX_BODY_BYTES = 64 * 1024
        const val MAX_IN_FLIGHT = 8
        const val CAR_DEADLINE_MILLIS = 15_000L
        const val PROTOCOL_VERSION_HEADER = "mcp-protocol-version"
        private const val MAX_FRAME_BYTES = 1024L * 1024
        private const val PING_SECONDS = 20
        private const val PING_TIMEOUT_SECONDS = 30
        private const val STOP_TIMEOUT_MILLIS = 1_000L
        private const val INVALID_PARAMS = -32602
        // Only these shapes reach the log; anything else a caller sent is replaced by a word.
        private val METHOD_SHAPE = Regex("[a-z/_]{1,40}")
        private val ERROR_SHAPE = Regex("[A-Z_]{1,32}")

        private fun sameText(left: String, right: String) =
            MessageDigest.isEqual(left.toByteArray(), right.toByteArray())

        /** The adopted tool names of `contracts/vehicle-read/catalog.json`. */
        fun adoptedTools(catalogText: String): Set<String> {
            val tools = JSONObject(catalogText).getJSONArray("tools")
            return (0 until tools.length())
                .map { tools.getJSONObject(it) }
                .filter { it.optString("adoption") == "adopted" }
                .map { it.getString("name") }
                .toSet()
        }
    }
}
