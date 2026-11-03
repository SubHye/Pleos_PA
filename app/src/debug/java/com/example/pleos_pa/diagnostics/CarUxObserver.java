package com.example.pleos_pa.diagnostics;

import android.car.Car;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;

/** Observes the Activity context's display. Never promotes unrestricted UX to parked evidence. */
public final class CarUxObserver {
    public interface Sink { void event(String event); }
    public static final class Observation {
        public final int displayId;
        public final boolean requiresDistractionOptimization;
        public final int activeRestrictions;
        public final long receivedElapsedMs;
        Observation(int displayId, CarUxRestrictions value) {
            this.displayId = displayId;
            requiresDistractionOptimization = value.isRequiresDistractionOptimization();
            activeRestrictions = value.getActiveRestrictions();
            receivedElapsedMs = SystemClock.elapsedRealtime();
        }
    }
    private final Context context;
    private final Sink sink;
    private Car car;
    private CarUxRestrictionsManager manager;
    private int generation;
    private boolean active;
    private volatile Observation latestObservation;

    public CarUxObserver(Context context, Sink sink) { this.context = context; this.sink = sink; }

    public void connect() {
        close("reconnect");
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            unknown("not_automotive"); return;
        }
        active = true;
        final int token = ++generation;
        try {
            Car createdCar = Car.createCar(context, new Handler(Looper.getMainLooper()),
                    Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, (connectedCar, ready) -> {
                if (!active || token != generation) return;
                // A ready callback may run before createCar() returns. close() must own this handle.
                car = connectedCar;
                if (!ready) { manager = null; unknown("service_not_ready"); return; }
                try {
                    manager = (CarUxRestrictionsManager) connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
                    if (manager == null) { unknown("manager_null"); return; }
                    sink.event("connected displayId=" + displayId()
                            + " driverDisplayVerified=false");
                    manager.registerListener(value -> {
                        if (!active || token != generation) return;
                        try { sample("callback", value); }
                        catch (RuntimeException exception) {
                            close("callback_error=" + exception.getClass().getSimpleName());
                        }
                    });
                    sample("initial", manager.getCurrentCarUxRestrictions());
                } catch (RuntimeException exception) { close("api_error=" + exception.getClass().getSimpleName()); }
            });
            if (!active || token != generation) {
                if (createdCar != null) createdCar.disconnect();
                return;
            }
            car = createdCar;
            if (car == null) close("car_null");
        } catch (RuntimeException exception) { close("connect_error=" + exception.getClass().getSimpleName()); }
    }

    private void sample(String source, CarUxRestrictions value) {
        if (value == null) { unknown(source + "_null"); return; }
        // No independent gear/driver-zone proof: boundary must remain UNKNOWN even for flags=0.
        DiagnosticSession.INSTANCE.unknown();
        latestObservation = new Observation(displayId(), value);
        sink.event(source + " receivedElapsedMs=" + SystemClock.elapsedRealtime()
                + " displayId=" + displayId()
                + " DO=" + value.isRequiresDistractionOptimization()
                + " activeRestrictions=" + value.getActiveRestrictions()
                + " boundary=UNKNOWN parkedVerified=false " + DiagnosticSession.INSTANCE.policySummary());
    }

    @SuppressWarnings("deprecation")
    private int displayId() {
        // WindowManager from the Activity context identifies its display on API 28+.
        // It still does not establish that this display belongs to the driver.
        WindowManager windows = context.getSystemService(WindowManager.class);
        if (windows == null) throw new IllegalStateException("display_unavailable");
        return windows.getDefaultDisplay().getDisplayId();
    }

    private void unknown(String reason) {
        latestObservation = null;
        DiagnosticSession.INSTANCE.unknown();
        sink.event("UNKNOWN reason=" + reason + " " + DiagnosticSession.INSTANCE.policySummary());
    }

    public Observation latestObservation() { return latestObservation; }

    public void close(String reason) {
        active = false;
        generation++;
        unknown(reason);
        CarUxRestrictionsManager previousManager = manager;
        Car previousCar = car;
        manager = null;
        car = null;
        try { if (previousManager != null) previousManager.unregisterListener(); }
        catch (RuntimeException exception) { sink.event("unregister_error=" + exception.getClass().getSimpleName()); }
        try { if (previousCar != null) previousCar.disconnect(); }
        catch (RuntimeException exception) { sink.event("disconnect_error=" + exception.getClass().getSimpleName()); }
    }
}
