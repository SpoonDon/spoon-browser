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

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class BlobDownloader {
    private final Context context;
    private final Handler mainHandler;

    public BlobDownloader(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @JavascriptInterface
    public void saveBase64ToFile(String base64Data, String mimeType, String fileName) {
        if (base64Data == null || fileName == null) return;
        
        try {
            int commaIndex = base64Data.indexOf(",");
            if (commaIndex != -1) {
                base64Data = base64Data.substring(commaIndex + 1);
            }
            
            // Decode payload
            byte[] fileBytes = Base64.decode(base64Data, Base64.DEFAULT);

            // --- SECURITY FIX: Prevent Resource Exhaustion / Storage Spam (100MB Limit) ---
            if (fileBytes.length > 100 * 1024 * 1024) {
                mainHandler.post(() ->
                    Toast.makeText(context, "File is too large for in-browser blob download.", Toast.LENGTH_LONG).show()
                );
                return;
            }
            // -----------------------------------------------------------------------------

            // AUDIT FIX: Catch and repair mislabeled PDF blobs/base64 streams
            String safeMimeType = (mimeType == null || mimeType.isEmpty()) ? "application/octet-stream" : mimeType;
            
            // --- SECURITY FIX: Prevent Path Traversal (e.g., "../../../secret.txt") ---
            String safeName = new File(fileName).getName();
            // Strip any invalid filesystem characters just in case
            safeName = safeName.replaceAll("[^a-zA-Z0-9._-]", "_");
            String cleanFileName = safeName;
            // --------------------------------------------------------------------------

            // --- MIME TYPE OVERRIDE: Prevent Android from appending .txt ---
            int lastDotIndex = cleanFileName.lastIndexOf(".");
            if (lastDotIndex != -1) {
                String extension = cleanFileName.substring(lastDotIndex + 1).toLowerCase();
                String guessedMime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                if (guessedMime != null) {
                    safeMimeType = guessedMime;
                } else if (extension.equals("json")) {
                    safeMimeType = "application/json";
                } else if (extension.equals("md")) {
                    safeMimeType = "text/markdown";
                }
            }
            // ---------------------------------------------------------------

            boolean isActuallyPdf = safeMimeType.equalsIgnoreCase("application/pdf") ||
                    cleanFileName.toLowerCase().contains(".pdf");

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

            // Modern Android (API 29+) Scoped Storage Pipeline
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, cleanFileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, safeMimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                
                Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            os.write(fileBytes);
                            os.flush();
                        }
                    }
                }
            } 
            // Legacy Android (API 21-28) Direct File Pipeline
            else {
                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadDir.exists()) downloadDir.mkdirs();
                
                // Note: cleanFileName is now guaranteed safe from path traversal
                File targetFile = new File(downloadDir, cleanFileName);
                try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                    fos.write(fileBytes);
                    fos.flush();
                }
            }

            final String finalFileName = cleanFileName;
            mainHandler.post(() ->
                Toast.makeText(context, "Download complete: " + finalFileName, Toast.LENGTH_LONG).show()
            );
            
        } catch (OutOfMemoryError e) {
            mainHandler.post(() ->
                Toast.makeText(context, "File is too large to download this way.", Toast.LENGTH_LONG).show()
            );
        } catch (Exception e) {
            mainHandler.post(() ->
                Toast.makeText(context, "Download error: " + e.getMessage(), Toast.LENGTH_SHORT).show()
            );
        }
    }
}
