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
        Uri url = request.getUrl();
        if (url == null) return false;
        String urlString = url.toString();

        // 1. Let the WebView load standard web pages natively
        if (urlString.startsWith("http://") || urlString.startsWith("https://")) {
            return false; 
        }

        // 2. Route EVERYTHING else (intent://, mailto:, tel:, market://, magnet:) to the Android OS
        try {
            Intent intent = Intent.parseUri(urlString, Intent.URI_INTENT_SCHEME);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                view.getContext().startActivity(intent);
                return true;
            }
        } catch (Exception e) {
            Toast.makeText(view.getContext(), "No app found to handle this link", Toast.LENGTH_SHORT).show();
        }
        return true; 
    }

    // Legacy support for older Android versions
    @SuppressWarnings("deprecation")
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String urlString) {
        if (urlString == null) return false;
        
        if (urlString.startsWith("http://") || urlString.startsWith("https://")) {
            return false;
        }
        
        try {
            Intent intent = Intent.parseUri(urlString, Intent.URI_INTENT_SCHEME);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                view.getContext().startActivity(intent);
                return true;
            }
        } catch (Exception e) {
             Toast.makeText(view.getContext(), "No app found to handle this link", Toast.LENGTH_SHORT).show();
        }
        return true;
    }
  
    @Override
    public void onPageStarted(android.webkit.WebView view, String url, android.graphics.Bitmap favicon) {
        super.onPageStarted(view, url, favicon);

        // 1. Your existing Cookie flush logic
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.webkit.CookieManager.getInstance().flush();
        }

        // 2. Your existing Address Bar UI update
        if (view == activity.getCurrentWebView() && activity.addressBar != null) {
            activity.addressBar.setText((url == null || url.isEmpty() || url.equals("about:blank")) ? "" : url);
        }

        // 3. NEW: The Domain-Specific Desktop Mode Engine
        if (url != null && !url.isEmpty() && !url.equals("about:blank")) {
            String host = android.net.Uri.parse(url).getHost();
            if (host != null) {
                // Read the saved domain list
                android.content.SharedPreferences prefs = activity.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE);
                java.util.Set<String> desktopSites = prefs.getStringSet("desktop_sites", new java.util.HashSet<>());

                // Apply the correct mode automatically
                if (desktopSites.contains(host)) {
                    view.getSettings().setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
                    view.getSettings().setLoadWithOverviewMode(true);
                    view.getSettings().setUseWideViewPort(true);
                } else {
                    view.getSettings().setUserAgentString("Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
                }
            }
        }
    }

    @Override
    public void doUpdateVisitedHistory(android.webkit.WebView view, String url, boolean isReload) {
        super.doUpdateVisitedHistory(view, url, isReload);
        
        // 🛠️ THE FIX: Ignore Cloudflare's invisible challenge redirects!
        if (url != null && !url.contains("cdn-cgi/challenge")) {
            activity.recordPageVisit(url, view.getTitle());
        }
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
