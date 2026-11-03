package com.example.pleos_pa

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

/**
 * Starts, stops and resumes the connection and shows its status. The bridge never opens by itself.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var notice: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private var everStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
            }
        status =
            TextView(this).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        content.addView(status)
        start =
            Button(this).apply {
                setOnClickListener {
                    requestNotificationsOnce()
                    everStarted = true
                    isEnabled = false
                    BridgeService.start(this@MainActivity)
                }
            }
        content.addView(start)
        stop =
            Button(this).apply {
                setText(R.string.action_stop)
                setOnClickListener {
                    isEnabled = false
                    BridgeService.stop(this@MainActivity)
                }
            }
        content.addView(stop)
        notice = TextView(this).apply { setPadding(0, padding, 0, 0) }
        content.addView(notice)
        content.addView(
            TextView(this).apply {
                setPadding(0, padding, 0, 0)
                setText(R.string.connection_limits)
            }
        )
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        ConnectionStatusBoard.observe(::render)
    }

    override fun onStop() {
        ConnectionStatusBoard.observe(null)
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render(ConnectionStatusBoard.status)
    }

    private fun requestNotificationsOnce() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !everStarted &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    private fun render(current: ConnectionStatus) {
        status.text = BridgeService.statusText(this, current)
        val down = current is ConnectionStatus.Stopped || current is ConnectionStatus.Failed
        start.isEnabled = down
        start.setText(if (everStarted) R.string.action_resume else R.string.action_start)
        stop.isEnabled = !down
        notice.visibility =
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                View.GONE
            } else {
                View.VISIBLE
            }
        notice.setText(R.string.notifications_off)
    }
}
