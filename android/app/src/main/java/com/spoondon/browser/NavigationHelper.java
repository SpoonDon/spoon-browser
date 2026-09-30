package com.spoondon.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Patterns;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.function.Function;

/**
 * Stateless helpers for two concerns that used to live inline in MainActivity:
 *
 *   1. Deciding what a typed string should do (navigate vs search).
 *   2. Applying the per-host desktop User-Agent.
 *
 * Hardening pass (2026-09-30): openUrl no longer passes file:, javascript:,
 * data:, or content: URLs through to the WebView. Only about: URLs are
 * honoured directly; everything else is either navigated or routed to the
 * search engine. This closes the "paste javascript: into the address bar"
 * execution vector and matches the WebView's file-access posture (see
 * WebViewFactory: setAllowFileAccess(false)).
 */
public final class NavigationHelper {

    private NavigationHelper() {
        // no instances
    }

    public static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Mobile Safari/537.36";

    public static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    private static final String BROWSER_PREFS = "browser_prefs";
    private static final String KEY_DESKTOP_SITES = "desktop_sites";

    private static volatile String cachedMobileUa;

    // ------------------------------------------------------------------------
    // Host normalization / desktop-site bookkeeping
    // ------------------------------------------------------------------------

    @NonNull
    public static String normalizeDesktopHost(@Nullable String host) {
        if (host == null) return "";
        String lower = host.toLowerCase();
        if (lower.equals("youtube.com")
                || lower.endsWith(".youtube.com")
                || lower.equals("youtu.be")) {
            return "youtube.com";
        }
        return lower;
    }

    public static boolean isDesktopHostEnabled(@NonNull Context ctx, @Nullable String host) {
        if (host == null || host.isEmpty()) return false;
        String normalized = normalizeDesktopHost(host);
        return ctx.getSharedPreferences(BROWSER_PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_DESKTOP_SITES, new HashSet<>())
                .contains(normalized);
    }

    // ------------------------------------------------------------------------
    // User-Agent
    // ------------------------------------------------------------------------

    @NonNull
    public static String getMobileUa(@NonNull Context ctx) {
        String cached = cachedMobileUa;
        if (cached != null) return cached;

        String ua = null;
        try {
            ua = WebSettings.getDefaultUserAgent(ctx);
        } catch (Exception ignored) {
        }

        if (ua == null || ua.isEmpty()) {
            ua = MOBILE_UA;
        } else {
            ua = ua.replace("; wv", "");
            ua = ua.replaceFirst("Version/[0-9.]+\\s", "");
        }

        cachedMobileUa = ua;
        return ua;
    }

    public static void applyDesktopUa(@Nullable WebView wv,
                                      boolean desktop,
                                      @NonNull Context ctx) {
        if (wv == null || wv.getSettings() == null) return;

        WebSettings settings = wv.getSettings();
        if (desktop) {
            settings.setUserAgentString(DESKTOP_UA);
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
        } else {
            settings.setUserAgentString(getMobileUa(ctx));
            settings.setLoadWithOverviewMode(false);
            settings.setUseWideViewPort(false);
        }
    }

    // ------------------------------------------------------------------------
    // Desktop-mode toggle (per host)
    // ------------------------------------------------------------------------

    public static void toggleDesktopMode(@NonNull Context ctx,
                                         @Nullable WebView wv,
                                         @Nullable String host,
                                         @NonNull Runnable onNoHost) {
        if (host == null || host.isEmpty()) {
            onNoHost.run();
            return;
        }

        String normalized = normalizeDesktopHost(host);
        SharedPreferences prefs = ctx.getSharedPreferences(BROWSER_PREFS, Context.MODE_PRIVATE);
        HashSet<String> sites = new HashSet<>(
                prefs.getStringSet(KEY_DESKTOP_SITES, new HashSet<>()));

        boolean wasDesktop = sites.contains(normalized);
        if (wasDesktop) {
            sites.remove(normalized);
        } else {
            sites.add(normalized);
        }
        prefs.edit().putStringSet(KEY_DESKTOP_SITES, sites).apply();

        if ("youtube.com".equals(normalized)) {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
        }

        if (wv != null) {
            applyDesktopUa(wv, !wasDesktop, ctx);
            wv.reload();
        }
    }

    // ------------------------------------------------------------------------
    // The navigation decision tree
    // ------------------------------------------------------------------------

    /**
     * Loads {@code input} in {@code wv}.
     *
     * Scheme policy (hardening pass 2026-09-30):
     *   - about:     passed through to the WebView (used for about:blank)
     *   - file:      rejected silently — file access is disabled on the
     *                WebView, and pasting a file:// URL should not attempt
     *                a navigation.
     *   - javascript: rejected silently — this was a raw code-execution
     *                vector when typed into the address bar.
     *   - data:      rejected silently — commonly used for phishing pages
     *                that present a fake lock and host forms entirely in
     *                the URL. Legitimate data: URLs still work when loaded
     *                by page content (this check only applies to address-bar
     *                and intent navigation).
     *   - content:   rejected silently — content:// is for the file chooser,
     *                not for user typing.
     *   - http(s) or bare hostname: navigated.
     *   - anything else: routed to the search engine.
     */
    public static void openUrl(@NonNull WebView wv,
                               @Nullable String input,
                               @NonNull Context ctx,
                               @NonNull Function<String, String> searchUrlResolver) {
        if (input == null) return;

        String host = null;
        try {
            host = Uri.parse(input).getHost();
        } catch (Exception ignored) {
        }
        applyDesktopUa(wv, isDesktopHostEnabled(ctx, host), ctx);

        String query = input.trim();
        if (query.isEmpty()) return;

        if (query.startsWith("about:")) {
            wv.loadUrl(query);
            return;
        }

        // Rejected schemes — no toast to avoid noise from accidental pastes.
        if (query.startsWith("file:")
                || query.startsWith("javascript:")
                || query.startsWith("data:")
                || query.startsWith("content:")) {
            return;
        }

        boolean looksLikeUrl = Patterns.WEB_URL.matcher(query).matches()
                || (query.contains(".") && !query.contains(" "));

        if (looksLikeUrl) {
            if (!query.startsWith("http://") && !query.startsWith("https://")) {
                query = "https://" + query;
            }
            wv.loadUrl(query);
        } else {
            wv.loadUrl(searchUrlResolver.apply(query));
        }
    }
}
