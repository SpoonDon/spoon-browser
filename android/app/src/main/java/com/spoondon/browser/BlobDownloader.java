package com.spoondon.browser;

import android.content.Context;
import android.os.Environment;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;

public class BlobDownloader {
    private final Context context;

    public BlobDownloader(Context context) {
        this.context = context;
    }

    @JavascriptInterface
    public void saveBase64ToFile(String base64Data, String mimeType, String fileName) {
        try {
            if (base64Data.contains(",")) {
                base64Data = base64Data.split(",")[1];
            }

            byte[] fileBytes = Base64.decode(base64Data, Base64.DEFAULT);
            File targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File outputFile = new File(targetDir, fileName);

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(fileBytes);
                fos.flush();
            }

            if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).runOnUiThread(() -> 
                    Toast.makeText(context, "Download complete: " + fileName, Toast.LENGTH_LONG).show()
                );
            }
        } catch (Exception e) {
            if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).runOnUiThread(() -> 
                    Toast.makeText(context, "Extraction error: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                );
            }
        }
    }
}
