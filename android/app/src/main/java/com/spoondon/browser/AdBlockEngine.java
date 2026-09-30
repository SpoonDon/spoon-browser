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
 * Security batch C (2026-09-30): network filter exceptions, $domain=,
 * resource types, party constraints.
 *
 * Backlog item #4 (2026-09-30): cosmetic rules rewritten to a three-tier
 * model that supports "~" negation on both ## (hide) and #@# (exception)
 * rules. Rules are stored as CosmeticRule objects with allow/deny host
 * sets instead of the old two-map host → [selectors] scheme. Cache version
 * bumped to 3; older caches are treated as a miss.
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

    // ------------------------------------------------------------------------
    // Cosmetic-rule state (backlog #4)
    // ------------------------------------------------------------------------
    /** Rules with no allow-list — apply to every host (subject to deny). */
    private static volatile List<CosmeticRule> cosmeticGlobal = new ArrayList<>();
    /** Rules with an allow-list — indexed by each host in the allow set. */
    private static volatile Map<String, List<CosmeticRule>> cosmeticByHost = new HashMap<>();

    private static volatile HashSet<String> whitelistedDomains = new HashSet<>();
    private static volatile boolean isEngineEnabled = true;

    private static final AtomicBoolean isUpdating = new AtomicBoolean(false);
    private static final String PREFS_NAME = "SpoonAdBlockPrefs";
    private static final String KEY_ENABLED = "adblock_enabled";
    private static final String KEY_WHITELIST = "adblock_whitelist";
    private static final String KEY_REFRESH_TIME = "filter_refresh_time";

    private static final int CACHE_VERSION = 3;

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
                 (blockRules != null && !blockRules.isEmpty()));
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
        return total;
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
            applyBuilder(new Builder());

            File cacheFile = new File(context.getFilesDir(), "adblock_cache.json");
            if (cacheFile.exists()) cacheFile.delete();
            File oldCacheFile = new File(context.getFilesDir(), "adblock_bin_cache.dat");
            if (oldCacheFile.exists()) oldCacheFile.delete();
        });
    }

    // ========================================================================
    // Network decision
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
                String parentDomain = parentOf(host);

                if (whitelistedDomains.contains(host)
                        || (parentDomain != null && whitelistedDomains.contains(parentDomain))) {
                    blocked = false;
                } else {
                    String pathAndQuery = buildPathAndQuery(uri);
                    boolean thirdParty = sourceHost != null
                            && !sameSite(host, sourceHost.toLowerCase(Locale.ROOT));

                    if (matchesInRules(exceptionRules, host, parentDomain, pathAndQuery,
                            resourceType, sourceHost, thirdParty)) {
                        blocked = false;
                    } else if (blockedDomains.contains(host)
                            || (parentDomain != null && blockedDomains.contains(parentDomain))) {
                        blocked = true;
                    } else if (checkPathMatch(host, pathAndQuery)
                            || (parentDomain != null && checkPathMatch(parentDomain, pathAndQuery))) {
                        blocked = true;
                    } else if (matchesInRules(blockRules, host, parentDomain, pathAndQuery,
                            resourceType, sourceHost, thirdParty)) {
                        blocked = true;
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
            String parent = parentOf(host);

            Set<String> hidden = new HashSet<>();
            Set<String> excepted = new HashSet<>();

            // Global rules (no allow-list) — apply everywhere, subject to deny.
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

            // Host-scoped rules — check both this host and its parent bucket.
            Map<String, List<CosmeticRule>> index = cosmeticByHost;
            if (index != null && !index.isEmpty()) {
                collectHostRules(index.get(host), host, parent, hidden, excepted);
                if (parent != null) {
                    collectHostRules(index.get(parent), host, parent, hidden, excepted);
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

    /**
     * A single cosmetic rule. Both the "hide" (##) and "exception" (#@#)
     * forms share this structure.
     *
     * allow: host set the rule applies to. Empty means "every host".
     * deny:  host set the rule is suppressed on, evaluated after allow.
     */
    public static final class CosmeticRule {
        final Set<String> allow;   // nullable; empty means global
        final Set<String> deny;    // nullable
        final String selector;
        final boolean exception;   // true = #@#

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
                                          String host,
                                          String parentDomain,
                                          String pathAndQuery,
                                          int resourceType,
                                          String sourceHost,
                                          boolean thirdParty) {
        if (index == null || index.isEmpty()) return false;

        List<Rule> atHost = index.get(host);
        if (atHost != null) {
            for (int i = 0; i < atHost.size(); i++) {
                if (atHost.get(i).matches(pathAndQuery, resourceType, sourceHost, thirdParty)) {
                    return true;
                }
            }
        }
        if (parentDomain != null) {
            List<Rule> atParent = index.get(parentDomain);
            if (atParent != null) {
                for (int i = 0; i < atParent.size(); i++) {
                    if (atParent.get(i).matches(pathAndQuery, resourceType, sourceHost, thirdParty)) {
                        return true;
                    }
                }
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

    private static String parentOf(String host) {
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
        final List<CosmeticRule> cosmeticRules = new ArrayList<>();

        boolean hasAnything() {
            return !domains.isEmpty() || !paths.isEmpty()
                    || !blockRules.isEmpty() || !exceptionRules.isEmpty()
                    || !cosmeticRules.isEmpty();
        }
    }

    private static void applyBuilder(Builder b) {
        blockedDomains = b.domains;
        scopedPathRules = b.paths;
        blockRules = b.blockRules;
        exceptionRules = b.exceptionRules;

        // Re-bucket cosmetic rules into global + host-indexed views.
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

    private static void parseLine(String raw, Builder b) {
        if (raw == null) return;
        String line = raw.trim();
        if (line.isEmpty()) return;
        if (line.startsWith("!") || line.startsWith("[")) return;

        // --- Cosmetic rules ------------------------------------------------
        // #@# (exception) is checked first because it is longer and more
        // specific; #@# contains ##, so ordering matters.
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

        String pattern = line;
        String options = null;
        int dollar = line.lastIndexOf('$');
        if (dollar > 0) {
            pattern = line.substring(0, dollar);
            options = line.substring(dollar + 1);
        }

        if (!pattern.startsWith("||")) return;

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
        if (host.isEmpty() || host.contains("*") || !host.contains(".")) return;

        if (!pathPattern.isEmpty()) {
            pathPattern = pathPattern.replace("*", "");
        }

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
                    partyFlag = PARTY_THIRD;
                    hasOptions = true;
                    continue;
                }
                if (opt.equals("~third-party")) {
                    partyFlag = PARTY_FIRST;
                    hasOptions = true;
                    continue;
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
                    continue;
                }

                int t = resourceTypeFromName(opt);
                if (t != 0) {
                    resourceTypesMask |= t;
                    hasOptions = true;
                }
            }
        }

        if (!hasOptions && !exception) {
            if (pathPattern.isEmpty()) {
                b.domains.add(host);
            } else {
                b.paths.computeIfAbsent(host, k -> new ArrayList<>()).add(pathPattern);
            }
            return;
        }

        Rule rule = new Rule(host, pathPattern, exception, resourceTypesMask,
                domainAllow, domainDeny, partyFlag);

        HashMap<String, List<Rule>> target = exception ? b.exceptionRules : b.blockRules;
        target.computeIfAbsent(host, k -> new ArrayList<>()).add(rule);
    }

    /**
     * Parses a cosmetic rule (## or #@#).
     *
     * Grammar (uBlock-compatible):
     *   [domain[,domain]...]  ## selector
     *   [domain[,domain]...]  #@# selector
     *
     * Each domain entry is either a plain host (allow) or a host prefixed
     * with "~" (deny). Mixing is allowed:
     *   example.com,~sub.example.com##.ad
     *
     * If allow is empty after parsing, the rule is global (subject to deny).
     * If allow is non-empty, the rule only applies where at least one allow
     * entry matches and no deny entry matches.
     */
    private static void parseCosmetic(String line, int markerIdx, int markerLen,
                                      boolean exception, Builder b) {
        String domainPart = line.substring(0, markerIdx).trim();
        String selector = line.substring(markerIdx + markerLen).trim();

        if (selector.isEmpty()) return;

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

        if (allow.isEmpty() && deny.isEmpty()) {
            // Global hide rule with no domain qualifiers — keep it but only
            // if it is a hide rule. A bare #@# with no domain qualifier is
            // a no-op (there is nothing to except); we still store it so
            // that the semantics are consistent, but it will never match
            // anything unique.
        }

        b.cosmeticRules.add(new CosmeticRule(
                allow.isEmpty() ? null : allow,
                deny.isEmpty() ? null : deny,
                selector,
                exception));
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
    // Network rule
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
            if (!pathPattern.isEmpty() && !path.contains(pathPattern)) return false;
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

        private static boolean hostMatchesSuffix(String host, String suffix) {
            if (host == null || suffix == null) return false;
            if (host.equals(suffix)) return true;
            return host.endsWith("." + suffix);
        }
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
    // Persistence (JSON, version 3)
    // ========================================================================

    private static void saveEngineToCache(Context context) {
        try {
            File cacheFile = new File(context.getFilesDir(), "adblock_cache.json");
            JSONObject root = new JSONObject();
            root.put("version", CACHE_VERSION);

            JSONArray domainsArr = new JSONArray();
            for (String d : blockedDomains) domainsArr.put(d);
            root.put("domains", domainsArr);

            root.put("paths", stringListMapToJson(scopedPathRules));
            root.put("blockRules", rulesToJson(blockRules));
            root.put("exceptionRules", rulesToJson(exceptionRules));
            root.put("cosmetic", cosmeticRulesToJson());

            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
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

            decisionCache.evictAll();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static JSONArray cosmeticRulesToJson() throws Exception {
        JSONArray arr = new JSONArray();
        // Serialize from the union of global + host-indexed rules. Since a
        // rule with allow={a,b} appears in two buckets, we dedupe by object
        // identity using a Set.
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
