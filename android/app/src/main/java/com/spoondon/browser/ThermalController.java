package com.spoondon.browser;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thin singleton wrapper around PowerManager's thermal status API.
 *
 * Purpose: surface the OS thermal state to components that want to reduce
 * their workload when the device is hot, and resume normal operation once
 * it cools. The OS exposes seven discrete states (NONE..SHUTDOWN); we
 * mirror those as constants so callers never reference PowerManager
 * directly and never need API-29 guards in their own code.
 *
 * Behaviour on API < 29: the OS has no thermal API. getCurrentStatus()
 * always returns STATUS_NONE, no listeners fire, isThrottled() is false.
 * That's the correct fallback — no thermal response is better than a
 * wrong one.
 *
 * Threading: callbacks always arrive on the main thread. addListener
 * fires the current status once immediately (also on the main thread)
 * so a new subscriber starts in sync without racing on getCurrentStatus.
 *
 * Lifecycle: the singleton lives for the process lifetime. Listeners
 * are held strongly; callers that outlive their subscription (e.g.
 * TabManager when it's torn down) MUST call removeListener() to avoid
 * leaking. TabManager.destroyAll() handles this.
 *
 * Created 2026-10-01 as Tier 2 of the Spoon thermal work.
 */
public final class ThermalController {

    // ------------------------------------------------------------------------
    // Public status codes
    //
    // Values map 1:1 with PowerManager.THERMAL_STATUS_* on API 29+. We
    // re-declare them here so callers can compare against a symbol without
    // pulling in the PowerManager class (which trips lint on minSdk 24).
    // ------------------------------------------------------------------------
    public static final int STATUS_NONE      = 0;
    public static final int STATUS_LIGHT     = 1;
    public static final int STATUS_MODERATE  = 2;
    public static final int STATUS_SEVERE    = 3;
    public static final int STATUS_CRITICAL  = 4;
    public static final int STATUS_EMERGENCY = 5;
    public static final int STATUS_SHUTDOWN  = 6;

    public interface Listener {
        /**
         * @param status one of the STATUS_* constants. Called on the main
         *               thread. Called once with the current value
         *               immediately after addListener.
         */
        void onThermalStatusChanged(int status);
    }

    // ------------------------------------------------------------------------
    // Singleton
    // ------------------------------------------------------------------------
    @Nullable
    private static volatile ThermalController instance;

    @NonNull
    public static ThermalController get(@NonNull Context ctx) {
        ThermalController local = instance;
        if (local == null) {
            synchronized (ThermalController.class) {
                local = instance;
                if (local == null) {
                    local = new ThermalController(ctx.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    // ------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------
    @Nullable
    private final PowerManager powerManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile int currentStatus = STATUS_NONE;

    @Nullable
    private PowerManager.OnThermalStatusChangedListener systemListener;

    private ThermalController(@NonNull Context appCtx) {
        PowerManager pm = null;
        try {
            pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
        } catch (Exception ignored) {}
        this.powerManager = pm;

        if (pm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // No thermal API available. Stay at STATUS_NONE.
            return;
        }

        try {
            currentStatus = pm.getCurrentThermalStatus();
        } catch (Exception ignored) {}

        systemListener = status -> mainHandler.post(() -> dispatch(status));

        try {
            pm.addThermalStatusListener(systemListener);
        } catch (Exception ignored) {
            // Some OEM builds reject the listener; degrade to polling-free
            // "always NONE" behaviour rather than crash.
            systemListener = null;
            currentStatus = STATUS_NONE;
        }
    }

    // ------------------------------------------------------------------------
    // Subscription
    // ------------------------------------------------------------------------

    public void addListener(@NonNull Listener l) {
        if (l == null) return;
        if (!listeners.contains(l)) listeners.add(l);

        // Fire current status asynchronously so the caller's constructor
        // (if any) finishes before we invoke a method on it.
        final int snapshot = currentStatus;
        mainHandler.post(() -> {
            try { l.onThermalStatusChanged(snapshot); } catch (Exception ignored) {}
        });
    }

    public void removeListener(@NonNull Listener l) {
        listeners.remove(l);
    }

    // ------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------

    public int getCurrentStatus() {
        return currentStatus;
    }

    /**
     * True when the device is warm enough to justify disabling GPU-friendly
     * optimisations that cost CPU. MODERATE is the right threshold: BELOW
     * it, the device is normal. ABOVE it, the OS has already started
     * cutting clock speeds and every saved cycle is visible in the hand.
     */
    public boolean isThrottled() {
        return currentStatus >= STATUS_MODERATE;
    }

    /**
     * True when the device is dangerously hot. Reserved for emergency-brake
     * behaviour such as pausing all WebView timers.
     */
    public boolean isCritical() {
        return currentStatus >= STATUS_SEVERE;
    }

    // ------------------------------------------------------------------------
    // Dispatch
    // ------------------------------------------------------------------------

    private void dispatch(int status) {
        if (status == currentStatus) return;
        currentStatus = status;
        for (Listener l : listeners) {
            try { l.onThermalStatusChanged(status); } catch (Exception ignored) {}
        }
    }
}
