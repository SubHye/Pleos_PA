package com.example.pleos_pa.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.pleos_pa.MainActivity
import com.example.pleos_pa.R
import com.example.pleos_pa.bridge.BridgeEvents
import com.example.pleos_pa.bridge.BridgeServer
import com.example.pleos_pa.bridge.ReaderBridgeGateway
import com.example.pleos_pa.contract.VehicleReadCatalog
import com.example.pleos_pa.mcp.EmbeddedMcpServer
import com.example.pleos_pa.mcp.EndpointGroup
import com.example.pleos_pa.mcp.VehicleReadTool
import com.example.pleos_pa.vehicle.PleosVehicleSdk
import com.example.pleos_pa.vehicle.ReadScheduler
import com.example.pleos_pa.vehicle.VehicleReader
import com.example.pleos_pa.vehicle.vehicleSourceOf
import java.util.concurrent.Executors

/** Runs reads on the main looper, which also serializes them with stop and resume. */
private class MainLooperScheduler(private val handler: Handler) : ReadScheduler {
    override fun nowMillis() = SystemClock.elapsedRealtime()

    override fun execute(action: () -> Unit) {
        handler.post(action)
    }

    override fun after(delayMillis: Long, action: () -> Unit): () -> Unit {
        val task = Runnable(action)
        handler.postDelayed(task, delayMillis)
        return { handler.removeCallbacks(task) }
    }
}

/**
 * Keeps the bridge running while the screen is closed. It exists only while the connection is up:
 * stopping ends the service and leaves a plain notification that can start it again. Never sticky,
 * so the system does not reopen a bridge the user did not start. Nothing binds to it, because a
 * binding would keep it alive after [stopSelf].
 */
class BridgeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scheduler = MainLooperScheduler(main)
    private val catalog by lazy {
        VehicleReadCatalog.parse(
            assets.open("catalog.json").use { it.readBytes().decodeToString() }
        )
    }
    private val controller by lazy {
        ConnectionController(::createSession, ::show, ::onEnded)
    }
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            controller.stop()
            return START_NOT_STICKY
        }
        // A foreground start must reach startForeground promptly, even during a teardown.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(ConnectionStatus.Starting),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        } catch (_: IllegalStateException) {
            onEnded(ConnectionStatus.Failed(FOREGROUND_NOT_ALLOWED))
            return START_NOT_STICKY
        }
        controller.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        // An ended connection keeps its final status, which may be a failure the screen must show.
        if (controller.active) controller.stop()
        super.onDestroy()
    }

    private fun createSession(): ConnectionSession {
        val source =
            vehicleSourceOf(
                Build.BRAND,
                Build.FINGERPRINT,
                Build.HARDWARE,
                packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE),
            )
        val reader = VehicleReader(catalog, PleosVehicleSdk(this), scheduler, source)
        val events = Events()
        val gateway = ReaderBridgeGateway(catalog, reader, scheduler, events)
        val server = BridgeServer(catalog, gateway, events, SystemClock::elapsedRealtime)
        // The embedded MCP endpoint shares the gateway, so the read slot and the stop order too.
        val tool = VehicleReadTool(catalog, gateway, events, SystemClock::elapsedRealtime)
        val endpoints = EndpointGroup(listOf(server, EmbeddedMcpServer(catalog, tool)))
        return ConnectionSession(
            gateway::close,
            endpoints,
            { released -> reader.close { main.post(released) } },
            bindExecutor,
        ) { session, status ->
            main.post { controller.onStatus(session, status) }
        }
    }

    /** Called on bridge threads; only fixed fields reach the log, never a vehicle value. */
    private inner class Events : BridgeEvents {
        override fun requestReceived() {
            main.post { controller.requestReceived() }
        }

        override fun peerClosedDuringRead() {
            main.post { controller.peerClosedDuringRead() }
        }

        override fun listenerFailed() {
            main.post { controller.fail(BRIDGE_LISTENER_FAILED) }
        }

        override fun ownReplyRejected() {
            Log.e(TAG, "OWN_REPLY_REJECTED")
        }

        override fun finished(
            requestId: String,
            query: String,
            result: String,
            elapsedMillis: Long,
        ) {
            Log.i(TAG, "requestId=$requestId query=$query result=$result elapsedMs=$elapsedMillis")
        }
    }

    private fun show(status: ConnectionStatus) {
        ConnectionStatusBoard.publish(status)
        if (!destroyed) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(status))
        }
    }

    private fun onEnded(status: ConnectionStatus) {
        ConnectionStatusBoard.publish(status)
        if (destroyed) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(status))
        stopSelf()
    }

    private fun notification(status: ConnectionStatus): Notification {
        val running = status is ConnectionStatus.Starting || status is ConnectionStatus.Connected
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val action =
            if (running) {
                NotificationCompat.Action.Builder(
                        0,
                        getString(R.string.action_stop),
                        PendingIntent.getService(
                            this,
                            1,
                            stopIntent(this),
                            PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                    .build()
            } else {
                // Tapping a notification is allowed to start a foreground service from the
                // background.
                NotificationCompat.Action.Builder(
                        0,
                        getString(R.string.action_resume),
                        PendingIntent.getForegroundService(
                            this,
                            2,
                            startIntent(this),
                            PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                    .build()
            }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText(this, status))
            .setContentIntent(open)
            .setOngoing(running)
            .addAction(action)
            .build()
    }

    companion object {
        private const val TAG = "BridgeService"
        private const val ACTION_START = "com.example.pleos_pa.action.START_BRIDGE"
        private const val ACTION_STOP = "com.example.pleos_pa.action.STOP_BRIDGE"
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1
        private const val FOREGROUND_NOT_ALLOWED = "FOREGROUND_NOT_ALLOWED"
        private const val BRIDGE_LISTENER_FAILED = "BRIDGE_LISTENER_FAILED"

        // Shared across sessions so a new bind waits for the previous session's bind.
        private val bindExecutor = Executors.newSingleThreadExecutor {
            Thread(it, "bridge-bind").apply { isDaemon = true }
        }

        private fun startIntent(context: Context) =
            Intent(context, BridgeService::class.java).setAction(ACTION_START)

        private fun stopIntent(context: Context) =
            Intent(context, BridgeService::class.java).setAction(ACTION_STOP)

        /** Only from a visible screen, which may start a foreground service. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, startIntent(context))
        }

        fun stop(context: Context) {
            context.startService(stopIntent(context))
        }

        fun statusText(context: Context, status: ConnectionStatus): String =
            when (status) {
                ConnectionStatus.Stopped -> context.getString(R.string.connection_stopped)
                ConnectionStatus.Starting -> context.getString(R.string.connection_starting)
                is ConnectionStatus.Connected ->
                    context.getString(
                        if (status.interrupted) {
                            R.string.connection_interrupted
                        } else {
                            R.string.connection_connected
                        }
                    )
                is ConnectionStatus.Failed ->
                    context.getString(R.string.connection_failed, status.code)
            }
    }
}
