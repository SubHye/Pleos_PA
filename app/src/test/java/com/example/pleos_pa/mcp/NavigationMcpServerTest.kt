package com.example.pleos_pa.mcp

import com.example.pleos_pa.bridge.BridgeEvents
import com.example.pleos_pa.bridge.ReaderBridgeGateway
import com.example.pleos_pa.contract.ContractFixtures
import com.example.pleos_pa.contract.DestinationFields
import com.example.pleos_pa.contract.NavigationCatalog
import com.example.pleos_pa.contract.NavigationFixtures
import com.example.pleos_pa.contract.WaypointFields
import com.example.pleos_pa.contract.jsonEquivalent
import com.example.pleos_pa.navigation.DestinationReader
import com.example.pleos_pa.navigation.DestinationSetter
import com.example.pleos_pa.navigation.FakeNaviSdk
import com.example.pleos_pa.navigation.NavTaskCoordinator
import com.example.pleos_pa.navigation.WaypointChanger
import com.example.pleos_pa.navigation.WaypointReader
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
    private lateinit var destinations: DestinationReader
    private val waypoints = WaypointReader(navi, scheduler, 10_000L) { destinations.unavailable() }
    private var server: EmbeddedMcpServer? = null
    @Volatile private var drivingBlock: String? = null

    private fun serve(navigationCatalog: NavigationCatalog): Int {
        destinations =
            DestinationReader(navi, scheduler, "simulation", 10_000L, waypoints::delivered)
        val coordinator = NavTaskCoordinator.shared()
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
                shared = coordinator,
            )
        val changer =
            WaypointChanger(
                navi,
                destinations,
                waypoints,
                scheduler,
                coordinator,
                navigationCatalog.baselineDeadlineMs,
                navigationCatalog.confirmTimeoutMs,
                POLL_MILLIS,
                navigationCatalog.matchToleranceDegrees,
                navigationCatalog.maxWaypoints,
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
                NavigationWaypointsTool(
                    navigationCatalog,
                    places,
                    waypoints,
                    "simulation",
                    events,
                    scheduler::nowMillis,
                ),
                NavigationWaypointChangeTool.add(
                    navigationCatalog,
                    places,
                    changer,
                    "simulation",
                    events,
                    scheduler::nowMillis,
                ),
                NavigationWaypointChangeTool.remove(
                    navigationCatalog,
                    places,
                    changer,
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

        assertEquals(26, tools.length())
        val expected = JSONArray(NavigationFixtures.text("tool-list"))
        assertEquals(6, expected.length())
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

    @Test
    fun theWaypointToolsAreNotOfferedUntilAdopted() = runBlocking {
        val client = client(serve(NavigationFixtures.catalogWith("undecided")))
        val names = withTimeout(5_000) { client.listTools() }.tools.map { it.name }
        assertTrue(names.none { "waypoint" in it })
        for (name in
            listOf(
                "get_navigation_waypoints",
                "add_navigation_waypoint",
                "remove_navigation_waypoint",
            )) {
            val error = runCatching {
                withTimeout(5_000) { client.callTool(name, mapOf("candidateId" to "coex")) }
            }
            assertTrue(name, error.isFailure)
        }
        assertEquals(0, navi.waypointRequests)
        assertEquals(0, navi.addedWaypoints.size)
        client.close()
    }

    @Test
    fun aWaypointReadAnswersWithTheListedPlaceNamedByItsKey() = runBlocking {
        val client = client(serve(NavigationFixtures.catalogWith("adopted")))
        val coex = NavigationFixtures.places.place("coex")!!
        val call =
            async(Dispatchers.IO) {
                withTimeout(5_000) { client.callTool("get_navigation_waypoints", emptyMap()) }
            }
        waitFor { navi.waypointRequests == 1 }
        navi.answerWaypoints(
            listOf(
                WaypointFields("코엑스", "동", coex.poiId),
                WaypointFields("시청", "서울 중구", "city-hall"),
            )
        )
        val result = call.await()

        assertEquals(null, result.isError)
        val structured = JSONObject(result.structuredContent.toString())
        assertEquals("HAS_WAYPOINTS", structured.getString("status"))
        val list = structured.getJSONArray("waypoints")
        assertEquals("coex", list.getJSONObject(0).getString("candidateId"))
        assertTrue(list.getJSONObject(1).isNull("candidateId"))
        assertTrue(!structured.toString().contains("latitude"))
        waitFor { finished.isNotEmpty() }
        assertEquals(listOf("navigation_waypoints:HAS_WAYPOINTS"), finished.toList())
        client.close()
    }

    @Test
    fun addingAWaypointAnswersOnlyAfterItIsReadBackWithTheDestinationUnchanged() = runBlocking {
        val client = client(serve(NavigationFixtures.catalogWith("adopted")))
        val coex = NavigationFixtures.places.place("coex")!!
        val call =
            async(Dispatchers.IO) {
                withTimeout(10_000) {
                    client.callTool("add_navigation_waypoint", mapOf("candidateId" to "coex"))
                }
            }
        // The reads before the request: a destination, then no waypoints.
        waitFor { navi.requests == 1 }
        navi.answer(FakeNaviSdk.OTHER)
        waitFor { navi.waypointRequests == 1 }
        assertEquals(0, navi.addedWaypoints.size)
        navi.answerWaypoints(emptyList())
        waitFor { navi.addedWaypoints.size == 1 }
        assertEquals(coex.latitude, navi.addedWaypoints.single().latitude, 0.0)

        // A list without it is not an answer.
        navi.answerWaypoints(emptyList())
        navi.answer(FakeNaviSdk.OTHER)
        waitFor { navi.waypointRequests >= 2 }
        assertTrue(!call.isCompleted)
        navi.answerWaypoints(listOf(WaypointFields("코엑스", "동", coex.poiId)))
        navi.answer(FakeNaviSdk.OTHER)
        val result = call.await()

        assertEquals(null, result.isError)
        val structured = JSONObject(result.structuredContent.toString())
        assertEquals("APPLIED", structured.getString("status"))
        assertEquals("coex", structured.getJSONObject("waypoint").getString("candidateId"))
        assertTrue(!structured.toString().contains("latitude"))
        waitFor { finished.isNotEmpty() }
        assertEquals(listOf("navigation_add_waypoint:APPLIED"), finished.toList())
        client.close()
    }

    @Test
    fun anUnknownCandidateOrAWrongArgumentNeverReachesTheWaypointSdk() = runBlocking {
        val client = client(serve(NavigationFixtures.catalogWith("adopted")))
        for (name in listOf("add_navigation_waypoint", "remove_navigation_waypoint")) {
            val unknown =
                withTimeout(5_000) { client.callTool(name, mapOf("candidateId" to "busan")) }
            assertEquals(name, true, unknown.isError)
            val structured = JSONObject(unknown.structuredContent.toString())
            assertEquals(name, "UNKNOWN_CANDIDATE", structured.getString("code"))
            assertEquals(name, false, structured.getBoolean("sent"))

            for (arguments in
                listOf(
                    emptyMap(),
                    mapOf("candidateId" to 3),
                    mapOf("candidateId" to "coex", "index" to "ALL"),
                )) {
                val refused = withTimeout(5_000) { client.callTool(name, arguments) }
                assertEquals("$name $arguments", true, refused.isError)
                assertEquals("$name $arguments", null, refused.structuredContent)
                assertTrue(
                    (refused.content.single() as TextContent)
                        .text
                        .startsWith("Input validation error:")
                )
            }
        }
        val extra =
            withTimeout(5_000) {
                client.callTool("get_navigation_waypoints", mapOf("candidateId" to "coex"))
            }
        assertEquals(true, extra.isError)
        assertEquals(0, navi.requests)
        assertEquals(0, navi.waypointRequests)
        assertEquals(0, navi.addedWaypoints.size)
        assertEquals(0, navi.removedWaypoints.size)
        client.close()
    }

    private companion object {
        /** Shorter than the catalog's interval so the socket tests stay quick. */
        const val POLL_MILLIS = 50L
    }
}
