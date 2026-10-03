package com.spoondon.browser;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Saves a JS-generated blob (delivered as a base64 data URL over the
 * SpoonDownloader bridge) to the user's Downloads folder.
 *
 * Size guard runs on the ENCODED string BEFORE decode. Base64 overhead is
 * roughly 4/3, so a raw base64 length of `maxBytes * 4 / 3 + slack` maps
 * to a decoded payload of at most `maxBytes`. Rejecting early avoids
 * allocating the byte array for anything larger than we're willing to
 * accept, and avoids the substring() copy entirely for oversized inputs.
 */
public class BlobDownloader {

    /** Hard cap on the decoded file size. 50 MB is plenty for a browser blob. */
    private static final int MAX_BLOB_BYTES = 50 * 1024 * 1024;

    /**
     * Max base64 string length that could possibly decode to MAX_BLOB_BYTES.
     * Base64 encodes 3 bytes per 4 chars, so decoded = encoded * 3 / 4.
     * We want an upper bound on encoded for a given decoded cap, i.e.
     * encoded <= decoded * 4 / 3. Add a small slack for padding.
     */
    private static final int MAX_BASE64_CHARS = (MAX_BLOB_BYTES / 3) * 4 + 64;

    private final Context context;
    private final Handler mainHandler;

    public BlobDownloader(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @JavascriptInterface
    public void saveBase64ToFile(String base64Data, String mimeType, String fileName) {
        if (base64Data == null || fileName == null) return;

        // Early size guard — BEFORE any substring() copy or Base64.decode()
        // allocation. If the encoded string is over the cap, we can't fit
        // the decoded payload no matter what; reject and stop.
        if (base64Data.length() > MAX_BASE64_CHARS) {
            toast("File is too large for in-browser blob download.");
            return;
        }

        try {
            int commaIndex = base64Data.indexOf(",");
            if (commaIndex != -1) {
                base64Data = base64Data.substring(commaIndex + 1);
            }

            // Second guard, now on the payload portion only.
            if (base64Data.length() > MAX_BASE64_CHARS) {
                toast("File is too large for in-browser blob download.");
                return;
            }

            byte[] fileBytes = Base64.decode(base64Data, Base64.DEFAULT);

            // Third guard, on the actual decoded size.
            if (fileBytes.length > MAX_BLOB_BYTES) {
                toast("File is too large for in-browser blob download.");
                return;
            }

            String safeMimeType = (mimeType == null || mimeType.isEmpty())
                    ? "application/octet-stream" : mimeType;

            // Path traversal: take just the base name, strip anything weird.
            String safeName = new File(fileName).getName();
            safeName = safeName.replaceAll("[^a-zA-Z0-9._-]", "_");
            String cleanFileName = safeName;

            // MIME override so Android doesn't append .txt to known extensions.
            int lastDotIndex = cleanFileName.lastIndexOf(".");
            if (lastDotIndex != -1) {
                String extension = cleanFileName.substring(lastDotIndex + 1).toLowerCase();
                String guessedMime = android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(extension);
                if (guessedMime != null) {
                    safeMimeType = guessedMime;
                } else if (extension.equals("json")) {
                    safeMimeType = "application/json";
                } else if (extension.equals("md")) {
                    safeMimeType = "text/markdown";
                }
            }

            boolean isActuallyPdf = safeMimeType.equalsIgnoreCase("application/pdf")
                    || cleanFileName.toLowerCase().contains(".pdf");

            if (isActuallyPdf) {
                if (safeMimeType.equals("application/octet-stream")) {
                    safeMimeType = "application/pdf";
                }
                if (cleanFileName.endsWith(".bin")) {
                    cleanFileName = cleanFileName.substring(0, cleanFileName.length() - 4) + ".pdf";
                } else if (!cleanFileName.toLowerCase().endsWith(".pdf")) {
                    cleanFileName += ".pdf";
                }
            }

            // Modern Android (API 29+): MediaStore, scoped storage.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, cleanFileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, safeMimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS);

                Uri uri = context.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            os.write(fileBytes);
                            os.flush();
                        }
                    }
                }
            }
            // Legacy Android (API 21-28): direct file write.
            else {
                File downloadDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (!downloadDir.exists()) downloadDir.mkdirs();

                File targetFile = new File(downloadDir, cleanFileName);
                try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                    fos.write(fileBytes);
                    fos.flush();
                }
            }

            final String finalFileName = cleanFileName;
            toast("Download complete: " + finalFileName);

        } catch (OutOfMemoryError e) {
            toast("File is too large to download this way.");
        } catch (Exception e) {
            toast("Download error: " + (e.getMessage() != null ? e.getMessage() : "unknown"));
        }
    }

    private void toast(@NonNull String msg) {
        mainHandler.post(() ->
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show());
    }
}
