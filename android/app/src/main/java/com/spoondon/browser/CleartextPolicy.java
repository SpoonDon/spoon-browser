package com.spoondon.browser;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth for "may this host use http:// ?".
 *
 * Security batch A (2026-09-30): introduced.
 * Batch D (2026-09-30): extended with a runtime user whitelist.
 *
 * Enforcement model changed in batch D. Previously, network_security_config
 * carried the whitelist and the platform enforced it. Now the NSC base-config
 * is permissive (cleartextTrafficPermitted="true"), and ALL enforcement lives
 * here + in SpoonWebViewClient.handleUrlLoading. This tradeoff is deliberate:
 * a browser must be able to reach arbitrary http:// hosts, and NSC is
 * compile-time only.
 *
 * A host is allowed cleartext if EITHER:
 *   1. It appears in the compiled list below, OR
 *   2. It appears in CleartextPreferences' user list.
 */
public final class CleartextPolicy {

    private CleartextPolicy() {
        // no instances
    }

    /**
     * Compiled-in hosts. Exact match, case-insensitive.
     */
    private static final Set<String> EXACT_HOSTS = new HashSet<>(Arrays.asList(
            "localhost",
            "127.0.0.1",
            "::1",
            "10.0.2.2",
            "10.0.2.3",
            // Common default gateway IPs
            "10.0.0.1", "10.0.0.138", "10.0.1.1", "10.1.1.1", "10.1.10.1",
            "172.16.0.1",
            "192.168.0.1", "192.168.1.1", "192.168.1.254", "192.168.2.1",
            "192.168.4.1", "192.168.8.1", "192.168.50.1", "192.168.100.1"
    ));

    /**
     * Compiled-in suffixes. Matched as the exact string OR any subdomain.
     */
    private static final List<String> SUFFIX_HOSTS = Arrays.asList(
            "tplinkwifi.net",
            "tplinklogin.net",
            "routerlogin.net",
            "routerlogin.com",
            "tendawifi.com",
            "asusrouter.com",
            "mwlogin.net",
            "pi.hole",
            "local"
    );

    /**
     * @return true if {@code host} is permitted to load over http://,
     *         consulting both the compiled list and the user list.
     *         Null ctx or host returns false.
     */
    public static boolean isCleartextAllowed(@Nullable Context ctx, @Nullable String host) {
        if (host == null) return false;
        String lower = host.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return false;

        if (isCompiledAllow(lower)) return true;
        if (ctx == null) return false;
        return CleartextPreferences.isUserAllowed(ctx, lower);
    }

    /**
     * Compiled-list check only. Used to distinguish "always safe" hosts from
     * user-added ones when rendering UI.
     */
    public static boolean isCompiledAllow(@Nullable String host) {
        if (host == null) return false;
        String lower = host.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return false;

        if (EXACT_HOSTS.contains(lower)) return true;

        for (String suffix : SUFFIX_HOSTS) {
            if (lower.equals(suffix)) return true;
            if (lower.endsWith("." + suffix)) return true;
        }
        return false;
    }
}
