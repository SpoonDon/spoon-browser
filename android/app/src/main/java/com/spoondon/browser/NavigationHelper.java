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
 * Everything here is a static pure function of its inputs (plus an optional
 * Context for the mobile-UA lookup). No Activity reference is retained.
 *
 * Backlog item #2 (2026-09-30): the desktop-UA path is now the single source
 * of truth. SpoonWebViewClient no longer contains an inline UA block — it
 * delegates to applyDesktopUa here. Mobile UA now uses the system WebView
 * UA (cached once per process) instead of a frozen Chrome string, matching
 * what SpoonWebViewClient used to do inline. The frozen MOBILE_UA remains
 * only as a fallback for the (unlikely) case where getDefaultUserAgent
 * fails.
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

    /**
     * Cached system WebView UA, cleaned of the "; wv" marker and the
     * "Version/x.x" prefix. Volatile because applyDesktopUa can theoretically
     * be called from any thread that has a WebView — though in practice all
     * callers are on the main thread.
     */
    private static volatile String cachedMobileUa;

    // ------------------------------------------------------------------------
    // Host normalization / desktop-site bookkeeping
    // ------------------------------------------------------------------------

    /**
     * Collapses known alias hosts to a single canonical form so that toggling
     * desktop mode on {@code m.youtube.com} also affects {@code youtu.be},
     * {@code www.youtube.com}, etc.
     */
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

    /**
     * Returns the current WebView's system UA string, with the "; wv" marker
     * and "Version/x.x" prefix stripped. Cached after the first call because
     * {@link WebSettings#getDefaultUserAgent(Context)} is documented as
     * expensive — it can force WebView initialization.
     *
     * Falls back to the frozen MOBILE_UA constant if the system lookup
     * throws or returns an empty string.
     */
    @NonNull
    public static String getMobileUa(@NonNull Context ctx) {
        String cached = cachedMobileUa;
        if (cached != null) return cached;

        String ua = null;
        try {
            ua = WebSettings.getDefaultUserAgent(ctx);
        } catch (Exception ignored) {
            // WebView not initialized, or the system UA is unavailable.
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

    /**
     * Switches a WebView between the mobile and desktop UA and adjusts the
     * viewport settings that pair with each mode.
     *
     * Fixed 2026-09-30: the mobile branch now sets
     * {@code useWideViewPort(false)} and {@code loadWithOverviewMode(false)},
     * matching SpoonWebViewClient's previous inline behavior. Previously this
     * method unconditionally set both to {@code true}, which caused mobile
     * pages to render zoomed-out when this helper was called from any code
     * path other than the SWVC inline block.
     */
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

    /**
     * Toggles desktop mode for {@code host}, persists the change, reloads
     * {@code wv}, and fires {@code onNoHost} when the host is empty.
     *
     * YouTube serves a different session per UA, so the cookies are flushed
     * before the reload to avoid a stale signed-in identity.
     */
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
     * Loads {@code input} in {@code wv}:
     *
     *   - internal schemes (about:, file:, javascript:, data:) pass through
     *   - looks-like-a-URL goes to https:// (unless already scheme-prefixed)
     *   - anything else is handed to {@code searchUrlResolver}
     *
     * The WebView's UA is set from the per-host desktop preference before
     * loading. The caller owns {@code wv} and must pass a non-null resolver.
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

        if (query.startsWith("about:")
                || query.startsWith("file:")
                || query.startsWith("javascript:")
                || query.startsWith("data:")) {
            wv.loadUrl(query);
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
