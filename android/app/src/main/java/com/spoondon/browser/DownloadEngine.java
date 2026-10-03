package com.spoondon.browser;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;

/**
 * Central registry and lifecycle manager for all downloads.
 *
 * 2026-10-03 refactor: enqueue now picks a {@link SeekableFile} impl based
 * on API level:
 *
 *   API 29+   -> {@link MediaStoreFile} writes directly to the public
 *                Downloads/Spoon/ collection via MediaStore.Downloads.
 *                No staging, no permission prompt. Chromium-style.
 *   API 24-28 -> {@link RealFile} writes to public
 *                /sdcard/Download/Spoon/ (requires WRITE_EXTERNAL_STORAGE,
 *                which the manifest already declares with maxSdkVersion=28).
 *                Fallback when MediaStore insert fails on API 29+: RealFile
 *                into the app-private staging dir (still shown in the UI,
 *                just not visible in file managers).
 *
 * Owns:
 *   - a shared OkHttpClient (one connection pool for the whole app)
 *   - a bounded chunk-executor: max 4 concurrent HTTP reads across all tasks
 *   - a small orchestrator pool: one thread per active download
 *   - a scheduled checkpoint that persists progress every 5s
 *   - the DownloadStore
 *
 * Persistence policy: on every state transition (add/start/pause/complete/
 * fail/cancel/remove) and on a 5s checkpoint while any task is RUNNING or
 * PAUSED. Progress byte-counts are NOT persisted per update — that would
 * hammer the disk.
 *
 * Restart behaviour: on construction we load persisted tasks. Only
 * COMPLETED records are restored (RealFile case) — in-flight resume on
 * API 29+ is not yet implemented because MediaStoreFile lacks a
 * reopen-existing method. In-flight tasks are dropped on restart; the
 * MediaStore staging row is orphaned (future cleanup pass will sweep).
 */
public final class DownloadEngine {

    public interface Listener {
        /** Fired when a task's state changes. Called on a worker thread. */
        void onStateChanged(long id, @NonNull DownloadTask.State state);
        /** Fired when a task is added or removed. Called on a worker thread. */
        void onRegistryChanged();
    }

    private static final int CHUNK_THREADS = 4;
    private static final int ORCH_THREADS  = 2;
    private static final long CHECKPOINT_SECONDS = 5;

    private final Context appContext;
    private final OkHttpClient client;
    private final DownloadStore store;
    private final ExecutorService chunkPool;
    private final ExecutorService orchPool;
    private final ScheduledExecutorService checkpointScheduler;

    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, DownloadTask> tasks = new LinkedHashMap<>();

    private volatile Listener listener;
    private volatile File legacyTargetDir;
    private volatile boolean shuttingDown = false;

    public DownloadEngine(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.store = new DownloadStore(appContext);

        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();

        this.chunkPool = Executors.newFixedThreadPool(
                CHUNK_THREADS, namedFactory("spoon-dl-chunk"));
        this.orchPool = Executors.newFixedThreadPool(
                ORCH_THREADS, namedFactory("spoon-dl-orch"));
        this.checkpointScheduler = Executors.newSingleThreadScheduledExecutor(
                namedFactory("spoon-dl-checkpoint"));

        this.legacyTargetDir = defaultLegacyDir();

        restoreFromDisk();
        startCheckpointLoop();
    }

    // ------------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------------

    public void setListener(@Nullable Listener l) {
        this.listener = l;
    }

    /** Override the fallback directory used when MediaStore isn't available. */
    public void setLegacyTargetDir(@NonNull File dir) {
        if (!dir.exists()) dir.mkdirs();
        this.legacyTargetDir = dir;
    }

    @NonNull
    public File getLegacyTargetDir() {
        return legacyTargetDir;
    }

    /**
     * Directory used only for API 24-28 real-file downloads or when a
     * MediaStore insert fails on API 29+.
     *
     * API 24-28: public /sdcard/Download/Spoon/ so files land where the
     *            user expects them. Requires WRITE_EXTERNAL_STORAGE.
     * API 29+:   app-private Downloads/ as a safety net — MediaStore should
     *            never fail, so this path only triggers on OEM weirdness.
     */
    @NonNull
    private File defaultLegacyDir() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File ext = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (ext == null) ext = appContext.getFilesDir();
            if (!ext.exists()) ext.mkdirs();
            return ext;
        }
        File pub = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                MediaStoreFile.SUBDIR);
        if (!pub.exists()) pub.mkdirs();
        return pub;
    }

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------

    /**
     * Create and start a new download. Returns the task id, or -1 if the
     * engine is shutting down or the target could not be created.
     */
    public long enqueue(@NonNull DownloadSpec spec) {
        if (shuttingDown) return -1;

        long id = nextId.getAndIncrement();
        SeekableFile target = pickTarget(spec);
        if (target == null) {
            return -1;
        }

        DownloadTask task = new DownloadTask(id, spec, target, client, taskListener);
        task.setWorkerPool(chunkPool);

        synchronized (tasks) {
            tasks.put(id, task);
        }

        task.start(orchPool);
        persistSoon();
        notifyRegistryChanged();
        return id;
    }

    @Nullable
    public DownloadTask getTask(long id) {
        synchronized (tasks) {
            return tasks.get(id);
        }
    }

    @NonNull
    public List<DownloadTask> getAll() {
        synchronized (tasks) {
            return new ArrayList<>(tasks.values());
        }
    }

    public int size() {
        synchronized (tasks) {
            return tasks.size();
        }
    }

    public void pause(long id) {
        DownloadTask t = getTask(id);
        if (t != null) {
            t.pause();
            persistSoon();
        }
    }

    public void resume(long id) {
        DownloadTask t = getTask(id);
        if (t != null) {
            t.resume(orchPool);
            persistSoon();
        }
    }

    public void cancel(long id) {
        DownloadTask t = getTask(id);
        if (t != null) {
            t.cancel();
            persistSoon();
        }
    }

    /** Remove the task from the registry and release its target. */
    public void remove(long id) {
        DownloadTask t;
        synchronized (tasks) {
            t = tasks.remove(id);
        }
        if (t != null) {
            // cancel() is a no-op if COMPLETED (file belongs to the user).
            // closeTarget() always releases the fd.
            t.cancel();
            t.closeTarget();
            persistSoon();
            notifyRegistryChanged();
        }
    }

    /** Delete every in-flight task and wipe the persisted store. */
    public void removeAll() {
        List<DownloadTask> all = getAll();
        for (DownloadTask t : all) {
            t.cancel();
            t.closeTarget();
        }
        synchronized (tasks) {
            tasks.clear();
        }
        store.wipe();
        notifyRegistryChanged();
    }

    /** Save immediately and halt the schedulers. Call from Service.onDestroy. */
    public void shutdown() {
        shuttingDown = true;
        checkpointScheduler.shutdownNow();
        orchPool.shutdownNow();
        chunkPool.shutdownNow();
        for (DownloadTask t : getAll()) {
            t.closeTarget();
        }
        persistNow();
    }

    // ------------------------------------------------------------------------
    // Task listener
    // ------------------------------------------------------------------------

    private final DownloadTask.Listener taskListener = new DownloadTask.Listener() {
        @Override
        public void onProgress(long id) {
            // Intentionally no-op. Progress is read live from DownloadTask
            // by the UI. Persisting per progress event would hammer disk.
        }

        @Override
        public void onStateChanged(long id, @NonNull DownloadTask.State newState) {
            persistSoon();
            Listener l = listener;
            if (l != null) l.onStateChanged(id, newState);
        }
    };

    private void notifyRegistryChanged() {
        Listener l = listener;
        if (l != null) l.onRegistryChanged();
    }

    // ------------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------------

    private void persistSoon() {
        // No-op by design. The 5s checkpoint loop picks up state changes;
        // shutdown() calls persistNow() directly. Kept as a distinct method
        // so future debounce logic has a home.
    }

    private void persistNow() {
        List<DownloadTaskState> snapshots = new ArrayList<>();
        synchronized (tasks) {
            for (DownloadTask t : tasks.values()) {
                try {
                    DownloadTaskState s = t.snapshot();
                    // Record the MediaStore URI so we can reopen the row
                    // after a restart. Empty for RealFile targets.
                    SeekableFile target = t.getTarget();
                    if (target instanceof MediaStoreFile) {
                        s.targetUri = ((MediaStoreFile) target).getUri().toString();
                    }
                    snapshots.add(s);
                } catch (Exception ignored) {
                }
            }
        }
        store.save(snapshots);
    }

    private void startCheckpointLoop() {
        checkpointScheduler.scheduleWithFixedDelay(() -> {
            if (shuttingDown) return;
            try {
                persistNow();
            } catch (Exception ignored) {
            }
        }, CHECKPOINT_SECONDS, CHECKPOINT_SECONDS, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------------
    // Restore
    // ------------------------------------------------------------------------

    /**
     * Load persisted task records on cold start.
     *
     * Only COMPLETED records are restored. In-flight tasks are dropped —
     * resuming partial transfers across process death is a separate feature
     * that needs per-chunk integrity checks. Any orphaned MediaStore staging
     * row (IS_PENDING=1) is left for a future cleanup pass.
     */
    private void restoreFromDisk() {
        List<DownloadTaskState> saved = store.load();
        if (saved.isEmpty()) return;

        long maxId = 0;
        for (DownloadTaskState s : saved) {
            if (s.id <= 0) continue;
            if (s.id > maxId) maxId = s.id;

            DownloadTask.State st;
            try {
                st = DownloadTask.State.values()[s.state];
            } catch (Exception e) {
                continue;
            }
            if (st != DownloadTask.State.COMPLETED) continue;

            SeekableFile target = reopenTarget(s);
            if (target == null) continue;

            DownloadSpec spec = new DownloadSpec(
                    s.url,
                    s.fileName,
                    emptyToNull(s.mime),
                    emptyToNull(s.userAgent),
                    emptyToNull(s.referer),
                    emptyToNull(s.cookies));

            DownloadTask task = new DownloadTask(s.id, spec, target, client, taskListener);
            task.setWorkerPool(chunkPool);
            task.restoreFrom(s);

            synchronized (tasks) {
                tasks.put(s.id, task);
            }
        }
        nextId.set(maxId + 1);
    }

    /**
     * Reopen the target for a restored task. Returns null if the target
     * cannot be recovered — the caller skips the record.
     *
     * MediaStoreFile: reopen via ContentResolver using the persisted URI.
     * RealFile:        open (or create) the file under legacyTargetDir.
     */
    @Nullable
    private SeekableFile reopenTarget(@NonNull DownloadTaskState s) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && s.targetUri != null && !s.targetUri.isEmpty()) {
            try {
                android.net.Uri uri = android.net.Uri.parse(s.targetUri);
                MediaStoreFile ms = MediaStoreFile.reopen(
                        appContext, uri, s.bytesDownloaded);
                if (ms != null) return ms;
            } catch (Exception ignored) {
            }
        }

        String safeName = DownloadNaming.sanitize(s.fileName);
        if (safeName.isEmpty()) return null;
        File target = new File(legacyTargetDir, safeName);
        return RealFile.create(target);
    }

    // ------------------------------------------------------------------------
    // Target selection
    // ------------------------------------------------------------------------

    /**
     * Pick the right {@link SeekableFile} impl for a new download.
     *
     * API 29+ : try MediaStore first. If the insert fails (rare), fall
     *           through to a RealFile in the app-private staging dir.
     * API 24-28: RealFile in public /sdcard/Download/Spoon/. Requires the
     *           caller to have WRITE_EXTERNAL_STORAGE granted.
     */
    @Nullable
    private SeekableFile pickTarget(@NonNull DownloadSpec spec) {
        String safeName = DownloadNaming.sanitize(spec.fileName);
        if (safeName.isEmpty()) safeName = "download.bin";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStoreFile ms = MediaStoreFile.create(appContext, safeName, spec.mime);
            if (ms != null) return ms;
        }

        File target = uniqueTargetFile(safeName);
        return RealFile.create(target);
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    @Nullable
    private static String emptyToNull(@Nullable String s) {
        if (s == null || s.isEmpty()) return null;
        return s;
    }

    /**
     * Return a file in legacyTargetDir whose name doesn't collide with an
     * existing one. Appends " (1)", " (2)", ... before the extension.
     */
    @NonNull
    private File uniqueTargetFile(@NonNull String desiredName) {
        File candidate = new File(legacyTargetDir, desiredName);
        if (!candidate.exists()) return candidate;

        String base = desiredName;
        String ext = "";
        int dot = desiredName.lastIndexOf('.');
        if (dot > 0 && desiredName.length() - dot <= 10) {
            base = desiredName.substring(0, dot);
            ext = desiredName.substring(dot);
        }
        for (int i = 1; i < 10000; i++) {
            File next = new File(legacyTargetDir, base + " (" + i + ")" + ext);
            if (!next.exists()) return next;
        }
        return new File(legacyTargetDir,
                base + "-" + System.currentTimeMillis() + ext);
    }

    private static ThreadFactory namedFactory(@NonNull final String prefix) {
        return new ThreadFactory() {
            private final AtomicLong n = new AtomicLong(1);
            @Override
            public Thread newThread(@NonNull Runnable r) {
                Thread t = new Thread(r, prefix + "-" + n.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
    }
}
