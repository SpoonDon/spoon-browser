package com.spoondon.browser;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Publishes a completed download to a location visible in the system
 * file manager.
 *
 *   API 29+   -> MediaStore.Downloads collection, RELATIVE_PATH=Download/Spoon
 *   API 24-28 -> /sdcard/Download/Spoon/ (WRITE_EXTERNAL_STORAGE required)
 *
 * The original app-private file is left untouched, so callers holding a
 * File handle to it remain valid (Open / Share via FileProvider continue
 * to work). This means two copies of the file exist on disk while the
 * download is retained in Spoon's list.
 *
 * Returns the content:// (29+) or file:// (24-28) URI of the published
 * file, or null on failure.
 */
public final class MediaStorePublisher {

    private static final String TAG = "MediaStorePublisher";
    public static final String SUBDIR = "Spoon";

    private MediaStorePublisher() {}

    @Nullable
    public static Uri publish(@NonNull Context context,
                              @NonNull File source,
                              @NonNull String displayName,
                              @Nullable String mime) {
        if (!source.exists() || source.length() == 0) return null;
        String effectiveMime = (mime == null || mime.isEmpty())
                ? "application/octet-stream" : mime;
        String safeName = DownloadNaming.sanitize(displayName);
        if (safeName.isEmpty()) safeName = "download.bin";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return publishModern(context, source, safeName, effectiveMime);
        } else {
            return publishLegacy(context, source, safeName, effectiveMime);
        }
    }

    private static Uri publishModern(Context context, File source,
                                     String displayName, String mime) {
        ContentResolver cr = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR);
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        Uri item = null;
        try {
            item = cr.insert(collection, values);
            if (item == null) return null;

            try (InputStream in = new FileInputStream(source);
                 OutputStream out = cr.openOutputStream(item, "w")) {
                if (out == null) {
                    cr.delete(item, null, null);
                    return null;
                }
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(item, done, null, null);
            return item;
        } catch (Exception e) {
            Log.w(TAG, "publishModern failed", e);
            if (item != null) {
                try { cr.delete(item, null, null); } catch (Exception ignored) {}
            }
            return null;
        }
    }

    private static Uri publishLegacy(Context context, File source,
                                     String displayName, String mime) {
        try {
            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS),
                    SUBDIR);
            if (!dir.exists() && !dir.mkdirs()) return null;
            File dest = uniqueFile(dir, displayName);

            try (InputStream in = new FileInputStream(source);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            }

            // Notify MediaScanner so it shows up in galleries/file managers.
            android.media.MediaScannerConnection.scanFile(
                    context, new String[]{dest.getAbsolutePath()},
                    new String[]{mime}, null);
            return Uri.fromFile(dest);
        } catch (Exception e) {
            Log.w(TAG, "publishLegacy failed", e);
            return null;
        }
    }

    /** Avoid clobbering an existing file — append (1), (2), ... before the ext. */
    private static File uniqueFile(File dir, String displayName) {
        File f = new File(dir, displayName);
        if (!f.exists()) return f;
        String base = displayName;
        String ext = "";
        int dot = displayName.lastIndexOf('.');
        if (dot > 0) {
            base = displayName.substring(0, dot);
            ext = displayName.substring(dot);
        }
        for (int i = 1; i < 1000; i++) {
            File cand = new File(dir, base + " (" + i + ")" + ext);
            if (!cand.exists()) return cand;
        }
        return f;
    }
}
