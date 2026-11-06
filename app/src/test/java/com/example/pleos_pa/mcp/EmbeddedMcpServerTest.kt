package com.example.pleos_pa.mcp

import com.example.pleos_pa.bridge.BridgeEvents
import com.example.pleos_pa.bridge.ReaderBridgeGateway
import com.example.pleos_pa.contract.ContractFixtures
import com.example.pleos_pa.vehicle.FakeVehicleSdk
import com.example.pleos_pa.vehicle.PropertyCapability
import com.example.pleos_pa.vehicle.ReadScheduler
import com.example.pleos_pa.vehicle.VehicleReader
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * COM-13: the embedded endpoint over a real loopback socket, with the SDK stand-in. These are
 * protocol and lifecycle checks, not vehicle-function verification.
 */
class EmbeddedMcpServerTest {
    private val catalog = ContractFixtures.catalog
    private val pool = Executors.newSingleThreadScheduledExecutor()
    private val scheduler =
        object : ReadScheduler {
            override fun nowMillis() = System.nanoTime() / 1_000_000

            override fun execute(action: () -> Unit) {
                pool.execute(action)
            }

            override fun after(delayMillis: Long, action: () -> Unit): () -> Unit {
                val task = pool.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
                return { task.cancel(false) }
            }
        }
    private val sdk = FakeVehicleSdk()
    private val reader = VehicleReader(catalog, sdk, scheduler, "pleos_emulator")
    private val finished = CopyOnWriteArrayList<String>()
    private val events =
        object : BridgeEvents {
            override fun requestReceived() = Unit

            override fun peerClosedDuringRead() = Unit

            override fun listenerFailed() = Unit

            override fun ownReplyRejected() = Unit

            override fun finished(
                requestId: String,
                query: String,
                result: String,
                elapsedMillis: Long,
            ) {
                finished += result
            }
        }
    private val gateway = ReaderBridgeGateway(catalog, reader, scheduler, events)
    private val tool = VehicleReadTool(catalog, gateway, events) { scheduler.nowMillis() }
    private val server = EmbeddedMcpServer(tool, port = 0)
    private var port = -1

    @Before
    fun start() {
        server.start()
        port = server.localPort
    }

    @After
    fun stop() {
        server.stop()
        pool.shutdownNow()
    }

    private suspend fun connectedClient(): Client {
        val client = Client(Implementation("com13-test", "0"))
        client.connect(StreamableHttpClientTransport(HttpClient(CIO), "http://127.0.0.1:$port/mcp"))
        return client
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000
        while (!condition()) {
            assertTrue("condition not met in time", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }

    /** Energy reads EV_BATTERY_PERCENT, then FUEL_PERCENT: capability, then value, for each. */
    private fun answerEnergy(battery: Int, fuel: Int) {
        for ((index, value) in listOf(battery, fuel).withIndex()) {
            waitFor { sdk.calls.size == index * 2 + 1 }
            sdk.answerCapability(PropertyCapability("READ"))
            waitFor { sdk.calls.size == index * 2 + 2 }
            sdk.answerRead(value)
        }
    }

    private fun CallToolResult.text() = (content.single() as TextContent).text

    @Test
    fun toolsListShowsTheOneEmbeddedTool() = runBlocking {
        val client = connectedClient()
        val tools = withTimeout(5_000) { client.listTools() }.tools

        assertEquals(listOf("get_energy_level"), tools.map { it.name })
        assertEquals(true, tools.single().annotations?.readOnlyHint)
        client.close()
    }

    @Test
    fun aCallReadsThroughTheReaderAndAnswersLikeNode() = runBlocking {
        val client = connectedClient()
        val call = async(Dispatchers.IO) { client.callTool("get_energy_level", emptyMap()) }
        answerEnergy(battery = 80, fuel = 62)
        val result = withTimeout(5_000) { call.await() }

        assertEquals(null, result.isError)
        val structured = result.structuredContent!!
        assertEquals(JsonPrimitive("COMPLETE"), structured["outcome"])
        val values =
            structured["items"]!!.jsonArray.associate {
                val item = it.jsonObject
                item.getValue("property").jsonPrimitive.content to
                    item.getValue("rawValue").toString()
            }
        assertEquals(mapOf("EV_BATTERY_PERCENT" to "80", "FUEL_PERCENT" to "62"), values)
        assertTrue(result.text(), result.text().contains("[FUEL_PERCENT]: 62 %"))
        assertEquals(listOf("COMPLETE"), finished)
        client.close()
    }

    @Test
    fun aSecondCallDuringAReadIsBusyWithoutQueueing() = runBlocking {
        val client = connectedClient()
        val first = async(Dispatchers.IO) { client.callTool("get_energy_level", emptyMap()) }
        waitFor { sdk.calls.size == 1 }
        val second = withTimeout(5_000) { client.callTool("get_energy_level", emptyMap()) }

        assertEquals(true, second.isError)
        assertEquals(JsonPrimitive("BUSY"), second.structuredContent!!["code"])
        assertEquals(JsonPrimitive("android"), second.structuredContent!!["origin"])
        answerEnergy(battery = 80, fuel = 62)
        assertEquals(null, withTimeout(5_000) { first.await() }.isError)
        client.close()
    }

    @Test
    fun aCallAfterTheGatewayClosesIsConnectionStopped() = runBlocking {
        val client = connectedClient()
        gateway.close()
        val result = withTimeout(5_000) { client.callTool("get_energy_level", emptyMap()) }

        assertEquals(true, result.isError)
        assertEquals(JsonPrimitive("CONNECTION_STOPPED"), result.structuredContent!!["code"])
        assertEquals(0, sdk.calls.size)
        client.close()
    }

    @Test
    fun anOriginHeaderIsRefused() {
        assertEquals(403, status(rawPost(PING, extra = "Origin: http://127.0.0.1\r\n")))
    }

    @Test
    fun aForeignHostHeaderIsRefused() {
        assertEquals(403, status(rawPost(PING, host = "evil.example")))
    }

    @Test
    fun afterStopTheSocketIsClosed() {
        server.stop()
        val refused = runCatching { connect().close() }.isFailure
        assertTrue("port $port still accepts connections", refused)
    }

    private fun status(answer: String) = answer.substringAfter(' ').take(3).toInt()

    private fun connect() = Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port)

    private fun rawPost(
        body: String,
        extra: String = "",
        host: String = "127.0.0.1:$port",
    ): String =
        connect().use { socket ->
            socket.soTimeout = 5_000
            val size = body.toByteArray().size
            val request =
                "POST /mcp HTTP/1.1\r\nHost: $host\r\nContent-Type: application/json\r\n" +
                    "Accept: application/json, text/event-stream\r\nContent-Length: $size\r\n" +
                    "Connection: close\r\n$extra\r\n$body"
            socket.getOutputStream().write(request.toByteArray())
            socket.getInputStream().readBytes().decodeToString()
        }

    private companion object {
        const val PING = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
    }
}
