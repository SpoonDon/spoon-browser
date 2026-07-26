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
            if (request.isForMainFrame() || !AdBlockEngine.hasRules()) {
                return super.shouldInterceptRequest(view, request);
            }

            String url = request.getUrl().toString();
            String host = request.getUrl().getHost();
            if (host != null) {
                String lowerHost = host.toLowerCase();
                if (lowerHost.contains("youtube.com") ||
                        lowerHost.contains("googlevideo.com") ||
                        lowerHost.contains("search.brave.com") ||
                        lowerHost.contains("duckduckgo.com")) {
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
        return handleUrlLoading(view, url);
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean shouldOverrideUrlLoading(android.webkit.WebView view, String urlString) {
        return handleUrlLoading(view, urlString);
    }

    private boolean handleUrlLoading(android.webkit.WebView view, String url) {
        if (url == null) return false;
        url = cleanUrl(url);

        if (url.startsWith("spoonsearch://")) {
            try {
                String query = java.net.URLDecoder.decode(url.substring(14), "UTF-8");
                view.loadUrl("https://search.brave.com/search?q=" + android.net.Uri.encode(query));
            } catch (Exception ignored) {}
            return true;
        }

        String cleanUrl = url.split("\\?")[0].split("#")[0].toLowerCase();
        if (cleanUrl.matches(".*\\.(mp4|webm|mkv|avi|mov|flv|wmv|ts|png|jpg|jpeg|gif|webp|apk|zip|rar|7z|pdf|iso)$")) {
            String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(android.webkit.MimeTypeMap.getFileExtensionFromUrl(cleanUrl));
            if (mime == null) mime = "application/octet-stream";
            activity.triggerManualDownload(url, mime);
            return true;
        }

        if (url.startsWith("http://") && !url.contains("localhost") && !url.contains("10.0.2.2")) {
            try {
                String remaining = url.substring(7);
                int slashIndex = remaining.indexOf("/");
                String rawHost = (slashIndex != -1) ? remaining.substring(0, slashIndex) : remaining;
                if (rawHost.contains(":")) {
                    rawHost = rawHost.split(":")[0];
                }
                rawHost = rawHost.trim().toLowerCase();

                boolean isIpAddress = rawHost.matches("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$");
                boolean isLocalRouter = rawHost.endsWith("tplinkwifi.net") ||
                        rawHost.endsWith("routerlogin.net") ||
                        rawHost.endsWith("tendawifi.com") ||
                        rawHost.endsWith("asusrouter.com") ||
                        rawHost.endsWith("mwlogin.net") ||
                        rawHost.endsWith("pi.hole") ||
                        rawHost.endsWith(".local");

                if (isIpAddress || isLocalRouter) {
                    return false;
                }
            } catch (Exception ignored) {}

            String secureUrl = url.replace("http://", "https://");
            view.loadUrl(secureUrl);
            return true;
        }

        if (url.contains(" ") && (url.contains("http://") || url.contains("https://"))) {
            int httpIndex = url.indexOf("http");
            if (httpIndex != -1) {
                String finalUrl = url.substring(httpIndex).trim();
                view.loadUrl(finalUrl);
                return true;
            }
        }

        if (url.startsWith("intent://")) {
            try {
                android.content.Context context = view.getContext();
                android.content.Intent intent = android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME);
                if (intent != null) {
                    // SECURITY FIX: Block self-targeting intents
                    if (intent.getPackage() != null && intent.getPackage().equals(context.getPackageName())) {
                        return true; 
                    }

                    android.content.pm.PackageManager packageManager = context.getPackageManager();
                    android.content.pm.ResolveInfo info = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
                    
                    if (info != null) {
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    } else {
                        String fallbackUrl = intent.getStringExtra("browser_fallback_url");
                        // SECURITY FIX: Validate fallback URL to prevent JS/File execution
                        if (fallbackUrl != null && (fallbackUrl.startsWith("http://") || fallbackUrl.startsWith("https://"))) {
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
                android.content.Intent intent = android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME);
                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    view.getContext().startActivity(intent);
                    return true;
                }
            } catch (Exception e) {
                android.widget.Toast.makeText(view.getContext(), "No app found to handle this link", android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
        }

        return false;
    }

    @Override
    public void onPageStarted(android.webkit.WebView view, String url, android.graphics.Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        if (activity.swipeRefresh != null && url != null) {    
            String lowerUrl = url.toLowerCase();
            
            boolean isSpaSite = lowerUrl.contains("youtube.com") || 
                        lowerUrl.contains("twitter.com") || 
                        lowerUrl.contains("x.com") || 
                        lowerUrl.contains("reddit.com") ||
                        lowerUrl.contains("instagram.com");
    
            activity.swipeRefresh.setEnabled(!isSpaSite);
        }
        injectBlobHook(view);

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
                    if (url.contains("youtube.com") && url.contains("app=desktop")) {
                        view.loadUrl(url.replace("app=desktop", "app=m"));
                        return;
                    }
                    String defaultUA = android.webkit.WebSettings.getDefaultUserAgent(activity);
                    if (defaultUA != null) {
                        defaultUA = defaultUA.replace("; wv", "").replaceFirst("Version/[0-9.]+\\s", "");
                        view.getSettings().setUserAgentString(defaultUA);
                    } else {
                        view.getSettings().setUserAgentString("Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
                    }
                    view.getSettings().setLoadWithOverviewMode(false);
                    view.getSettings().setUseWideViewPort(false);
                }
            }
        }
    }

    @Override
    public void doUpdateVisitedHistory(android.webkit.WebView view, String url, boolean isReload) {
        super.doUpdateVisitedHistory(view, url, isReload);
        injectBlobHook(view);

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

                final String finalUrl = cleanUrl(url);
                final String finalTitle = view.getTitle();

                activity.backgroundExecutor.execute(() -> {
                    try {
                        activity.dbHelper.addHistory(finalUrl, finalTitle);
                    } catch (Exception ignored) {}
                });
            }
        }
    }

    public void injectBlobHook(android.webkit.WebView view) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            view.evaluateJavascript(
                    "javascript:(function() {" +
                            "   if (window.spoonBlobHooked) return;" +
                            "   window.spoonBlobHooked = true;" +
                            "   window.spoonBlobStore = {};" +
                            "   var origCreate = window.URL.createObjectURL;" +
                            "   window.URL.createObjectURL = function(blob) {" +
                            "       var url = origCreate.call(window.URL, blob);" +
                            "       window.spoonBlobStore[url] = blob;" +
                            "       return url;" +
                            "   };" +
                            "   var origClick = HTMLAnchorElement.prototype.click;" +
                            "   HTMLAnchorElement.prototype.click = function() {" +
                            "       if (this.href && this.href.startsWith('blob:')) {" +
                            "           var blob = window.spoonBlobStore[this.href];" +
                            "           if (blob) {" +
                            "               var mime = blob.type;" +
                            "               var filename = this.download || 'downloaded_file';" +
                            "               var reader = new FileReader();" +
                            "               reader.readAsDataURL(blob);" +
                            "               reader.onloadend = function() {" +
                            "                   AndroidDownloader.saveBase64ToFile(reader.result, mime, filename);" +
                            "               };" +
                            "               return;" +
                            "           }" +
                            "       }" +
                            "       return origClick.apply(this, arguments);" +
                            "   };" +
                            "})();", null);
        }
    }

    @Override
    public void onPageFinished(android.webkit.WebView view, String url) {
        String webrtcSanitizer = "javascript:(function() {" +    
            "if (window.RTCPeerConnection) {" +    
            "  var OrigPC = window.RTCPeerConnection;" +    
            "  window.RTCPeerConnection = function(config, constraints) {" +    
            "    var pc = new OrigPC(config, constraints);" +    
            "    var origCreateOffer = pc.createOffer;" +    
            "    pc.createOffer = function(opts) {" +    
            "      return origCreateOffer.call(pc, opts).then(function(offer) {" +    
            "        var ipRegex = new RegExp('([0-9]{1,3}\\\\.){3}[0-9]{1,3}', 'g');" +    
            "        offer.sdp = offer.sdp.replace(ipRegex, '0.0.0.0');" +    
            "        return offer;" +    
            "      });" +    
            "    };" +    
            "    return pc;" +    
            "  };" +    
            "  window.RTCPeerConnection.prototype = OrigPC.prototype;" +    
            "}" +    
            "})();";
        view.evaluateJavascript(webrtcSanitizer, null);
        super.onPageFinished(view, url);
        if (activity.swipeRefresh != null) activity.swipeRefresh.setRefreshing(false);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.webkit.CookieManager.getInstance().flush();
        }

        String cosmeticCss = AdBlockEngine.getCosmeticCss(url);
        if (!cosmeticCss.isEmpty()) {
            String cleanCss = cosmeticCss.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"");
            String injectScript = "javascript:(function() {" +
                    "var style = document.createElement('style');" +
                    "style.type = 'text/css';" +
                    "style.innerHTML = '" + cleanCss + "';" +
                    "document.head.appendChild(style);" +
                    "})();";
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

        String gpuAccelerationScript = "javascript:(function() { " +
                "var videos = document.getElementsByTagName('video');" +
                "for(var i=0; i<videos.length; i++) {" +
                "    videos[i].style.transform = 'translateZ(0)';" +
                "    videos[i].style.willChange = 'transform';" +
                "}})()";
        view.evaluateJavascript(gpuAccelerationScript, null);
    }

    @Override
    public boolean onRenderProcessGone(android.webkit.WebView view, android.webkit.RenderProcessGoneDetail detail) {
        if (activity != null && view != null) {
            activity.handleDeadRenderProcess(view);
        }
        return true;
    }

    @Override
    public void onReceivedError(android.webkit.WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
        super.onReceivedError(view, request, error);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (request.isForMainFrame()) {
                handleNetworkError(view, error.getErrorCode());
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onReceivedError(android.webkit.WebView view, int errorCode, String description, String failingUrl) {
        super.onReceivedError(view, errorCode, description, failingUrl);
        if (failingUrl != null && failingUrl.equals(view.getUrl())) {
            handleNetworkError(view, errorCode);
        }
    }

    private void handleNetworkError(android.webkit.WebView view, int errorCode) {
    if (errorCode == ERROR_HOST_LOOKUP || errorCode == ERROR_CONNECT || errorCode == ERROR_TIMEOUT) {
        
        // 1. Capture the original URL before we overwrite the WebView with the error page
        String originalUrl = view.getUrl();
        String retryJs = "window.location.reload()"; // Fallback
        
        // 2. If we have a valid original URL, navigate to it instead of reloading the data: URI
        if (originalUrl != null && !originalUrl.startsWith("data:")) {
            // Escape single quotes so it doesn't break the JavaScript string
            String safeUrl = originalUrl.replace("'", "\\'");
            retryJs = "window.location.href='" + safeUrl + "'";
        }

        // UX FIX: Added a Retry button that actually retries the original URL
        String errorHtml = "<html><body style='display:flex;justify-content:center;align-items:center;height:100vh;background-color:#202124;font-family:sans-serif;color:#e8eaed;text-align:center;padding:20px;'>" +
                "<div><svg width='64' height='64' viewBox='0 0 24 24' fill='none' stroke='#e8eaed' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'>" +
                "<path d='M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z'/>" +
                "<line x1='12' y1='9' x2='12' y2='13'/><line x1='12' y1='17' x2='12.01' y2='17'/></svg>" +
                "<h2 style='margin-top:20px;margin-bottom:10px;'>No Connection</h2>" +
                "<p style='color:#9aa0a6;'>Check your internet connection or the IP address and try again.</p>" +
                "<button onclick='" + retryJs + "' style='margin-top:20px;padding:12px 24px;background:#8ab4f8;color:#202124;border:none;border-radius:8px;font-size:16px;font-weight:bold;cursor:pointer;'>Retry</button>" +
                "</div>" +
                "</body></html>";
                
        view.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null);
        android.widget.Toast.makeText(view.getContext(), "Offline or Unreachable", android.widget.Toast.LENGTH_SHORT).show();
    }
    
    // Stop the pull-to-refresh spinner for ANY network error, not just the 3 listed above
    if (activity.swipeRefresh != null) activity.swipeRefresh.setRefreshing(false);
}

    @Override
    public void onReceivedSslError(android.webkit.WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
        String url = error.getUrl();
        if (url != null) {
            try {
                android.net.Uri uri = android.net.Uri.parse(url);
                String host = uri.getHost();
                if (host != null) {
                    host = host.toLowerCase();
                    boolean isIpAddress = host.matches("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$");
                    boolean isLocalRouter = host.endsWith("tplinkwifi.net") ||
                            host.endsWith("routerlogin.net") ||
                            host.endsWith("tendawifi.com") ||
                            host.endsWith("asusrouter.com") ||
                            host.endsWith("mwlogin.net") ||
                            host.endsWith("pi.hole") ||
                            host.endsWith(".local");

                    if (isIpAddress || isLocalRouter) {
                        handler.proceed();
                        return;
                    }
                }
            } catch (Exception ignored) {}
        }
        handler.cancel();
        android.widget.Toast.makeText(view.getContext(), "SSL Certificate Error Blocked", android.widget.Toast.LENGTH_SHORT).show();
    }

    // --- TRACKER STRIPPING ENGINE ---
private static final java.util.Set<String> EXACT_TRACKERS = new java.util.HashSet<>(java.util.Arrays.asList(
    "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "twclid",
    "igshid", "si", "mc_cid", "mc_eid", "zanpid", "yclid", "utm_source", "utm_medium",
    "utm_campaign", "utm_term", "utm_content", "utm_id"
));

private static boolean isTracker(String key) {
    if (key == null) return false;
    String lowerKey = key.toLowerCase();
    if (lowerKey.startsWith("utm_") || lowerKey.startsWith("oly_") || lowerKey.startsWith("vero_") || lowerKey.startsWith("trk_")) return true;
    return EXACT_TRACKERS.contains(lowerKey);
}

public static String cleanUrl(String url) {
    if (url == null || !url.contains("?")) return url;
    try {
        int queryStart = url.indexOf('?');
        String baseUrl = url.substring(0, queryStart);
        String queryAndFragment = url.substring(queryStart + 1);
        
        String fragment = "";
        int fragmentStart = queryAndFragment.indexOf('#');
        if (fragmentStart != -1) {
            fragment = queryAndFragment.substring(fragmentStart);
            queryAndFragment = queryAndFragment.substring(0, fragmentStart);
        }
        
        if (queryAndFragment.isEmpty()) return url;
        
        String[] pairs = queryAndFragment.split("&");
        StringBuilder cleanQuery = new StringBuilder();
        
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            String key = (idx > 0) ? pair.substring(0, idx) : pair;
            
            if (!isTracker(key)) {
                if (cleanQuery.length() > 0) cleanQuery.append("&");
                cleanQuery.append(pair);
            }
        }
        
        if (cleanQuery.length() == 0) {
            return baseUrl + fragment;
        } else {
            return baseUrl + "?" + cleanQuery.toString() + fragment;
        }
    } catch (Exception e) {
        return url; // Fallback to original on any parsing error to prevent breakage
    }
}
    
}
