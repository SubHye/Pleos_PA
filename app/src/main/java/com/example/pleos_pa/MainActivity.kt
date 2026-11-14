package com.example.pleos_pa

import android.Manifest
import android.animation.Animator
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isNotEmpty
import com.example.pleos_pa.connection.BridgeService
import com.example.pleos_pa.connection.ConnectionStatus
import com.example.pleos_pa.connection.ConnectionStatusBoard
import com.example.pleos_pa.ui.CarUiRestrictionSource
import com.example.pleos_pa.ui.CardMotion
import com.example.pleos_pa.ui.ChatCards
import com.example.pleos_pa.ui.ChatRow
import com.example.pleos_pa.ui.DriverHistory
import com.example.pleos_pa.ui.NewestRows
import com.example.pleos_pa.ui.RequestHistory
import com.example.pleos_pa.ui.UiRestrictionSource
import com.example.pleos_pa.ui.UiRestrictions
import com.example.pleos_pa.ui.rounded
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

/**
 * Shows each AI request and the app answer as a short conversation, with one control for the
 * connection. The control stays reachable while vehicle values are hidden by display UX
 * restrictions, and only the newest few requests are shown: this screen never browses a record.
 */
open class MainActivity : AppCompatActivity() {
    protected open val history: RequestHistory
        get() = DriverHistory.history

    protected open val previewNotice: String?
        get() = null

    private lateinit var status: TextView
    private lateinit var notice: TextView
    private lateinit var restriction: TextView
    private lateinit var toggle: Button
    private lateinit var dot: View
    private lateinit var ring: View
    private lateinit var rowList: LinearLayout
    private lateinit var empty: TextView
    private val rows = LinkedHashMap<Long, ChatRow>()
    private val cards: ChatCards by lazy {
        val titles = mutableMapOf<String, String>()
        val navigation = mutableSetOf<String>()
        for (file in listOf("catalog.json", "navigation-catalog.json")) {
            val tools =
                JSONObject(assets.open(file).use { it.readBytes().decodeToString() })
                    .getJSONArray("tools")
            for (index in 0 until tools.length()) {
                val tool = tools.getJSONObject(index)
                titles[tool.getString("name")] = tool.getString("title")
                if (file == "navigation-catalog.json") navigation += tool.getString("name")
            }
        }
        ChatCards(titles, navigation)
    }
    private var pulse: Animator? = null
    private var everStarted = false
    private var down = true
    private var visible = false

    /** The first drawing after the screen appears is not animated; later arrivals are. */
    private var settled = false
    private var unit = 1f
    private var textScale = 1f
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

    private fun px(dp: Float) = (dp * unit).toInt()

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restrictionSource = createRestrictionSource()
        // The vehicle display is far larger than a phone; one scale keeps both readable.
        textScale = if (resources.configuration.smallestScreenWidthDp >= 900) 1.5f else 1f
        unit = resources.displayMetrics.density * textScale
        val padding = px(20f)
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isSaveFromParentEnabled = false
                setPadding(padding, padding, padding, padding)
            }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(
            TextView(this).apply {
                setText(R.string.app_name)
                textSize = 20f * textScale
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(color(R.color.text_primary))
            }
        )
        ring =
            View(this).apply {
                background =
                    rounded(
                        Color.TRANSPARENT,
                        px(5f).toFloat(),
                        color(R.color.accent),
                        px(1f).coerceAtLeast(1),
                    )
                alpha = 0f
            }
        dot = View(this)
        header.addView(
            FrameLayout(this).apply {
                addView(ring, FrameLayout.LayoutParams(px(10f), px(10f), Gravity.CENTER))
                addView(dot, FrameLayout.LayoutParams(px(10f), px(10f), Gravity.CENTER))
            },
            LinearLayout.LayoutParams(px(30f), px(30f)).apply {
                marginStart = px(8f)
                marginEnd = px(4f)
            },
        )
        status =
            TextView(this).apply {
                textSize = 15f * textScale
                setTextColor(color(R.color.text_secondary))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        header.addView(status, LinearLayout.LayoutParams(0, WRAP, 1f))
        restriction =
            TextView(this).apply {
                textSize = 12f * textScale
                setPadding(px(10f), px(4f), px(10f), px(4f))
            }
        header.addView(
            restriction,
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = px(10f) },
        )
        root.addView(header)

        previewNotice?.let { text ->
            root.addView(
                TextView(this).apply {
                    this.text = text
                    textSize = 12f * textScale
                    setTextColor(color(R.color.text_secondary))
                }
            )
        }
        val preview = LinearLayout(this)
        addPreviewControls(preview)
        if (preview.isNotEmpty()) {
            root.addView(HorizontalScrollView(this).apply { addView(preview) })
        }
        notice =
            TextView(this).apply {
                setText(R.string.notifications_off)
                textSize = 12f * textScale
                setTextColor(color(R.color.text_secondary))
                setPadding(0, px(6f), 0, 0)
            }
        root.addView(notice)

        rowList = LinearLayout(this)
        empty =
            TextView(this).apply {
                setText(R.string.history_empty)
                textSize = 15f * textScale
                setTextColor(color(R.color.text_muted))
            }
        root.addView(
            FrameLayout(this).apply {
                addView(
                    NewestRows(this@MainActivity, rowList),
                    FrameLayout.LayoutParams(FILL, FILL),
                )
                addView(empty, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            },
            LinearLayout.LayoutParams(FILL, 0, 1f).apply {
                topMargin = px(12f)
                bottomMargin = px(12f)
            },
        )

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(
            TextView(this).apply {
                setText(R.string.connection_limits)
                textSize = 12f * textScale
                setTextColor(color(R.color.text_muted))
            },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = px(16f) },
        )
        toggle =
            MaterialButton(this).apply {
                textSize = 16f * textScale
                cornerRadius = px(14f)
                insetTop = 0
                insetBottom = 0
                setOnClickListener {
                    isEnabled = false
                    if (down) {
                        startConnection()
                        everStarted = true
                    } else stopConnection()
                }
            }
        bar.addView(toggle, LinearLayout.LayoutParams(px(132f), px(56f)))
        root.addView(bar)

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
                // Polls renew freshness; do not redraw rows on every unchanged sample.
                if (ux.permitsHistory(SystemClock.elapsedRealtime()) != wasPermitted)
                    renderHistory()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        settled = false
        rowList.removeAllViews()
        rows.clear()
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
        visible = false
        rows.values.forEach { it.stop() }
        stopPulse()
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
        down = current is ConnectionStatus.Stopped || current is ConnectionStatus.Failed
        // A live service proves a prior start even when this Activity was recreated.
        if (!down) everStarted = true
        toggle.isEnabled = true
        toggle.setText(
            when {
                !down -> R.string.action_stop
                everStarted -> R.string.action_resume
                else -> R.string.action_start
            }
        )
        val on = current is ConnectionStatus.Connected
        dot.background =
            rounded(color(if (on) R.color.accent else R.color.indicator_off), px(5f).toFloat())
        stopPulse()
        if (on && visible) pulse = CardMotion.ring(ring)
        notice.visibility =
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) View.GONE
            else View.VISIBLE
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        ring.alpha = 0f
        ring.scaleX = 1f
        ring.scaleY = 1f
    }

    private fun renderHistory() {
        if (!visible) return
        val permitted = ux.permitsHistory(SystemClock.elapsedRealtime())
        wasPermitted = permitted
        restriction.setText(if (permitted) R.string.ux_unrestricted else R.string.ux_restricted)
        restriction.setTextColor(color(if (permitted) R.color.accent else R.color.text_secondary))
        restriction.background =
            rounded(
                color(if (permitted) R.color.accent_container else R.color.bubble),
                px(8f).toFloat(),
            )
        // Newest first in the record; oldest first on the screen.
        val records = history.snapshot().take(MAX_ROWS).asReversed()
        val keep = records.mapTo(HashSet()) { it.token.sequence }
        for (sequence in rows.keys.filter { it !in keep }) {
            val row = rows.remove(sequence) ?: continue
            row.stop()
            rowList.removeView(row.request)
            rowList.removeView(row.response)
        }
        for (record in records) {
            val card = cards.of(record, permitted)
            val existing = rows[card.sequence]
            val row =
                existing
                    ?: ChatRow(this, unit, textScale).also {
                        rows[card.sequence] = it
                        rowList.addView(it.request)
                        rowList.addView(it.response)
                    }
            row.bind(
                card,
                if (card.time.isEmpty()) getString(R.string.chat_request)
                else getString(R.string.chat_request_at, card.time),
                settled,
            )
            if (existing == null && settled) row.enter()
        }
        empty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        settled = true
    }

    companion object {
        /** Requests kept on the screen. Older ones leave; the record itself is not browsed here. */
        private const val MAX_ROWS = 5
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private const val FILL = LinearLayout.LayoutParams.MATCH_PARENT
    }
}
