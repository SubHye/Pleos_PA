package com.example.pleos_pa.mcp

import com.example.pleos_pa.bridge.BridgeEvents
import com.example.pleos_pa.bridge.ReaderBridgeGateway
import com.example.pleos_pa.contract.ContractFixtures
import com.example.pleos_pa.contract.DestinationFields
import com.example.pleos_pa.contract.NavigationCatalog
import com.example.pleos_pa.contract.NavigationFixtures
import com.example.pleos_pa.contract.jsonEquivalent
import com.example.pleos_pa.navigation.DestinationReader
import com.example.pleos_pa.navigation.DestinationSetter
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
import kotlinx.coroutines.delay
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
    @Volatile private var drivingBlock: String? = null

    private fun serve(navigationCatalog: NavigationCatalog): Int {
        destinations.open()
        navi.connect()
        val setter =
            DestinationSetter(
                navi,
                destinations,
                scheduler,
                navigationCatalog.baselineDeadlineMs,
                navigationCatalog.confirmTimeoutMs,
                POLL_MILLIS,
                navigationCatalog.matchToleranceDegrees,
                restriction = { drivingBlock },
            )
        val places = NavigationFixtures.places
        val tools =
            listOf(
                NavigationDestinationTool(
                    navigationCatalog,
                    destinations,
                    "simulation",
                    events,
                    scheduler::nowMillis,
                ),
                NavigationPlaceTool(navigationCatalog, places, events, scheduler::nowMillis),
                NavigationSetDestinationTool(
                    navigationCatalog,
                    places,
                    setter,
                    "simulation",
                    events,
                    scheduler::nowMillis,
                ),
            )
        val started = EmbeddedMcpServer(catalog, vehicleTool, tools, port = 0)
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

        assertEquals(23, tools.length())
        val expected = JSONArray(NavigationFixtures.text("tool-list"))
        assertEquals(3, expected.length())
        // The one known difference, as for the vehicle tools: no additionalProperties.
        for (index in 0 until expected.length()) {
            assertTrue(
                "${tools.getJSONObject(20 + index)}",
                jsonEquivalent(expected.getJSONObject(index), tools.getJSONObject(20 + index)),
            )
        }
        client.close()
    }

    @Test
    fun findingAPlaceTellsNoneOneAndSeveralApartWithoutAnSdkCall() = runBlocking {
        val client = client(serve(NavigationFixtures.catalogWith("adopted")))
        suspend fun find(query: String) =
            JSONObject(
                withTimeout(5_000) {
                        client.callTool("find_navigation_place", mapOf("query" to query))
                    }
                    .structuredContent
                    .toString()
            )

        val one = find("서울역 3번 출구")
        assertEquals("SINGLE", one.getString("status"))
        assertEquals(
            "seoul_station",
            one.getJSONArray("candidates").getJSONObject(0).getString("candidateId"),
        )
        assertEquals("MULTIPLE", find("판교").getString("status"))
        val none = find("부산역")
        assertEquals("NO_MATCH", none.getString("status"))
        // Never the whole list instead.
        assertEquals(0, none.getJSONArray("candidates").length())

        assertEquals(0, navi.requests)
        assertEquals(0, navi.routes.size)
        // The log labels carry the status only, never the query or a place.
        waitFor { finished.size == 3 }
        assertEquals(
            listOf(
                "navigation_place:SINGLE",
                "navigation_place:MULTIPLE",
                "navigation_place:NO_MATCH",
            ),
            finished.toList(),
        )
        client.close()
    }

    @Test
    fun mcpChangeIsRefusedBeforeSdkAndIsNotRetriedWhenRestrictionsLift() = runBlocking {
        val client = client(serve(NavigationFixtures.catalogWith("adopted")))
        for (code in
            listOf(DestinationSetter.DRIVING_RESTRICTED, DestinationSetter.DRIVING_STATE_UNKNOWN)) {
            drivingBlock = code
            val result =
                withTimeout(5_000) {
                    client.callTool("set_navigation_destination", mapOf("candidateId" to "coex"))
                }
            val value = JSONObject(result.structuredContent.toString())
            assertEquals(true, result.isError)
            assertEquals(code, value.getString("code"))
            assertEquals(false, value.getBoolean("sent"))
        }
        drivingBlock = null
        delay(100)
        assertEquals(0, navi.routes.size)
        assertEquals(0, navi.requests)
        client.close()
    }

    @Test
    fun settingAListedPlaceAnswersOnlyAfterTheDestinationReadBackMatches() = runBlocking {
        val client = client(serve(NavigationFixtures.catalogWith("adopted")))
        val coex = NavigationFixtures.places.place("coex")!!
        val call =
            async(Dispatchers.IO) {
                withTimeout(10_000) {
                    client.callTool("set_navigation_destination", mapOf("candidateId" to "coex"))
                }
            }
        // The read before the request: another place is the destination.
        waitFor { navi.requests == 1 }
        assertEquals(0, navi.routes.size)
        navi.answer(FakeNaviSdk.OTHER)
        waitFor { navi.routes.size == 1 }
        // An empty destination while the route is replaced is not an answer.
        navi.answer(DestinationFields("", "", 0.0, 0.0, 0, 0))
        waitFor { navi.requests >= 2 }
        assertTrue(!call.isCompleted)
        navi.answer(DestinationFields("코엑스", "강남구 삼성동", coex.latitude, coex.longitude, 9000, 900))
        val result = call.await()

        assertEquals(null, result.isError)
        val structured = JSONObject(result.structuredContent.toString())
        assertEquals("APPLIED", structured.getString("status"))
        assertEquals("coex", structured.getJSONObject("destination").getString("candidateId"))
        assertTrue(!structured.toString().contains("latitude"))
        assertEquals(coex.latitude, navi.routes.single().latitude, 0.0)
        waitFor { finished.isNotEmpty() }
        assertEquals(listOf("navigation_set_destination:APPLIED"), finished.toList())
        client.close()
    }

    @Test
    fun anUnknownCandidateOrAWrongArgumentNeverReachesTheSdk() = runBlocking {
        val client = client(serve(NavigationFixtures.catalogWith("adopted")))
        val unknown =
            withTimeout(5_000) {
                client.callTool("set_navigation_destination", mapOf("candidateId" to "busan"))
            }
        assertEquals(true, unknown.isError)
        val structured = JSONObject(unknown.structuredContent.toString())
        assertEquals("UNKNOWN_CANDIDATE", structured.getString("code"))
        assertEquals(false, structured.getBoolean("sent"))

        for (arguments in
            listOf(
                emptyMap(),
                mapOf("candidateId" to 3),
                mapOf("candidateId" to "coex", "latitude" to 37.5),
                mapOf("query" to "코엑스"),
            )) {
            val refused =
                withTimeout(5_000) { client.callTool("set_navigation_destination", arguments) }
            assertEquals("$arguments", true, refused.isError)
            assertEquals("$arguments", null, refused.structuredContent)
            assertTrue(
                (refused.content.single() as TextContent).text.startsWith("Input validation error:")
            )
        }
        assertEquals(0, navi.requests)
        assertEquals(0, navi.routes.size)
        client.close()
    }

    @Test
    fun theDestinationToolsAreNotOfferedUntilAdopted() = runBlocking {
        val client = client(serve(NavigationFixtures.catalogWith("undecided")))
        for (name in listOf("find_navigation_place", "set_navigation_destination")) {
            val error = runCatching {
                withTimeout(5_000) { client.callTool(name, mapOf("candidateId" to "coex")) }
            }
            assertTrue(name, error.isFailure)
        }
        assertEquals(0, navi.routes.size)
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

    private companion object {
        /** Shorter than the catalog's interval so the socket tests stay quick. */
        const val POLL_MILLIS = 50L
    }
}
