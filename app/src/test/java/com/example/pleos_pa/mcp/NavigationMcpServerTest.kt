package com.example.pleos_pa.mcp

import com.example.pleos_pa.bridge.BridgeEvents
import com.example.pleos_pa.bridge.ReaderBridgeGateway
import com.example.pleos_pa.contract.ContractFixtures
import com.example.pleos_pa.contract.NavigationCatalog
import com.example.pleos_pa.contract.NavigationFixtures
import com.example.pleos_pa.contract.jsonEquivalent
import com.example.pleos_pa.navigation.DestinationReader
import com.example.pleos_pa.navigation.FakeNaviSdk
import com.example.pleos_pa.vehicle.FakeVehicleSdk
import com.example.pleos_pa.vehicle.ReadScheduler
import com.example.pleos_pa.vehicle.VehicleReader
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `get_navigation_destination` through the embedded endpoint over a real loopback socket, with the
 * NaviHelper stand-in. Protocol checks, not navigation-function verification.
 */
class NavigationMcpServerTest {
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
    private val finished = CopyOnWriteArrayList<String>()
    private val events =
        object : BridgeEvents {
            override fun requestReceived() = Unit

            override fun ownReplyRejected() = Unit

            override fun finished(
                requestId: String,
                query: String,
                result: String,
                elapsedMillis: Long,
            ) {
                finished += "$query:$result"
            }
        }
    private val reader = VehicleReader(catalog, FakeVehicleSdk(), scheduler, "pleos_emulator")
    private val gateway = ReaderBridgeGateway(catalog, reader, scheduler, events)
    private val vehicleTool = VehicleReadTool(catalog, gateway, events) { scheduler.nowMillis() }
    private val navi = FakeNaviSdk()
    private val destinations = DestinationReader(navi, scheduler, "simulation", 10_000L)
    private var server: EmbeddedMcpServer? = null

    private fun serve(navigationCatalog: NavigationCatalog): Int {
        destinations.open()
        navi.connect()
        val tool =
            NavigationDestinationTool(
                navigationCatalog,
                destinations,
                "simulation",
                events,
                scheduler::nowMillis,
            )
        val started = EmbeddedMcpServer(catalog, vehicleTool, tool, port = 0)
        started.start()
        server = started
        return started.localPort
    }

    @After
    fun stop() {
        server?.stop()
        pool.shutdownNow()
    }

    private suspend fun client(port: Int): Client {
        val client = Client(Implementation("navigation-test", "0"))
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

    private fun listed(tools: List<Tool>) =
        JSONArray(McpJson.encodeToString(ListSerializer(Tool.serializer()), tools))

    @Test
    fun anAdoptedToolIsListedAfterTheVehicleToolsAsTheFixtureSays() = runBlocking {
        val client = client(serve(NavigationFixtures.catalogWith("adopted")))
        val tools = listed(withTimeout(5_000) { client.listTools() }.tools)

        assertEquals(21, tools.length())
        val expected = JSONArray(NavigationFixtures.text("tool-list"))
        // The one known difference, as for the vehicle tools: no additionalProperties.
        assertTrue("$tools", jsonEquivalent(expected.getJSONObject(0), tools.getJSONObject(20)))
        client.close()
    }

    @Test
    fun anUndecidedToolIsNotListedAndCannotBeCalled() = runBlocking {
        val client = client(serve(NavigationFixtures.catalogWith("undecided")))
        val names = withTimeout(5_000) { client.listTools() }.tools.map { it.name }

        assertEquals(20, names.size)
        assertTrue("get_navigation_destination" !in names)
        val error = runCatching {
            withTimeout(5_000) { client.callTool("get_navigation_destination", emptyMap()) }
        }
        assertTrue(error.isFailure)
        assertEquals(0, navi.requests)
        client.close()
    }

    @Test
    fun aCallReadsOnceAndAnswersWithTheJudgedDestination() = runBlocking {
        val client = client(serve(NavigationFixtures.catalogWith("adopted")))
        val call =
            async(Dispatchers.IO) {
                withTimeout(5_000) { client.callTool("get_navigation_destination", emptyMap()) }
            }
        waitFor { navi.requests == 1 }
        navi.answer()
        val result = call.await()

        assertEquals(null, result.isError)
        val structured = JSONObject(result.structuredContent.toString())
        assertEquals("COMPLETE", structured.getString("status"))
        assertEquals("simulation", structured.getString("source"))
        assertEquals(1620, structured.getInt("remainTimeSeconds"))
        assertEquals("2026-11-10T01:27:00Z", structured.getString("estimatedArrivalAt"))
        assertTrue((result.content.single() as TextContent).text.contains("실제 차량 값이 아닙니다"))
        assertEquals(
            structured.getString("requestId"),
            result.structuredContent!!["requestId"]!!.jsonPrimitive.content,
        )
        waitFor { finished.isNotEmpty() }
        assertEquals(listOf("navigation_destination:COMPLETE"), finished)
        assertEquals(1, navi.requests)
        client.close()
    }

    @Test
    fun anyArgumentIsRefusedWithoutAnSdkCall() = runBlocking {
        val client = client(serve(NavigationFixtures.catalogWith("adopted")))
        val result =
            withTimeout(5_000) {
                client.callTool("get_navigation_destination", mapOf("destination" to "x"))
            }

        assertEquals(true, result.isError)
        assertEquals(null, result.structuredContent)
        assertTrue(
            (result.content.single() as TextContent).text.startsWith("Input validation error:")
        )
        assertEquals(0, navi.requests)
        client.close()
    }
}
