package com.spoondon.browser;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Canonical URL constants for the HTML vault page.
 *
 * The vault is served by WebViewAssetLoader over the synthetic HTTPS origin
 * {@code https://appassets.androidplatform.net}. This gives the page a real
 * origin instead of the opaque {@code file://} origin it used to have — which
 * in turn lets the WebMessageListener origin-scope its subscriptions and lets
 * us stop relying on {@code file://} asset loading for a security-sensitive page.
 *
 * Security batch B (2026-09-30).
 */
public final class VaultUrls {

    private VaultUrls() {
        // no instances
    }

    /** Default domain WebViewAssetLoader uses. Do not change without updating the loader. */
    public static final String DOMAIN = "appassets.androidplatform.net";

    /** Origin served by WebViewAssetLoader. Use this for origin whitelists. */
    public static final String ORIGIN = "https://" + DOMAIN;

    /** Full URL of the vault page. */
    public static final String HTML = ORIGIN + "/assets/vault.html";

    /**
     * True if {@code url} points at the vault page (with optional query or
     * fragment). Case-insensitive.
     */
    public static boolean isVaultUrl(@Nullable String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        String prefix = HTML.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(prefix)) return false;
        if (lower.length() == prefix.length()) return true;
        char next = lower.charAt(prefix.length());
        return next == '?' || next == '#';
    }
}
