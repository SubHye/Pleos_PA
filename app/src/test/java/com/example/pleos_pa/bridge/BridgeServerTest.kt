package com.example.pleos_pa.bridge

import com.example.pleos_pa.contract.ContractFixtures
import com.example.pleos_pa.vehicle.FakeVehicleSdk
import com.example.pleos_pa.vehicle.PropertyCapability
import com.example.pleos_pa.vehicle.ReadScheduler
import com.example.pleos_pa.vehicle.ReportedArea
import com.example.pleos_pa.vehicle.VehicleReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val ID = "11111111-1111-4111-8111-111111111111"

/** Exercises the real socket path. The SDK is a test double, so nothing here reads a vehicle. */
class BridgeServerTest {
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
    private val reader = VehicleReader(catalog, sdk, scheduler, "simulation")
    private val interruptions = CountDownLatch(1)
    private val received = CopyOnWriteArrayList<String>()
    private val events =
        object : BridgeEvents {
            override fun requestReceived() {
                received += "received"
            }

            override fun peerClosedDuringRead() = interruptions.countDown()

            override fun listenerFailed() {
                received += "listener-failed"
            }

            override fun ownReplyRejected() {
                received += "rejected"
            }

            override fun finished(
                requestId: String,
                query: String,
                result: String,
                elapsedMillis: Long,
            ) {
                received += result
            }
        }
    private val gateway = ReaderBridgeGateway(catalog, reader, scheduler, events)
    private val server = BridgeServer(catalog, gateway, events, { scheduler.nowMillis() }, port = 0)

    private fun body(query: String = "front_left_door_position", id: String = ID) =
        """{"requestId":"$id","remainingMs":9000,"query":"$query"}"""

    private fun rawRequest(
        body: String,
        path: String = catalog.bridge.path,
        extraHeaders: String = "",
        contentType: String = "application/json",
        host: String = "127.0.0.1:${server.localPort}",
        method: String = "POST",
    ): String {
        val size = body.toByteArray().size
        return "$method $path HTTP/1.1\r\nHost: $host\r\nContent-Type: $contentType\r\n" +
            "Content-Length: $size\r\n$extraHeaders\r\n$body"
    }

    private fun connect() =
        Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), server.localPort)

    private fun exchange(text: String): Pair<Int, String> =
        connect().use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(text.toByteArray())
            val answer = socket.getInputStream().readBytes().decodeToString()
            answer.substringAfter(' ').take(3).toInt() to answer
        }

    private fun answerBody(answer: String) = answer.substringAfter("\r\n\r\n")

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000
        while (!condition()) {
            assertTrue("condition not met in time", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }

    private fun answerDoor() {
        waitFor { sdk.calls.size == 1 }
        sdk.answerCapability(
            PropertyCapability("READ", listOf(ReportedArea("ROW_1_LEFT", 0.0, 1.0)))
        )
        waitFor { sdk.calls.size == 2 }
        sdk.answerRead(1)
    }

    @Before fun start() = server.start()

    @After
    fun stop() {
        server.stop()
        pool.shutdownNow()
    }

    @Test
    fun aValidRequestGetsAJsonAnswerFromTheReader() {
        val request = rawRequest(body())
        val done =
            Executors.newSingleThreadExecutor().submit<Pair<Int, String>> { exchange(request) }
        answerDoor()

        val (status, answer) = done.get(5, TimeUnit.SECONDS)
        assertEquals(200, status)
        assertTrue(answer.contains("Content-Type: application/json; charset=utf-8"))
        val json = JSONObject(answerBody(answer))
        assertEquals(ID, json.getString("requestId"))
        assertEquals("COMPLETE", json.getString("outcome"))
        assertEquals("simulation", json.getString("source"))
    }

    @Test
    fun theRequestFixturesGetTheSameVerdictHere() {
        sdk.unsupported += catalog.properties.keys
        for (case in ContractFixtures.cases("requests")) {
            val text = case.getJSONObject("body").toString()
            val (status, _) = exchange(rawRequest(text))
            val name = case.getString("name")
            if (case.getBoolean("accept")) {
                assertEquals(name, 503, status)
            } else {
                assertEquals(name, 400, status)
            }
        }
        assertTrue(sdk.calls.isEmpty())
    }

    @Test
    fun malformedBodiesNeverReachTheSdk() {
        for (text in
            listOf("${body()} trailing", """{"query":"a","query":"b"}""", "{'query':'a'}")) {
            assertEquals(text, 400, exchange(rawRequest(text)).first)
        }
        assertTrue(sdk.calls.isEmpty())
    }

    @Test
    fun contractFailuresKeepTheirWireShape() {
        val (status, answer) = exchange(rawRequest("not json"))
        assertEquals(400, status)
        assertEquals(
            setOf("ok", "code"),
            JSONObject(answerBody(answer)).keys().asSequence().toSet(),
        )
    }

    @Test
    fun oversizedBodyWrongTypeAndForeignRequestsAreRefused() {
        val big = "x".repeat(catalog.bridge.maxRequestBytes + 1)
        assertEquals(413, exchange(rawRequest(big)).first)
        assertEquals(415, exchange(rawRequest(body(), contentType = "text/plain")).first)
        assertEquals(403, exchange(rawRequest(body(), extraHeaders = "Origin: http://x\r\n")).first)
        assertEquals(403, exchange(rawRequest(body(), host = "example.com")).first)
        assertTrue(sdk.calls.isEmpty())
    }

    @Test
    fun otherRoutesAndMethodsAreRefused() {
        assertEquals(400, exchange(rawRequest(body(), path = "/v2/vehicle-read")).first)
        assertEquals(400, exchange(rawRequest(body(), method = "PUT")).first)
        assertTrue(sdk.calls.isEmpty())
    }

    @Test
    fun concurrentRequestsAreToldTheyAreBusy() {
        val workers = Executors.newFixedThreadPool(8)
        val first = workers.submit<Pair<Int, String>> { exchange(rawRequest(body())) }
        waitFor { sdk.calls.size == 1 }

        val others =
            (2..8).map { n ->
                val id = ID.dropLast(1) + n
                workers.submit<Pair<Int, String>> { exchange(rawRequest(body(id = id))) }
            }
        for (other in others) {
            val (status, answer) = other.get(5, TimeUnit.SECONDS)
            assertEquals(409, status)
            assertEquals("BUSY", JSONObject(answerBody(answer)).getString("code"))
        }

        answerDoor()
        assertEquals(200, first.get(5, TimeUnit.SECONDS).first)
        workers.shutdownNow()
    }

    @Test
    fun aPeerLeavingEndsOnlyItsOwnRequest() {
        val socket = connect()
        socket.getOutputStream().write(rawRequest(body()).toByteArray())
        waitFor { sdk.calls.size == 1 }

        socket.close()

        assertTrue(interruptions.await(3, TimeUnit.SECONDS))
        waitFor { "CANCELLED" in received }

        sdk.calls.clear()
        val next =
            Executors.newSingleThreadExecutor().submit<Pair<Int, String>> {
                exchange(rawRequest(body()))
            }
        answerDoor()
        assertEquals(200, next.get(5, TimeUnit.SECONDS).first)
    }

    @Test
    fun aRequestAfterAdmissionClosesLearnsItWasStopped() {
        gateway.close()

        val (status, answer) = exchange(rawRequest(body()))

        assertEquals(503, status)
        val json = JSONObject(answerBody(answer))
        assertEquals("CONNECTION_STOPPED", json.getString("code"))
        assertEquals(ID, json.getString("requestId"))
        assertTrue(sdk.calls.isEmpty())
    }

    @Test
    fun theReadBudgetCountsTheTimeSpentReceivingTheRequest() {
        val measured = CompletableFuture<Long>()
        val recording = BridgeGateway { _, acceptedAt, respond ->
            measured.complete(scheduler.nowMillis() - acceptedAt)
            respond(null)
            val abandon: () -> Unit = {}
            abandon
        }
        val slow = BridgeServer(catalog, recording, events, { scheduler.nowMillis() }, port = 0)
        slow.start()
        try {
            val request = rawRequest(body(), host = "127.0.0.1:${slow.localPort}")
            val headSize = request.length - body().length
            Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), slow.localPort).use {
                val output = it.getOutputStream()
                output.write(request.take(headSize).toByteArray())
                output.flush()
                Thread.sleep(400)
                output.write(request.drop(headSize).toByteArray())
                output.flush()
                assertTrue(measured.get(3, TimeUnit.SECONDS) >= 400)
            }
        } finally {
            slow.stop()
        }
    }

    @Test
    fun stoppingFreesThePortForTheNextSession() {
        val port = server.localPort
        assertNotEquals(-1, port)
        server.stop()

        val next = BridgeServer(catalog, gateway, events, { scheduler.nowMillis() }, port = port)
        next.start()
        assertEquals(port, next.localPort)
        next.stop()
    }

    private fun boundListener(target: BridgeServer = server): ServerSocket {
        val field = BridgeServer::class.java.getDeclaredField("listener")
        field.isAccessible = true
        return field.get(target) as ServerSocket
    }

    @Test
    fun itListensOnLoopbackOnly() {
        val address = boundListener().inetAddress
        assertTrue(address.isLoopbackAddress)
        assertFalse(address.isAnyLocalAddress)
    }

    @Test
    fun aListenerThatDiesIsReportedOnce() {
        boundListener().close()
        waitFor { "listener-failed" in received }
        assertEquals(1, received.count { it == "listener-failed" })
    }

    @Test
    fun stoppingIsNotReportedAsAListenerFailure() {
        server.stop()
        Thread.sleep(100)
        assertFalse("listener-failed" in received)
    }
}
