package com.example.pleos_pa.placecheck

import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
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
import org.json.JSONObject

/**
 * One check per launch, from `adb shell am start` (README.md):
 * - `capture`: reads the current destination, e.g. one set by hand in the stock app.
 * - `route`: sets a listed place with `requestRoute`, waits, and reads it back.
 * - `cancel`: `cancelRoute`, then reads to confirm there is no destination.
 *
 * Every step is one JSON line under the `PlaceCheck` log tag. Nothing is retried. Only destination
 * values are written; the vehicle's own position is never read.
 */
class CheckActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "place-check") }
    private val answers = LinkedBlockingQueue<DestinationInfo>()
    private val events =
        object : NaviHelperEventListener {
            override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                answers.offer(destinationInfo)
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
        val waitMs = intent.getIntExtra(EXTRA_WAIT_MS, DEFAULT_WAIT_MS).toLong()
        if (!running.compareAndSet(false, true)) {
            emit(JSONObject().put("event", "error").put("stage", "start").put("type", "BUSY"))
            return
        }
        worker.execute {
            try {
                run(mode, key, waitMs)
            } finally {
                running.set(false)
                // So the next `am start` makes a new instance instead of reusing this one.
                runOnUiThread { finish() }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun run(mode: String, key: String?, waitMs: Long) {
        if (mode !in setOf(MODE_CAPTURE, MODE_ROUTE, MODE_CANCEL)) {
            emit(error("mode", "UNKNOWN_MODE"))
            return
        }
        // An unlisted place is never set, as in the product rule.
        val place = if (mode == MODE_ROUTE) key?.let(::listedPlace) else null
        if (mode == MODE_ROUTE && place == null) {
            emit(error("place", "UNKNOWN_PLACE").put("key", key ?: JSONObject.NULL))
            return
        }
        val helper = NaviHelper(applicationContext)
        try {
            if (!connect(helper)) return
            when (mode) {
                MODE_CAPTURE -> read(helper, "capture")?.let { emit(event("capture", it)) }
                MODE_ROUTE -> route(helper, place!!, waitMs)
                MODE_CANCEL -> cancel(helper)
            }
        } finally {
            runCatching {
                helper.removeListener(events)
                helper.release()
            }
        }
    }

    private fun route(helper: NaviHelper, place: JSONObject, waitMs: Long) {
        val before = read(helper, "before") ?: return
        emit(event("before", before))
        val poiId = place.getString("poiId")
        val request =
            RouteInfo(
                latitude = place.getDouble("latitude"),
                longitude = place.getDouble("longitude"),
                displayLatitude = place.getDouble("latitude"),
                displayLongitude = place.getDouble("longitude"),
                poiName = place.getString("displayName"),
                poiId = poiId,
                address = place.getString("address"),
                poiSubId = if (poiId.isEmpty()) "" else place.getString("poiSubId"),
            )
        val requested =
            JSONObject()
                .put("key", place.getString("key"))
                .put("latitude", request.latitude)
                .put("longitude", request.longitude)
                .put("poiName", request.poiName)
                .put("poiId", request.poiId)
                .put("poiSubId", request.poiSubId)
                .put("address", request.address)
        try {
            helper.requestRoute(request)
        } catch (e: Exception) {
            emit(error("requestRoute", e.javaClass.simpleName))
            return
        }
        emit(JSONObject().put("event", "requested").put("request", requested))
        Thread.sleep(waitMs)
        val after = read(helper, "reread") ?: return
        val verdict =
            PlaceCheckVerdict.judge(
                request.latitude,
                request.longitude,
                after.latitude,
                after.longitude,
            )
        emit(
            event("route", after)
                .put("key", place.getString("key"))
                .put("requestedPoiId", poiId)
                .put("waitMs", waitMs)
                .put("verdict", if (verdict.pass) "PASS" else "FAIL")
                .put("deltaLatitude", verdict.deltaLatitude)
                .put("deltaLongitude", verdict.deltaLongitude)
        )
    }

    private fun cancel(helper: NaviHelper) {
        try {
            helper.cancelRoute()
        } catch (e: Exception) {
            emit(error("cancelRoute", e.javaClass.simpleName))
            return
        }
        Thread.sleep(CANCEL_WAIT_MS)
        val after = read(helper, "after-cancel") ?: return
        emit(
            event("cancel", after)
                .put(
                    "noDestination",
                    PlaceCheckVerdict.isNoDestination(after.name, after.latitude, after.longitude),
                )
        )
    }

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

    /**
     * The first `onDestinationInfo` after the call. The callback carries no request ID and other
     * apps' reads trigger it too (`contracts/navigation/CONTRACT.md`), so the value is whatever is
     * current when it arrives.
     */
    private fun read(helper: NaviHelper, stage: String): DestinationInfo? {
        answers.clear()
        try {
            helper.getDestinationInfo()
        } catch (e: Exception) {
            emit(error(stage, e.javaClass.simpleName))
            return null
        }
        val answer = answers.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (answer == null) emit(error(stage, "TIMEOUT"))
        return answer
    }

    private fun listedPlace(key: String): JSONObject? {
        val places =
            JSONObject(assets.open("places.json").bufferedReader().use { it.readText() })
                .getJSONArray("places")
        return (0 until places.length())
            .map { places.getJSONObject(it) }
            .firstOrNull { it.getString("key") == key }
    }

    private fun event(name: String, info: DestinationInfo) =
        JSONObject()
            .put("event", name)
            .put(
                "destination",
                JSONObject()
                    .put("name", info.name)
                    .put("address", info.address)
                    .put("latitude", info.latitude)
                    .put("longitude", info.longitude)
                    .put("displayLatitude", info.displayLatitude ?: JSONObject.NULL)
                    .put("displayLongitude", info.displayLongitude ?: JSONObject.NULL)
                    .put("poiId", info.poiId)
                    .put("poiSubId", info.poiSubId ?: JSONObject.NULL)
                    .put("poiProvider", info.poiProvider)
                    .put("remainDistance", info.remainDistance)
                    .put("remainTime", info.remainTime),
            )

    private fun error(stage: String, type: String) =
        JSONObject().put("event", "error").put("stage", stage).put("type", type)

    private fun emit(line: JSONObject) {
        val text = line.put("epochMs", System.currentTimeMillis()).toString()
        Log.i(TAG, text)
        runOnUiThread { output.append(text + "\n\n") }
    }

    companion object {
        const val TAG = "PlaceCheck"
        const val EXTRA_MODE = "mode"
        const val EXTRA_PLACE = "place"
        const val EXTRA_WAIT_MS = "waitMs"
        const val MODE_CAPTURE = "capture"
        const val MODE_ROUTE = "route"
        const val MODE_CANCEL = "cancel"

        // COM-8 saw 5.7-8.1 s from requestRoute to onRouteStarted.
        const val DEFAULT_WAIT_MS = 10_000
        const val CANCEL_WAIT_MS = 3_000L
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val READ_TIMEOUT_MS = 10_000L

        private val running = AtomicBoolean(false)
    }
}
