package com.example.pleos_pa.relay

import com.example.pleos_pa.bridge.BridgeEvents
import com.example.pleos_pa.bridge.ReaderBridgeGateway
import com.example.pleos_pa.contract.ContractFixtures
import com.example.pleos_pa.contract.NavigationFixtures
import com.example.pleos_pa.contract.NavigationToolEntry
import com.example.pleos_pa.mcp.EmbeddedMcpServer
import com.example.pleos_pa.mcp.NavigationTool
import com.example.pleos_pa.mcp.VehicleReadTool
import com.example.pleos_pa.relayserver.RelayServer
import com.example.pleos_pa.ui.HistoryPresentation
import com.example.pleos_pa.ui.RequestHistory
import com.example.pleos_pa.vehicle.FakeVehicleSdk
import com.example.pleos_pa.vehicle.PropertyCapability
import com.example.pleos_pa.vehicle.ReadScheduler
import com.example.pleos_pa.vehicle.VehicleReader
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.modelcontextprotocol.kotlin.sdk.types.TextContent as McpText
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
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
 * The car's relay link against the real relay and the real embedded endpoint, over loopback
 * sockets, with the SDK stand-in. These check the chain and what the car lets through; they are not
 * vehicle-function verification and say nothing about Android networking.
 */
class RelayClientEndpointTest {
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
    private val events =
        object : BridgeEvents {
            override fun requestReceived() = Unit

            override fun ownReplyRejected() = Unit

            override fun finished(
                requestId: String,
                query: String,
                result: String,
                elapsedMillis: Long,
            ) = Unit
        }
    private val gateway =
        ReaderBridgeGateway(
            catalog,
            reader,
            scheduler,
            events,
            HistoryPresentation(RequestHistory(), { catalog.tools.getValue(it).name }),
        )
    private val tool = VehicleReadTool(catalog, gateway, events) { scheduler.nowMillis() }
    private val navigationEntry = NavigationFixtures.catalog.tools.first()
    private val navigation =
        object : NavigationTool {
            override val entry = navigationEntry
            override val inputSchema = ToolSchema()

            override suspend fun call(tool: NavigationToolEntry, arguments: JsonObject?) =
                CallToolResult(content = listOf(McpText("navigation answered")))
        }
    private val vehicleNames =
        catalog.tools.values.filter { it.adoption == "adopted" }.map { it.name }.toSet()

    // The relay's own allow-list is opened to the navigation tool, so the car is what refuses it.
    private val relay = RelayServer(SECRET, TOKEN, vehicleNames + navigationEntry.name, port = 0) {}
    private val states = CopyOnWriteArrayList<String>()
    private val local = EmbeddedMcpServer(catalog, tool, listOf(navigation), port = 0)
    private val vehicleOnly = EmbeddedMcpServer(catalog, tool, port = 0)
    private lateinit var composite: RelayComposite

    @Before
    fun start() {
        relay.start()
        val config = RelayConfig.parse("ws://127.0.0.1:${relay.localPort}/car", TOKEN)!!
        val record = { state: String, code: String -> states += "$state $code" }
        val link =
            RelayClientEndpoint(
                config,
                { vehicleOnly.localPort },
                record,
                redialDelaysMillis = emptyList(),
            )
        composite = RelayComposite(local, vehicleOnly, link, record)
        composite.start()
        waitFor { "LINKED -" in states }
    }

    @After
    fun stop() {
        composite.stop()
        relay.stop()
        pool.shutdownNow()
    }

    private suspend fun client(url: String): Client {
        val client = Client(Implementation("relay-test", "0"))
        client.connect(StreamableHttpClientTransport(HttpClient(CIO), url))
        return client
    }

    private suspend fun throughRelay() = client("http://127.0.0.1:${relay.localPort}$MCP_PATH")

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!condition()) {
            assertTrue("condition not met in time: $states", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }

    private suspend fun rawPost(): HttpResponse =
        HttpClient(CIO).use {
            it.post("http://127.0.0.1:${relay.localPort}$MCP_PATH") {
                setBody(TextContent(LIST, ContentType.Application.Json))
            }
        }

    @Test
    fun throughTheRelayOnlyTheVehicleToolsAreListed() = runBlocking {
        val direct = client("http://127.0.0.1:${local.localPort}/mcp")
        val remote = throughRelay()
        val directNames = withTimeout(5_000) { direct.listTools() }.tools.map { it.name }.toSet()
        val remoteNames = withTimeout(5_000) { remote.listTools() }.tools.map { it.name }.toSet()

        assertEquals(vehicleNames + navigationEntry.name, directNames)
        assertEquals(20, remoteNames.size)
        assertEquals(vehicleNames, remoteNames)
        direct.close()
        remote.close()
    }

    @Test
    fun aNavigationCallThroughTheRelayIsRefusedByTheCar() = runBlocking {
        val remote = throughRelay()
        val error = runCatching {
            withTimeout(5_000) { remote.callTool(navigationEntry.name, emptyMap()) }
        }
        val cause = error.exceptionOrNull()

        assertTrue("$error", cause is McpException)
        assertEquals(RPCError.ErrorCode.INVALID_PARAMS, (cause as McpException).code)
        assertEquals(0, sdk.calls.size)
        remote.close()
    }

    @Test
    fun aReadThroughTheRelayGoesThroughTheReader() = runBlocking {
        val remote = throughRelay()
        val call = async(Dispatchers.IO) { remote.callTool("get_energy_level", emptyMap()) }
        for ((index, value) in listOf(80, 62).withIndex()) {
            waitFor { sdk.calls.size == index * 2 + 1 }
            sdk.answerCapability(PropertyCapability("READ"))
            waitFor { sdk.calls.size == index * 2 + 2 }
            sdk.answerRead(value)
        }
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
        remote.close()
    }

    @Test
    fun aStopClosesTheLinkAndTheVehicleOnlyEndpoint() = runBlocking {
        assertEquals(200, rawPost().status.value)
        composite.stop()

        assertEquals(-1, vehicleOnly.localPort)
        assertEquals(-1, local.localPort)
        assertTrue("STOPPED -" in states)
        val deadline = System.nanoTime() + 5_000_000_000
        while (rawPost().status.value != 503) assertTrue(System.nanoTime() < deadline)
    }

    @Test
    fun aStopBeforeStartOpensNothing() {
        val seen = CopyOnWriteArrayList<String>()
        val config = RelayConfig.parse("ws://127.0.0.1:${freePort()}/car", TOKEN)!!
        val otherLocal = EmbeddedMcpServer(catalog, tool, port = 0)
        val otherVehicleOnly = EmbeddedMcpServer(catalog, tool, port = 0)
        val link = RelayClientEndpoint(config, { -1 }, { state, _ -> seen += state })
        val other = RelayComposite(otherLocal, otherVehicleOnly, link) { state, _ -> seen += state }
        other.stop()
        other.start()

        assertEquals(-1, otherLocal.localPort)
        assertEquals(-1, otherVehicleOnly.localPort)
        Thread.sleep(100)
        assertEquals(emptyList<String>(), seen.toList())
    }

    @Test
    fun aRelayThatCannotBeReachedIsDialledAFewTimesAndThenLeftFailed() {
        val seen = CopyOnWriteArrayList<String>()
        val config = RelayConfig.parse("ws://127.0.0.1:${freePort()}/car", TOKEN)!!
        val link =
            RelayClientEndpoint(
                config,
                { -1 },
                { state, _ -> seen += state },
                redialDelaysMillis = listOf(10, 10, 10),
            )
        link.start()
        try {
            waitFor { "FAILED" in seen }
            Thread.sleep(100)
            assertEquals(4, seen.count { it == "CONNECTING" })
            assertEquals(3, seen.count { it == "REDIAL" })
            assertEquals("FAILED", seen.last())
        } finally {
            link.stop()
        }
    }

    @Test
    fun aLocalEndpointThatIsGoneIsAGatewayErrorAndNotAValue() = runBlocking {
        vehicleOnly.stop()
        val reply = rawPost()

        assertEquals(502, reply.status.value)
        assertEquals("LOCAL_MCP_FAILED", reply.bodyAsText())
    }

    private fun freePort(): Int =
        ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }

    private companion object {
        const val SECRET = "s3cr3t-path-s3cr3t-path-s3cr3t-path-0001"
        const val TOKEN = "car-token-car-token-car-token-car-0002"
        const val MCP_PATH = "/r/$SECRET/mcp"
        const val LIST = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
    }
}
