package com.example.pleos_pa.ui

import android.app.Activity
import android.car.Car
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
class CarUiRestrictionSource(private val activity: Activity) : UiRestrictionSource {
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
                sample()
                if (active) handler.postDelayed(this, 1_000)
            }
        }

    override fun start(changed: (UiRestrictions) -> Unit) {
        stop()
        this.changed = changed
        changed(UiRestrictions.UNKNOWN)
        active = true
        val token = ++generation
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
                        manager = null
                        this.changed?.invoke(UiRestrictions.UNKNOWN)
                        return@createCar
                    }
                    try {
                        manager?.unregisterListener()
                        manager =
                            connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE)
                                as? CarUxRestrictionsManager
                        manager?.registerListener { if (active && token == generation) sample() }
                        sample()
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

    private fun sample() {
        try {
            val restrictions = manager?.currentCarUxRestrictions
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
        } catch (_: RuntimeException) {
            invalidateDisplay()
        }
    }

    private fun invalidateDisplay() {
        val notify = changed
        stop()
        notify?.invoke(UiRestrictions.UNKNOWN)
    }

    override fun stop() {
        active = false
        generation++
        handler.removeCallbacks(poll)
        try {
            displays?.unregisterDisplayListener(displayListener)
        } catch (_: RuntimeException) {}
        displays = null
        try {
            manager?.unregisterListener()
        } catch (_: RuntimeException) {}
        manager = null
        try {
            car?.disconnect()
        } catch (_: RuntimeException) {}
        car = null
        changed = null
    }
}
