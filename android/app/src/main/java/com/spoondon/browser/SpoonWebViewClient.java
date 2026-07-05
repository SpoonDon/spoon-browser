package com.spoondon.browser;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class SpoonWebViewClient extends WebViewClient {

    private final MainActivity activity;

    public SpoonWebViewClient(MainActivity activity) {
        this.activity = activity;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        if (url != null) {
            String urlString = url.toString();
            // Removed synchronized lock: filterEngine is inherently thread-safe
            if (activity.filterEngine.shouldBlock(urlString)) {
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }
        }
        return super.shouldInterceptRequest(view, request);
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (!request.isForMainFrame()) {
            return false;
        }

        Uri url = request.getUrl();
        if (url != null) {
            String urlString = url.toString();

            if (urlString.startsWith("magnet:") || urlString.endsWith(".torrent")) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, url);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    view.getContext().startActivity(intent);
                    return true;
                } catch (Exception e) {
                    Toast.makeText(view.getContext(), "No app found to handle torrent links", Toast.LENGTH_SHORT).show();
                    return true;
                }
            }
        }
        return false;
    }
    
    // Legacy support for Android 5.0 - 6.0
    @SuppressWarnings("deprecation")
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String urlString) {
        if (urlString != null && (urlString.startsWith("magnet:") || urlString.endsWith(".torrent"))) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(urlString));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                view.getContext().startActivity(intent);
                return true;
            } catch (Exception e) {
                Toast.makeText(view.getContext(), "No app found to handle torrent links", Toast.LENGTH_SHORT).show();
                return true;
            }
        }
        return false;
    }
  
    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().flush();
        }

        if (view == activity.getCurrentWebView() && activity.addressBar != null) {
            activity.addressBar.setText((url == null || url.isEmpty() || url.equals("about:blank")) ? "" : url);
        }
    }

    @Override
    public void doUpdateVisitedHistory(android.webkit.WebView view, String url, boolean isReload) {
        super.doUpdateVisitedHistory(view, url, isReload);
        
        // Pass the data cleanly to MainActivity! No private access errors!
        activity.recordPageVisit(url, view.getTitle());
    }

    @Override
    public void onPageFinished(android.webkit.WebView view, String url) {
        super.onPageFinished(view, url);

        // 1. Inject Cosmetic AdBlock Filters
        view.evaluateJavascript(activity.filterEngine.compileCosmeticJavascript(), null);
        java.util.List<String> cssBatches = activity.filterEngine.getCosmeticStyleBatches(url);
        for (String cssChunk : cssBatches) {
            String cleanChunk = cssChunk.replace("\\", "\\\\").replace("'", "\\'");
            String injectScript = "javascript:(function() {" +
                    "var style = document.getElementById('spoon-cosmetic-sheets');" +
                    "if (style) { style.appendChild(document.createTextNode('" + cleanChunk + "\\n')); }" +
                    "})()";
            view.evaluateJavascript(injectScript, null);
        }
        
        // Notice how clean this is now! No more autofill script headaches here.
    }
    
}
