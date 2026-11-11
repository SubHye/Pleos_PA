package com.example.pleos_pa.placecheck

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * COM-47 waypoint probe, one action per launch from `adb shell am start` (README.md):
 * - `get`: `getWaypointInfo()` and `getDestinationInfo()`; changes nothing.
 * - `add`: `addWaypoint` with a listed place at `--es index FIRST|SECOND|ALL`, then waits and
 *   rereads.
 * - `remove`: `removeWaypoint(index)`, then waits and rereads.
 * - `via`: `requestRouteWithWaypoints` for a listed destination and waypoint list, then rereads.
 *
 * Every line is JSON under the `WaypointCheck` log tag, including each listener event seen during
 * the wait. Nothing is retried. Only destination and waypoint values are read; the vehicle's own
 * position is never read.
 */
class WaypointActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "waypoint-check") }
    private val waypointAnswers = LinkedBlockingQueue<List<WaypointInfo>>()
    private val destinationAnswers = LinkedBlockingQueue<DestinationInfo>()
    private val events =
        object : NaviHelperEventListener {
            override fun onWayPointInfo(waypointInfo: List<WaypointInfo>) {
                waypointAnswers.offer(waypointInfo)
            }

            override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                destinationAnswers.offer(destinationInfo)
            }

            override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
                emit(
                    JSONObject()
                        .put("event", "onWaypointChanged")
                        .put("index", waypointChangedInfo.index.name)
                        .put("cmdType", waypointChangedInfo.cmdType.name)
                )
            }

            override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
                emit(
                    JSONObject()
                        .put("event", "onWayPointArrived")
                        .put("index", waypointArrivedInfo.index.name)
                )
            }

            override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
                emit(JSONObject().put("event", "onRouteStarted"))
            }

            override fun onRouteCancelled() {
                emit(JSONObject().put("event", "onRouteCancelled"))
            }

            override fun onRouteEnded() {
                emit(JSONObject().put("event", "onRouteEnded"))
            }

            override fun onRoutingCompleted() {
                emit(JSONObject().put("event", "onRoutingCompleted"))
            }

            override fun onDestinationChanged() {
                emit(JSONObject().put("event", "onDestinationChanged"))
            }

            override fun onError(naviErrorCode: NaviErrorCode) {
                emit(JSONObject().put("event", "onError").put("code", naviErrorCode.name))
            }
        }
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { setPadding(32, 32, 32, 32) }
        setContentView(output)
        if (savedInstanceState != null) return
        val mode = intent.getStringExtra(EXTRA_MODE).orEmpty()
        val key = intent.getStringExtra(EXTRA_PLACE)
        val index = intent.getStringExtra(EXTRA_INDEX)
        val waitMs = intent.getIntExtra(EXTRA_WAIT_MS, DEFAULT_WAIT_MS).toLong()
        if (!running.compareAndSet(false, true)) {
            emit(error("start", "BUSY"))
            return
        }
        val dest = intent.getStringExtra(EXTRA_DEST)
        val wps = intent.getStringExtra(EXTRA_WAYPOINTS)
        worker.execute {
            try {
                if (mode == MODE_VIA) runVia(dest, wps, waitMs) else run(mode, key, index, waitMs)
            } finally {
                running.set(false)
                runOnUiThread { finish() }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun run(mode: String, key: String?, indexName: String?, waitMs: Long) {
        if (mode !in setOf(MODE_GET, MODE_ADD, MODE_REMOVE)) {
            emit(error("mode", "UNKNOWN_MODE"))
            return
        }
        val index =
            if (mode == MODE_GET) null
            else WaypointIndex.entries.firstOrNull { it.name == indexName }
        if (mode != MODE_GET && index == null) {
            emit(error("index", "UNKNOWN_INDEX").put("index", indexName ?: JSONObject.NULL))
            return
        }
        // An unlisted place is never sent, as in the product rule.
        val place = if (mode == MODE_ADD) key?.let(::listedPlace) else null
        if (mode == MODE_ADD && place == null) {
            emit(error("place", "UNKNOWN_PLACE").put("key", key ?: JSONObject.NULL))
            return
        }
        val helper = NaviHelper(applicationContext)
        try {
            if (!connect(helper)) return
            when (mode) {
                MODE_GET -> snapshot(helper, "get")
                MODE_ADD -> add(helper, place!!, index!!, waitMs)
                MODE_REMOVE -> remove(helper, index!!, waitMs)
            }
        } finally {
            runCatching {
                helper.removeListener(events)
                helper.release()
            }
        }
    }

    /**
     * `via`: `requestRouteWithWaypoints` with `--es dest <key>` and `--es wps "<key>:<INDEX>,..."`
     * (empty for no waypoints). Only listed places are sent.
     */
    private fun runVia(destKey: String?, wps: String?, waitMs: Long) {
        val dest = destKey?.let(::listedPlace)
        if (dest == null) {
            emit(error("dest", "UNKNOWN_PLACE").put("key", destKey ?: JSONObject.NULL))
            return
        }
        val waypoints = mutableListOf<RequestWaypointInfo>()
        val requestedWaypoints = JSONArray()
        for (token in wps.orEmpty().split(",").filter { it.isNotBlank() }) {
            val (key, indexName) = token.split(":").let { it[0] to it.getOrNull(1) }
            val place = listedPlace(key)
            val index = WaypointIndex.entries.firstOrNull { it.name == indexName }
            if (place == null || index == null) {
                emit(error("wps", "UNKNOWN_PLACE_OR_INDEX").put("token", token))
                return
            }
            waypoints += waypointRequest(place, index)
            requestedWaypoints.put(JSONObject().put("key", key).put("index", index.name))
        }
        val poiId = dest.getString("poiId")
        val destination =
            RouteInfo(
                latitude = dest.getDouble("latitude"),
                longitude = dest.getDouble("longitude"),
                displayLatitude = dest.getDouble("latitude"),
                displayLongitude = dest.getDouble("longitude"),
                poiName = dest.getString("displayName"),
                poiId = poiId,
                address = dest.getString("address"),
                poiSubId = if (poiId.isEmpty()) "" else dest.getString("poiSubId"),
            )
        val helper = NaviHelper(applicationContext)
        try {
            if (!connect(helper)) return
            snapshot(helper, "before")
            try {
                helper.requestRouteWithWaypoints(RouteWithWaypointsRequest(destination, waypoints))
            } catch (e: Exception) {
                emit(error("requestRouteWithWaypoints", e.javaClass.simpleName))
                return
            }
            emit(
                JSONObject()
                    .put("event", "requested")
                    .put("call", "requestRouteWithWaypoints")
                    .put("dest", dest.getString("key"))
                    .put("waypoints", requestedWaypoints)
            )
            Thread.sleep(waitMs)
            snapshot(helper, "reread")
        } finally {
            runCatching {
                helper.removeListener(events)
                helper.release()
            }
        }
    }

    private fun waypointRequest(place: JSONObject, index: WaypointIndex): RequestWaypointInfo {
        val poiId = place.getString("poiId")
        return RequestWaypointInfo(
            latitude = place.getDouble("latitude"),
            longitude = place.getDouble("longitude"),
            poiName = place.getString("displayName"),
            waypointIndex = index,
            poiId = poiId,
            address = place.getString("address"),
            poiSubId = if (poiId.isEmpty()) "" else place.getString("poiSubId"),
            poiProvider = "",
        )
    }

    private fun add(helper: NaviHelper, place: JSONObject, index: WaypointIndex, waitMs: Long) {
        snapshot(helper, "before")
        val poiId = place.getString("poiId")
        val request =
            RequestWaypointInfo(
                latitude = place.getDouble("latitude"),
                longitude = place.getDouble("longitude"),
                poiName = place.getString("displayName"),
                waypointIndex = index,
                poiId = poiId,
                address = place.getString("address"),
                poiSubId = if (poiId.isEmpty()) "" else place.getString("poiSubId"),
                poiProvider = "",
            )
        val requested =
            JSONObject()
                .put("key", place.getString("key"))
                .put("index", index.name)
                .put("latitude", request.latitude)
                .put("longitude", request.longitude)
                .put("poiId", request.poiId)
        try {
            helper.addWaypoint(request)
        } catch (e: Exception) {
            emit(error("addWaypoint", e.javaClass.simpleName))
            return
        }
        emit(
            JSONObject()
                .put("event", "requested")
                .put("call", "addWaypoint")
                .put("request", requested)
        )
        Thread.sleep(waitMs)
        snapshot(helper, "reread")
    }

    private fun remove(helper: NaviHelper, index: WaypointIndex, waitMs: Long) {
        snapshot(helper, "before")
        try {
            helper.removeWaypoint(index)
        } catch (e: Exception) {
            emit(error("removeWaypoint", e.javaClass.simpleName))
            return
        }
        emit(
            JSONObject()
                .put("event", "requested")
                .put("call", "removeWaypoint")
                .put("index", index.name)
        )
        Thread.sleep(waitMs)
        snapshot(helper, "reread")
    }

    /** Reads waypoints, then the destination, each as the first callback after its call. */
    private fun snapshot(helper: NaviHelper, stage: String) {
        waypointAnswers.clear()
        try {
            helper.getWaypointInfo()
        } catch (e: Exception) {
            emit(error("$stage-waypoints", e.javaClass.simpleName))
        }
        val waypoints = waypointAnswers.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (waypoints == null) {
            emit(error("$stage-waypoints", "TIMEOUT"))
        } else {
            val list = JSONArray()
            waypoints.forEach { list.put(waypointJson(it)) }
            emit(
                JSONObject()
                    .put("event", stage)
                    .put("waypointCount", waypoints.size)
                    .put("waypoints", list)
            )
        }
        destinationAnswers.clear()
        try {
            helper.getDestinationInfo()
        } catch (e: Exception) {
            emit(error("$stage-destination", e.javaClass.simpleName))
            return
        }
        val info = destinationAnswers.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (info == null) {
            emit(error("$stage-destination", "TIMEOUT"))
        } else {
            emit(
                JSONObject()
                    .put("event", "$stage-destination")
                    .put("name", info.name)
                    .put("latitude", info.latitude)
                    .put("longitude", info.longitude)
                    .put("remainDistance", info.remainDistance)
                    .put("remainTime", info.remainTime)
            )
        }
    }

    private fun waypointJson(info: WaypointInfo) =
        JSONObject()
            .put("name", info.name)
            .put("address", info.address)
            .put("latitude", info.latitude)
            .put("longitude", info.longitude)
            .put("poiId", info.poiId)
            .put("poiSubId", info.poiSubId)
            .put("poiProvider", info.poiProvider)
            .put("remainDistance", info.remainDistance)
            .put("remainTime", info.remainTime)

    private fun connect(helper: NaviHelper): Boolean {
        val done = CountDownLatch(1)
        val connected = AtomicBoolean(false)
        try {
            helper.addListener(events)
            helper.initialize(
                onConnected = {
                    connected.set(true)
                    done.countDown()
                },
                onFailed = { done.countDown() },
            )
        } catch (e: Exception) {
            emit(error("initialize", e.javaClass.simpleName))
            return false
        }
        if (!done.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) || !connected.get()) {
            emit(error("initialize", "NAVI_NOT_CONNECTED"))
            return false
        }
        return true
    }

    private fun listedPlace(key: String): JSONObject? {
        val places =
            JSONObject(assets.open("places.json").bufferedReader().use { it.readText() })
                .getJSONArray("places")
        return (0 until places.length())
            .map { places.getJSONObject(it) }
            .firstOrNull { it.getString("key") == key }
    }

    private fun error(stage: String, type: String) =
        JSONObject().put("event", "error").put("stage", stage).put("type", type)

    private fun emit(line: JSONObject) {
        val text = line.put("epochMs", System.currentTimeMillis()).toString()
        Log.i(TAG, text)
        runOnUiThread { output.append(text + "\n\n") }
    }

    companion object {
        const val TAG = "WaypointCheck"
        const val EXTRA_MODE = "mode"
        const val EXTRA_PLACE = "place"
        const val EXTRA_INDEX = "index"
        const val EXTRA_WAIT_MS = "waitMs"
        const val MODE_GET = "get"
        const val MODE_ADD = "add"
        const val MODE_REMOVE = "remove"
        const val MODE_VIA = "via"
        const val EXTRA_DEST = "dest"
        const val EXTRA_WAYPOINTS = "wps"

        const val DEFAULT_WAIT_MS = 8_000
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val READ_TIMEOUT_MS = 10_000L

        private val running = AtomicBoolean(false)
    }
}
