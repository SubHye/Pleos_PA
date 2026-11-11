package com.example.pleos_pa.navigation

import android.car.Car
import android.car.CarOccupantZoneManager
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.view.Display

/**
 * Service-owned policy source; closing the Activity cannot remove the MCP execution guard. All
 * methods and callbacks run on the service main looper. No cached unrestricted fallback.
 */
class CarNavigationGuard(private val context: Context, private val handler: Handler) {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var active = false
    private var generation = 0

    fun start() {
        close()
        active = true
        val token = ++generation
        try {
            val created =
                Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready
                    ->
                    if (!active || token != generation) return@createCar
                    car = connected
                    manager = null
                    if (ready) {
                        try {
                            manager =
                                connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE)
                                    as? CarUxRestrictionsManager
                        } catch (_: RuntimeException) {
                            manager = null
                        }
                    }
                }
            if (!active || token != generation) created?.disconnect() else car = created
        } catch (_: RuntimeException) {
            manager = null
        } catch (_: LinkageError) {
            manager = null
        }
    }

    /**
     * Synchronously refresh immediately before SDK handoff; error, loss or wrong display blocks.
     */
    fun blockCode(): String? {
        if (!active) return DestinationSetter.DRIVING_STATE_UNKNOWN
        return try {
            val ux = manager?.currentCarUxRestrictions
            val uxBlock =
                navigationRestrictionCode(
                    ux?.isRequiresDistractionOptimization,
                    ux?.activeRestrictions,
                    true,
                )
            if (uxBlock != null) return uxBlock
            val zones = car?.getCarManager(Car.CAR_OCCUPANT_ZONE_SERVICE) as? CarOccupantZoneManager
            val mine = zones?.myOccupantZone
            val display = mine?.let {
                zones.getDisplayForOccupant(it, CarOccupantZoneManager.DISPLAY_TYPE_MAIN)
            }
            // This service's default Car UX manager is for display 0. Never use a passenger's UX.
            val driverMain =
                mine?.occupantType == CarOccupantZoneManager.OCCUPANT_TYPE_DRIVER &&
                    display?.displayId == Display.DEFAULT_DISPLAY
            navigationRestrictionCode(
                ux?.isRequiresDistractionOptimization,
                ux?.activeRestrictions,
                driverMain,
            )
        } catch (_: RuntimeException) {
            DestinationSetter.DRIVING_STATE_UNKNOWN
        } catch (_: LinkageError) {
            DestinationSetter.DRIVING_STATE_UNKNOWN
        }
    }

    fun close() {
        active = false
        generation++
        manager = null
        try {
            car?.disconnect()
        } catch (_: RuntimeException) {}
        car = null
    }
}
