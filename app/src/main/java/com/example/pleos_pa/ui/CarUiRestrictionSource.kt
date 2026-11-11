package com.example.pleos_pa.ui

import android.app.Activity
import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager

/**
 * Public Car UX reads for this Activity; no driving-state permission, shell identity or vehicle
 * writes.
 */
class CarUiRestrictionSource(
    private val activity: Activity,
    private val observe: ((CarUxObservation) -> Unit)? = null,
) : UiRestrictionSource {
    private val handler = Handler(Looper.getMainLooper())
    private var changed: ((UiRestrictions) -> Unit)? = null
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var generation = 0
    private var active = false
    private var displayId = -1
    private var displays: DisplayManager? = null
    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = invalidateDisplay()

            override fun onDisplayRemoved(id: Int) = invalidateDisplay()

            override fun onDisplayChanged(id: Int) {
                if (id == displayId) invalidateDisplay()
            }
        }
    private val poll =
        object : Runnable {
            override fun run() {
                if (!active) return
                sample("poll")
                if (active) handler.postDelayed(this, 1_000)
            }
        }

    override fun start(changed: (UiRestrictions) -> Unit) {
        stop()
        this.changed = changed
        changed(UiRestrictions.UNKNOWN)
        active = true
        val token = ++generation
        emit("STARTING", "connect")
        try {
            @Suppress("DEPRECATION")
            displayId =
                activity.getSystemService(WindowManager::class.java).defaultDisplay.displayId
            displays = activity.getSystemService(DisplayManager::class.java)
            displays?.registerDisplayListener(displayListener, handler)
            val created =
                Car.createCar(activity, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) {
                    connected,
                    ready ->
                    if (!active || token != generation) return@createCar
                    car = connected
                    if (!ready) {
                        emit("UNAVAILABLE", "service_not_ready")
                        manager = null
                        this.changed?.invoke(UiRestrictions.UNKNOWN)
                        return@createCar
                    }
                    emit("CONNECTED", "service_ready")
                    try {
                        manager?.unregisterListener()
                        manager =
                            connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE)
                                as? CarUxRestrictionsManager
                        manager?.registerListener { delivered ->
                            if (active && token == generation) {
                                record("CALLBACK", "ux_callback", delivered)
                                sample("callback_refresh")
                            }
                        }
                        sample("initial")
                    } catch (_: RuntimeException) {
                        invalidateDisplay()
                    }
                }
            if (!active || token != generation) {
                created?.disconnect()
                return
            }
            car = created
            if (created == null) {
                invalidateDisplay()
                return
            }
            handler.post(poll)
        } catch (_: RuntimeException) {
            invalidateDisplay()
        }
    }

    private fun sample(origin: String) {
        try {
            val restrictions = manager?.currentCarUxRestrictions
            record("SAMPLE", origin, restrictions)
            changed?.invoke(
                if (restrictions == null) UiRestrictions.UNKNOWN
                else
                    UiRestrictions.observed(
                        restrictions.isRequiresDistractionOptimization,
                        restrictions.activeRestrictions,
                        SystemClock.elapsedRealtime(),
                        // COM-9: occupant-zone mapping and restricted-state UI policy are not
                        // verified. DO=false/flags=0 alone must not unlock history. Deliberately
                        // no runtime toggle, intent extra, remote input or build flag can bypass.
                        displayPolicyVerified = false,
                    )
            )
        } catch (error: RuntimeException) {
            emit("UNAVAILABLE", "read_error_${error.javaClass.simpleName}")
            invalidateDisplay()
        }
    }

    private fun invalidateDisplay() {
        emit("INVALIDATED", "display_or_connection_error")
        val notify = changed
        stop()
        notify?.invoke(UiRestrictions.UNKNOWN)
    }

    override fun stop() {
        val wasActive = active || car != null
        var clean = true
        active = false
        generation++
        handler.removeCallbacks(poll)
        try {
            displays?.unregisterDisplayListener(displayListener)
        } catch (_: RuntimeException) {
            clean = false
        }
        displays = null
        try {
            manager?.unregisterListener()
        } catch (_: RuntimeException) {
            clean = false
        }
        manager = null
        try {
            car?.disconnect()
        } catch (_: RuntimeException) {
            clean = false
        }
        car = null
        changed = null
        if (wasActive) emit("STOPPED", "observer_cleanup", clean = clean)
    }

    private fun record(event: String, origin: String, restrictions: CarUxRestrictions?) {
        if (observe == null) return
        emit(
            if (restrictions == null) "UNAVAILABLE" else event,
            origin,
            restrictions?.isRequiresDistractionOptimization,
            restrictions?.activeRestrictions,
            maxString = restrictions?.maxRestrictedStringLength,
            maxItems = restrictions?.maxCumulativeContentItems,
            maxDepth = restrictions?.maxContentDepth,
        )
    }

    private fun emit(
        event: String,
        origin: String,
        doRequired: Boolean? = null,
        flags: Int? = null,
        clean: Boolean? = null,
        maxString: Int? = null,
        maxItems: Int? = null,
        maxDepth: Int? = null,
    ) {
        val receiver = observe ?: return
        // Diagnostics must never change fail-closed behavior if a collector fails.
        try {
            receiver(
                CarUxObservation(
                    event,
                    origin,
                    displayId,
                    SystemClock.elapsedRealtime(),
                    doRequired,
                    flags,
                    clean,
                    maxString,
                    maxItems,
                    maxDepth,
                )
            )
        } catch (_: RuntimeException) {}
    }
}
