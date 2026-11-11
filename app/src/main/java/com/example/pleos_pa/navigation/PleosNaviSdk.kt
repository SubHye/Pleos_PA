package com.example.pleos_pa.navigation

import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import com.example.pleos_pa.contract.DestinationFields
import com.example.pleos_pa.contract.WaypointFields
import java.util.concurrent.Executors

/**
 * NaviHelper 3.0.5 on one thread. One helper per session: after `release()` the SDK needs a new
 * object, and a resume builds a new session anyway. The receipt time is taken from [wallClock] the
 * moment a callback arrives, before any hop to another thread.
 */
class PleosNaviSdk(
    context: Context,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : NaviSdk {
    private val context = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "navi-sdk") }

    // Touched only on the executor.
    private var helper: NaviHelper? = null
    private var events: NaviHelperEventListener? = null

    @Volatile private var closing = false

    override fun open(listener: NaviSdkListener) {
        executor.execute {
            if (closing) return@execute
            val created = NaviHelper(context)
            val forwarded =
                object : NaviHelperEventListener {
                    override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                        val at = wallClock()
                        listener.destination(fieldsOf(destinationInfo), at)
                    }

                    override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
                        val at = wallClock()
                        listener.waypoints(waypointInfoList.map(::waypointOf), at)
                    }
                }
            helper = created
            events = forwarded
            try {
                created.addListener(forwarded)
                created.initialize(
                    onConnected = { listener.connected() },
                    onFailed = { listener.connectionFailed() },
                )
            } catch (_: Exception) {
                listener.connectionFailed()
            }
        }
    }

    override fun requestDestination(failed: (Exception) -> Unit) {
        executor.execute {
            val current = helper
            if (closing || current == null) return@execute
            try {
                current.getDestinationInfo()
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    /** The `RouteInfo` of `contracts/navigation/CONTRACT.md`; everything else stays default. */
    override fun requestRoute(route: RouteRequest, returned: (Exception?) -> Unit) {
        executor.execute {
            val current = helper
            if (closing || current == null) return@execute
            val failure =
                try {
                    current.requestRoute(
                        RouteInfo(
                            latitude = route.latitude,
                            longitude = route.longitude,
                            displayLatitude = route.latitude,
                            displayLongitude = route.longitude,
                            poiName = route.name,
                            poiId = route.poiId,
                            address = route.address,
                            poiSubId = "",
                        )
                    )
                    null
                } catch (e: Exception) {
                    e
                }
            returned(failure)
        }
    }

    override fun requestWaypoints(failed: (Exception) -> Unit) {
        executor.execute {
            val current = helper
            if (closing || current == null) return@execute
            try {
                current.getWaypointInfo()
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    /**
     * `RequestWaypointInfo` as `contracts/navigation/CONTRACT.md` builds it; the rest stays
     * default.
     */
    override fun addWaypoint(waypoint: WaypointRequest, returned: (Exception?) -> Unit) {
        executor.execute {
            val current = helper
            if (closing || current == null) return@execute
            val failure =
                try {
                    current.addWaypoint(
                        RequestWaypointInfo(
                            latitude = waypoint.latitude,
                            longitude = waypoint.longitude,
                            poiName = waypoint.name,
                            waypointIndex = indexOf(waypoint.slot),
                            poiId = waypoint.poiId,
                            address = waypoint.address,
                            poiSubId = "",
                            poiProvider = "",
                        )
                    )
                    null
                } catch (e: Exception) {
                    e
                }
            returned(failure)
        }
    }

    override fun removeWaypoint(slot: WaypointSlot, returned: (Exception?) -> Unit) {
        executor.execute {
            val current = helper
            if (closing || current == null) return@execute
            val failure =
                try {
                    current.removeWaypoint(indexOf(slot))
                    null
                } catch (e: Exception) {
                    e
                }
            returned(failure)
        }
    }

    override fun close(closed: () -> Unit) {
        closing = true
        // Queued behind every call already handed to the executor.
        executor.execute {
            try {
                events?.let { helper?.removeListener(it) }
                helper?.release()
            } catch (_: Exception) {
                // Release is best effort; nothing new is issued after this point either way.
            } finally {
                helper = null
                events = null
                closed()
            }
        }
        executor.shutdown()
    }

    private fun indexOf(slot: WaypointSlot) =
        when (slot) {
            WaypointSlot.FIRST -> WaypointIndex.FIRST
            WaypointSlot.SECOND -> WaypointIndex.SECOND
        }

    private fun waypointOf(info: WaypointInfo) =
        // The stock app leaves some fields empty or null (COM-47), and a null must not end a read.
        WaypointFields(
            name = info.name.orEmpty(),
            address = info.address.orEmpty(),
            poiId = info.poiId.orEmpty(),
        )

    private fun fieldsOf(info: DestinationInfo) =
        DestinationFields(
            name = info.name,
            address = info.address,
            latitude = info.latitude,
            longitude = info.longitude,
            remainDistance = info.remainDistance,
            remainTime = info.remainTime,
        )
}
