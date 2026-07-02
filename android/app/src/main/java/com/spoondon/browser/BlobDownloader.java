package com.spoondon.browser;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;

public class BlobDownloader {
    private final Context context;
    private final Handler mainHandler;

    public BlobDownloader(Context context) {
        this.context = context;
        // Instantiate a dedicated main looper pipeline to safely run UI updates from background JS threads
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @JavascriptInterface
    public void saveBase64ToFile(String base64Data, String mimeType, String fileName) {
        if (base64Data == null || fileName == null) return;

        try {
            // OPTIMIZATION: High-performance substring index extraction to bypass memory-heavy string splitting arrays
            int commaIndex = base64Data.indexOf(",");
            if (commaIndex != -1) {
                base64Data = base64Data.substring(commaIndex + 1);
            }

            byte[] fileBytes = Base64.decode(base64Data, Base64.DEFAULT);
            File targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File outputFile = new File(targetDir, fileName);

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(fileBytes);
                fos.flush();
            }

            // OPTIMIZATION: Looper pipeline ensures toasts display securely regardless of contextual instantiation types
            mainHandler.post(() -> 
                Toast.makeText(context, "Download complete: " + fileName, Toast.LENGTH_LONG).show()
            );

        } catch (Exception e) {
            mainHandler.post(() -> 
                Toast.makeText(context, "Extraction error: " + e.getMessage(), Toast.LENGTH_SHORT).show()
            );
        }
    }
}
