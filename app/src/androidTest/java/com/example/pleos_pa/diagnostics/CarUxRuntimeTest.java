package com.example.pleos_pa.diagnostics;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real API read, no shell permission adoption or vehicle state writes. Run explicitly on Pleos. */
@RunWith(AndroidJUnit4.class)
public final class CarUxRuntimeTest {
    @Test public void readsActualUxThenBothStopEntrypointsReleaseObserver() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertTrue("Target must be Automotive", context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE));
        Activity activity = instrumentation.startActivitySync(new Intent(context, Com9DiagnosticActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicReference<CarUxObserver> reference = new AtomicReference<>();
        BlockingQueue<String> initials = new LinkedBlockingQueue<>();
        AtomicReference<String> lastEvent = new AtomicReference<>("no event");
        Object owner = new Object();
        try {
            instrumentation.runOnMainSync(() -> {
                CarUxObserver observer = new CarUxObserver(activity, event -> {
                    lastEvent.set(event);
                    Log.i("COM9_TEST", event);
                    if (event.startsWith("initial receivedElapsedMs=")) initials.offer(event);
                });
                reference.set(observer);
                DiagnosticSession.INSTANCE.attachObserver(owner, observer::close);
                observer.connect();
            });
            String initial = initials.poll(15, TimeUnit.SECONDS);
            assertNotNull("No real initial UX within 15s: " + lastEvent.get(), initial);
            CarUxObserver.Observation first = reference.get().latestObservation();
            assertNotNull("Real initial observation missing", first);
            assertTrue(first.receivedElapsedMs > 0);
            assertTrue(first.displayId >= 0);
            assertFalse("Raw unrestricted UX must not enable navigation", DiagnosticSession.INSTANCE.navigationAllowed());
            instrumentation.runOnMainSync(() -> DiagnosticSession.INSTANCE.stop("app_stop"));
            assertNull("App stop must drop the observation", reference.get().latestObservation());
            instrumentation.runOnMainSync(() -> reference.get().connect());
            String reconnected = initials.poll(15, TimeUnit.SECONDS);
            assertNotNull("No real UX after reconnect: " + lastEvent.get(), reconnected);
            assertNotNull(reference.get().latestObservation());
            // Exercise the actual receiver's action handling without injecting an exported broadcast.
            instrumentation.runOnMainSync(() -> new Com9StopReceiver().onReceive(context,
                    new Intent(context, Com9StopReceiver.class).setAction("com.example.pleos_pa.COM9_STOP")));
            assertNull(reference.get().latestObservation());
            assertFalse(DiagnosticSession.INSTANCE.navigationAllowed());
        } finally {
            instrumentation.runOnMainSync(() -> {
                DiagnosticSession.INSTANCE.detachObserver(owner);
                if (reference.get() != null) reference.get().close("test_cleanup");
                activity.finish();
            });
        }
    }
}
