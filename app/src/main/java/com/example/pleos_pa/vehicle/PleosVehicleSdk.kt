package com.example.pleos_pa.vehicle

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * One Vehicle instance on one worker thread, so SDK calls stay off the UI thread and release comes
 * after every call that was issued.
 */
class PleosVehicleSdk(context: Context) : VehicleSdk {
    private val context = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private var vehicle: Vehicle? = null
    private var initialization: Exception? = null
    private var initialized = false
    @Volatile private var closing = false

    override fun supports(property: String) = property in SDK_PROPERTIES

    override fun checkCapability(
        property: String,
        active: () -> Boolean,
        complete: (PropertyCapability) -> Unit,
        failed: (Exception) -> Unit,
    ) =
        submit(active, failed) { connected ->
            SDK_PROPERTIES.getValue(property).checkCapability(connected, complete, failed)
        }

    override fun read(
        property: String,
        area: String?,
        active: () -> Boolean,
        complete: (Any?, Long) -> Unit,
        failed: (Exception, Long) -> Unit,
    ) {
        val failedNow: (Exception) -> Unit = { failed(it, System.currentTimeMillis()) }
        submit(active, failedNow) { connected ->
            SDK_PROPERTIES.getValue(property)
                .read(
                    connected,
                    area,
                    { value -> complete(contractValue(value), System.currentTimeMillis()) },
                    failedNow,
                )
        }
    }

    override fun close(closed: () -> Unit) {
        closing = true
        // Runs after every call already queued, and anything queued later returns at the guard.
        worker.execute {
            try {
                vehicle?.release()
            } catch (error: Exception) {
                Log.w(TAG, "release failed: ${error.javaClass.simpleName}")
            } finally {
                vehicle = null
                closed()
            }
        }
        worker.shutdown()
    }

    private fun submit(
        active: () -> Boolean,
        failed: (Exception) -> Unit,
        call: (Vehicle) -> Unit,
    ) {
        worker.execute {
            if (closing || !active()) return@execute
            try {
                val connected = connect()
                if (closing || !active()) return@execute
                call(connected)
            } catch (error: Exception) {
                failed(error)
            }
        }
    }

    private fun connect(): Vehicle {
        if (!initialized) {
            initialized = true
            try {
                vehicle = Vehicle(context).also { it.initialize() }
            } catch (error: Exception) {
                initialization = error
            }
        }
        initialization?.let { throw it }
        return vehicle!!
    }

    private companion object {
        const val TAG = "PleosVehicleSdk"
    }
}
