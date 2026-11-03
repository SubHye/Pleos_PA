package com.example.pleos_pa.diagnostics;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Debug-only, parked/manual diagnostic screen; no DO declaration or vehicle writes. */
public final class Com9DiagnosticActivity extends Activity {
    private TextView output;
    private CarUxObserver observer;
    private android.hardware.display.DisplayManager displays;
    private final android.hardware.display.DisplayManager.DisplayListener displayListener =
            new android.hardware.display.DisplayManager.DisplayListener() {
                public void onDisplayAdded(int id) { observer.close("display_added_reconnect_required"); }
                public void onDisplayRemoved(int id) { observer.close("display_removed_reconnect_required"); }
                public void onDisplayChanged(int id) { observer.close("display_changed_reconnect_required"); }
            };
    private void event(String value) {
        Log.i("COM9_DIAG", value);
        // Keep the screen to the latest diagnostic event; full history is only in filtered logs.
        runOnUiThread(() -> output.setText(value));
    }
    private void button(LinearLayout layout, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setOnClickListener(view -> action.run());
        layout.addView(button);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        output = new TextView(this);
        observer = new CarUxObserver(this, this::event);
        DiagnosticSession.INSTANCE.attachObserver(this, observer::close);
        displays = getSystemService(android.hardware.display.DisplayManager.class);
        displays.registerDisplayListener(displayListener, new android.os.Handler(getMainLooper()));
        button(layout, "Connect / read initial UX", observer::connect);
        button(layout, "Disconnect observer (local loss injection)", () -> observer.close("manual_disconnect_injection"));
        button(layout, "Stop", () -> {
            DiagnosticSession.INSTANCE.stop("app_stop");
            event("stop_ack source=app navigationAllowed=" + DiagnosticSession.INSTANCE.navigationAllowed());
        });
        button(layout, "Create diagnostic stop notification", this::showStopNotification);
        layout.addView(output);
        setContentView(layout);
        event("boundary=UNKNOWN navigation disabled; approval UI/request authentication absent "
                + DiagnosticSession.INSTANCE.policySummary());
    }
    private void showStopNotification() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9);
            event("notification permission requested; press notification button again after grant");
            return;
        }
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("com9_stop", "COM-9 diagnostic stop", NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, Com9StopReceiver.class).setAction("com.example.pleos_pa.COM9_STOP");
        PendingIntent pending = PendingIntent.getBroadcast(this, 0, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        notifications.notify(9, new Notification.Builder(this, "com9_stop")
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setContentTitle("COM-9 diagnostic: navigation disabled")
                .addAction(new Notification.Action.Builder(null, "Stop", pending).build()).build());
        event("notification posted; reachability not yet verified");
    }
    @Override protected void onStop() {
        DiagnosticSession.INSTANCE.stop("activity_stopped");
        super.onStop();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        observer.close("configuration_changed_reconnect_required");
    }
    @Override protected void onDestroy() {
        DiagnosticSession.INSTANCE.detachObserver(this);
        displays.unregisterDisplayListener(displayListener);
        super.onDestroy();
    }
}
