package com.example.pleos_pa.bridge

import com.example.pleos_pa.contract.BridgeLimits
import com.example.pleos_pa.contract.BridgeResult
import com.example.pleos_pa.contract.ReadSelection
import com.example.pleos_pa.contract.VehicleReadCatalog
import com.example.pleos_pa.contract.VehicleReadRequest
import com.example.pleos_pa.contract.VehicleReadValidator
import com.example.pleos_pa.vehicle.ReadAdmission
import com.example.pleos_pa.vehicle.ReadScheduler
import com.example.pleos_pa.vehicle.ReadTicket
import com.example.pleos_pa.vehicle.VehicleReader
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** What one accepted request should do. [respond] with null closes without an answer. */
fun interface BridgeGateway {
    /** Called on a bridge worker. Returns a function that gives the request up. */
    fun submit(
        request: VehicleReadRequest,
        receivedAtMillis: Long,
        respond: (BridgeReply?) -> Unit,
    ): () -> Unit
}

/** What the connection status needs to hear from the server. May be called from any thread. */
interface BridgeEvents {
    fun requestReceived()

    fun peerClosedDuringRead()

    /** The listener died while the bridge was meant to be up. */
    fun listenerFailed()

    fun ownReplyRejected()

    fun finished(requestId: String, query: String, result: String, elapsedMillis: Long)
}

/**
 * Admits requests on the reader's dispatcher. After [close] nothing new reaches the reader and a
 * request that still arrives learns the connection was stopped.
 */
class ReaderBridgeGateway(
    catalog: VehicleReadCatalog,
    private val reader: VehicleReader,
    private val scheduler: ReadScheduler,
    private val events: BridgeEvents,
) : BridgeGateway {
    private val validator = VehicleReadValidator(catalog)
    @Volatile private var open = true

    override fun submit(
        request: VehicleReadRequest,
        receivedAtMillis: Long,
        respond: (BridgeReply?) -> Unit,
    ): () -> Unit {
        val ticket = AtomicReference<ReadTicket?>()
        val abandoned = AtomicBoolean()
        fun finish(reply: BridgeReply?, result: String) {
            events.finished(
                request.requestId,
                request.query,
                result,
                scheduler.nowMillis() - receivedAtMillis,
            )
            respond(reply)
        }
        scheduler.execute {
            if (!open) {
                finish(BridgeHttp.stopped(request.requestId), "CONNECTION_STOPPED")
                return@execute
            }
            if (abandoned.get()) {
                finish(null, "CANCELLED")
                return@execute
            }
            val elapsed = scheduler.nowMillis() - receivedAtMillis
            val budget = request.remainingMs - elapsed - REPLY_MARGIN_MILLIS
            val admission =
                reader.read(request, budget) { response ->
                    val text = response.toJson().toString()
                    val selection = ReadSelection(request.properties, request.areas)
                    val verdict =
                        validator.validate(200, text, request.requestId, request.query, selection)
                    if (verdict is BridgeResult.Accepted) {
                        finish(BridgeReply(200, text), response.outcome.name)
                    } else {
                        events.ownReplyRejected()
                        finish(null, "OWN_REPLY_REJECTED")
                    }
                }
            when (admission) {
                is ReadAdmission.Accepted -> ticket.set(admission.ticket)
                ReadAdmission.Busy -> finish(BridgeHttp.busy(request.requestId), "BUSY")
                ReadAdmission.Unavailable ->
                    finish(BridgeHttp.notReady(request.requestId), "APP_NOT_READY")
                ReadAdmission.NothingSelected -> finish(BridgeHttp.invalid(400), "INVALID_REQUEST")
            }
        }
        return {
            abandoned.set(true)
            scheduler.execute { ticket.get()?.let(reader::cancel) }
        }
    }

    fun close() {
        open = false
    }

    private companion object {
        /**
         * Ends the read early enough that a partial answer reaches Node before its own deadline.
         */
        const val REPLY_MARGIN_MILLIS = 300L
    }
}

/** The socket half of a session. */
interface BridgeEndpoint {
    /** Blocking; called on the bind executor. */
    @Throws(IOException::class) fun start()

    fun stop()
}

/**
 * Loopback-only, single-route HTTP endpoint with no client authentication. One acceptor, a few
 * worker slots so concurrent requests can be told they are busy, and one deadline scheduler. A
 * connection without a free slot is closed, never queued. Each connection carries one request.
 */
class BridgeServer(
    private val catalog: VehicleReadCatalog,
    private val gateway: BridgeGateway,
    private val events: BridgeEvents,
    private val clock: () -> Long,
    private val port: Int = catalog.bridge.port,
    private val readDeadlineMillis: Long = 1_000,
) : BridgeEndpoint {
    private val limits: BridgeLimits = catalog.bridge
    private val slots = Semaphore(WORKERS)
    private val workers =
        Executors.newFixedThreadPool(WORKERS) {
            Thread(it, "bridge-worker").apply { isDaemon = true }
        }
    private val watchdog =
        ScheduledThreadPoolExecutor(1) { Thread(it, "bridge-deadline").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var http: BridgeHttp? = null
    @Volatile private var stopped = false

    /** The bound port, which differs from the catalog's only in tests. */
    val localPort: Int
        get() = listener?.localPort ?: -1

    /** Binds 127.0.0.1 only. Blocking; call off the main thread. */
    @Throws(IOException::class)
    override fun start() {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(
                InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port),
                WORKERS,
            )
        } catch (error: IOException) {
            socket.close()
            throw error
        }
        http = BridgeHttp(limits.path, "127.0.0.1:${socket.localPort}", limits.maxRequestBytes)
        listener = socket
        if (stopped) socket.close()
        Thread({ acceptLoop(socket) }, "bridge-accept").apply { isDaemon = true }.start()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!stopped) {
            val socket =
                try {
                    server.accept()
                } catch (_: IOException) {
                    if (!stopped) events.listenerFailed()
                    return
                }
            if (!socket.inetAddress.isLoopbackAddress) {
                closeQuietly(socket)
                continue
            }
            val acceptedAt = clock()
            sockets.add(socket)
            if (stopped) {
                closeQuietly(socket)
                return
            }
            val closeAll = schedule(socket, limits.deadlineMs)
            val closeUnread = schedule(socket, readDeadlineMillis)
            val admitted =
                slots.tryAcquire() &&
                    try {
                        workers.execute {
                            try {
                                handle(socket, acceptedAt, closeUnread, closeAll)
                            } finally {
                                slots.release()
                            }
                        }
                        true
                    } catch (_: RejectedExecutionException) {
                        slots.release()
                        false
                    }
            if (!admitted) {
                closeUnread?.cancel(false)
                closeAll?.cancel(false)
                sockets.remove(socket)
                closeQuietly(socket)
            }
        }
    }

    private fun schedule(socket: Socket, delay: Long): Future<*>? =
        try {
            watchdog.schedule({ closeQuietly(socket) }, delay, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            closeQuietly(socket)
            null
        }

    private fun handle(
        socket: Socket,
        acceptedAt: Long,
        closeUnread: Future<*>?,
        closeAll: Future<*>?,
    ) {
        try {
            val input = BufferedInputStream(socket.getInputStream(), 2 * 1024)
            val reply = exchange(socket, input, acceptedAt, closeUnread) ?: return
            socket.getOutputStream().apply {
                write(reply.toBytes())
                flush()
            }
            socket.shutdownOutput()
            socket.soTimeout = DRAIN_MILLIS
            drain(input)
        } catch (_: IOException) {} finally {
            closeUnread?.cancel(false)
            closeAll?.cancel(false)
            sockets.remove(socket)
            closeQuietly(socket)
        }
    }

    /** Returns the one reply for this connection, or null to close without writing. */
    private fun exchange(
        socket: Socket,
        input: InputStream,
        acceptedAt: Long,
        closeUnread: Future<*>?,
    ): BridgeReply? {
        val head = readHead(input) ?: return null
        if (head.isEmpty()) return BridgeHttp.invalid(400)
        val length =
            when (val check = http!!.checkHead(head)) {
                is HeadCheck.Rejected -> return check.reply
                is HeadCheck.Accepted -> check.contentLength
            }
        val body = readExactly(input, length) ?: return null
        closeUnread?.cancel(false)
        if (input.available() > 0) return BridgeHttp.invalid(400)
        val request = parse(body) ?: return BridgeHttp.invalid(400)
        events.requestReceived()
        val receivedAt = clock()
        if (stopped) return null
        val reply = CompletableFuture<BridgeReply?>()
        val abandon = gateway.submit(request, receivedAt) { reply.complete(it) }
        return awaitReply(socket, input, reply, abandon, acceptedAt)
    }

    /** Waits for the reply while watching for the peer leaving, which ends only this request. */
    private fun awaitReply(
        socket: Socket,
        input: InputStream,
        reply: CompletableFuture<BridgeReply?>,
        abandon: () -> Unit,
        acceptedAt: Long,
    ): BridgeReply? {
        socket.soTimeout = PEER_POLL_MILLIS
        while (!reply.isDone) {
            if (clock() - acceptedAt >= limits.deadlineMs) {
                abandon()
                return null
            }
            val next =
                try {
                    input.read()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (error: IOException) {
                    abandon()
                    if (!stopped) events.peerClosedDuringRead()
                    throw error
                }
            if (next < 0) {
                abandon()
                if (!stopped) events.peerClosedDuringRead()
                return null
            }
        }
        return reply.get()
    }

    private fun parse(body: ByteArray): VehicleReadRequest? {
        val text =
            try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString()
            } catch (_: CharacterCodingException) {
                return null
            }
        if (!StrictRequestSyntax.isWellFormed(text)) return null
        return VehicleReadRequest.parse(catalog, text)
    }

    /** Reads up to the blank line; "" when the head exceeds the limit, null on EOF. */
    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var match = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            out.write(byte)
            match =
                when {
                    byte == '\r'.code && (match == 0 || match == 2) -> match + 1
                    byte == '\n'.code && (match == 1 || match == 3) -> match + 1
                    byte == '\r'.code -> 1
                    else -> 0
                }
            if (match == 4) return out.toString(Charsets.ISO_8859_1.name()).dropLast(4)
            if (out.size() >= MAX_HEAD_BYTES) return ""
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray? {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) return null
            read += count
        }
        return body
    }

    /** Lets the peer read the reply before close instead of seeing a reset. */
    private fun drain(input: InputStream) {
        val sink = ByteArray(1024)
        var total = 0
        while (total < 64 * 1024) {
            val count = input.read(sink)
            if (count < 0) return
            total += count
        }
    }

    /** Idempotent. Closes the listener and every open socket; in-flight workers are interrupted. */
    override fun stop() {
        if (stopped) return
        stopped = true
        listener?.let(::closeQuietly)
        sockets.forEach(::closeQuietly)
        workers.shutdownNow()
        watchdog.shutdownNow()
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: IOException) {}
    }

    private companion object {
        const val WORKERS = 8
        const val MAX_HEAD_BYTES = 8 * 1024
        const val DRAIN_MILLIS = 250
        const val PEER_POLL_MILLIS = 100
    }
}
