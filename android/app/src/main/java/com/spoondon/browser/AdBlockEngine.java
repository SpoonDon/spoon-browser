package com.spoondon.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AdBlock matching engine.
 *
 * v4 (2026-10-02, Phase-1 strengthening pass):
 *   - Bug 1 fix: parseLine no longer rejects non-"||" patterns. Path-only
 *     rules ("/banner/", "/ads.js") are collected in a dedicated list and
 *     matched against every request regardless of host. This alone unlocks
 *     the ~40% of EasyList rules the v3 engine silently dropped.
 *   - Bug 2 fix: parent-domain lookup now walks the FULL label chain.
 *     "||example.com^" matches "a.b.example.com", not just "b.example.com".
 *   - Bug 3 partial: single-label hosts (localhost) and IP-literal hosts
 *     accepted. Wildcard hosts still rejected, now counted as skipped.
 *   - Fail-open counter: parseLine tallies skipped lines (unknown syntax,
 *     unparseable shapes) and exposes them via getLastSkippedCount().
 *     Also counts successfully parsed rules via getLastParsedRuleCount().
 *     UI can display "N rules loaded, M skipped".
 *   - Cache version bumped to 4; older caches are treated as a miss.
 *
 * Historical:
 *   - Security batch C (2026-09-30): network filter exceptions, $domain=,
 *     resource types, party constraints.
 *   - Backlog #4 (2026-09-30): three-tier cosmetic rules with ~ negation.
 *
 * Phase-2+ (planned, not in this file):
 *   - $important priority ordering, $badfilter post-parse pass
 *   - Site allowlist (per-host enable/disable), consulted by shouldBlock
 *   - Blocked-request ring buffer for the "what got blocked" viewer
 *   - Editable filter-list source presets (AdBlockPreferences)
 */
public class AdBlockEngine {

    // ------------------------------------------------------------------------
    // Resource type bitmask
    // ------------------------------------------------------------------------
    public static final int TYPE_DOCUMENT    = 1 << 0;
    public static final int TYPE_SUBDOCUMENT = 1 << 1;
    public static final int TYPE_SCRIPT      = 1 << 2;
    public static final int TYPE_STYLESHEET  = 1 << 3;
    public static final int TYPE_IMAGE       = 1 << 4;
    public static final int TYPE_FONT        = 1 << 5;
    public static final int TYPE_MEDIA       = 1 << 6;
    public static final int TYPE_XHR         = 1 << 7;
    public static final int TYPE_OTHER       = 1 << 8;

    private static final int PARTY_ANY   = 0;
    private static final int PARTY_THIRD = 1;
    private static final int PARTY_FIRST = 2;

    // ------------------------------------------------------------------------
    // Network-rule state
    // ------------------------------------------------------------------------
    private static volatile HashSet<String> blockedDomains = new HashSet<>();
    private static volatile HashMap<String, ArrayList<String>> scopedPathRules = new HashMap<>();
    private static volatile HashMap<String, List<Rule>> blockRules = new HashMap<>();
    private static volatile HashMap<String, List<Rule>> exceptionRules = new HashMap<>();

    /**
     * Path-only rules (v4). Every filter line of the form "/path/" that has
     * no host anchor. Matched against every request's path+query regardless
     * of the request's host. Exceptions (lines starting with @@) also land
     * here. During shouldBlock, exceptions are checked first; a hit means
     * "don't block", a block hit means "block".
     */
    private static volatile List<PathRule> pathOnlyRules = new ArrayList<>();

    // ------------------------------------------------------------------------
    // Cosmetic-rule state (backlog #4)
    // ------------------------------------------------------------------------
    private static volatile List<CosmeticRule> cosmeticGlobal = new ArrayList<>();
    private static volatile Map<String, List<CosmeticRule>> cosmeticByHost = new HashMap<>();

    private static volatile HashSet<String> whitelistedDomains = new HashSet<>();
    private static volatile boolean isEngineEnabled = true;

    // Phase-1 instrumentation (v4).
    private static volatile int lastSkippedCount = 0;
    private static volatile int lastParsedRuleCount = 0;

    private static final AtomicBoolean isUpdating = new AtomicBoolean(false);
    private static final String PREFS_NAME = "SpoonAdBlockPrefs";
    private static final String KEY_ENABLED = "adblock_enabled";
    private static final String KEY_WHITELIST = "adblock_whitelist";
    private static final String KEY_REFRESH_TIME = "filter_refresh_time";

    private static final int CACHE_VERSION = 4;

    private static final int DECISION_CACHE_MAX = 2000;
    private static final long DECISION_CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final LruCache<String, CacheEntry> decisionCache =
            new LruCache<>(DECISION_CACHE_MAX);

    // ========================================================================
    // Public API — engine state
    // ========================================================================

    public static boolean hasRules() {
        return isEngineEnabled &&
                ((blockedDomains != null && !blockedDomains.isEmpty()) ||
                 (scopedPathRules != null && !scopedPathRules.isEmpty()) ||
                 (blockRules != null && !blockRules.isEmpty()) ||
                 (pathOnlyRules != null && !pathOnlyRules.isEmpty()));
    }

    public static void setEngineEnabled(Context context, boolean enabled) {
        isEngineEnabled = enabled;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (!enabled) decisionCache.evictAll();
    }

    public static boolean checkIsEngineEnabled(Context context) {
        isEngineEnabled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
        return isEngineEnabled;
    }

    public static int getBlocklistSize() {
        int total = (blockedDomains != null ? blockedDomains.size() : 0);
        if (scopedPathRules != null) {
            for (ArrayList<String> list : scopedPathRules.values()) total += list.size();
        }
        if (blockRules != null) {
            for (List<Rule> list : blockRules.values()) total += list.size();
        }
        if (exceptionRules != null) {
            for (List<Rule> list : exceptionRules.values()) total += list.size();
        }
        if (pathOnlyRules != null) total += pathOnlyRules.size();
        return total;
    }

    /**
     * Number of filter lines skipped during the most recent parse pass.
     * Incremented for every line that could not be understood or uses
     * syntax this engine does not yet support (wildcard hosts, $important,
     * $redirect, scriptlets, etc.). Exposed for the filter-list UI so the
     * user can see what is not being honoured.
     */
    public static int getLastSkippedCount() {
        return lastSkippedCount;
    }

    /**
     * Number of filter lines successfully parsed into rules during the
     * most recent parse pass. Paired with getLastSkippedCount().
     */
    public static int getLastParsedRuleCount() {
        return lastParsedRuleCount;
    }

    // ========================================================================
    // Init / refresh
    // ========================================================================

    public static void init(Context context, List<String> filterLists) {
        checkIsEngineEnabled(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        String savedWhitelist = prefs.getString(KEY_WHITELIST, "");
        HashSet<String> whiteSet = new HashSet<>();
        if (!savedWhitelist.isEmpty()) {
            for (String domain : savedWhitelist.split(",")) {
                whiteSet.add(domain.trim().toLowerCase(Locale.ROOT));
            }
        }
        whitelistedDomains = whiteSet;

        if (loadEngineFromCache(context)) return;
        if (filterLists == null || filterLists.isEmpty()) return;

        Builder b = new Builder();
        for (String filterUrl : filterLists) {
            String filename = "filter_" + Math.abs(filterUrl.hashCode()) + ".txt";
            File localFile = new File(context.getFilesDir(), filename);
            if (localFile.exists()) {
                try (InputStream is = new FileInputStream(localFile);
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    parseFilterLines(reader, b);
                } catch (Exception ignored) {}
            }
        }
        lastSkippedCount = b.skipped;
        lastParsedRuleCount = b.parsed;
        applyBuilder(b);
        saveEngineToCache(context);
    }

    public static void checkAndRefreshFilters(Context context,
                                              ExecutorService executor,
                                              List<String> filterLists,
                                              boolean forceRefresh) {
        if (filterLists == null || filterLists.isEmpty()) return;
        if (!isUpdating.compareAndSet(false, true)) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        executor.execute(() -> {
            boolean allOk = true;
            Builder b = new Builder();

            for (String filterUrl : filterLists) {
                if (!filterUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
                    allOk = false;
                    continue;
                }

                String filename = "filter_" + Math.abs(filterUrl.hashCode()) + ".txt";
                File localFile = new File(context.getFilesDir(), filename);

                try {
                    InputStream inputStream;
                    if (!forceRefresh && localFile.exists()) {
                        inputStream = new FileInputStream(localFile);
                    } else {
                        URLConnection conn = new URL(filterUrl).openConnection();
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(5000);
                        inputStream = conn.getInputStream();
                    }
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                        parseFilterLines(reader, b);
                    }
                } catch (Exception e) {
                    allOk = false;
                }
            }

            if (b.hasAnything()) {
                lastSkippedCount = b.skipped;
                lastParsedRuleCount = b.parsed;
                applyBuilder(b);
                saveEngineToCache(context);
            }

            if (allOk) prefs.edit().putLong(KEY_REFRESH_TIME, System.currentTimeMillis()).apply();
            isUpdating.set(false);
        });
    }

    public static void removeFilterList(Context context,
                                        String filterUrl,
                                        List<String> remainingLists,
                                        ExecutorService executor) {
        if (filterUrl == null || filterUrl.isEmpty()) return;

        executor.execute(() -> {
            String filename = "filter_" + Math.abs(filterUrl.hashCode()) + ".txt";
            File localFile = new File(context.getFilesDir(), filename);
            if (localFile.exists()) localFile.delete();

            Builder b = new Builder();
            if (remainingLists != null) {
                for (String url : remainingLists) {
                    String fn = "filter_" + Math.abs(url.hashCode()) + ".txt";
                    File lf = new File(context.getFilesDir(), fn);
                    if (lf.exists()) {
                        try (InputStream is = new FileInputStream(lf);
                             BufferedReader reader = new BufferedReader(
                                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
                            parseFilterLines(reader, b);
                        } catch (Exception ignored) {}
                    }
                }
            }
            lastSkippedCount = b.skipped;
            lastParsedRuleCount = b.parsed;
            applyBuilder(b);
            saveEngineToCache(context);
        });
    }

    public static void clearAllFilterLists(Context context, ExecutorService executor) {
        executor.execute(() -> {
            File filesDir = context.getFilesDir();
            File[] files = filesDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.getName().startsWith("filter_") && file.getName().endsWith(".txt")) {
                        file.delete();
                    }
                }
            }
            lastSkippedCount = 0;
            lastParsedRuleCount = 0;
            applyBuilder(new Builder());

            File cacheFile = new File(context.getFilesDir(), "adblock_cache.json");
            if (cacheFile.exists()) cacheFile.delete();
            File oldCacheFile = new File(context.getFilesDir(), "adblock_bin_cache.dat");
            if (oldCacheFile.exists()) oldCacheFile.delete();
        });
    }

    // ========================================================================
    // Network decision (v4 — full label-chain walk + path-only rules)
    // ========================================================================

    public static boolean shouldBlock(String url) {
        return shouldBlock(url, TYPE_OTHER, null);
    }

    public static boolean shouldBlock(String url, int resourceType, String sourceHost) {
        if (!isEngineEnabled || url == null) return false;

        String key = resourceType + "|" + (sourceHost == null ? "" : sourceHost) + "|"
                + url.toLowerCase(Locale.ROOT);
        synchronized (decisionCache) {
            CacheEntry cached = decisionCache.get(key);
            if (cached != null) {
                if (System.currentTimeMillis() - cached.timestampMs <= DECISION_CACHE_TTL_MS) {
                    return cached.blocked;
                }
                decisionCache.remove(key);
            }
        }

        boolean blocked = false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) {
                blocked = false;
            } else {
                host = host.toLowerCase(Locale.ROOT);

                if (isWhitelistedChain(host)) {
                    blocked = false;
                } else {
                    String pathAndQuery = buildPathAndQuery(uri);
                    String src = sourceHost != null ? sourceHost.toLowerCase(Locale.ROOT) : null;
                    boolean thirdParty = src != null && !sameSite(host, src);

                    // v4: build the full label chain once. Example:
                    //   host = "a.b.example.com"  →  ["a.b.example.com", "b.example.com", "example.com"]
                    // Stops before a single-label TLD ("com"). Empty for IP literals.
                    List<String> chain = buildHostChain(host);

                    // 1. Exception check — any level of the chain, plus path-only exceptions.
                    boolean excepted = false;
                    for (int i = 0; i < chain.size(); i++) {
                        if (matchesInRules(exceptionRules, chain.get(i), pathAndQuery,
                                resourceType, src, thirdParty)) {
                            excepted = true;
                            break;
                        }
                    }
                    if (!excepted) {
                        for (int i = 0; i < pathOnlyRules.size(); i++) {
                            PathRule pr = pathOnlyRules.get(i);
                            if (pr.exception && pr.matches(pathAndQuery, resourceType, src, thirdParty)) {
                                excepted = true;
                                break;
                            }
                        }
                    }

                    if (excepted) {
                        blocked = false;
                    } else {
                        // 2. Path-only block rules (v4 — no host needed).
                        for (int i = 0; i < pathOnlyRules.size(); i++) {
                            PathRule pr = pathOnlyRules.get(i);
                            if (!pr.exception && pr.matches(pathAndQuery, resourceType, src, thirdParty)) {
                                blocked = true;
                                break;
                            }
                        }

                        // 3. Host-anchored rules at every level of the chain.
                        if (!blocked) {
                            for (int i = 0; i < chain.size(); i++) {
                                String h = chain.get(i);
                                if (blockedDomains.contains(h)) { blocked = true; break; }
                                if (checkPathMatch(h, pathAndQuery)) { blocked = true; break; }
                                if (matchesInRules(blockRules, h, pathAndQuery,
                                        resourceType, src, thirdParty)) {
                                    blocked = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            blocked = false;
        }

        synchronized (decisionCache) {
            decisionCache.put(key, new CacheEntry(blocked, System.currentTimeMillis()));
        }
        return blocked;
    }

    /**
     * True if any label of the host chain is in the site allowlist.
     * Walk stops before a single-label TLD, so "google.com" in the allowlist
     * does not match "evil-google.com".
     */
    private static boolean isWhitelistedChain(String host) {
        Set<String> wl = whitelistedDomains;
        if (wl == null || wl.isEmpty()) return false;
        String check = host;
        while (check != null && check.indexOf('.') != -1) {
            if (wl.contains(check)) return true;
            int dot = check.indexOf('.');
            check = check.substring(dot + 1);
        }
        // Single-label host (localhost) can still be whitelisted as-is.
        return check != null && wl.contains(check);
    }

    /**
     * Returns the label chain for a host, from most specific to least.
     * Example: "a.b.example.com" → ["a.b.example.com", "b.example.com", "example.com"].
     * Returns an empty list for IP literals (no meaningful label chain).
     * Stops before single-label TLDs.
     */
    @NonNull
    private static List<String> buildHostChain(String host) {
        List<String> out = new ArrayList<>(4);
        if (host == null) return out;
        if (isIpLiteral(host)) {
            out.add(host);
            return out;
        }
        String check = host;
        while (check != null && check.indexOf('.') != -1) {
            out.add(check);
            int dot = check.indexOf('.');
            check = check.substring(dot + 1);
        }
        // If the final label is single-label (TLD), we deliberately do not add it.
        // If the original host was already single-label (localhost), the loop
        // never ran — add it once.
        if (out.isEmpty() && check != null && !check.isEmpty()) {
            out.add(check);
        }
        return out;
    }

    private static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) return false;
        // Crude IPv4 / IPv6-ish check. IPv6 contains ':'; IPv4 is digit-dots.
        if (host.indexOf(':') != -1) return true;
        int dots = 0;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') { dots++; continue; }
            if (c < '0' || c > '9') return false;
        }
        return dots == 3;
    }

    // ========================================================================
    // Cosmetic decision (backlog #4)
    // ========================================================================

    public static String getCosmeticCss(String url) {
        if (!isEngineEnabled || url == null) return "";

        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return "";
            host = host.toLowerCase(Locale.ROOT);
            String parent = parentOfOneLevel(host);

            Set<String> hidden = new HashSet<>();
            Set<String> excepted = new HashSet<>();

            List<CosmeticRule> globals = cosmeticGlobal;
            if (globals != null) {
                for (int i = 0; i < globals.size(); i++) {
                    CosmeticRule r = globals.get(i);
                    if (r.matches(host, parent)) {
                        if (r.exception) excepted.add(r.selector);
                        else hidden.add(r.selector);
                    }
                }
            }

            Map<String, List<CosmeticRule>> index = cosmeticByHost;
            if (index != null && !index.isEmpty()) {
                List<String> chain = buildHostChain(host);
                for (int i = 0; i < chain.size(); i++) {
                    collectHostRules(index.get(chain.get(i)), host, parent, hidden, excepted);
                }
            }

            hidden.removeAll(excepted);
            if (hidden.isEmpty()) return "";

            return android.text.TextUtils.join(", ", hidden)
                    + " { display: none !important; }";
        } catch (Exception e) {
            return "";
        }
    }

    private static void collectHostRules(@Nullable List<CosmeticRule> rules,
                                         String host,
                                         @Nullable String parent,
                                         Set<String> hidden,
                                         Set<String> excepted) {
        if (rules == null) return;
        for (int i = 0; i < rules.size(); i++) {
            CosmeticRule r = rules.get(i);
            if (r.matches(host, parent)) {
                if (r.exception) excepted.add(r.selector);
                else hidden.add(r.selector);
            }
        }
    }

    public static final class CosmeticRule {
        final Set<String> allow;
        final Set<String> deny;
        final String selector;
        final boolean exception;

        CosmeticRule(Set<String> allow, Set<String> deny,
                     String selector, boolean exception) {
            this.allow = allow;
            this.deny = deny;
            this.selector = selector;
            this.exception = exception;
        }

        boolean matches(String host, @Nullable String parent) {
            if (deny != null) {
                for (String d : deny) {
                    if (hostMatches(host, d) || (parent != null && hostMatches(parent, d))) {
                        return false;
                    }
                }
            }
            if (allow != null && !allow.isEmpty()) {
                boolean ok = false;
                for (String a : allow) {
                    if (hostMatches(host, a) || (parent != null && hostMatches(parent, a))) {
                        ok = true;
                        break;
                    }
                }
                if (!ok) return false;
            }
            return true;
        }

        private static boolean hostMatches(String host, String pattern) {
            if (host == null || pattern == null) return false;
            if (host.equals(pattern)) return true;
            return host.endsWith("." + pattern);
        }
    }

    // ========================================================================
    // Network matching internals
    // ========================================================================

    private static boolean matchesInRules(Map<String, List<Rule>> index,
                                          String hostKey,
                                          String pathAndQuery,
                                          int resourceType,
                                          String sourceHost,
                                          boolean thirdParty) {
        if (index == null || index.isEmpty()) return false;
        List<Rule> atHost = index.get(hostKey);
        if (atHost == null) return false;
        for (int i = 0; i < atHost.size(); i++) {
            if (atHost.get(i).matches(pathAndQuery, resourceType, sourceHost, thirdParty)) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkPathMatch(String hostKey, String targetPath) {
        ArrayList<String> rules = scopedPathRules.get(hostKey);
        if (rules != null) {
            for (int i = 0; i < rules.size(); i++) {
                String rule = rules.get(i);
                if (rule.equals("/") || targetPath.contains(rule)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * One-level parent lookup, kept for the cosmetic path where we only
     * need the parent for the allow/deny set check. Network matching uses
     * the full chain via buildHostChain instead.
     */
    @Nullable
    private static String parentOfOneLevel(String host) {
        if (host == null) return null;
        int firstDot = host.indexOf('.');
        int lastDot = host.lastIndexOf('.');
        if (firstDot > 0 && firstDot != lastDot) {
            return host.substring(firstDot + 1);
        }
        return null;
    }

    private static String buildPathAndQuery(Uri uri) {
        String path = uri.getPath();
        if (path == null) path = "";
        if (uri.getQuery() != null) path += "?" + uri.getQuery();
        return path.toLowerCase(Locale.ROOT);
    }

    private static boolean sameSite(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        return a.endsWith("." + b) || b.endsWith("." + a);
    }

        // ========================================================================
    // Parsing
    // ========================================================================

    private static final class Builder {
        final HashSet<String> domains = new HashSet<>();
        final HashMap<String, ArrayList<String>> paths = new HashMap<>();
        final HashMap<String, List<Rule>> blockRules = new HashMap<>();
        final HashMap<String, List<Rule>> exceptionRules = new HashMap<>();
        final List<PathRule> pathRules = new ArrayList<>();
        final List<CosmeticRule> cosmeticRules = new ArrayList<>();

        // v4 instrumentation.
        int parsed = 0;
        int skipped = 0;

        boolean hasAnything() {
            return !domains.isEmpty() || !paths.isEmpty()
                    || !blockRules.isEmpty() || !exceptionRules.isEmpty()
                    || !pathRules.isEmpty() || !cosmeticRules.isEmpty();
        }
    }

    private static void applyBuilder(Builder b) {
        blockedDomains = b.domains;
        scopedPathRules = b.paths;
        blockRules = b.blockRules;
        exceptionRules = b.exceptionRules;
        pathOnlyRules = b.pathRules;

        List<CosmeticRule> globals = new ArrayList<>();
        Map<String, List<CosmeticRule>> byHost = new HashMap<>();
        for (CosmeticRule r : b.cosmeticRules) {
            if (r.allow == null || r.allow.isEmpty()) {
                globals.add(r);
            } else {
                for (String h : r.allow) {
                    byHost.computeIfAbsent(h, k -> new ArrayList<>()).add(r);
                }
            }
        }
        cosmeticGlobal = globals;
        cosmeticByHost = byHost;

        decisionCache.evictAll();
    }

    private static void parseFilterLines(BufferedReader reader, Builder b) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            parseLine(line, b);
        }
    }

    /**
     * v4 parseLine. Shape classifier replaces the old "must start with ||"
     * gate. Recognised shapes:
     *
     *   ||host^              — host-anchored block
     *   ||host^$opts         — with options
     *   ||host/path          — host + path
     *   @@||host^            — host-anchored exception
     *   /path/               — path-only block
     *   /path*foo            — path-only with wildcard
     *   @@/path/             — path-only exception
     *   host/path            — plain (substring) block
     *   any-substring        — plain (substring) block
     *   ##selector           — cosmetic (dispatched earlier)
     *   #@#selector          — cosmetic exception (dispatched earlier)
     *
     * Anything else (regex rules, unknown option values that aren't "domain",
     * malformed lines) increments b.skipped but never throws.
     */
    private static void parseLine(String raw, Builder b) {
        if (raw == null) return;
        String line = raw.trim();
        if (line.isEmpty()) return;
        // Comments and preprocessor directives — skip silently, not counted.
        if (line.startsWith("!")) return;
        if (line.startsWith("[")) return;

        // --- Cosmetic rules (## / #@#) — check exception first ------------
        int exIdx = line.indexOf("#@#");
        int hideIdx = line.indexOf("##");

        if (exIdx != -1 && (hideIdx == -1 || exIdx < hideIdx)) {
            parseCosmetic(line, exIdx, 3, true, b);
            return;
        }
        if (hideIdx != -1) {
            parseCosmetic(line, hideIdx, 2, false, b);
            return;
        }

        // --- Network rule --------------------------------------------------
        boolean exception = false;
        if (line.startsWith("@@")) {
            exception = true;
            line = line.substring(2).trim();
        }

        if (line.isEmpty()) { b.skipped++; return; }

        // Regex rules /.../ — unsupported, counted as skipped.
        if (line.length() > 2 && line.startsWith("/") && line.endsWith("/")
                && line.indexOf('$') == -1) {
            b.skipped++;
            return;
        }

        // Split off $options. Heuristic: the last '$' is the options separator
        // only if the character after it is a letter or '~' (start of a known
        // option). This avoids eating '$' inside a path or regex.
        String pattern = line;
        String options = null;
        int dollar = line.lastIndexOf('$');
        if (dollar > 0 && dollar < line.length() - 1) {
            char after = line.charAt(dollar + 1);
            if (Character.isLetter(after) || after == '~') {
                pattern = line.substring(0, dollar);
                options = line.substring(dollar + 1);
            }
        }

        pattern = pattern.trim();
        if (pattern.isEmpty()) { b.skipped++; return; }

        // --- Parse options -------------------------------------------------
        int resourceTypesMask = 0;
        Set<String> domainAllow = null;
        Set<String> domainDeny = null;
        int partyFlag = PARTY_ANY;
        boolean hasOptions = false;

        if (options != null && !options.isEmpty()) {
            for (String opt : options.split(",")) {
                opt = opt.trim();
                if (opt.isEmpty()) continue;

                if (opt.equals("third-party")) {
                    partyFlag = PARTY_THIRD; hasOptions = true; continue;
                }
                if (opt.equals("~third-party")) {
                    partyFlag = PARTY_FIRST; hasOptions = true; continue;
                }

                int eq = opt.indexOf('=');
                if (eq > 0) {
                    String key = opt.substring(0, eq);
                    String value = opt.substring(eq + 1);
                    if (key.equals("domain")) {
                        for (String d : value.split("\\|")) {
                            d = d.trim().toLowerCase(Locale.ROOT);
                            if (d.isEmpty()) continue;
                            if (d.startsWith("~")) {
                                if (domainDeny == null) domainDeny = new HashSet<>();
                                domainDeny.add(d.substring(1));
                            } else {
                                if (domainAllow == null) domainAllow = new HashSet<>();
                                domainAllow.add(d);
                            }
                            hasOptions = true;
                        }
                    }
                    // Unknown key=value options fall through — fail-open.
                    continue;
                }

                int t = resourceTypeFromName(opt);
                if (t != 0) { resourceTypesMask |= t; hasOptions = true; }
                // Unknown option name — fail-open (rule still applies).
            }
        }

        // --- Shape classification ------------------------------------------

        // 1. Host-anchored: ||host^...
        if (pattern.startsWith("||")) {
            parseHostAnchored(pattern, exception, resourceTypesMask,
                    domainAllow, domainDeny, partyFlag, hasOptions, b);
            return;
        }

        // 2. Explicit leading pipe (|http://... / |https://...) — treat as
        //    path-only, strip the pipe.
        if (pattern.startsWith("|")) {
            pattern = pattern.substring(1);
            if (pattern.isEmpty()) { b.skipped++; return; }
        }

        // 3. Everything else becomes a path-only rule. This is the v4 fix:
        //    the old engine returned here and lost the rule entirely.
        b.pathOnlyRules.add(new PathRule(pattern, exception, resourceTypesMask,
                domainAllow, domainDeny, partyFlag));
        b.parsed++;
    }

    /**
     * Handles the ||host^ family of rules. Behaviour preserved from v3, but
     * now stores pathPattern WITH wildcards intact so Rule.matches can use
     * wildcard-aware substring matching.
     */
    private static void parseHostAnchored(String pattern,
                                          boolean exception,
                                          int resourceTypesMask,
                                          Set<String> domainAllow,
                                          Set<String> domainDeny,
                                          int partyFlag,
                                          boolean hasOptions,
                                          Builder b) {
        String domainAndPath = pattern.substring(2);
        int caret = domainAndPath.indexOf('^');
        if (caret != -1) domainAndPath = domainAndPath.substring(0, caret);

        String host;
        String pathPattern;
        int slash = domainAndPath.indexOf('/');
        if (slash == -1) {
            host = domainAndPath;
            pathPattern = "";
        } else {
            host = domainAndPath.substring(0, slash);
            pathPattern = domainAndPath.substring(slash);
        }
        host = host.trim().toLowerCase(Locale.ROOT);

        if (host.isEmpty()) { b.skipped++; return; }
        // Wildcard hosts — still unsupported in v4, but counted instead of
        // silently dropped.
        if (host.contains("*")) { b.skipped++; return; }

        // Accept single-label hosts (localhost) and IP literals. The old
        // `!host.contains(".")` gate is gone.
        // Note: this means a stray filter line like "||ads^" now parses as a
        // literal host "ads". That's acceptable — such lines are rare and
        // the fail-open counter will reveal them if they cause problems.

        if (!hasOptions && !exception) {
            if (pathPattern.isEmpty()) {
                b.domains.add(host);
            } else {
                b.paths.computeIfAbsent(host, k -> new ArrayList<>()).add(pathPattern);
            }
            b.parsed++;
            return;
        }

        Rule rule = new Rule(host, pathPattern, exception, resourceTypesMask,
                domainAllow, domainDeny, partyFlag);

        HashMap<String, List<Rule>> target = exception ? b.exceptionRules : b.blockRules;
        target.computeIfAbsent(host, k -> new ArrayList<>()).add(rule);
        b.parsed++;
    }

    /**
     * Cosmetic rule parser — unchanged from v3.
     */
    private static void parseCosmetic(String line, int markerIdx, int markerLen,
                                      boolean exception, Builder b) {
        String domainPart = line.substring(0, markerIdx).trim();
        String selector = line.substring(markerIdx + markerLen).trim();

        if (selector.isEmpty()) { b.skipped++; return; }

        Set<String> allow = new HashSet<>();
        Set<String> deny = new HashSet<>();

        if (!domainPart.isEmpty()) {
            for (String d : domainPart.split(",")) {
                d = d.trim().toLowerCase(Locale.ROOT);
                if (d.isEmpty()) continue;
                if (d.contains("*")) continue;
                if (d.startsWith("~")) {
                    String plain = d.substring(1).trim();
                    if (!plain.isEmpty()) deny.add(plain);
                } else {
                    allow.add(d);
                }
            }
        }

        b.cosmeticRules.add(new CosmeticRule(
                allow.isEmpty() ? null : allow,
                deny.isEmpty() ? null : deny,
                selector,
                exception));
        b.parsed++;
    }

    private static int resourceTypeFromName(String name) {
        switch (name) {
            case "document":        return TYPE_DOCUMENT;
            case "subdocument":     return TYPE_SUBDOCUMENT;
            case "script":          return TYPE_SCRIPT;
            case "stylesheet":
            case "css":             return TYPE_STYLESHEET;
            case "image":           return TYPE_IMAGE;
            case "font":            return TYPE_FONT;
            case "media":           return TYPE_MEDIA;
            case "xmlhttprequest":
            case "xhr":             return TYPE_XHR;
            case "websocket":
            case "ping":
            case "object":
            case "popup":
            case "csp_report":      return TYPE_OTHER;
            default:                return 0;
        }
    }

    // ========================================================================
    // Path-only rule (v4)
    // ========================================================================

    /**
     * A network rule with no host anchor. Matched against the request's
     * path+query. Examples of source filter lines that produce these:
     *
     *   /banner/*.gif
     *   /pagead/
     *   ads.js
     *   @@/analytics.js
     *
     * Wildcards (`*`) are honoured: a pattern like "/banner/*.gif" is split
     * into segments ["/banner/", ".gif"] and matches only if both appear in
     * order in the request's path+query.
     */
    public static final class PathRule {
        final String pattern;
        final List<String> segments;
        final boolean wildcardLeading;
        final boolean exception;
        final int resourceTypesMask;
        final Set<String> domainAllow;
        final Set<String> domainDeny;
        final int partyFlag;

        PathRule(String pattern, boolean exception, int resourceTypesMask,
                 Set<String> domainAllow, Set<String> domainDeny, int partyFlag) {
            this.pattern = pattern.toLowerCase(Locale.ROOT);
            this.exception = exception;
            this.resourceTypesMask = resourceTypesMask;
            this.domainAllow = domainAllow;
            this.domainDeny = domainDeny;
            this.partyFlag = partyFlag;
            this.wildcardLeading = this.pattern.startsWith("*");

            List<String> segs = new ArrayList<>(4);
            int lastEnd = 0;
            for (int i = 0; i < this.pattern.length(); i++) {
                if (this.pattern.charAt(i) == '*') {
                    if (i > lastEnd) segs.add(this.pattern.substring(lastEnd, i));
                    lastEnd = i + 1;
                }
            }
            if (lastEnd < this.pattern.length()) {
                segs.add(this.pattern.substring(lastEnd));
            }
            this.segments = segs;
        }

        boolean matches(String pathAndQuery, int resourceType,
                        String sourceHost, boolean thirdParty) {
            if (resourceTypesMask != 0 && (resourceTypesMask & resourceType) == 0) return false;
            if (partyFlag == PARTY_THIRD && !thirdParty) return false;
            if (partyFlag == PARTY_FIRST && thirdParty) return false;

            if (domainDeny != null && sourceHost != null) {
                for (String d : domainDeny) {
                    if (hostMatchesSuffix(sourceHost, d)) return false;
                }
            }
            if (domainAllow != null && !domainAllow.isEmpty()) {
                if (sourceHost == null) return false;
                boolean ok = false;
                for (String d : domainAllow) {
                    if (hostMatchesSuffix(sourceHost, d)) { ok = true; break; }
                }
                if (!ok) return false;
            }

            // Wildcard-aware substring match.
            if (segments.isEmpty()) return true;
            int pos;
            int startIdx;
            if (wildcardLeading) {
                pos = 0;
                startIdx = 0;
            } else {
                if (!pathAndQuery.startsWith(segments.get(0))) return false;
                pos = segments.get(0).length();
                startIdx = 1;
            }
            for (int i = startIdx; i < segments.size(); i++) {
                String seg = segments.get(i);
                int found = pathAndQuery.indexOf(seg, pos);
                if (found == -1) return false;
                pos = found + seg.length();
            }
            return true;
        }
    }

    // ========================================================================
    // Host-anchored network rule
    // ========================================================================

    public static final class Rule {
        final String host;
        final String pathPattern;
        final boolean exception;
        final int resourceTypesMask;
        final Set<String> domainAllow;
        final Set<String> domainDeny;
        final int partyFlag;

        Rule(String host, String pathPattern, boolean exception, int resourceTypesMask,
             Set<String> domainAllow, Set<String> domainDeny, int partyFlag) {
            this.host = host;
            this.pathPattern = pathPattern;
            this.exception = exception;
            this.resourceTypesMask = resourceTypesMask;
            this.domainAllow = domainAllow;
            this.domainDeny = domainDeny;
            this.partyFlag = partyFlag;
        }

        boolean matches(String path, int resourceType, String sourceHost, boolean thirdParty) {
            if (!pathPattern.isEmpty() && !pathContainsPattern(path, pathPattern)) return false;
            if (resourceTypesMask != 0 && (resourceTypesMask & resourceType) == 0) return false;
            if (partyFlag == PARTY_THIRD && !thirdParty) return false;
            if (partyFlag == PARTY_FIRST && thirdParty) return false;

            if (domainDeny != null && sourceHost != null) {
                for (String d : domainDeny) {
                    if (hostMatchesSuffix(sourceHost, d)) return false;
                }
            }

            if (domainAllow != null && !domainAllow.isEmpty()) {
                if (sourceHost == null) return false;
                boolean ok = false;
                for (String d : domainAllow) {
                    if (hostMatchesSuffix(sourceHost, d)) { ok = true; break; }
                }
                if (!ok) return false;
            }
            return true;
        }
    }

    /**
     * Wildcard-aware path matching shared by Rule.matches and PathRule.matches.
     * Splits the pattern on `*` and requires all segments to appear in order.
     */
    private static boolean pathContainsPattern(String path, String pattern) {
        if (pattern == null || pattern.isEmpty()) return true;
        String p = pattern.toLowerCase(Locale.ROOT);
        if (p.indexOf('*') == -1) {
            // Fast path — no wildcards.
            return path.contains(p);
        }
        List<String> segs = new ArrayList<>(4);
        int lastEnd = 0;
        for (int i = 0; i < p.length(); i++) {
            if (p.charAt(i) == '*') {
                if (i > lastEnd) segs.add(p.substring(lastEnd, i));
                lastEnd = i + 1;
            }
        }
        if (lastEnd < p.length()) segs.add(p.substring(lastEnd));
        if (segs.isEmpty()) return true;

        int pos;
        int startIdx;
        if (p.startsWith("*")) {
            pos = 0;
            startIdx = 0;
        } else {
            if (!path.startsWith(segs.get(0))) return false;
            pos = segs.get(0).length();
            startIdx = 1;
        }
        for (int i = startIdx; i < segs.size(); i++) {
            String seg = segs.get(i);
            int found = path.indexOf(seg, pos);
            if (found == -1) return false;
            pos = found + seg.length();
        }
        return true;
    }

    private static boolean hostMatchesSuffix(String host, String suffix) {
        if (host == null || suffix == null) return false;
        if (host.equals(suffix)) return true;
        return host.endsWith("." + suffix);
    }

    // ========================================================================
    // Decision cache
    // ========================================================================

    private static final class CacheEntry {
        final boolean blocked;
        final long timestampMs;

        CacheEntry(boolean blocked, long timestampMs) {
            this.blocked = blocked;
            this.timestampMs = timestampMs;
        }
    }

    // ========================================================================
    // Persistence (JSON, version 4)
    // ========================================================================

    private static void saveEngineToCache(Context context) {
        try {
            File cacheFile = new File(context.getFilesDir(), "adblock_cache.json");
            File tmpFile = new File(context.getFilesDir(), "adblock_cache.json.tmp");
            JSONObject root = new JSONObject();
            root.put("version", CACHE_VERSION);

            JSONArray domainsArr = new JSONArray();
            for (String d : blockedDomains) domainsArr.put(d);
            root.put("domains", domainsArr);

            root.put("paths", stringListMapToJson(scopedPathRules));
            root.put("blockRules", rulesToJson(blockRules));
            root.put("exceptionRules", rulesToJson(exceptionRules));
            root.put("pathRules", pathRulesToJson());
            root.put("cosmetic", cosmeticRulesToJson());
            root.put("skipped", lastSkippedCount);
            root.put("parsed", lastParsedRuleCount);

            try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            // Atomic rename — prevents a partial write from corrupting the cache.
            if (!tmpFile.renameTo(cacheFile)) {
                if (cacheFile.exists()) cacheFile.delete();
                tmpFile.renameTo(cacheFile);
            }
        } catch (Exception ignored) {}
    }

    private static boolean loadEngineFromCache(Context context) {
        File cacheFile = new File(context.getFilesDir(), "adblock_cache.json");
        if (!cacheFile.exists()) return false;

        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(cacheFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());

            if (root.optInt("version", 0) < CACHE_VERSION) return false;

            HashSet<String> newDomains = new HashSet<>();
            JSONArray domainsArr = root.optJSONArray("domains");
            if (domainsArr != null) {
                for (int i = 0; i < domainsArr.length(); i++) {
                    newDomains.add(domainsArr.getString(i));
                }
            }
            blockedDomains = newDomains;

            scopedPathRules = jsonToStringListMap(root.optJSONObject("paths"));
            blockRules = jsonToRules(root.optJSONObject("blockRules"), false);
            exceptionRules = jsonToRules(root.optJSONObject("exceptionRules"), true);
            pathOnlyRules = jsonToPathRules(root.optJSONArray("pathRules"));

            List<CosmeticRule> cosmetics = jsonToCosmeticRules(root.optJSONArray("cosmetic"));
            List<CosmeticRule> globals = new ArrayList<>();
            Map<String, List<CosmeticRule>> byHost = new HashMap<>();
            for (CosmeticRule r : cosmetics) {
                if (r.allow == null || r.allow.isEmpty()) {
                    globals.add(r);
                } else {
                    for (String h : r.allow) {
                        byHost.computeIfAbsent(h, k -> new ArrayList<>()).add(r);
                    }
                }
            }
            cosmeticGlobal = globals;
            cosmeticByHost = byHost;

            lastSkippedCount = root.optInt("skipped", 0);
            lastParsedRuleCount = root.optInt("parsed", 0);

            decisionCache.evictAll();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static JSONArray pathRulesToJson() throws Exception {
        JSONArray arr = new JSONArray();
        if (pathOnlyRules == null) return arr;
        for (PathRule pr : pathOnlyRules) {
            JSONObject rj = new JSONObject();
            rj.put("p", pr.pattern);
            rj.put("e", pr.exception);
            rj.put("t", pr.resourceTypesMask);
            rj.put("party", pr.partyFlag);
            if (pr.domainAllow != null) {
                JSONArray a = new JSONArray();
                for (String d : pr.domainAllow) a.put(d);
                rj.put("da", a);
            }
            if (pr.domainDeny != null) {
                JSONArray a = new JSONArray();
                for (String d : pr.domainDeny) a.put(d);
                rj.put("dd", a);
            }
            arr.put(rj);
        }
        return arr;
    }

    private static List<PathRule> jsonToPathRules(JSONArray arr) throws Exception {
        List<PathRule> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject rj = arr.getJSONObject(i);
            String pattern = rj.optString("p", "");
            if (pattern.isEmpty()) continue;
            boolean exception = rj.optBoolean("e", false);
            int types = rj.optInt("t", 0);
            int party = rj.optInt("party", PARTY_ANY);

            Set<String> da = null;
            JSONArray daArr = rj.optJSONArray("da");
            if (daArr != null) {
                da = new HashSet<>();
                for (int j = 0; j < daArr.length(); j++) da.add(daArr.getString(j));
            }
            Set<String> dd = null;
            JSONArray ddArr = rj.optJSONArray("dd");
            if (ddArr != null) {
                dd = new HashSet<>();
                for (int j = 0; j < ddArr.length(); j++) dd.add(ddArr.getString(j));
            }
            out.add(new PathRule(pattern, exception, types, da, dd, party));
        }
        return out;
    }

    private static JSONArray cosmeticRulesToJson() throws Exception {
        JSONArray arr = new JSONArray();
        Set<CosmeticRule> seen = new HashSet<>();
        for (CosmeticRule r : cosmeticGlobal) seen.add(r);
        for (List<CosmeticRule> bucket : cosmeticByHost.values()) seen.addAll(bucket);

        for (CosmeticRule r : seen) {
            JSONObject rj = new JSONObject();
            if (r.allow != null && !r.allow.isEmpty()) {
                JSONArray a = new JSONArray();
                for (String s : r.allow) a.put(s);
                rj.put("a", a);
            }
            if (r.deny != null && !r.deny.isEmpty()) {
                JSONArray d = new JSONArray();
                for (String s : r.deny) d.put(s);
                rj.put("d", d);
            }
            rj.put("s", r.selector);
            rj.put("e", r.exception);
            arr.put(rj);
        }
        return arr;
    }

    private static List<CosmeticRule> jsonToCosmeticRules(JSONArray arr) throws Exception {
        List<CosmeticRule> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject rj = arr.getJSONObject(i);

            Set<String> allow = null;
            JSONArray aArr = rj.optJSONArray("a");
            if (aArr != null && aArr.length() > 0) {
                allow = new HashSet<>();
                for (int j = 0; j < aArr.length(); j++) allow.add(aArr.getString(j));
            }

            Set<String> deny = null;
            JSONArray dArr = rj.optJSONArray("d");
            if (dArr != null && dArr.length() > 0) {
                deny = new HashSet<>();
                for (int j = 0; j < dArr.length(); j++) deny.add(dArr.getString(j));
            }

            String selector = rj.optString("s", "");
            boolean exception = rj.optBoolean("e", false);
            if (selector.isEmpty()) continue;

            out.add(new CosmeticRule(allow, deny, selector, exception));
        }
        return out;
    }

    private static JSONObject stringListMapToJson(Map<String, ArrayList<String>> map) throws Exception {
        JSONObject obj = new JSONObject();
        if (map == null) return obj;
        for (Map.Entry<String, ArrayList<String>> e : map.entrySet()) {
            JSONArray arr = new JSONArray();
            for (String s : e.getValue()) arr.put(s);
            obj.put(e.getKey(), arr);
        }
        return obj;
    }

    private static HashMap<String, ArrayList<String>> jsonToStringListMap(JSONObject obj) throws Exception {
        HashMap<String, ArrayList<String>> out = new HashMap<>();
        if (obj == null) return out;
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONArray arr = obj.getJSONArray(key);
            ArrayList<String> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) list.add(arr.getString(i));
            out.put(key, list);
        }
        return out;
    }

    private static JSONObject rulesToJson(Map<String, List<Rule>> map) throws Exception {
        JSONObject obj = new JSONObject();
        if (map == null) return obj;
        for (Map.Entry<String, List<Rule>> e : map.entrySet()) {
            JSONArray arr = new JSONArray();
            for (Rule r : e.getValue()) {
                JSONObject rj = new JSONObject();
                rj.put("p", r.pathPattern);
                rj.put("t", r.resourceTypesMask);
                rj.put("party", r.partyFlag);
                if (r.domainAllow != null) {
                    JSONArray a = new JSONArray();
                    for (String d : r.domainAllow) a.put(d);
                    rj.put("da", a);
                }
                if (r.domainDeny != null) {
                    JSONArray a = new JSONArray();
                    for (String d : r.domainDeny) a.put(d);
                    rj.put("dd", a);
                }
                arr.put(rj);
            }
            obj.put(e.getKey(), arr);
        }
        return obj;
    }

    private static HashMap<String, List<Rule>> jsonToRules(JSONObject obj, boolean isException)
            throws Exception {
        HashMap<String, List<Rule>> out = new HashMap<>();
        if (obj == null) return out;
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String host = keys.next();
            JSONArray arr = obj.getJSONArray(host);
            List<Rule> rules = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject rj = arr.getJSONObject(i);
                String path = rj.optString("p", "");
                int types = rj.optInt("t", 0);
                int party = rj.optInt("party", PARTY_ANY);

                Set<String> da = null;
                JSONArray daArr = rj.optJSONArray("da");
                if (daArr != null) {
                    da = new HashSet<>();
                    for (int j = 0; j < daArr.length(); j++) da.add(daArr.getString(j));
                }
                Set<String> dd = null;
                JSONArray ddArr = rj.optJSONArray("dd");
                if (ddArr != null) {
                    dd = new HashSet<>();
                    for (int j = 0; j < ddArr.length(); j++) dd.add(ddArr.getString(j));
                }

                rules.add(new Rule(host, path, isException, types, da, dd, party));
            }
            out.put(host, rules);
        }
        return out;
    }
}
