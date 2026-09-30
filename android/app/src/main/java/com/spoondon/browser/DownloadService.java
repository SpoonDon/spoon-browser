package com.spoondon.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.Locale;

/**
 * Foreground service that keeps the process alive while downloads are
 * active and drives the aggregate progress notification.
 *
 * The engine itself is process-scoped: {@link #getEngine(Context)} returns
 * a singleton, so task state survives service restarts. The service is a
 * thin notifier that watches engine events.
 *
 * Lifecycle: {@link #ensureRunning(Context)} starts the service if it isn't
 * already up. When the engine reports all tasks are terminal (completed,
 * failed, cancelled), the notification is dismissed and the service stops
 * itself after a 4s grace period.
 */
public final class DownloadService extends Service implements DownloadEngine.Listener {

    public static final String ACTION_ENSURE_RUNNING =
            "com.spoondon.browser.downloads.ENSURE_RUNNING";
    public static final String ACTION_STOP_ALL =
            "com.spoondon.browser.downloads.STOP_ALL";

    private static final String CHANNEL_ID = "spoon_downloads";
    private static final int NOTIFICATION_ID = 0x5B0D;   // "SPOOD"
    private static final long NOTIFICATION_MIN_INTERVAL_MS = 1000L;
    private static final long STOP_GRACE_MS = 4000L;

    // ------------------------------------------------------------------------
    // Singleton engine
    // ------------------------------------------------------------------------

    private static volatile DownloadEngine engineSingleton;

    @NonNull
    public static DownloadEngine getEngine(@NonNull Context context) {
        DownloadEngine e = engineSingleton;
        if (e == null) {
            synchronized (DownloadService.class) {
                e = engineSingleton;
                if (e == null) {
                    e = new DownloadEngine(context.getApplicationContext());
                    engineSingleton = e;
                }
            }
        }
        return e;
    }

    /** Start the service (and thus the foreground notification) if not running. */
    public static void ensureRunning(@NonNull Context context) {
        Intent i = new Intent(context, DownloadService.class)
                .setAction(ACTION_ENSURE_RUNNING);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------

    private DownloadEngine engine;
    private NotificationManager notificationManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable progressTicker;
    private long lastNotificationUpdate = 0L;
    private boolean isForeground = false;
    private volatile boolean alive = false;

    // ------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        alive = true;
        engine = getEngine(this);
        engine.setListener(this);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP_ALL.equals(action)) {
            engine.removeAll();
            stopEverything();
            return START_NOT_STICKY;
        }

        // ENSURE_RUNNING (and legacy null-intent starts): keep ourselves
        // foreground if there's work to do, otherwise go away quietly.
        if (hasActiveWork()) {
            enterForeground();
            startProgressTicker();
        } else {
            stopEverything();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        alive = false;
        stopProgressTicker();
        if (engine != null) {
            engine.setListener(null);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------------
    // Engine.Listener (called from worker threads)
    // ------------------------------------------------------------------------

    @Override
    public void onStateChanged(long id, @NonNull DownloadTask.State state) {
        mainHandler.post(() -> {
            if (!alive) return;
            if (hasActiveWork()) {
                enterForeground();
                startProgressTicker();
                updateNotificationNow(/*force=*/true);
            } else {
                stopProgressTicker();
                updateNotificationNow(/*force=*/true);
                mainHandler.postDelayed(this::stopEverything, STOP_GRACE_MS);
            }
        });
    }

    @Override
    public void onRegistryChanged() {
        mainHandler.post(() -> {
            if (!alive) return;
            if (hasActiveWork()) {
                enterForeground();
                startProgressTicker();
                updateNotificationNow(/*force=*/true);
            }
        });
    }

    // ------------------------------------------------------------------------
    // Foreground plumbing
    // ------------------------------------------------------------------------

    private boolean hasActiveWork() {
        if (engine == null) return false;
        for (DownloadTask t : engine.getAll()) {
            DownloadTask.State s = t.getState();
            if (s == DownloadTask.State.RUNNING
                    || s == DownloadTask.State.QUEUED
                    || s == DownloadTask.State.PAUSED) {
                return true;
            }
        }
        return false;
    }

    private void enterForeground() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            isForeground = true;
        } catch (Exception e) {
            // Some OEMs reject dataSync FGS without the matching permission.
            // Try the typeless variant before giving up — a notification
            // without a foreground service is still better than none.
            try {
                startForeground(NOTIFICATION_ID, n);
                isForeground = true;
            } catch (Exception ignored) {
            }
        }
    }

    private void stopEverything() {
        stopProgressTicker();
        if (isForeground) {
            try { stopForeground(true); } catch (Exception ignored) {}
            isForeground = false;
        }
        stopSelf();
    }

    // ------------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Downloads",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Active downloads");
            ch.setShowBadge(false);
            notificationManager.createNotificationChannel(ch);
        } catch (Exception ignored) {
        }
    }

    @NonNull
    private Notification buildNotification() {
        int running = 0;
        int paused = 0;
        long totalBytes = 0;
        long totalDone = 0;
        String firstName = null;

        for (DownloadTask t : engine.getAll()) {
            DownloadTask.State s = t.getState();
            if (s == DownloadTask.State.RUNNING || s == DownloadTask.State.QUEUED) {
                running++;
                if (firstName == null) firstName = t.getSpec().fileName;
            } else if (s == DownloadTask.State.PAUSED) {
                paused++;
                if (firstName == null) firstName = t.getSpec().fileName;
            }
            if (t.getBytesTotal() > 0) totalBytes += t.getBytesTotal();
            totalDone += t.getBytesDownloaded();
        }

        String title;
        if (running > 0) {
            title = running == 1 ? "Downloading…" : (running + " downloads");
        } else if (paused > 0) {
            title = paused == 1 ? "Download paused" : (paused + " downloads paused");
        } else {
            title = "Downloads";
        }

        StringBuilder text = new StringBuilder();
        if (firstName != null) text.append(firstName);
        if (totalBytes > 0) {
            if (text.length() > 0) text.append("  •  ");
            text.append(formatBytes(totalDone))
                .append(" / ")
                .append(formatBytes(totalBytes));
        }
        if (text.length() == 0) text.append("No active transfers");

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text.toString())
                .setOngoing(running > 0)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        if (totalBytes > 0 && running > 0) {
            int pct = (int) Math.min(100L, totalDone * 100L / totalBytes);
            b.setProgress(100, pct, false);
        } else if (running > 0) {
            b.setProgress(0, 0, true);
        }

        // Tap → open the browser
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        b.setContentIntent(pi);

        // Action → cancel everything
        Intent stop = new Intent(this, DownloadService.class)
                .setAction(ACTION_STOP_ALL);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        b.addAction(0, "Cancel all", stopPi);

        return b.build();
    }

    // ------------------------------------------------------------------------
    // Progress ticker
    // ------------------------------------------------------------------------

    private void startProgressTicker() {
        if (progressTicker != null) return;
        progressTicker = new Runnable() {
            @Override
            public void run() {
                if (!alive) return;
                if (!hasActiveWork()) {
                    progressTicker = null;
                    return;
                }
                updateNotificationNow(/*force=*/false);
                mainHandler.postDelayed(this, NOTIFICATION_MIN_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(progressTicker, NOTIFICATION_MIN_INTERVAL_MS);
    }

    private void stopProgressTicker() {
        if (progressTicker != null) {
            mainHandler.removeCallbacks(progressTicker);
            progressTicker = null;
        }
    }

    private void updateNotificationNow(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotificationUpdate < NOTIFICATION_MIN_INTERVAL_MS) {
            return;
        }
        lastNotificationUpdate = now;
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static int immutableFlag() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PendingIntent.FLAG_IMMUTABLE;
        }
        return 0;
    }

    @NonNull
    private static String formatBytes(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return String.format(Locale.US, "%.1f GB",
                bytes / (double) (1024L * 1024 * 1024));
    }
}
