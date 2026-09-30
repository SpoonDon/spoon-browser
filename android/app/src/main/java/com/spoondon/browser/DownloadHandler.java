package com.spoondon.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;

/**
 * Handles WebView download callbacks.
 *
 * Updated 2026-09-30 to fix "downloads silently fail":
 *   - enqueue is now delegated to {@link DownloadsController}, which
 *     registers a BroadcastReceiver so failures surface as a toast.
 *   - the current page URL is added as a Referer header. Many CDNs 403
 *     a request without one.
 *   - filename sanitization was moved into DownloadsController and
 *     strengthened (colons, control chars, reserved chars).
 */
public class DownloadHandler implements DownloadListener {

    private final Activity activity;
    private final WebViewProvider webViewProvider;
    private final DownloadsController downloadsController;

    public interface WebViewProvider {
        /** @return the currently active WebView (may be null). */
        WebView get();
    }

    public DownloadHandler(Activity activity,
                           WebViewProvider webViewProvider,
                           DownloadsController downloadsController) {
        this.activity = activity;
        this.webViewProvider = webViewProvider;
        this.downloadsController = downloadsController;
    }

    /** Install this handler on a freshly created WebView. */
    public void attach(WebView webView) {
        webView.setDownloadListener(this);
    }

    @Override
    public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                String mimeType, long contentLength) {
        if (url == null) return;

        if (url.startsWith("blob:")) {
            handleBlobDownload(url, contentDisposition, mimeType);
            return;
        }

        try {
            String targetFileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            String lowerUrl = url.toLowerCase();

            boolean isActuallyPdf =
                    (mimeType != null && mimeType.equalsIgnoreCase("application/pdf"))
                            || lowerUrl.contains(".pdf")
                            || (contentDisposition != null
                                && contentDisposition.toLowerCase().contains(".pdf"));

            if (targetFileName.endsWith(".bin") || targetFileName.equals("downloadfile")) {
                if (isActuallyPdf) {
                    targetFileName = targetFileName.replace(".bin", "")
                            .replace("downloadfile", "download") + ".pdf";
                } else {
                    String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                    if (ext != null) {
                        targetFileName = targetFileName.replace(".bin", "")
                                .replace("downloadfile", "download") + "." + ext;
                    }
                }
            }

            if (isActuallyPdf && !targetFileName.toLowerCase().endsWith(".pdf")) {
                targetFileName += ".pdf";
            }
            if (targetFileName.startsWith(".")) {
                targetFileName = targetFileName.substring(1) + ".txt";
            }
            if (lowerUrl.contains(".md") && !targetFileName.endsWith(".md"))     targetFileName += ".md";
            if (lowerUrl.contains(".json") && !targetFileName.endsWith(".json")) targetFileName += ".json";

            String safeMime = (mimeType == null || mimeType.isEmpty())
                    ? "application/octet-stream"
                    : mimeType;
            if (isActuallyPdf && safeMime.equals("application/octet-stream")) {
                safeMime = "application/pdf";
            }

            final String finalName = targetFileName;
            final String finalMime = safeMime;
            final String finalUserAgent = userAgent;

            new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Download File")
                    .setMessage("Do you want to download " + finalName + "?")
                    .setPositiveButton("Download",
                            (d, i) -> enqueueDownload(url, finalUserAgent, finalMime, finalName))
                    .setNeutralButton("External Only",
                            (d, i) -> triggerExternalDownload(url, finalMime))
                    .setNegativeButton("Cancel", null)
                    .show();

        } catch (Exception err) {
            Toast.makeText(activity, "Download manager initialization failed",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void enqueueDownload(String url, String userAgent, String mime, String fileName) {
        String referer = null;
        try {
            WebView wv = webViewProvider != null ? webViewProvider.get() : null;
            if (wv != null) referer = wv.getUrl();
        } catch (Exception ignored) {}

        if (downloadsController == null) {
            // Fallback: no controller wired. Try the external path.
            triggerExternalDownload(url, mime);
            return;
        }

        long id = downloadsController.enqueue(url, userAgent, mime, fileName, referer);
        if (id == -1) {
            // enqueue already reported the error to the user.
        }
    }

    public void triggerExternalDownload(String url, String mime) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "No external app found", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------------
    // Blob URL handling
    // ------------------------------------------------------------------------
    private void handleBlobDownload(String url, String contentDisposition, String mimeType) {
        WebView webView = webViewProvider != null ? webViewProvider.get() : null;
        if (webView == null) return;

        String safeDisposition = contentDisposition != null
                ? contentDisposition.replace("'", "\\'") : "";
        String safeMime = mimeType != null ? mimeType.replace("'", "\\'") : "";

        String script = "javascript:(function() {" +
                "var url = '" + url + "';" +
                "var blob = window.spoonBlobStore ? window.spoonBlobStore[url] : null;" +
                "function processBlob(b) {" +
                "  var mime = b.type || '" + safeMime + "';" +
                "  var filename = '" + safeDisposition + "'.match(/filename=[\"']?([^\"';]+)[\"']?/i);" +
                "  filename = filename ? filename[1] : 'downloaded_file';" +
                "  if (filename === 'downloaded_file') {" +
                "    if (mime.includes('json')) filename += '.json';" +
                "    else if (mime.includes('pdf')) filename += '.pdf';" +
                "    else if (mime.includes('text')) filename += '.txt';" +
                "    else filename += '.bin';" +
                "  }" +
                "  var reader = new FileReader();" +
                "  reader.readAsDataURL(b);" +
                "  reader.onloadend = function() {" +
                "    AndroidDownloader.saveBase64ToFile(reader.result, mime, filename);" +
                "  };" +
                "}" +
                "if (blob) { processBlob(blob); }" +
                "else {" +
                "  fetch(url).then(function(r){return r.blob();}).then(processBlob)" +
                "    .catch(function(e){alert('Blob download failed. The site bypassed the memory hook.');});" +
                "}" +
                "})()";

        webView.evaluateJavascript(script, null);
        Toast.makeText(activity, "Extracting file...", Toast.LENGTH_SHORT).show();
    }
}
