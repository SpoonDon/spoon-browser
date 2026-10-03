package com.spoondon.browser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * One file download. Splits into N parallel byte-range chunks when the
 * server supports ranges, otherwise falls back to a single stream.
 *
 * 2026-10-03 refactor: writes go through {@link SeekableFile} using
 * positional FileChannel writes instead of a per-chunk RandomAccessFile.
 * This is what makes MediaStore-backed downloads possible (no staging
 * copy — Chromium-style) and reduces fd churn. Concurrent chunk writes
 * are safe: FileChannel.write with an explicit position does not touch
 * any shared cursor.
 *
 * Lifecycle: {@link #start(ExecutorService)} dispatches the work onto
 * the given orchestrator pool. Each chunk runs to completion independently;
 * progress accumulates into an AtomicLong. Pause/cancel are cooperative —
 * a chunk checks {@code paused}/{@code cancelled} between reads and returns
 * cleanly, leaving its byte offset in {@link Chunk#downloaded}.
 *
 * The SeekableFile is owned by the caller (DownloadEngine), which creates
 * it before construction and calls {@link #closeTarget()} after removal.
 */
public final class DownloadTask {

    public enum State { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

    public interface Listener {
        /** Called from a worker thread when progress advances. */
        void onProgress(long id);
        /** Called from a worker thread on state transitions. */
        void onStateChanged(long id, @NonNull State newState);
    }

    private static final long MIN_CHUNKED_SIZE = 4L * 1024 * 1024;   // 4 MB
    private static final long CHUNK_SIZE      = 2L * 1024 * 1024;    // 2 MB
    private static final int  MAX_CHUNKS      = 6;
    private static final int  READ_BUFFER     = 64 * 1024;

    private final long id;
    private final DownloadSpec spec;
    private final SeekableFile target;
    private final OkHttpClient client;
    private final Listener listener;

    private final List<Chunk> chunks = new ArrayList<>();
    private final AtomicLong bytesDownloaded = new AtomicLong(0);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private volatile State state = State.QUEUED;
    private volatile long bytesTotal = -1;
    private volatile String errorMessage;
    private volatile long createdAt = System.currentTimeMillis();
    private volatile long completedAt;

    private volatile boolean rangeSupported = false;
    private volatile ExecutorService workerPool;

    private static final class Chunk {
        final long start;
        final long end;              // inclusive; -1 if unknown
        long downloaded;             // bytes written so far within this chunk
        final AtomicBoolean done = new AtomicBoolean(false);
        Chunk(long start, long end) { this.start = start; this.end = end; }
    }

    public DownloadTask(long id,
                        @NonNull DownloadSpec spec,
                        @NonNull SeekableFile target,
                        @NonNull OkHttpClient client,
                        @NonNull Listener listener) {
        this.id = id;
        this.spec = spec;
        this.target = target;
        this.client = client;
        this.listener = listener;
    }

    // ------------------------------------------------------------------------
    // Getters
    // ------------------------------------------------------------------------

    public long getId() { return id; }
    public DownloadSpec getSpec() { return spec; }
    @NonNull public SeekableFile getTarget() { return target; }
    @NonNull public State getState() { return state; }
    public long getBytesTotal() { return bytesTotal; }
    public long getBytesDownloaded() { return bytesDownloaded.get(); }
    @Nullable public String getErrorMessage() { return errorMessage; }

    public void setWorkerPool(@NonNull ExecutorService pool) {
        this.workerPool = pool;
    }

    // ------------------------------------------------------------------------
    // Control
    // ------------------------------------------------------------------------

    /** Dispatch (or resume) all pending chunks onto the given pool. */
    public void start(@NonNull ExecutorService pool) {
        if (state == State.COMPLETED || state == State.CANCELLED) return;
        paused.set(false);
        setState(State.RUNNING);
        pool.execute(this::runAllChunks);
    }

    /** Alias for start() when the task was previously paused. */
    public void resume(@NonNull ExecutorService pool) {
        if (state != State.PAUSED) return;
        start(pool);
    }

    public void pause() {
        if (state != State.RUNNING) return;
        paused.set(true);
        setState(State.PAUSED);
    }

    /**
     * Abort an in-flight task and delete its partial file.
     *
     * Terminal no-op when the task already COMPLETED — the file belongs
     * to the user at that point and remove() must not destroy it.
     */
    public void cancel() {
        if (state == State.COMPLETED) return;
        cancelled.set(true);
        setState(State.CANCELLED);
        try { target.delete(); } catch (Exception ignored) {}
        try { target.close(); } catch (Exception ignored) {}
    }

    /**
     * Release the target's file descriptor. Safe to call after commit.
     * Idempotent. Called by DownloadEngine.remove() for every task.
     */
    public void closeTarget() {
        try { target.close(); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------------
    // Orchestration
    // ------------------------------------------------------------------------

    private void runAllChunks() {
        try {
            if (chunks.isEmpty()) {
                if (!probe()) {
                    fail("Could not reach server");
                    return;
                }
                if (!prepareTarget()) {
                    fail("Could not create file");
                    return;
                }
                buildChunks();
            }

            CountDownLatch latch = new CountDownLatch(chunks.size());
            for (Chunk c : chunks) {
                if (c.done.get()) { latch.countDown(); continue; }
                final Chunk chunk = c;
                workerPool.execute(() -> {
                    try {
                        if (!paused.get() && !cancelled.get()) downloadChunk(chunk);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return;
        }

        if (cancelled.get()) return;
        if (paused.get()) return;

        boolean allDone = true;
        for (Chunk c : chunks) if (!c.done.get()) { allDone = false; break; }

        if (allDone) {
            try {
                target.commit();
            } catch (Exception e) {
                fail("commit failed: "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                return;
            }
            completedAt = System.currentTimeMillis();
            setState(State.COMPLETED);
        } else {
            setState(State.FAILED);
            errorMessage = "Incomplete download";
        }
    }

    // ------------------------------------------------------------------------
    // Probe (HEAD-style, but many CDNs reject HEAD so we use GET range 0-0)
    // ------------------------------------------------------------------------

    private boolean probe() {
        try {
            Request.Builder b = new Request.Builder()
                    .url(spec.url)
                    .header("Range", "bytes=0-0")
                    .header("Accept-Encoding", "identity");
            applyHeaders(b);
            Request req = b.build();

            Response resp = client.newCall(req).execute();
            try (ResponseBody body = resp.body()) {
                int code = resp.code();
                if (code != 200 && code != 206) {
                    if (code == 416) return false;
                }

                String ranges = resp.header("Accept-Ranges", "");
                String contentRange = resp.header("Content-Range", "");
                String contentLength = resp.header("Content-Length", "");

                if ("bytes".equalsIgnoreCase(ranges) || contentRange != null) {
                    rangeSupported = true;
                }

                if (contentRange != null && contentRange.contains("/")) {
                    String total = contentRange.substring(contentRange.indexOf('/') + 1).trim();
                    if (!"*".equals(total)) {
                        try { bytesTotal = Long.parseLong(total); } catch (Exception ignored) {}
                    }
                } else if (contentLength != null && !contentLength.isEmpty()) {
                    try {
                        long len = Long.parseLong(contentLength);
                        if (code == 200) bytesTotal = len;
                    } catch (Exception ignored) {}
                }
                return true;
            }
        } catch (Exception e) {
            errorMessage = e.getMessage();
            return false;
        }
    }

    private void applyHeaders(Request.Builder b) {
        if (spec.userAgent != null && !spec.userAgent.isEmpty()) {
            b.header("User-Agent", spec.userAgent);
        }
        if (spec.referer != null && !spec.referer.isEmpty()) {
            b.header("Referer", spec.referer);
        }
        if (spec.cookies != null && !spec.cookies.isEmpty()) {
            b.header("Cookie", spec.cookies);
        }
        if (spec.mime != null && !spec.mime.isEmpty()) {
            b.header("Accept", spec.mime + ",*/*;q=0.8");
        }
    }

    // ------------------------------------------------------------------------
    // Target file + chunk plan
    // ------------------------------------------------------------------------

    private boolean prepareTarget() {
        try {
            // Best-effort preallocation. MediaStoreFile ignores this (cannot
            // extend via truncate) — parallel writes past EOF extend the file.
            if (bytesTotal > 0) {
                target.setLength(bytesTotal);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void buildChunks() {
        if (!rangeSupported || bytesTotal <= 0 || bytesTotal < MIN_CHUNKED_SIZE) {
            // Single-stream: one chunk covering the whole file (end == -1 => read until EOF)
            chunks.add(new Chunk(0, bytesTotal > 0 ? bytesTotal - 1 : -1));
            return;
        }
        int n = (int) Math.min(MAX_CHUNKS, Math.max(1, bytesTotal / CHUNK_SIZE));
        long base = bytesTotal / n;
        long cursor = 0;
        for (int i = 0; i < n; i++) {
            long end = (i == n - 1) ? (bytesTotal - 1) : (cursor + base - 1);
            chunks.add(new Chunk(cursor, end));
            cursor = end + 1;
        }
    }

    // ------------------------------------------------------------------------
    // The actual download loop for one chunk
    // ------------------------------------------------------------------------

    private void downloadChunk(@NonNull Chunk c) {
        try {
            while (true) {
                if (cancelled.get()) return;
                if (paused.get()) return;

                long fetchFrom = c.start + c.downloaded;
                long fetchTo = c.end >= 0 ? c.end : -1;
                if (fetchTo >= 0 && fetchFrom > fetchTo) {
                    c.done.set(true);
                    return;
                }

                Request.Builder b = new Request.Builder()
                        .url(spec.url)
                        .header("Accept-Encoding", "identity");
                if (c.end >= 0) {
                    b.header("Range", "bytes=" + fetchFrom + "-" + fetchTo);
                }
                applyHeaders(b);

                Response resp = null;
                try {
                    resp = client.newCall(b.build()).execute();
                    int code = resp.code();
                    if (code != 200 && code != 206) {
                        fail("HTTP " + code);
                        return;
                    }

                    ResponseBody body = resp.body();
                    if (body == null) {
                        fail("Empty response");
                        return;
                    }

                    InputStream in = body.byteStream();
                    byte[] buf = new byte[READ_BUFFER];
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        if (cancelled.get() || paused.get()) return;
                        // Positioned write — no seek, no shared cursor mutation.
                        target.writeAt(c.start + c.downloaded, buf, 0, read);
                        c.downloaded += read;
                        bytesDownloaded.addAndGet(read);
                        listener.onProgress(id);
                    }

                    // If this was the whole range in one shot (or read to EOF on a
                    // single-stream download), we're done.
                    if (c.end < 0) {
                        c.done.set(true);
                        return;
                    }
                    if (c.start + c.downloaded > c.end) {
                        c.done.set(true);
                        return;
                    }
                    // Otherwise, the connection closed early — loop and re-request
                    // from where we stopped (server may have chunked the response).
                } finally {
                    if (resp != null) resp.close();
                }
            }
        } catch (Exception e) {
            if (!cancelled.get() && !paused.get()) {
                fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------------
    // State management
    // ------------------------------------------------------------------------

    private void setState(State s) {
        if (state == s) return;
        state = s;
        listener.onStateChanged(id, s);
    }

    private void fail(String msg) {
        errorMessage = msg;
        setState(State.FAILED);
        // Partial file is useless — roll it back so the user doesn't see a
        // truncated artifact in their file manager.
        try { target.delete(); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------------
    // Snapshot for persistence
    // ------------------------------------------------------------------------

    @NonNull
    public DownloadTaskState snapshot() {
        DownloadTaskState s = new DownloadTaskState();
        s.id = id;
        s.url = spec.url;
        s.fileName = spec.fileName;
        s.mime = spec.mime;
        s.userAgent = spec.userAgent;
        s.referer = spec.referer;
        s.cookies = spec.cookies;
        s.state = state.ordinal();
        s.bytesTotal = bytesTotal;
        s.bytesDownloaded = bytesDownloaded.get();
        s.errorMessage = errorMessage;
        s.createdAt = createdAt;
        s.completedAt = completedAt;
        for (Chunk c : chunks) {
            DownloadTaskState.Chunk out = new DownloadTaskState.Chunk();
            out.start = c.start;
            out.end = c.end;
            out.downloaded = c.downloaded;
            out.done = c.done.get();
            s.chunks.add(out);
        }
        return s;
    }

    /** Restore chunk progress from a persisted snapshot (for app-restart resume). */
    public void restoreFrom(@NonNull DownloadTaskState s) {
        this.bytesTotal = s.bytesTotal;
        this.bytesDownloaded.set(s.bytesDownloaded);
        this.errorMessage = s.errorMessage;
        this.createdAt = s.createdAt;
        this.completedAt = s.completedAt;
        this.rangeSupported = s.chunks.size() > 1;
        this.chunks.clear();
        for (DownloadTaskState.Chunk in : s.chunks) {
            Chunk c = new Chunk(in.start, in.end);
            c.downloaded = in.downloaded;
            c.done.set(in.done);
            chunks.add(c);
        }
        try {
            this.state = State.values()[s.state];
        } catch (Exception e) {
            this.state = State.QUEUED;
        }
        if (this.state == State.RUNNING) this.state = State.PAUSED;
    }
}
