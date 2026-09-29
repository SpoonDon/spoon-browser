package com.spoondon.browser;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth for "may this host use http:// ?".
 *
 * The Android platform enforces the policy via
 * res/xml/network_security_config.xml — that file is what actually blocks
 * cleartext at the network layer. This class lets app code reason about
 * the same list without duplicating hostname strings inline.
 *
 * KEEP IN SYNC WITH res/xml/network_security_config.xml.
 *
 * Extracted in security batch A (2026-09-30).
 */
public final class CleartextPolicy {

    private CleartextPolicy() {
        // no instances
    }

    /**
     * Exact hostnames — matched as-is (case-insensitive). Used for IP
     * literals and named hosts where suffix matching would be wrong.
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
     * Suffix hosts — matched as the exact string OR any subdomain
     * ({@code ".suffix"}). Used for brand aliases and mDNS.
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
     * @return true if {@code host} is permitted to load over http://.
     *         Null, empty, and whitespace hosts return false.
     */
    public static boolean isCleartextAllowed(@Nullable String host) {
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
