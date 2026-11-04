package com.example.pleos_pa.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * One Vehicle instance on one worker thread, so SDK calls stay off the UI thread and release comes
 * after every call that was issued. This build reads the single door property the connection
 * lifecycle needs; COM-20 adds the rest of the catalog.
 */
class PleosVehicleSdk(context: Context) : VehicleSdk {
    private val context = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private var vehicle: Vehicle? = null
    private var initialization: Exception? = null
    private var initialized = false
    @Volatile private var closing = false

    override fun supports(property: String) = property == DOOR_POSITION

    override fun checkCapability(
        property: String,
        active: () -> Boolean,
        complete: (PropertyCapability) -> Unit,
        failed: (Exception) -> Unit,
    ) =
        submit(active, failed) { connected ->
            connected
                .getDoor()
                .checkDoorPositionCapabilities(
                    { config: CapabilityConfig, areas: Map<DoorArea, AreaBounds<Int>> ->
                        complete(
                            PropertyCapability(
                                config.accessType.name,
                                areas.map { (area, bounds) ->
                                    ReportedArea(
                                        area.values.joinToString("+") { it.name },
                                        bounds.minValue.toDouble(),
                                        bounds.maxValue.toDouble(),
                                    )
                                },
                            )
                        )
                    },
                    failed,
                )
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
            connected
                .getDoor()
                .getCurrentDoorPosition(
                    doorArea(area!!),
                    { value: Int? -> complete(value, System.currentTimeMillis()) },
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

    private fun doorArea(area: String) =
        DoorArea(*area.split('+').map(DoorValue::valueOf).toTypedArray())

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
        const val DOOR_POSITION = "DOOR_POSITION"
    }
}
