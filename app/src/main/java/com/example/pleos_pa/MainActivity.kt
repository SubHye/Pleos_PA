package com.example.pleos_pa

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.pleos_pa.connection.BridgeService
import com.example.pleos_pa.connection.ConnectionStatus
import com.example.pleos_pa.connection.ConnectionStatusBoard
import com.example.pleos_pa.ui.CarUiRestrictionSource
import com.example.pleos_pa.ui.DriverHistory
import com.example.pleos_pa.ui.RequestHistory
import com.example.pleos_pa.ui.UiRestrictionSource
import com.example.pleos_pa.ui.UiRestrictions
import org.json.JSONObject

/** User controls stay reachable when detailed history is hidden by display UX restrictions. */
open class MainActivity : AppCompatActivity() {
    protected open val history: RequestHistory
        get() = DriverHistory.history

    protected open val previewNotice: String?
        get() = null

    private lateinit var status: TextView
    private lateinit var notice: TextView
    private lateinit var restriction: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var historyRows: LinearLayout
    private lateinit var features: TextView
    private val featureText: String by lazy {
        val tools =
            JSONObject(assets.open("catalog.json").use { it.readBytes().decodeToString() })
                .getJSONArray("tools")
        getString(R.string.feature_title) +
            "\n" +
            (0 until tools.length()).joinToString("\n") { index ->
                val tool = tools.getJSONObject(index)
                tool.getString("name") +
                    " · " +
                    getString(
                        if (tool.optString("adoption") == "adopted") R.string.feature_adopted
                        else R.string.feature_unavailable
                    )
            }
    }
    private lateinit var details: TextView
    private lateinit var older: Button
    private lateinit var newer: Button
    private var everStarted = false
    private var offset = 0
    private var selected: RequestHistory.Token? = null
    private var ux = UiRestrictions.UNKNOWN
    private var wasPermitted = false
    private lateinit var restrictionSource: UiRestrictionSource
    private val handler = Handler(Looper.getMainLooper())
    private val observer: (ConnectionStatus) -> Unit = { renderConnection(it) }
    private val historyObserver: () -> Unit = { runOnUiThread { renderHistory() } }
    private val tick =
        object : Runnable {
            override fun run() {
                val permitted = ux.permitsHistory(SystemClock.elapsedRealtime())
                if (permitted != wasPermitted) renderHistory()
                handler.postDelayed(this, 250)
            }
        }

    protected open fun createRestrictionSource(): UiRestrictionSource =
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            try {
                CarUiRestrictionSource(this)
            } catch (_: LinkageError) {
                unknownSource()
            }
        } else unknownSource()

    protected open fun addPreviewControls(container: LinearLayout) {}

    private fun unknownSource() =
        object : UiRestrictionSource {
            override fun start(changed: (UiRestrictions) -> Unit) {
                changed(UiRestrictions.UNKNOWN)
            }

            override fun stop() {}
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restrictionSource = createRestrictionSource()
        val padding = (20 * resources.displayMetrics.density).toInt()
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isSaveFromParentEnabled = false
                setPadding(padding, padding, padding, padding)
            }
        fun label(text: String) =
            TextView(this).apply {
                this.text = text
                root.addView(this)
            }
        label("Pleos PA").textSize = 26f
        previewNotice?.let { label(it) }
        status = label("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        label(getString(R.string.client_unknown))
        val controls = LinearLayout(this)
        start =
            Button(this).apply {
                setOnClickListener {
                    everStarted = true
                    isEnabled = false
                    startConnection()
                }
            }
        stop =
            Button(this).apply {
                setText(R.string.action_stop)
                setOnClickListener {
                    isEnabled = false
                    stopConnection()
                }
            }
        val controlHeight = (64 * resources.displayMetrics.density).toInt()
        controls.addView(start, LinearLayout.LayoutParams(0, controlHeight, 1f))
        controls.addView(stop, LinearLayout.LayoutParams(0, controlHeight, 1f))
        root.addView(controls)
        notice = label(getString(R.string.notifications_off))
        restriction = label("")
        features = TextView(this)
        val scrolling = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addPreviewControls(content)
        content.addView(
            Button(this).apply {
                setText(R.string.recheck_ux)
                setOnClickListener { connectRestrictions() }
            }
        )
        content.addView(TextView(this).apply { setText(R.string.undo_unavailable) })
        content.addView(
            Button(this).apply {
                setText(R.string.undo_action)
                isEnabled = false
            }
        )
        content.addView(TextView(this).apply { setText(R.string.connection_limits) })
        content.addView(features)
        content.addView(
            TextView(this).apply {
                setText(R.string.history_title)
                textSize = 20f
            }
        )
        val paging = LinearLayout(this)
        newer =
            Button(this).apply {
                setText(R.string.history_newer)
                setOnClickListener {
                    offset = (offset - PAGE_SIZE).coerceAtLeast(0)
                    renderHistory()
                }
            }
        older =
            Button(this).apply {
                setText(R.string.history_older)
                setOnClickListener {
                    if (ux.permitsHistory(SystemClock.elapsedRealtime())) {
                        offset += PAGE_SIZE
                        renderHistory()
                    }
                }
            }
        paging.addView(newer)
        paging.addView(older)
        content.addView(paging)
        content.addView(
            Button(this).apply {
                setText(R.string.history_clear)
                setOnClickListener {
                    history.clear()
                    selected = null
                    offset = 0
                    renderHistory()
                }
            }
        )
        historyRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(historyRows)
        details = TextView(this)
        content.addView(details)
        scrolling.addView(content)
        root.addView(
            scrolling,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                padding + bars.left,
                padding + bars.top,
                padding + bars.right,
                padding + bars.bottom,
            )
            insets
        }
    }

    private fun connectRestrictions() {
        ux = UiRestrictions.UNKNOWN
        renderHistory()
        restrictionSource.start { next ->
            runOnUiThread {
                ux = next
                renderHistory()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ConnectionStatusBoard.observe(observer)
        history.observe(historyObserver)
        connectRestrictions()
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        restrictionSource.stop()
        ux = UiRestrictions.UNKNOWN
        // Remove vehicle values from the backgrounded view too, not only when it resumes.
        renderHistory()
        history.remove(historyObserver)
        ConnectionStatusBoard.remove(observer)
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderConnection(ConnectionStatusBoard.status)
    }

    protected open fun startConnection() {
        requestNotificationsOnce()
        BridgeService.start(this)
    }

    protected open fun stopConnection() {
        BridgeService.stop(this)
    }

    private fun requestNotificationsOnce() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                !everStarted &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    protected open fun renderConnection(current: ConnectionStatus) {
        status.text = BridgeService.statusText(this, current)
        val down = current is ConnectionStatus.Stopped || current is ConnectionStatus.Failed
        start.isEnabled = down
        start.setText(if (everStarted) R.string.action_resume else R.string.action_start)
        stop.isEnabled = !down
        notice.visibility =
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) View.GONE
            else View.VISIBLE
    }

    private fun renderHistory() {
        val permitted = ux.permitsHistory(SystemClock.elapsedRealtime())
        wasPermitted = permitted
        restriction.setText(if (permitted) R.string.ux_unrestricted else R.string.ux_restricted)
        historyRows.removeAllViews()
        details.text = ""
        features.text = ""
        val records = history.snapshot()
        if (!permitted) {
            selected = null
            offset = 0
            older.isEnabled = false
            newer.isEnabled = false
            historyRows.addView(
                TextView(this).apply {
                    text =
                        getString(
                            R.string.history_restricted_summary,
                            getString(R.string.history_hidden, records.size),
                            records.firstOrNull()?.let {
                                getString(R.string.history_latest, stateText(it.state))
                            } ?: "",
                        )
                }
            )
            return
        }
        features.text = featureText
        offset = offset.coerceAtMost(((records.size - 1).coerceAtLeast(0) / PAGE_SIZE) * PAGE_SIZE)
        newer.isEnabled = offset > 0
        older.isEnabled = offset + PAGE_SIZE < records.size
        for (record in records.drop(offset).take(PAGE_SIZE)) {
            historyRows.addView(
                Button(this).apply {
                    text =
                        getString(
                            R.string.history_row,
                            record.tool,
                            stateText(record.state),
                            record.requestId,
                            record.receivedAt,
                        )
                    isAllCaps = false
                    setOnClickListener {
                        if (ux.permitsHistory(SystemClock.elapsedRealtime())) {
                            selected = record.token
                            renderHistory()
                        }
                    }
                }
            )
        }
        if (records.isEmpty())
            historyRows.addView(TextView(this).apply { setText(R.string.history_empty) })
        val record = records.firstOrNull { it.token === selected }
        if (record != null) {
            details.text =
                getString(
                    R.string.history_details,
                    record.tool,
                    record.requestId,
                    record.arguments,
                    record.receivedAt,
                    stateText(record.state),
                    sourceText(record.source),
                    getString(R.string.receipt_not_measurement),
                    record.details,
                )
        }
    }

    private fun sourceText(source: String?) =
        when (source) {
            "simulation" -> "모의 값 · 실제 차량 값 아님"
            "pleos_emulator" -> "Pleos 에뮬레이터"
            "real_vehicle" -> "실제 차량 (응답 출처 표기)"
            else -> "미확인"
        }

    private fun stateText(state: String) =
        when (state) {
            "RECEIVED" -> "조회 처리 중"
            "COMPLETE" -> "조회 완료"
            "PARTIAL" -> "일부 항목만 확인"
            "FAILED" -> "조회 실패"
            "BUSY" -> "다른 조회 처리 중 (BUSY)"
            "CANCELLED" -> "취소됨 · 결과 미확인"
            "CONNECTION_STOPPED" -> "연결 중지됨"
            "APP_NOT_READY" -> "조회 준비 안 됨"
            else -> "결과 미확인 ($state)"
        }

    companion object {
        private const val PAGE_SIZE = 10
    }
}
