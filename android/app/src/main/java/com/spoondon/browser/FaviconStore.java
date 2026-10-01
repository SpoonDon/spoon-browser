package com.spoondon.browser;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Disk + memory cache of site favicons for the home-page bookmarks grid.
 *
 * Fetches directly from {@code https://HOST/favicon.ico} — no third-party
 * favicon service — so nothing about the user's bookmark list leaves the
 * device. Failures fall back to a letter tile in the renderer; we never
 * block a render on the network.
 *
 * Memory layer is an LruCache of host -> data: URL (base64). Disk layer
 * is one file per host in {@code getCacheDir()/favicons/}, TTL 7 days.
 * In-flight dedup prevents N parallel fetches for the same host when the
 * home page is rendered repeatedly while a fetch is still running.
 */
public class FaviconStore {

    private static final String TAG = "SpoonBrowser_Favicon";
    private static final String DIR_NAME = "favicons";

    /** Refuse anything bigger than this — a favicon has no business being 30KB. */
    private static final long MAX_ICON_BYTES = 30 * 1024;

    /** Cache lifetime per host. Sites change favicons rarely. */
    private static final long CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    /** Small — the working set is the visible grid. */
    private static final int MEM_CACHE_SIZE = 48;

    private final File dir;
    private final OkHttpClient http;
    private final ExecutorService executor;
    private final Set<String> inFlight =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final LruCache<String, String> memCache;

    public FaviconStore(@NonNull Context context) {
        this.dir = new File(context.getCacheDir(), DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        this.http = new OkHttpClient.Builder().build();
        this.executor = Executors.newFixedThreadPool(4);
        this.memCache = new LruCache<>(MEM_CACHE_SIZE);
    }

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------

    /**
     * Returns a {@code data:image/...;base64,...} URL for the host if a
     * usable icon is cached, otherwise {@code null}. Never performs I/O
     * beyond a couple of stat() calls plus one small file read on miss.
     */
    @Nullable
    public String getCachedDataUrl(@Nullable String host) {
        if (host == null || host.isEmpty()) return null;

        String mem = memCache.get(host);
        if (mem != null) return mem.isEmpty() ? null : mem;

        File f = fileFor(host);
        if (!f.exists() || f.length() == 0 || f.length() > MAX_ICON_BYTES) return null;
        if (System.currentTimeMillis() - f.lastModified() > CACHE_TTL_MS) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }

        try (FileInputStream in = new FileInputStream(f)) {
            byte[] bytes = readAll(in);
            if (!looksLikeImage(bytes)) return null;
            String dataUrl = "data:" + sniffMime(bytes) + ";base64,"
                    + Base64.encodeToString(bytes, Base64.NO_WRAP);
            memCache.put(host, dataUrl);
            return dataUrl;
        } catch (Exception e) {
            Log.d(TAG, "read failed for " + host, e);
            return null;
        }
    }

    /**
     * Fire-and-forget fetch. Safe to call on every render — in-flight
     * requests are deduped and cache hits are no-ops. {@code onDone}
     * runs on a background thread once the fetch completes (success or
     * failure). Pass {@code null} if you don't care.
     */
    public void fetchAsync(@NonNull String host, @Nullable Runnable onDone) {
        if (host.isEmpty()) {
            if (onDone != null) onDone.run();
            return;
        }
        if (getCachedDataUrl(host) != null) {
            if (onDone != null) onDone.run();
            return;
        }
        if (!inFlight.add(host)) return;

        executor.execute(() -> {
            try {
                Request req = new Request.Builder()
                        .url("https://" + host + "/favicon.ico")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                        .header("Accept", "image/*,*/*;q=0.5")
                        .build();

                try (Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful()) return;
                    ResponseBody body = resp.body();
                    if (body == null) return;

                    long declared = body.contentLength();
                    if (declared > MAX_ICON_BYTES) return;

                    byte[] bytes = body.bytes();
                    if (bytes.length == 0 || bytes.length > MAX_ICON_BYTES) return;
                    if (!looksLikeImage(bytes)) return;

                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    try (FileOutputStream out = new FileOutputStream(fileFor(host))) {
                        out.write(bytes);
                    }
                    // Bust the memory layer so the next getCachedDataUrl re-reads
                    // the freshly-written disk file.
                    memCache.remove(host);
                }
            } catch (Exception e) {
                Log.d(TAG, "fetch failed for " + host);
            } finally {
                inFlight.remove(host);
                if (onDone != null) onDone.run();
            }
        });
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    @NonNull
    private File fileFor(@NonNull String host) {
        // Host strings are already safe (alphanumeric + dot + hyphen) but be
        // defensive — an attacker-controlled URL with weird characters must
        // not be able to traverse the cache directory.
        String safe = host.replaceAll("[^a-zA-Z0-9.-]", "_");
        return new File(dir, safe);
    }

    @NonNull
    private static byte[] readAll(@NonNull FileInputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Magic-byte sniff. Rejects HTML error pages served with a 200. */
    private static boolean looksLikeImage(@NonNull byte[] b) {
        if (b.length < 4) return false;
        int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF, b2 = b[2] & 0xFF, b3 = b[3] & 0xFF;
        // PNG
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true;
        // ICO / CUR
        if (b0 == 0x00 && b1 == 0x00 && b2 == 0x01 && b3 == 0x00) return true;
        // JPEG
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return true;
        // GIF87a / GIF89a
        if (b0 == 'G' && b1 == 'I' && b2 == 'F' && b3 == '8') return true;
        // WebP (RIFF....WEBP)
        if (b.length >= 12
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return true;
        return false;
    }

    @NonNull
    private static String sniffMime(@NonNull byte[] b) {
        if (b.length < 4) return "image/png";
        int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
        if (b0 == 0x89 && b1 == 0x50) return "image/png";
        if (b0 == 0xFF && b1 == 0xD8) return "image/jpeg";
        if (b0 == 'G' && b1 == 'I') return "image/gif";
        if (b.length >= 12 && b[8] == 'W' && b[9] == 'E') return "image/webp";
        return "image/x-icon";
    }
}
