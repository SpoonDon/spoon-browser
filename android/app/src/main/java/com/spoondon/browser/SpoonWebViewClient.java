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
    public android.webkit.WebResourceResponse shouldInterceptRequest(android.webkit.WebView view, android.webkit.WebResourceRequest request) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            
            if (request.isForMainFrame()) {
                return super.shouldInterceptRequest(view, request);
            }

            String url = request.getUrl().toString();
            String host = request.getUrl().getHost();

            if (host != null) {
                String lowerHost = host.toLowerCase();
                if (lowerHost.contains("youtube.com") || lowerHost.contains("googlevideo.com")) {
                    return super.shouldInterceptRequest(view, request);
                }
            }
            
            if (AdBlockEngine.shouldBlock(url)) {
                return new android.webkit.WebResourceResponse(
                    "text/plain", 
                    "UTF-8", 
                    new java.io.ByteArrayInputStream(new byte[0])
                );
            }
        }
        return super.shouldInterceptRequest(view, request);
    }
    
    @Override
    public boolean shouldOverrideUrlLoading(android.webkit.WebView view, android.webkit.WebResourceRequest request) {
        String url = request.getUrl().toString();

        if (url.contains(" ") && (url.contains("http://") || url.contains("https://"))) {
            int httpIndex = url.indexOf("http");
            if (httpIndex != -1) {
                String cleanUrl = url.substring(httpIndex).trim();
                view.loadUrl(cleanUrl);
                return true; 
            }
        }

        if (url.startsWith("intent://")) {
            try {
                android.content.Context context = view.getContext();
                android.content.Intent intent = android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME);
                
                if (intent != null) {
                    android.content.pm.PackageManager packageManager = context.getPackageManager();
                    android.content.pm.ResolveInfo info = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
                    
                    if (info != null) {
                        context.startActivity(intent);
                    } else {
                        String fallbackUrl = intent.getStringExtra("browser_fallback_url");
                        if (fallbackUrl != null) {
                            view.loadUrl(fallbackUrl);
                        }
                    }
                    return true; 
                }
            } catch (Exception e) {
                return true; 
            }
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            try {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                view.getContext().startActivity(intent);
                return true;
            } catch (Exception e) {
                return true; 
            }
        }

        return false;
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean shouldOverrideUrlLoading(android.webkit.WebView view, String urlString) {
        if (urlString == null) return false;
        
        if (urlString.startsWith("http://") && !urlString.contains("localhost") && !urlString.contains("10.0.2.2")) {
            String secureUrl = urlString.replace("http://", "https://");
            view.loadUrl(secureUrl);
            return true;
        }

        if (urlString.startsWith("https://")) {
            return false;
        }
        
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

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.webkit.CookieManager.getInstance().flush();
        }

        if (view == activity.getCurrentWebView() && activity.addressBar != null) {
            activity.addressBar.setText((url == null || url.isEmpty() || url.equals("about:blank")) ? "" : url);
        }

        if (url != null && !url.isEmpty() && !url.equals("about:blank")) {
            String host = android.net.Uri.parse(url).getHost();
            if (host != null) {
                android.content.SharedPreferences prefs = activity.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE);
                java.util.Set<String> desktopSites = prefs.getStringSet("desktop_sites", new java.util.HashSet<>());

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

            android.net.Uri currentUri = android.net.Uri.parse(url);
            android.net.Uri lastUri = android.net.Uri.parse(lastRecordedHistoryUrl);

            String currentHost = currentUri.getHost() != null ? currentUri.getHost().replaceFirst("^www\\.", "") : "";
            String currentPath = currentUri.getPath() != null ? currentUri.getPath() : "";

            String lastHost = lastUri.getHost() != null ? lastUri.getHost().replaceFirst("^www\\.", "") : "";
            String lastPath = lastUri.getPath() != null ? lastUri.getPath() : "";

            boolean isSameCorePage = currentHost.equals(lastHost) && currentPath.equals(lastPath);
            boolean isRapidFire = (currentTime - lastRecordedHistoryTime) < 1500;

            if (activity.dbHelper != null) {
                if (isSameCorePage && isRapidFire) {
                    return; 
                }

                lastRecordedHistoryUrl = url;
                lastRecordedHistoryTime = currentTime;

                final String finalUrl = url;
                final String finalTitle = view.getTitle();
                
                activity.backgroundExecutor.execute(() -> {
                    try {
                        activity.dbHelper.addHistory(finalUrl, finalTitle);
                    } catch (Exception ignored) {}
                });
            }
        }
    }

    @Override
    public void onPageFinished(android.webkit.WebView view, String url) {
        super.onPageFinished(view, url);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.webkit.CookieManager.getInstance().flush();
        }

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
        
        String script = "javascript:(function() {" +
            "document.addEventListener('submit', function(e) {" +
                "var passBox = e.target.querySelector('input[type=password]');" +
                "var userBox = e.target.querySelector('input[type=text], input[type=email], input[name=username], input[name=login]');" +
                "if (passBox && passBox.value && userBox && userBox.value) {" +
                    "SpoonVault.saveCredentials(userBox.value, passBox.value);" +
                "}" +
            "});" +
            "var lastKnownUser = '';" +
            "document.addEventListener('input', function(e) {" +
                "var t = e.target;" +
                "if (t.tagName === 'INPUT') {" +
                    "var type = t.type ? t.type.toLowerCase() : '';" +
                    "var name = t.name ? t.name.toLowerCase() : '';" +
                    "if (type === 'email' || type === 'text' || name === 'username' || name === 'identifier') {" +
                        "lastKnownUser = t.value;" +
                    "}" +
                "}" +
            "});" +
            "function extractAndSave() {" +
                "var passBox = document.querySelector('input[type=password]');" +
                "if (passBox && passBox.value) {" +
                    "var userBox = document.querySelector('input[type=email], input[name=username], input[name=login], input[type=text]');" +
                    "var finalUser = (userBox && userBox.value) ? userBox.value : lastKnownUser;" +
                    "if (!finalUser && window.location.hostname.includes('google.com')) {" +
                        "var profileDiv = document.querySelector('#profileIdentifier');" +
                        "if (profileDiv) finalUser = profileDiv.innerText.trim();" +
                    "}" +
                    "if (finalUser && passBox.value) {" +
                        "SpoonVault.saveCredentials(finalUser, passBox.value);" +
                    "}" +
                "}" +
            "}" +
            "document.addEventListener('click', function(e) {" +
                "var t = e.target;" +
                "if (t.closest('button') || t.closest('input[type=submit]') || t.closest('[role=button]')) {" +
                    "extractAndSave();" +
                "}" +
            "});" +
            "document.addEventListener('keydown', function(e) {" +
                "if (e.key === 'Enter') {" +
                    "extractAndSave();" +
                "}" +
            "});" +
        "})();";
        
        view.evaluateJavascript(script, null);
    }
    
}
