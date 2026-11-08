package com.example.pleos_pa.connection

import com.example.pleos_pa.bridge.BridgeEndpoint
import java.io.IOException
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSessionTest {
    private val steps = mutableListOf<String>()
    private val binds = ArrayDeque<Runnable>()
    private val releases = ArrayDeque<() -> Unit>()
    private val reported = mutableListOf<ConnectionStatus>()
    private val ended = mutableListOf<ConnectionStatus>()
    private var bindFails = false
    private var created = 0
    private val sessions = mutableListOf<ConnectionSession>()

    private val controller: ConnectionController by lazy {
        ConnectionController(::session, { reported += it }, { ended += it })
    }

    private fun session(): ConnectionSession {
        val id = ++created
        val endpoint =
            object : BridgeEndpoint {
                override fun start() {
                    if (bindFails) throw IOException("in use")
                    steps += "bind$id"
                }

                override fun stop() {
                    steps += "sockets$id"
                }
            }
        val session =
            ConnectionSession(
                { steps += "admission$id" },
                endpoint,
                { released ->
                    steps += "reader$id"
                    releases += released
                },
                Executor { binds += it },
            ) { session, status ->
                controller.onStatus(session, status)
            }
        sessions += session
        return session
    }

    private fun finishBind() = binds.removeFirst().run()

    private fun release() = releases.removeFirst()()

    @Test
    fun startingReportsPreparingThenConnected() {
        controller.start()
        assertEquals(listOf<ConnectionStatus>(ConnectionStatus.Starting), reported)

        finishBind()
        assertEquals(ConnectionStatus.Connected(), reported.last())
        assertTrue(ended.isEmpty())
    }

    @Test
    fun aFailedBindEndsWithAFixedCodeAndNoRetry() {
        bindFails = true
        controller.start()
        finishBind()
        release()

        assertEquals(
            listOf<ConnectionStatus>(ConnectionStatus.Failed("BRIDGE_START_FAILED")),
            ended,
        )
        assertEquals(1, created)
    }

    @Test
    fun stoppingClosesInOrderAndReportsStoppedOnlyAfterRelease() {
        controller.start()
        finishBind()
        steps.clear()

        controller.stop()
        assertEquals(listOf("admission1", "sockets1", "reader1"), steps)
        assertTrue(ended.isEmpty())

        release()
        assertEquals(listOf<ConnectionStatus>(ConnectionStatus.Stopped), ended)
    }

    @Test
    fun stoppingWhileStartingNeverReportsConnected() {
        controller.start()
        controller.stop()
        finishBind()
        release()

        assertTrue(ConnectionStatus.Connected() !in reported)
        assertEquals(listOf<ConnectionStatus>(ConnectionStatus.Stopped), ended)
        assertTrue("bind1" !in steps)
    }

    @Test
    fun resumingDuringTeardownStartsANewSessionAfterRelease() {
        controller.start()
        finishBind()
        controller.stop()

        controller.start()
        assertEquals(1, created)

        release()
        assertEquals(2, created)
        assertTrue(ended.isEmpty())
    }

    @Test
    fun theLastCommandWins() {
        controller.start()
        finishBind()
        controller.stop()
        controller.start()
        controller.stop()

        release()
        assertEquals(1, created)
        assertEquals(listOf<ConnectionStatus>(ConnectionStatus.Stopped), ended)
    }

    @Test
    fun aRepeatedStopTearsDownOnce() {
        controller.start()
        finishBind()
        controller.stop()
        controller.stop()

        assertEquals(1, steps.count { it == "reader1" })
        release()
        assertEquals(1, ended.size)
    }

    @Test
    fun aLostPeerMarksTheConnectionUntilTheNextRequest() {
        controller.start()
        finishBind()

        controller.peerClosedDuringRead()
        assertEquals(ConnectionStatus.Connected(interrupted = true), reported.last())

        controller.requestReceived()
        assertEquals(ConnectionStatus.Connected(), reported.last())
    }

    @Test
    fun aRequestOnAHealthyConnectionReportsNothing() {
        controller.start()
        finishBind()
        val before = reported.size

        controller.requestReceived()
        assertEquals(before, reported.size)
    }

    @Test
    fun aLostPeerChangesNothingBeforeConnectingOrDuringTeardown() {
        controller.start()
        controller.peerClosedDuringRead()
        assertEquals(listOf<ConnectionStatus>(ConnectionStatus.Starting), reported)

        finishBind()
        controller.stop()
        val before = reported.size
        controller.peerClosedDuringRead()
        controller.requestReceived()
        assertEquals(before, reported.size)
    }

    @Test
    fun aRemovedScreenCannotClearTheNewerOne() {
        val older: (ConnectionStatus) -> Unit = {}
        val seen = mutableListOf<ConnectionStatus>()
        val newer: (ConnectionStatus) -> Unit = { seen += it }
        ConnectionStatusBoard.observe(older)
        ConnectionStatusBoard.observe(newer)
        seen.clear()

        ConnectionStatusBoard.remove(older)
        ConnectionStatusBoard.publish(ConnectionStatus.Starting)
        assertEquals(listOf<ConnectionStatus>(ConnectionStatus.Starting), seen)

        ConnectionStatusBoard.remove(newer)
        ConnectionStatusBoard.publish(ConnectionStatus.Stopped)
        assertEquals(1, seen.size)
    }

    @Test
    fun aFailedConnectionIsNoLongerActive() {
        bindFails = true
        controller.start()
        assertTrue(controller.active)

        finishBind()
        assertTrue(controller.active)

        release()
        assertFalse(controller.active)
    }

    @Test
    fun aRepeatedStartKeepsOneSession() {
        controller.start()
        controller.start()
        assertEquals(1, created)
    }

    @Test
    fun anOldSessionsStatusIsIgnored() {
        controller.start()
        finishBind()
        controller.stop()
        release()
        controller.start()
        val before = reported.size

        controller.onStatus(sessions.first(), ConnectionStatus.Failed("LATE"))

        assertEquals(before, reported.size)
        assertEquals(listOf<ConnectionStatus>(ConnectionStatus.Stopped), ended)
    }

    @Test
    fun stoppingWithNothingRunningEndsTheService() {
        controller.stop()
        assertEquals(listOf<ConnectionStatus>(ConnectionStatus.Stopped), ended)
    }
}
