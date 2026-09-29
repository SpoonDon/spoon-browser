package com.spoondon.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.LruCache;

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
 * Security batch C (2026-09-30): added support for:
 *   - @@ exception rules
 *   - $domain= allow/deny source constraints
 *   - Resource-type options ($script, $image, $stylesheet, ...)
 *   - Party constraints ($third-party, $~third-party)
 *
 * Storage model:
 *   - blockedDomains / scopedPathRules: legacy fast path. Plain
 *     {@code ||domain^} and {@code ||domain/path} rules, O(1) lookup.
 *   - blockRules / exceptionRules: rules that carry ANY option or that
 *     are exceptions. Linear scan within a host bucket, but small.
 *
 * Matching order for a request:
 *   1. User whitelist (from prefs)              -> never block
 *   2. Exception rules (host then parent)       -> never block
 *   3. Block rules (host then parent)           -> block
 *
 * Cache format version bumped to 2. Version 1 (or missing) is treated as
 * a miss — forces a reparse from local filter files.
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

    // ------------------------------------------------------------------------
    // Party constraint codes
    // ------------------------------------------------------------------------
    private static final int PARTY_ANY   = 0;
    private static final int PARTY_THIRD = 1;
    private static final int PARTY_FIRST = 2;

    // ------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------
    private static volatile HashSet<String> blockedDomains = new HashSet<>();
    private static volatile HashMap<String, ArrayList<String>> scopedPathRules = new HashMap<>();

    /** Rules that carry options. Keyed by host (no parent index — callers walk up). */
    private static volatile HashMap<String, List<Rule>> blockRules = new HashMap<>();
    private static volatile HashMap<String, List<Rule>> exceptionRules = new HashMap<>();

    private static volatile HashSet<String> whitelistedDomains = new HashSet<>();
    private static volatile boolean isEngineEnabled = true;
    private static volatile HashMap<String, ArrayList<String>> cosmeticRules = new HashMap<>();

    private static final AtomicBoolean isUpdating = new AtomicBoolean(false);
    private static final String PREFS_NAME = "SpoonAdBlockPrefs";
    private static final String KEY_ENABLED = "adblock_enabled";
    private static final String KEY_WHITELIST = "adblock_whitelist";
    private static final String KEY_REFRESH_TIME = "filter_refresh_time";

    private static final int CACHE_VERSION = 2;

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
                // HTTPS only — filter lists are a network injection vector.
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
    // Decision
    // ========================================================================

    /**
     * Backward-compatible overload. Callers that know the resource type and
     * the top-level page host should use the 3-arg version so that
     * {@code $script}, {@code $domain=}, and {@code $third-party} rules can
     * actually match.
     */
    public static boolean shouldBlock(String url) {
        return shouldBlock(url, TYPE_OTHER, null);
    }

    /**
     * @param resourceType one of the TYPE_* constants
     * @param sourceHost   hostname of the page making the request, or null
     *                     if unknown (top-level navigations)
     */
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

                    // 1. Exceptions win.
                    if (matchesInRules(exceptionRules, host, parentDomain, pathAndQuery,
                            resourceType, sourceHost, thirdParty)) {
                        blocked = false;
                    }
                    // 2. Legacy fast path for plain domain blocks.
                    else if (blockedDomains.contains(host)
                            || (parentDomain != null && blockedDomains.contains(parentDomain))) {
                        blocked = true;
                    }
                    // 3. Legacy path rules.
                    else if (checkPathMatch(host, pathAndQuery)
                            || (parentDomain != null && checkPathMatch(parentDomain, pathAndQuery))) {
                        blocked = true;
                    }
                    // 4. Option-carrying block rules.
                    else if (matchesInRules(blockRules, host, parentDomain, pathAndQuery,
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
    // Cosmetic rules
    // ========================================================================

    public static String getCosmeticCss(String url) {
        if (!isEngineEnabled || url == null) return "";
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return "";
            host = host.toLowerCase(Locale.ROOT);

            ArrayList<String> selectors = new ArrayList<>();
            if (cosmeticRules.containsKey(host)) {
                selectors.addAll(cosmeticRules.get(host));
            }
            String parent = parentOf(host);
            if (parent != null && cosmeticRules.containsKey(parent)) {
                selectors.addAll(cosmeticRules.get(parent));
            }

            if (selectors.isEmpty()) return "";
            return android.text.TextUtils.join(", ", selectors) + " { display: none !important; }";
        } catch (Exception e) {
            return "";
        }
    }

    // ========================================================================
    // Rule matching internals
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
        final HashMap<String, ArrayList<String>> cosmetic = new HashMap<>();

        boolean hasAnything() {
            return !domains.isEmpty() || !paths.isEmpty()
                    || !blockRules.isEmpty() || !exceptionRules.isEmpty()
                    || !cosmetic.isEmpty();
        }
    }

    private static void applyBuilder(Builder b) {
        blockedDomains = b.domains;
        scopedPathRules = b.paths;
        blockRules = b.blockRules;
        exceptionRules = b.exceptionRules;
        cosmeticRules = b.cosmetic;
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
        // ## (element hide) — keep current behavior. #@# (exception) still
        // ignored for now; separate slice.
        if (!line.startsWith("@@") && line.contains("##") && !line.contains("#@#")) {
            int splitIdx = line.indexOf("##");
            String domainPart = line.substring(0, splitIdx).trim();
            String selector = line.substring(splitIdx + 2).trim();
            if (!domainPart.isEmpty() && !selector.isEmpty() && !domainPart.contains("*")) {
                for (String d : domainPart.split(",")) {
                    d = d.trim().toLowerCase(Locale.ROOT);
                    if (d.isEmpty() || d.startsWith("~")) continue;
                    b.cosmetic.computeIfAbsent(d, k -> new ArrayList<>()).add(selector);
                }
            }
            return;
        }

        // --- Exception prefix ----------------------------------------------
        boolean exception = false;
        if (line.startsWith("@@")) {
            exception = true;
            line = line.substring(2).trim();
        }

        // --- Split pattern from options ------------------------------------
        String pattern = line;
        String options = null;
        int dollar = line.lastIndexOf('$');
        if (dollar > 0) {
            pattern = line.substring(0, dollar);
            options = line.substring(dollar + 1);
        }

        // --- Only handle domain-anchored rules for now ---------------------
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

        // Strip wildcards from the path — substring match still works.
        if (!pathPattern.isEmpty()) {
            pathPattern = pathPattern.replace("*", "");
        }

        // --- Parse options --------------------------------------------------
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

        // --- Fast path: no options, not an exception -----------------------
        if (!hasOptions && !exception) {
            if (pathPattern.isEmpty()) {
                b.domains.add(host);
            } else {
                b.paths.computeIfAbsent(host, k -> new ArrayList<>()).add(pathPattern);
            }
            return;
        }

        // --- Rich rule -----------------------------------------------------
        Rule rule = new Rule(host, pathPattern, exception, resourceTypesMask,
                domainAllow, domainDeny, partyFlag);

        HashMap<String, List<Rule>> target = exception ? b.exceptionRules : b.blockRules;
        target.computeIfAbsent(host, k -> new ArrayList<>()).add(rule);
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
    // Rule
    // ========================================================================

    public static final class Rule {
        final String host;
        final String pathPattern;
        final boolean exception;
        final int resourceTypesMask;
        final Set<String> domainAllow;   // nullable; empty means "any source"
        final Set<String> domainDeny;    // nullable
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
    // Persistence (JSON, version 2)
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
            root.put("cosmetic", stringListMapToJson(cosmeticRules));

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

            // Version gate — v1 caches are treated as a miss.
            if (root.optInt("version", 0) < CACHE_VERSION) return false;

            HashSet<String> newDomains = new HashSet<>();
            JSONArray domainsArr = root.optJSONArray("domains");
            if (domainsArr != null) {
                for (int i = 0; i < domainsArr.length(); i++) newDomains.add(domainsArr.getString(i));
            }
            blockedDomains = newDomains;

            scopedPathRules = jsonToStringListMap(root.optJSONObject("paths"));
            blockRules = jsonToRules(root.optJSONObject("blockRules"));
            exceptionRules = jsonToRules(root.optJSONObject("exceptionRules"));
            cosmeticRules = jsonToStringListMap(root.optJSONObject("cosmetic"));

            decisionCache.evictAll();
            return true;
        } catch (Exception e) {
            return false;
        }
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

    private static HashMap<String, List<Rule>> jsonToRules(JSONObject obj) throws Exception {
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

                // exception flag is implied by which map the rule lives in
                boolean isException = (obj == null) ? false : false;
                rules.add(new Rule(host, path, false, types, da, dd, party));
            }
            out.put(host, rules);
        }
        return out;
    }
}
