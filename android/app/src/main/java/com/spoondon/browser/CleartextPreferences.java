package com.spoondon.browser;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Persistent store of user-added cleartext (http://) allowed hosts.
 *
 * These sit on top of the compiled whitelist in CleartextPolicy. A host is
 * permitted to load over http:// if EITHER:
 *   - it appears in CleartextPolicy's compiled list (router brands, gateway
 *     IPs, localhost, emulator host, mDNS), OR
 *   - it has been added here by the user via the "Trusted cleartext hosts"
 *     menu.
 *
 * Batch D (2026-09-30).
 */
public final class CleartextPreferences {

    private static final String PREFS = "spoon_cleartext";
    private static final String KEY_HOSTS = "user_hosts";

    private CleartextPreferences() {
        // no instances
    }

    @NonNull
    public static Set<String> getUserHosts(@NonNull Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> stored = p.getStringSet(KEY_HOSTS, Collections.emptySet());
        // Defensive copy — SharedPreferences returns a live Set that must
        // not be mutated in place.
        return new HashSet<>(stored);
    }

    public static void addUserHost(@NonNull Context ctx, @Nullable String rawHost) {
        String host = normalize(rawHost);
        if (host == null) return;

        Set<String> current = getUserHosts(ctx);
        if (current.add(host)) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putStringSet(KEY_HOSTS, current)
                    .apply();
        }
    }

    public static void removeUserHost(@NonNull Context ctx, @Nullable String rawHost) {
        String host = normalize(rawHost);
        if (host == null) return;

        Set<String> current = getUserHosts(ctx);
        if (current.remove(host)) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putStringSet(KEY_HOSTS, current)
                    .apply();
        }
    }

    public static boolean isUserAllowed(@NonNull Context ctx, @Nullable String rawHost) {
        String host = normalize(rawHost);
        if (host == null) return false;
        return getUserHosts(ctx).contains(host);
    }

    /**
     * Normalizes arbitrary user input into a bare lowercase hostname.
     * Accepts:
     *   "192.168.1.1"
     *   "192.168.1.1:8080"
     *   "http://192.168.1.1/admin"
     *   "Router.LAN"
     * Returns null if nothing usable remains.
     */
    @Nullable
    private static String normalize(@Nullable String input) {
        if (input == null) return null;
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;

        // Strip scheme
        int schemeIdx = s.indexOf("://");
        if (schemeIdx != -1) s = s.substring(schemeIdx + 3);

        // Strip path
        int slash = s.indexOf('/');
        if (slash != -1) s = s.substring(0, slash);

        // Strip port
        int colon = s.indexOf(':');
        if (colon != -1) s = s.substring(0, colon);

        s = s.trim();
        return s.isEmpty() ? null : s;
    }
}
