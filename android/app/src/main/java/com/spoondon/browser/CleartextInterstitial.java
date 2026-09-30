package com.spoondon.browser;

import android.net.Uri;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * State + HTML for the cleartext (http://) interstitial page.
 *
 * Backlog item #3 (2026-09-30). Previously, a click on a non-whitelisted
 * http:// URL silently upgraded to https://. That broke any site that
 * genuinely requires http:// (auth redirect chains, corporate intranets)
 * and hid from the user that a downgrade was happening.
 *
 * New behavior: on the first http:// navigation to a host this session,
 * show an interstitial with three choices. Once the user picks "Continue",
 * the host is approved for the remainder of the process lifetime — the
 * interstitial does not fire again for that host until the app is killed.
 *
 * The approved-host set is intentionally process-scoped, not persisted.
 * Persisting it would mean the user can't tell whether a site is actually
 * safe without a fresh prompt, and would make the persisted cleartext
 * whitelist (CleartextPreferences) the only durable surface for this —
 * which is exactly what the menu entry is for.
 */
public final class CleartextInterstitial {

    private CleartextInterstitial() {
        // no instances
    }

    private static final Set<String> approvedThisSession =
            ConcurrentHashMap.newKeySet();

    public static boolean isApprovedForSession(@Nullable String host) {
        if (host == null || host.isEmpty()) return false;
        return approvedThisSession.contains(host);
    }

    public static void approveForSession(@Nullable String host) {
        if (host == null || host.isEmpty()) return;
        approvedThisSession.add(host);
    }

    /**
     * Builds the interstitial HTML for the given target URL. All
     * interpolations are escaped — the URL comes from an untrusted page.
     */
    public static String buildHtml(String targetUrl) {
        String displayHost = extractHost(targetUrl);
        String escapedHost = htmlEscape(displayHost == null ? targetUrl : displayHost);

        // JSONObject.quote produces a properly-escaped JS string literal
        // suitable for embedding directly in a <script> block. It escapes
        // backslash, double-quote, and all control characters.
        String jsUrl;
        try {
            jsUrl = JSONObject.quote(targetUrl);
        } catch (Exception e) {
            jsUrl = "\"\"";
        }

        return "<!DOCTYPE html>"
                + "<html><head>"
                + "<meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>"
                + "body{margin:0;background:#1f1f1f;color:#e8eaed;"
                + "font-family:sans-serif;padding:40px 24px;text-align:center;}"
                + ".icon{font-size:56px;line-height:1;margin-top:40px;}"
                + "h1{font-size:22px;font-weight:600;margin:24px 0 8px;}"
                + ".host{color:#8ab4f8;word-break:break-all;font-size:14px;margin-bottom:32px;}"
                + "p{color:#9aa0a6;font-size:15px;line-height:1.5;margin:0 0 32px;}"
                + "button{display:block;width:100%;padding:14px;border:none;"
                + "border-radius:8px;font-size:16px;margin-bottom:12px;"
                + "font-weight:600;font-family:inherit;cursor:pointer;}"
                + ".primary{background:#8ab4f8;color:#202124;}"
                + ".secondary{background:#2a2a2a;color:#e8eaed;}"
                + ".tertiary{background:transparent;color:#9aa0a6;"
                + "border:1px solid #3a3a3c;}"
                + "</style></head><body>"
                + "<div class='icon'>\u26A0\uFE0F</div>"
                + "<h1>Not secure</h1>"
                + "<div class='host'>" + escapedHost + "</div>"
                + "<p>You are about to visit a site over plain HTTP. "
                + "Anyone on your network can see or change what you send.</p>"
                + "<button class='primary' onclick='goUpgrade()'>Try HTTPS instead</button>"
                + "<button class='secondary' onclick='goProceed()'>Continue to HTTP</button>"
                + "<button class='tertiary' onclick='goCancel()'>Go back</button>"
                + "<script>"
                + "var URL=" + jsUrl + ";"
                + "function goProceed(){"
                + "  if(window.SpoonCleartext)SpoonCleartext.proceed(URL);"
                + "  else history.back();"
                + "}"
                + "function goUpgrade(){"
                + "  if(window.SpoonCleartext)SpoonCleartext.upgrade(URL);"
                + "  else history.back();"
                + "}"
                + "function goCancel(){"
                + "  if(window.SpoonCleartext)SpoonCleartext.cancel();"
                + "  else history.back();"
                + "}"
                + "</script></body></html>";
    }

    @Nullable
    private static String extractHost(String url) {
        if (url == null) return null;
        try {
            return Uri.parse(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
