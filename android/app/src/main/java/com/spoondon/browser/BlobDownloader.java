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

            // Modern Android (API 29+) Scoped Storage Pipeline
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType != null ? mimeType : "application/octet-stream");
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
                
                File targetFile = new File(downloadDir, fileName);
                try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                    fos.write(fileBytes);
                    fos.flush();
                }
            }

            mainHandler.post(() ->
                    Toast.makeText(context, "Download complete: " + fileName, Toast.LENGTH_LONG).show()
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
