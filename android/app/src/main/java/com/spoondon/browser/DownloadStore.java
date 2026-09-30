package com.spoondon.browser;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistence for DownloadTaskState. Stores a single JSON file at
 * {@code <filesDir>/downloads.json}.
 *
 * Writes are atomic: we serialize to downloads.json.tmp first, then rename.
 * A crash between write and rename leaves the previous good file intact.
 *
 * All public methods are synchronized; callers may invoke from any thread.
 * The file itself is small (a few KB for hundreds of downloads), so we
 * don't bother with incremental writes.
 */
public final class DownloadStore {

    private static final String FILE_NAME = "downloads.json";
    private static final String TMP_NAME  = "downloads.json.tmp";
    private static final int    VERSION   = 1;

    private final File file;
    private final File tmpFile;

    public DownloadStore(@NonNull Context context) {
        File dir = context.getFilesDir();
        this.file = new File(dir, FILE_NAME);
        this.tmpFile = new File(dir, TMP_NAME);
    }

    // ------------------------------------------------------------------------
    // Save
    // ------------------------------------------------------------------------

    /** Persist the given tasks. Never throws — a write failure is swallowed. */
    public synchronized void save(@NonNull List<DownloadTaskState> tasks) {
        FileOutputStream fos = null;
        OutputStreamWriter writer = null;
        try {
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            JSONArray arr = new JSONArray();
            for (DownloadTaskState s : tasks) {
                arr.put(s.toJson());
            }
            root.put("tasks", arr);

            fos = new FileOutputStream(tmpFile, false);
            writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            writer.write(root.toString());
            writer.flush();
            writer.close();
            writer = null;
            fos = null;

            // Atomic swap. File.renameTo across the same directory is atomic
            // on every Android filesystem we care about.
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            if (!tmpFile.renameTo(file)) {
                // Fallback: copy contents then delete tmp.
                copyFile(tmpFile, file);
                //noinspection ResultOfMethodCallIgnored
                tmpFile.delete();
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly(writer);
            closeQuietly(fos);
        }
    }

    // ------------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------------

    /** Read all persisted tasks. Returns an empty list if none or on error. */
    @NonNull
    public synchronized List<DownloadTaskState> load() {
        List<DownloadTaskState> out = new ArrayList<>();
        if (!file.exists()) return out;

        FileInputStream fis = null;
        BufferedReader reader = null;
        try {
            fis = new FileInputStream(file);
            reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);

            JSONObject root = new JSONObject(sb.toString());
            // version is present for forward-compat; we ignore it for now.
            JSONArray arr = root.optJSONArray("tasks");
            if (arr == null) return out;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                DownloadTaskState s = DownloadTaskState.fromJson(o);
                if (s != null) out.add(s);
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly(reader);
            closeQuietly(fis);
        }
        return out;
    }

    // ------------------------------------------------------------------------
    // Wipe
    // ------------------------------------------------------------------------

    /** Delete the on-disk record. Used by "clear all downloads" and logout. */
    public synchronized void wipe() {
        try {
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            if (tmpFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmpFile.delete();
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static void copyFile(@NonNull File src, @NonNull File dst) throws Exception {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst, false);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            closeQuietly(out);
            closeQuietly(in);
        }
    }

    private static void closeQuietly(@Nullable Object c) {
        if (c == null) return;
        try {
            if (c instanceof java.io.Closeable) {
                ((java.io.Closeable) c).close();
            }
        } catch (Exception ignored) {
        }
    }
}
