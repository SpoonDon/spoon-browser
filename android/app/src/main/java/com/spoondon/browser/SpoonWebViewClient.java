package com.spoondon.browser;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Map;

/**
 * Spoon's WebViewClient. Handles:
 *   - AdBlock network interception (via AdBlockEngine.shouldBlock)
 *   - Vault URL short-circuit (VaultUrls)
 *   - Home-page custom scheme interception (spoonhome://)
 *   - Cleartext policy three-tier decision (whitelist / session / interstitial)
 *   - URL cleaning (tracking params)
 *   - Blob-URL download hook
 *   - WebRTC IP leak sanitizer
 *   - Cosmetic CSS injection
 *   - Password autosave injection (multi-step + shadow-DOM aware)
 *   - Pull-to-refresh scroll hook (SpoonScroll bridge)
 *   - History recording (with dedup)
 *
 * 2026-10-01 (bookmarks grid): adds intercept for {@link HomePageRenderer#HOME_SCHEME}
 * so the home page's "All N" tile can open the bookmark manager dialog.
 * The interceptor is scoped to a single known action
 * ({@link HomePageRenderer#HOME_ACTION_MANAGER}) — any other spoonhome:// URL
 * is silently discarded.
 */
public class SpoonWebViewClient extends WebViewClient {
    private final MainActivity activity;
    private final WebViewAssetLoader assetLoader;
    private String lastRecordedHistoryUrl = "";
    private long lastRecordedHistoryTime = 0;

    public SpoonWebViewClient(@NonNull MainActivity activity,
                              @NonNull WebViewAssetLoader assetLoader) {
        this.activity = activity;
        this.assetLoader = assetLoader;
    }

    // ------------------------------------------------------------------------
    // AdBlock interception
    // ------------------------------------------------------------------------

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WebResourceResponse assetResponse = assetLoader.shouldInterceptRequest(request.getUrl());
            if (assetResponse != null) {
                return assetResponse;
            }

            if (request.isForMainFrame() || !AdBlockEngine.hasRules()) {
                return super.shouldInterceptRequest(view, request);
            }

            String url = request.getUrl().toString();
            String host = request.getUrl().getHost();
            if (host != null) {
                String lowerHost = host.toLowerCase(Locale.ROOT);
                if (lowerHost.contains("youtube.com") ||
                        lowerHost.contains("googlevideo.com") ||
                        lowerHost.contains("search.brave.com") ||
                        lowerHost.contains("duckduckgo.com")) {
                    return super.shouldInterceptRequest(view, request);
                }
            }

            int resourceType = classifyResource(request);
            String sourceHost = extractSourceHost(view);

            if (AdBlockEngine.shouldBlock(url, resourceType, sourceHost)) {
                return new WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        new ByteArrayInputStream(new byte[0])
                );
            }
        }
        return super.shouldInterceptRequest(view, request);
    }

    @Nullable
    private static String extractSourceHost(@Nullable WebView view) {
        if (view == null) return null;
        String topUrl = view.getUrl();
        if (topUrl == null) return null;
        try {
            return Uri.parse(topUrl).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static int classifyResource(WebResourceRequest request) {
        if (request.isForMainFrame()) return AdBlockEngine.TYPE_DOCUMENT;

        Map<String, String> headers = request.getRequestHeaders();
        String accept = headers != null ? headers.get("Accept") : null;
        if (accept == null) return AdBlockEngine.TYPE_OTHER;

        String a = accept.toLowerCase(Locale.ROOT);
        if (a.contains("text/css")) return AdBlockEngine.TYPE_STYLESHEET;
        if (a.startsWith("image/")) return AdBlockEngine.TYPE_IMAGE;
        if (a.contains("javascript")) return AdBlockEngine.TYPE_SCRIPT;
        if (a.startsWith("font/")) return AdBlockEngine.TYPE_FONT;
        if (a.startsWith("video/") || a.startsWith("audio/")) return AdBlockEngine.TYPE_MEDIA;
        if (a.contains("json") || a.contains("xml")) return AdBlockEngine.TYPE_XHR;
        if (a.startsWith("text/html")) return AdBlockEngine.TYPE_SUBDOCUMENT;
        return AdBlockEngine.TYPE_OTHER;
    }

    // ------------------------------------------------------------------------
    // URL loading
    // ------------------------------------------------------------------------

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return handleUrlLoading(view, request.getUrl().toString());
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String urlString) {
        return handleUrlLoading(view, urlString);
    }

    private boolean handleUrlLoading(WebView view, String url) {
        if (url == null) return false;

        if (VaultUrls.isVaultUrl(url)) return false;

        url = cleanUrl(url);

        // Reject page-initiated file:// and content:// navigations. Local
        // file access is disabled on the WebView (see WebViewFactory), so
        // these could not load anyway — intercepting here prevents the
        // fallthrough into the intent:// handler below, which would
        // otherwise try to open the URI in an external app.
        if (url.startsWith("file:") || url.startsWith("content:")) {
            return true;
        }

        // Home-page custom scheme. Only one action is defined; anything
        // else is discarded. Emitted by the bookmark grid's "See all" tile.
        // Reaching here requires the URL to have survived cleanUrl() (which
        // is a no-op for this scheme since there is no query string).
        if (url.startsWith(HomePageRenderer.HOME_SCHEME)) {
            String action = url.substring(HomePageRenderer.HOME_SCHEME.length());
            if (HomePageRenderer.HOME_ACTION_MANAGER.equals(action)) {
                BookmarkManager bm = activity.getBookmarkManager();
                if (bm != null) bm.showBookmarks();
            }
            return true;
        }

        if (url.startsWith("spoonsearch://")) {
            try {
                String query = java.net.URLDecoder.decode(url.substring(14), "UTF-8");
                view.loadUrl(activity.getSearchUrlFor(query));
            } catch (Exception ignored) {}
            return true;
        }

        String cleanUrl = url.split("\\?")[0].split("#")[0].toLowerCase(Locale.ROOT);
        if (cleanUrl.matches(".*\\.(mp4|webm|mkv|avi|mov|flv|wmv|ts|apk|zip|rar|7z|pdf|iso|dmg|exe|msi|tar|gz|md|json|csv)$")) {
            String mime = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(
                            android.webkit.MimeTypeMap.getFileExtensionFromUrl(cleanUrl));
            if (mime == null) mime = "application/octet-stream";
            activity.triggerManualDownload(url, mime);
            return true;
        }

        // Cleartext policy — three tiers.
        if (url.startsWith("http://")) {
            String rawHost = extractHostFromHttpUrl(url);

            if (CleartextPolicy.isCleartextAllowed(view.getContext(), rawHost)) {
                return false;
            }
            if (CleartextInterstitial.isApprovedForSession(rawHost)) {
                return false;
            }

            view.loadDataWithBaseURL(
                    "about:blank",
                    CleartextInterstitial.buildHtml(url),
                    "text/html",
                    "UTF-8",
                    null);
            return true;
        }

        if (url.contains(" ") && (url.contains("http://") || url.contains("https://"))) {
            int httpIndex = url.indexOf("http");
            if (httpIndex != -1) {
                view.loadUrl(url.substring(httpIndex).trim());
                return true;
            }
        }

        if (url.startsWith("intent://")) {
            try {
                android.content.Context context = view.getContext();
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                if (intent != null) {
                    if (intent.getPackage() != null
                            && intent.getPackage().equals(context.getPackageName())) {
                        return true;
                    }
                    android.content.pm.PackageManager pm = context.getPackageManager();
                    android.content.pm.ResolveInfo info = pm.resolveActivity(
                            intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
                    if (info != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    } else {
                        String fallbackUrl = intent.getStringExtra("browser_fallback_url");
                        if (fallbackUrl != null
                                && (fallbackUrl.startsWith("http://")
                                    || fallbackUrl.startsWith("https://"))) {
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
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    view.getContext().startActivity(intent);
                    return true;
                }
            } catch (Exception e) {
                Toast.makeText(view.getContext(), "No app found to handle this link",
                        Toast.LENGTH_SHORT).show();
                return true;
            }
        }

        return false;
    }

    private static String extractHostFromHttpUrl(String url) {
        try {
            String remaining = url.substring("http://".length());
            int slashIndex = remaining.indexOf('/');
            String rawHost = (slashIndex != -1) ? remaining.substring(0, slashIndex) : remaining;
            if (rawHost.contains(":")) rawHost = rawHost.split(":")[0];
            return rawHost.trim();
        } catch (Exception e) {
            return "";
        }
    }

    // === PART 2 CONTINUES HERE ===
}
