package com.spoondon.browser;

import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

/**
 * JS bridge backing the cleartext interstitial page.
 *
 * Exposes three methods to the interstitial's buttons:
 *   - {@link #proceed(String)}  user explicitly chose to load over http://
 *   - {@link #upgrade(String)}  user chose to try https:// instead
 *   - {@link #cancel()}         user chose to go back
 *
 * Backlog item #3 (2026-09-30).
 *
 * Navigation happens via {@code location.replace(...)} rather than
 * {@code webView.loadUrl(...)}. The difference matters:
 *   - location.replace swaps the current history entry in place, so the
 *     interstitial does not stay in the back-stack behind the target page.
 *   - It is a page-initiated navigation, so SpoonWebViewClient's
 *     shouldOverrideUrlLoading DOES fire. That is intentional — after
 *     approveForSession, the same check that showed the interstitial in the
 *     first place will see the approval and let the navigation through.
 */
public class CleartextBridge {

    private final WebView webView;

    public CleartextBridge(@NonNull WebView webView) {
        this.webView = webView;
    }

    @JavascriptInterface
    public void proceed(String url) {
        if (url == null) return;
        CleartextInterstitial.approveForSession(extractHost(url));
        navigateInPlace(url);
    }

    @JavascriptInterface
    public void upgrade(String url) {
        if (url == null) return;
        String target = url.startsWith("http://")
                ? "https://" + url.substring("http://".length())
                : url;
        navigateInPlace(target);
    }

    @JavascriptInterface
    public void cancel() {
        webView.post(() -> {
            if (webView.canGoBack()) {
                webView.goBack();
            } else {
                webView.loadUrl("about:blank");
            }
        });
    }

    // ------------------------------------------------------------------------

    private void navigateInPlace(String target) {
        webView.post(() -> {
            String quoted;
            try {
                quoted = JSONObject.quote(target);
            } catch (Exception e) {
                return;
            }
            webView.evaluateJavascript(
                    "location.replace(" + quoted + ")", null);
        });
    }

    @Nullable
    private static String extractHost(String url) {
        try {
            return Uri.parse(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
