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
    private String lastRecordedHistoryUrl = "";
    private long lastRecordedHistoryTime = 0;

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
    public boolean shouldOverrideUrlLoading(android.webkit.WebView view, android.webkit.WebResourceRequest request) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.net.Uri url = request.getUrl();
            if (url == null) return false;
            String urlString = url.toString();

            // 1. Enforce HTTPS upgrades for raw HTTP endpoints
            if (urlString.startsWith("http://") && !urlString.contains("localhost") && !urlString.contains("10.0.2.2")) {
                String secureUrl = urlString.replace("http://", "https://");
                view.loadUrl(secureUrl);
                return true; // Cancel the unencrypted request, we just kicked off the secure one
            }

            if (urlString.startsWith("https://")) {
                return false; // Load native HTTPS requests normally
            }

            // 2. Route EVERYTHING else (intent://, mailto:, tel:, market://, magnet:) to the Android OS
            try {
                android.content.Intent intent = android.content.Intent.parseUri(urlString, android.content.Intent.URI_INTENT_SCHEME);
                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    view.getContext().startActivity(intent);
                    return true;
                }
            } catch (Exception e) {
                android.widget.Toast.makeText(view.getContext(), "No app found to handle this link", android.widget.Toast.LENGTH_SHORT).show();
            }
        }
        return true; 
    }

    // Legacy support for older Android versions
    @SuppressWarnings("deprecation")
    @Override
    public boolean shouldOverrideUrlLoading(android.webkit.WebView view, String urlString) {
        if (urlString == null) return false;
        
        // 1. Enforce HTTPS upgrades for raw HTTP endpoints
        if (urlString.startsWith("http://") && !urlString.contains("localhost") && !urlString.contains("10.0.2.2")) {
            String secureUrl = urlString.replace("http://", "https://");
            view.loadUrl(secureUrl);
            return true;
        }

        if (urlString.startsWith("https://")) {
            return false;
        }
        
        // 2. Route EVERYTHING else out
        try {
            android.content.Intent intent = android.content.Intent.parseUri(urlString, android.content.Intent.URI_INTENT_SCHEME);
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                view.getContext().startActivity(intent);
                return true;
            }
        } catch (Exception e) {
             android.widget.Toast.makeText(view.getContext(), "No app found to handle this link", android.widget.Toast.LENGTH_SHORT).show();
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
    public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        super.doUpdateVisitedHistory(view, url, isReload);

        if (url != null && !isReload && !url.contains("cdn-cgi/challenge")) {
            long currentTime = System.currentTimeMillis();

            // 1. Parse URLs securely using Android's native engine
            android.net.Uri currentUri = android.net.Uri.parse(url);
            android.net.Uri lastUri = android.net.Uri.parse(lastRecordedHistoryUrl);

            // 2. Extract ONLY the Host (domain) and Path (folder), stripping www. and ignoring http/https
            String currentHost = currentUri.getHost() != null ? currentUri.getHost().replaceFirst("^www\\.", "") : "";
            String currentPath = currentUri.getPath() != null ? currentUri.getPath() : "";

            String lastHost = lastUri.getHost() != null ? lastUri.getHost().replaceFirst("^www\\.", "") : "";
            String lastPath = lastUri.getPath() != null ? lastUri.getPath() : "";

            // 3. Logic Engine: Is it the exact same core page loaded rapidly?
            boolean isSameCorePage = currentHost.equals(lastHost) && currentPath.equals(lastPath);
            boolean isRapidFire = (currentTime - lastRecordedHistoryTime) < 1500;

            if (activity.dbHelper != null) {
                // Block if it's the same base page doing an automated redirect/append within 1.5 seconds.
                // Otherwise, save the clean URL to the database.
                if (!(isSameCorePage && isRapidFire)) {
                    activity.dbHelper.addHistory(url, view.getTitle());
                    lastRecordedHistoryUrl = url;
                    lastRecordedHistoryTime = currentTime;
                }
            }
        }
    }

    @Override
    public void onPageFinished(android.webkit.WebView view, String url) {
        super.onPageFinished(view, url);

        // 1. Inject Cosmetic AdBlock Filters (Your existing code)
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
        
        // 2. NEW: Inject Password Autosave Listener
        String script = "javascript:(function() {" +
            "document.addEventListener('submit', function(e) {" +
                "var passBox = e.target.querySelector('input[type=password]');" +
                "var userBox = e.target.querySelector('input[type=text], input[type=email], input[name=username], input[name=login]');" +
                "if (passBox && passBox.value && userBox && userBox.value) {" +
                    "SpoonVault.saveCredentials(userBox.value, passBox.value);" +
                "}" +
            "});" +
        "})();";
        
        view.evaluateJavascript(script, null);
    }
    
}
