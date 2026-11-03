package com.example.pleos_pa.diagnostics;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class Com9StopReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!"com.example.pleos_pa.COM9_STOP".equals(intent.getAction())) return;
        DiagnosticSession.INSTANCE.stop("notification_stop");
        Log.i("COM9_DIAG", "stop_ack source=notification navigationAllowed="
                + DiagnosticSession.INSTANCE.navigationAllowed());
    }
}
