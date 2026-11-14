package com.example.pleos_pa.bridge

import com.example.pleos_pa.contract.BridgeResult
import com.example.pleos_pa.contract.ReadSelection
import com.example.pleos_pa.contract.VehicleReadCatalog
import com.example.pleos_pa.contract.VehicleReadRequest
import com.example.pleos_pa.contract.VehicleReadResponse
import com.example.pleos_pa.contract.VehicleReadValidator
import com.example.pleos_pa.ui.ReadPresentation
import com.example.pleos_pa.ui.RequestHistory
import com.example.pleos_pa.vehicle.ReadAdmission
import com.example.pleos_pa.vehicle.ReadScheduler
import com.example.pleos_pa.vehicle.ReadTicket
import com.example.pleos_pa.vehicle.VehicleReader
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** What one accepted request should do. [respond] with null means no answer is given. */
fun interface BridgeGateway {
    /** Returns a function that gives the request up. */
    fun submit(
        request: VehicleReadRequest,
        acceptedAtMillis: Long,
        respond: (BridgeReply?) -> Unit,
    ): () -> Unit
}

/** What the connection status needs to hear from the server. May be called from any thread. */
interface BridgeEvents {
    fun requestReceived()

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
    private val presentation: ReadPresentation = ReadPresentation.None,
) : BridgeGateway {
    private val validator = VehicleReadValidator(catalog)
    @Volatile private var open = true

    override fun submit(
        request: VehicleReadRequest,
        acceptedAtMillis: Long,
        respond: (BridgeReply?) -> Unit,
    ): () -> Unit {
        val ticket = AtomicReference<ReadTicket?>()
        val abandoned = AtomicBoolean()
        var record: RequestHistory.Token? = null
        fun finish(reply: BridgeReply?, result: String, response: VehicleReadResponse? = null) {
            presentation.finish(record, result, response)
            events.finished(
                request.requestId,
                request.query,
                result,
                scheduler.nowMillis() - acceptedAtMillis,
            )
            respond(reply)
        }
        scheduler.execute {
            record = presentation.begin(request)
            if (!open) {
                finish(BridgeReplies.stopped(request.requestId), "CONNECTION_STOPPED")
                return@execute
            }
            if (abandoned.get()) {
                finish(null, "CANCELLED")
                return@execute
            }
            val elapsed = scheduler.nowMillis() - acceptedAtMillis
            val budget = request.remainingMs - elapsed - REPLY_MARGIN_MILLIS
            val admission =
                reader.read(request, budget) { response ->
                    val text = response.toJson().toString()
                    val selection = ReadSelection(request.properties, request.areas)
                    val verdict =
                        validator.validate(200, text, request.requestId, request.query, selection)
                    if (verdict is BridgeResult.Accepted) {
                        finish(BridgeReply(200, text), response.outcome.name, response)
                    } else {
                        events.ownReplyRejected()
                        finish(null, "OWN_REPLY_REJECTED")
                    }
                }
            when (admission) {
                is ReadAdmission.Accepted -> ticket.set(admission.ticket)
                ReadAdmission.Busy -> finish(BridgeReplies.busy(request.requestId), "BUSY")
                ReadAdmission.Unavailable ->
                    finish(BridgeReplies.notReady(request.requestId), "APP_NOT_READY")
                ReadAdmission.NothingSelected ->
                    finish(BridgeReplies.invalid(400), "INVALID_REQUEST")
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
         * Ends the read early enough that a partial answer is back before the MCP call's deadline.
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
