package com.spoondon.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;

/**
 * Handles WebView download callbacks.
 *
 * 2026-10-03 (Option B — direct-to-MediaStore):
 *   - enqueueDownload now forwards contentDisposition to the 6-arg
 *     DownloadsController.enqueue(). DownloadNaming.resolve() owns all
 *     filename derivation (RFC5987 CD -> simple CD -> URL basename ->
 *     MIME->ext -> .bin). The old inline .bin-correction block was
 *     deleted along with its MimeTypeMap import — that logic moved to
 *     DownloadNaming.
 *   - URLUtil.guessFileName is retained only as a display preview in the
 *     confirm dialog and as the callerFileName fallback hint.
 *
 * 2026-09-30 (external download fix):
 *   - triggerExternalDownload no longer uses ACTION_VIEW. It hands off to
 *     the system DownloadManager, which is the correct target for an
 *     "external download".
 *   - onDownloadStart is callable directly by AppWiring so the
 *     extension-regex path in SpoonWebViewClient can show the same
 *     dialog instead of bypassing the in-app engine.
 *
 * Earlier fixes retained:
 *   - enqueue delegated to DownloadsController (OkHttp engine).
 *   - Referer header captured from the current WebView.
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
            // Display-only preview for the confirm dialog. DownloadNaming
            // will produce the real on-disk name inside the engine.
            String previewName = URLUtil.guessFileName(url, contentDisposition, mimeType);

            String safeMime = (mimeType == null || mimeType.isEmpty())
                    ? "application/octet-stream"
                    : mimeType;

            final String finalPreviewName = previewName;
            final String finalMime = safeMime;
            final String finalUserAgent = userAgent;
            final String finalDisposition = contentDisposition;

            new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Download File")
                    .setMessage("Do you want to download " + finalPreviewName + "?")
                    .setPositiveButton("Download",
                            (d, i) -> enqueueDownload(url, finalUserAgent, finalMime,
                                    finalDisposition, finalPreviewName))
                    .setNeutralButton("System Downloader",
                            (d, i) -> triggerExternalDownload(url, finalMime))
                    .setNegativeButton("Cancel", null)
                    .show();

        } catch (Exception err) {
            Toast.makeText(activity, "Download manager initialization failed",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void enqueueDownload(String url,
                                 String userAgent,
                                 String mime,
                                 String contentDisposition,
                                 String callerFileName) {
        String referer = null;
        try {
            WebView wv = webViewProvider != null ? webViewProvider.get() : null;
            if (wv != null) referer = wv.getUrl();
        } catch (Exception ignored) {}

        if (downloadsController == null) {
            // No controller wired. Fall back to the system downloader
            // rather than ACTION_VIEW (which would open a browser).
            triggerExternalDownload(url, mime);
            return;
        }

        long id = downloadsController.enqueue(
                url, userAgent, mime, contentDisposition, callerFileName, referer);
        if (id == -1) {
            // enqueue already reported the error to the user.
        }
    }

    /**
     * Hands the URL to the system DownloadManager. This is the intended
     * meaning of "external" for a download — NOT opening a browser via
     * ACTION_VIEW.
     */
    public void triggerExternalDownload(String url, String mime) {
        if (url == null) return;
        try {
            String fileName = URLUtil.guessFileName(url, null, mime);
            if (fileName == null || fileName.isEmpty()) {
                fileName = "download";
            }

            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(fileName);
            if (mime != null && !mime.isEmpty()) {
                req.setMimeType(mime);
            }
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            req.setAllowedOverMetered(true);
            req.setAllowedOverRoaming(true);

            DownloadManager dm = (DownloadManager)
                    activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                Toast.makeText(activity, "System downloader unavailable",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            dm.enqueue(req);
            Toast.makeText(activity, "Handed to system downloader",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(activity, "External download failed",
                    Toast.LENGTH_SHORT).show();
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
