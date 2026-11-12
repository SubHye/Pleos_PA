package com.example.pleos_pa.relayserver

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The relay over real loopback sockets with a stand-in car. These check framing, refusals and what
 * reaches the log. Nothing here runs the app or a vehicle SDK.
 */
class RelayServerTest {
    private val logged = CopyOnWriteArrayList<String>()
    private val relay =
        RelayServer(SECRET, CAR_TOKEN, ALLOWED, port = 0, carDeadlineMillis = 400) { logged += it }
    private val http = HttpClient(CIO) { install(WebSockets) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var port = -1

    @Before
    fun start() {
        relay.start()
        port = relay.localPort
    }

    @After
    fun stop() {
        scope.cancel()
        http.close()
        relay.stop()
    }

    private suspend fun post(
        body: String,
        path: String = "/r/$SECRET/mcp",
        type: ContentType = ContentType.Application.Json,
        version: String? = null,
    ): HttpResponse =
        http.post("http://127.0.0.1:$port$path") {
            version?.let { header(RelayServer.PROTOCOL_VERSION_HEADER, it) }
            setBody(TextContent(body, type))
        }

    /** Links a stand-in car that answers each request frame with [answer]; null sends nothing. */
    private suspend fun linkCar(
        token: String = CAR_TOKEN,
        answer: suspend DefaultClientWebSocketSession.(JSONObject) -> JSONObject?,
    ): Job {
        val linked = CompletableDeferred<Unit>()
        val job = scope.launch {
            http.webSocket(
                "ws://127.0.0.1:$port/car",
                { header(HttpHeaders.Authorization, "Bearer $token") },
            ) {
                linked.complete(Unit)
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val request = JSONObject(frame.readText())
                    launch { answer(request)?.let { send(Frame.Text(it.toString())) } }
                }
            }
        }
        withTimeout(3_000) { linked.await() }
        waitFor { logged.any { it == "event=car_connected" } }
        return job
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000
        while (!condition()) {
            assertTrue("condition not met in time", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }

    private fun ok(request: JSONObject, body: String) =
        JSONObject()
            .put("v", 1)
            .put("t", "res")
            .put("id", request.getString("id"))
            .put("status", 200)
            .put("contentType", "application/json")
            .put("body", body)

    @Test
    fun aWrongSecretOrAnotherPathIsNotFound() = runBlocking {
        linkCar { ok(it, "{}") }
        val bodies = listOf(LIST)
        for (body in bodies) {
            assertEquals(404, post(body, "/r/${SECRET.reversed()}/mcp").status.value)
            assertEquals(404, post(body, "/r/$SECRET").status.value)
            assertEquals(404, post(body, "/mcp").status.value)
            assertEquals(404, post(body, "/").status.value)
        }
        assertFalse(logged.any { it.startsWith("event=mcp") })
    }

    @Test
    fun onlyAJsonObjectPostIsAccepted() = runBlocking {
        linkCar { ok(it, "{}") }
        assertEquals(405, http.get("http://127.0.0.1:$port/r/$SECRET/mcp").status.value)
        assertEquals(415, post(LIST, type = ContentType.Text.Plain).status.value)
        assertEquals(400, post("[$LIST]").status.value)
        assertEquals(400, post("not json").status.value)
        val large = """{"jsonrpc":"2.0","id":1,"method":"x","p":"${"a".repeat(70_000)}"}"""
        assertEquals(413, post(large).status.value)
        assertFalse(logged.any { it.startsWith("event=mcp") })
    }

    @Test
    fun aToolOutsideTheAllowListNeverReachesTheCar() = runBlocking {
        val frames = CopyOnWriteArrayList<JSONObject>()
        linkCar {
            frames += it
            ok(it, "{}")
        }
        for (name in listOf("set_navigation_destination", "get_navigation_destination", "")) {
            val call =
                JSONObject(LIST).put("method", "tools/call").put("params", mapOf("name" to name))
            val reply = post(call.toString())
            assertEquals(200, reply.status.value)
            val error = JSONObject(reply.bodyAsText()).getJSONObject("error")
            assertEquals(name, -32602, error.getInt("code"))
        }
        assertEquals(0, frames.size)
        assertFalse(logged.any { it.contains("navigation") })
    }

    @Test
    fun withoutACarTheAnswerIsServiceUnavailable() = runBlocking {
        val reply = post(LIST)
        assertEquals(503, reply.status.value)
        assertEquals("CAR_NOT_CONNECTED", reply.bodyAsText())
    }

    @Test
    fun aRequestTravelsAsOneFrameAndItsAnswerComesBack() = runBlocking {
        val frames = CopyOnWriteArrayList<JSONObject>()
        linkCar {
            frames += it
            ok(it, """{"jsonrpc":"2.0","id":7,"result":{"text":"배터리 80 %"}}""")
        }
        val call =
            """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"get_energy_level"}}"""
        val reply = post(call, version = "2025-06-18")

        assertEquals(200, reply.status.value)
        assertTrue(reply.contentType()!!.match(ContentType.Application.Json))
        assertEquals(
            "배터리 80 %",
            JSONObject(reply.bodyAsText()).getJSONObject("result").getString("text"),
        )
        val frame = frames.single()
        assertEquals(setOf("v", "t", "id", "body", "protocolVersion"), frame.keySet())
        assertEquals(call, frame.getString("body"))
        assertEquals("2025-06-18", frame.getString("protocolVersion"))
        assertTrue(logged.any { it.contains("tool=get_energy_level status=200") })
    }

    @Test
    fun anAnswerWithoutABodyKeepsItsStatus() = runBlocking {
        linkCar { JSONObject().put("v", 1).put("t", "res").put("id", it["id"]).put("status", 202) }
        val reply = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, reply.status.value)
        assertEquals("", reply.bodyAsText())
    }

    @Test
    fun answersAreMatchedToTheirRequestsWhateverTheOrder() = runBlocking {
        linkCar {
            val asked = JSONObject(it.getString("body")).getInt("id")
            // The first request is answered last.
            delay(if (asked == 1) 150L else 0L)
            ok(it, """{"jsonrpc":"2.0","id":$asked,"result":{}}""")
        }
        val replies =
            (1..3).map { id ->
                async(Dispatchers.IO) {
                    JSONObject(post(LIST.replace("\"id\":1", "\"id\":$id")).bodyAsText())
                }
            }
        assertEquals(listOf(1, 2, 3), replies.map { it.await().getInt("id") })
    }

    @Test
    fun aCarThatFailsOrNeverAnswersIsAGatewayError() = runBlocking {
        linkCar {
            val asked = JSONObject(it.getString("body")).getInt("id")
            if (asked == 1) null
            else JSONObject().put("v", 1).put("t", "res").put("id", it["id"]).put("error", ERROR)
        }
        val silent = post(LIST)
        assertEquals(504, silent.status.value)
        val failed = post(LIST.replace("\"id\":1", "\"id\":2"))
        assertEquals(502, failed.status.value)
        assertEquals(ERROR, failed.bodyAsText())
    }

    @Test
    fun aCarThatDropsFailsWhatWasWaitingAndMayLinkAgain() = runBlocking {
        val asked = CompletableDeferred<Unit>()
        val car = linkCar {
            asked.complete(Unit)
            null
        }
        val waiting = async(Dispatchers.IO) { post(LIST) }
        withTimeout(3_000) { asked.await() }
        car.cancelAndJoin()

        assertEquals(502, waiting.await().status.value)
        waitFor { logged.any { it == "event=car_disconnected" } }
        assertEquals(503, post(LIST).status.value)
        logged.clear()
        linkCar { ok(it, "{}") }
        assertEquals(200, post(LIST).status.value)
    }

    @Test
    fun onlyOneCarWithTheTokenIsLinked() = runBlocking {
        val refused = runCatching { linkCar(token = CAR_TOKEN.reversed()) { null } }
        assertTrue("$refused", refused.isFailure)
        assertEquals(503, post(LIST).status.value)

        linkCar { ok(it, "{}") }
        val second = CompletableDeferred<Throwable?>()
        scope.launch {
            second.complete(
                runCatching {
                    http.webSocket(
                        "ws://127.0.0.1:$port/car",
                        { header(HttpHeaders.Authorization, "Bearer $CAR_TOKEN") },
                    ) {}
                }
                    .exceptionOrNull()
            )
        }
        assertTrue(withTimeout(3_000) { second.await() } != null)
        assertEquals(200, post(LIST).status.value)
    }

    @Test
    fun neitherSecretReachesTheLog() = runBlocking {
        linkCar { ok(it, "{}") }
        post(LIST)
        post(LIST, "/r/wrong/mcp")
        post(JSONObject(LIST).put("method", SECRET).toString())
        runCatching { linkCar(token = "wrong") { null } }

        assertTrue(logged.isNotEmpty())
        assertFalse("$logged", logged.any { it.contains(SECRET) || it.contains(CAR_TOKEN) })
        assertTrue(logged.any { it.contains("method=other") })
    }

    @Test
    fun theAllowListIsTheAdoptedVehicleToolsAndNoNavigationTool() {
        val contracts = File("../../contracts")
        val adopted =
            RelayServer.adoptedTools(File(contracts, "vehicle-read/catalog.json").readText())
        val navigation = JSONObject(File(contracts, "navigation/catalog.json").readText())
        val navigationNames =
            navigation.getJSONArray("tools").map { (it as JSONObject).getString("name") }

        assertEquals(20, adopted.size)
        assertTrue(navigationNames.isNotEmpty())
        assertTrue(navigationNames.none { it in adopted })
        assertFalse("get_toll_status" in adopted)
    }

    private companion object {
        const val SECRET = "s3cr3t-path-s3cr3t-path-s3cr3t-path-0001"
        const val CAR_TOKEN = "car-token-car-token-car-token-car-0002"
        const val ERROR = "LOCAL_MCP_FAILED"
        const val LIST = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        val ALLOWED = setOf("get_energy_level", "get_door_status")
    }
}
