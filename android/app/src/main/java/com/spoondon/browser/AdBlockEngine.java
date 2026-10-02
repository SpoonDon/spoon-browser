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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AdBlock matching engine — hosts-only mode (v5, 2026-10-02).
 *
 * Rewrite of the v4 rich-rule engine into the flat-domain model used by
 * every DNS/hosts blocker (AdAway, DNS66, Blokada, 1DM). v4 allocated
 * 200-300 MB of Rule/PathRule/CosmeticRule objects on real lists (OISD
 * Basic, EasyList, uBO Filters) and OOM'd on a 512 MB heap. Hosts-only
 * parses 500k+ entries into one HashSet at ~30-40 MB.
 *
 * Keeps: ||host^ blocks, @@||host^ exceptions, bare hosts (OISD,
 * StevenBlack), hosts-file format (0.0.0.0 host), site allowlist, full
 * label-chain matching.
 *
 * Drops: paths, wildcards, $options, resource types, cosmetics.
 * getCosmeticCss is a stub returning "" so SpoonWebViewClient is unchanged.
 */
public class AdBlockEngine {

    // Signature-compat constants used by SpoonWebViewClient.classifyResource.
    public static final int TYPE_DOCUMENT    = 1 << 0;
    public static final int TYPE_SUBDOCUMENT = 1 << 1;
    public static final int TYPE_SCRIPT      = 1 << 2;
    public static final int TYPE_STYLESHEET  = 1 << 3;
    public static final int TYPE_IMAGE       = 1 << 4;
    public static final int TYPE_FONT        = 1 << 5;
    public static final int TYPE_MEDIA       = 1 << 6;
    public static final int TYPE_XHR         = 1 << 7;
    public static final int TYPE_OTHER       = 1 << 8;

    private static volatile Set<String> blockedDomains = new HashSet<>();
    private static volatile Set<String> exceptionDomains = new HashSet<>();
    private static volatile Set<String> whitelistedDomains = new HashSet<>();
    private static volatile boolean isEngineEnabled = true;
    private static volatile int lastSkippedCount = 0;
    private static volatile int lastParsedRuleCount = 0;

    private static final AtomicBoolean isUpdating = new AtomicBoolean(false);
    private static final String PREFS_NAME = "SpoonAdBlockPrefs";
    private static final String KEY_ENABLED = "adblock_enabled";
    private static final String KEY_WHITELIST = "adblock_whitelist";
    private static final String KEY_REFRESH_TIME = "filter_refresh_time";

    private static final int CACHE_VERSION = 5;
    private static final int DECISION_CACHE_MAX = 2000;
    private static final long DECISION_CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final LruCache<String, CacheEntry> decisionCache =
            new LruCache<>(DECISION_CACHE_MAX);

    // ========================================================================
    // Public state
    // ========================================================================

    public static boolean hasRules() {
        return isEngineEnabled && blockedDomains != null && !blockedDomains.isEmpty();
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
        int b = blockedDomains != null ? blockedDomains.size() : 0;
        int e = exceptionDomains != null ? exceptionDomains.size() : 0;
        return b + e;
    }

    public static int getLastSkippedCount() { return lastSkippedCount; }
    public static int getLastParsedRuleCount() { return lastParsedRuleCount; }

    // ========================================================================
    // Site allowlist (Phase 3 — unchanged API)
    // ========================================================================

    @NonNull
    public static List<String> getWhitelistedDomainsSorted() {
        List<String> out = new ArrayList<>(whitelistedDomains);
        Collections.sort(out);
        return out;
    }

    public static int getWhitelistSize() { return whitelistedDomains.size(); }

    public static boolean isHostDirectlyWhitelisted(@Nullable String rawHost) {
        String h = normalizeHost(rawHost);
        return !h.isEmpty() && whitelistedDomains.contains(h);
    }

    public static void addWhitelistedDomain(@NonNull Context context,
                                            @NonNull String rawHost) {
        String h = normalizeHost(rawHost);
        if (h.isEmpty()) return;
        Set<String> updated = new HashSet<>(whitelistedDomains);
        if (!updated.add(h)) return;
        whitelistedDomains = updated;
        persistWhitelist(context, updated);
        decisionCache.evictAll();
    }

    public static void removeWhitelistedDomain(@NonNull Context context,
                                               @NonNull String rawHost) {
        String h = normalizeHost(rawHost);
        if (h.isEmpty()) return;
        Set<String> updated = new HashSet<>(whitelistedDomains);
        if (!updated.remove(h)) return;
        whitelistedDomains = updated;
        persistWhitelist(context, updated);
        decisionCache.evictAll();
    }

    public static void clearWhitelist(@NonNull Context context) {
        if (whitelistedDomains.isEmpty()) return;
        whitelistedDomains = new HashSet<>();
        persistWhitelist(context, whitelistedDomains);
        decisionCache.evictAll();
    }

    private static void persistWhitelist(Context context, Set<String> set) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String h : set) {
            if (!first) sb.append(',');
            sb.append(h);
            first = false;
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_WHITELIST, sb.toString()).apply();
    }

    // ========================================================================
    // Init / refresh
    // ========================================================================

    public static void init(Context context, List<String> filterLists) {
        checkIsEngineEnabled(context);
        loadWhitelistFromPrefs(context);

        if (loadEngineFromCache(context)) return;
        if (filterLists == null || filterLists.isEmpty()) return;

        Builder b = new Builder();
        for (String filterUrl : filterLists) {
            File localFile = new File(context.getFilesDir(), filterFileName(filterUrl));
            if (localFile.exists()) {
                try (InputStream is = new FileInputStream(localFile);
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(is, StandardCharsets.UTF_8), 65536)) {
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

        final List<String> snapshot = new ArrayList<>(filterLists);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        executor.execute(() -> {
            boolean allOk = true;
            Builder b = new Builder();

            for (String filterUrl : snapshot) {
                if (filterUrl == null) { allOk = false; continue; }
                String lower = filterUrl.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("https://")) { allOk = false; continue; }

                File localFile = new File(context.getFilesDir(), filterFileName(filterUrl));

                try {
                    byte[] body;
                    if (!forceRefresh && localFile.exists() && localFile.length() > 0) {
                        body = readFileFully(localFile);
                    } else {
                        body = fetchBytes(filterUrl, 8000, 15000);
                        if (body != null && body.length > 0) {
                            writeAtomically(localFile, body);
                        }
                    }
                    if (body != null && body.length > 0) {
                        parseFilterLines(new BufferedReader(
                                new InputStreamReader(
                                        new ByteArrayInputStream(body),
                                        StandardCharsets.UTF_8), 65536), b);
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

            if (allOk) prefs.edit()
                    .putLong(KEY_REFRESH_TIME, System.currentTimeMillis()).apply();
            isUpdating.set(false);
        });
    }

    public static void removeFilterList(Context context,
                                        String filterUrl,
                                        List<String> remainingLists,
                                        ExecutorService executor) {
        if (filterUrl == null || filterUrl.isEmpty()) return;
        final List<String> snapshot = remainingLists == null
                ? new ArrayList<>() : new ArrayList<>(remainingLists);

        executor.execute(() -> {
            File localFile = new File(context.getFilesDir(), filterFileName(filterUrl));
            if (localFile.exists()) localFile.delete();

            Builder b = new Builder();
            for (String url : snapshot) {
                File lf = new File(context.getFilesDir(), filterFileName(url));
                if (lf.exists()) {
                    try (InputStream is = new FileInputStream(lf);
                         BufferedReader reader = new BufferedReader(
                                 new InputStreamReader(is, StandardCharsets.UTF_8), 65536)) {
                        parseFilterLines(reader, b);
                    } catch (Exception ignored) {}
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
            File[] files = context.getFilesDir().listFiles();
            if (files != null) {
                for (File f : files) {
                    String n = f.getName();
                    if (n.startsWith("filter_") && n.endsWith(".txt")) f.delete();
                }
            }
            lastSkippedCount = 0;
            lastParsedRuleCount = 0;
            applyBuilder(new Builder());
            File cache = new File(context.getFilesDir(), "adblock_cache.json");
            if (cache.exists()) cache.delete();
        });
    }

    // ========================================================================
    // Network decision
    // ========================================================================

    public static boolean shouldBlock(String url) {
        return shouldBlock(url, TYPE_OTHER, null);
    }

    /**
     * Signature preserved for SpoonWebViewClient. resourceType and sourceHost
     * are accepted but ignored — hosts-only mode has no resource-type or
     * party scoping.
     */
    public static boolean shouldBlock(String url, int resourceType, String sourceHost) {
        if (!isEngineEnabled || url == null) return false;
        Set<String> blocks = blockedDomains;
        if (blocks.isEmpty()) return false;

        String key = url.toLowerCase(Locale.ROOT);
        CacheEntry cached = decisionCache.get(key);
        if (cached != null) {
            if (System.currentTimeMillis() - cached.timestampMs <= DECISION_CACHE_TTL_MS) {
                return cached.blocked;
            }
            decisionCache.remove(key);
        }

        boolean blocked = false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host != null && !host.isEmpty()) {
                blocked = matchChain(host.toLowerCase(Locale.ROOT));
            }
        } catch (Exception ignored) {}

        decisionCache.put(key, new CacheEntry(blocked, System.currentTimeMillis()));
        return blocked;
    }

    /** Walks the label chain once per decision; stops before a single-label TLD. */
    private static boolean matchChain(String host) {
        Set<String> blocks = blockedDomains;
        Set<String> exceptions = exceptionDomains;
        Set<String> allowlist = whitelistedDomains;

        if (isIpLiteral(host)) {
            if (inAnyChain(allowlist, host)) return false;
            if (inAnyChain(exceptions, host)) return false;
            return inAnyChain(blocks, host);
        }

        String check = host;
        boolean hasException = false;
        boolean hasBlock = false;
        boolean hasAllow = false;

        while (check != null && check.indexOf('.') != -1) {
            if (!hasAllow && allowlist.contains(check)) hasAllow = true;
            if (!hasException && exceptions.contains(check)) hasException = true;
            if (!hasBlock && blocks.contains(check)) hasBlock = true;
            if (hasAllow) return false;
            if (hasException && hasBlock) break;
            int dot = check.indexOf('.');
            check = check.substring(dot + 1);
        }

        if (hasAllow) return false;
        if (hasException) return false;
        if (hasBlock) return true;
        return false;
    }

    /** Single-level check for IP-literal hosts, which have no label chain. */
    private static boolean inAnyChain(Set<String> set, String host) {
        return set != null && set.contains(host);
    }

    /**
     * Cosmetic CSS is no longer produced. SpoonWebViewClient still calls this
     * — returning "" makes the injection a no-op without changing that file.
     */
    @NonNull
    public static String getCosmeticCss(String url) {
        return "";
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) return false;
        if (host.indexOf(':') != -1) return true;
        int dots = 0;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') { dots++; continue; }
            if (c < '0' || c > '9') return false;
        }
        return dots == 3;
    }

    @NonNull
    private static String normalizeHost(@Nullable String raw) {
        if (raw == null) return "";
        String h = raw.trim().toLowerCase(Locale.ROOT);
        if (h.isEmpty()) return "";
        int scheme = h.indexOf("://");
        if (scheme != -1) h = h.substring(scheme + 3);
        int slash = h.indexOf('/');
        if (slash != -1) h = h.substring(0, slash);
        int colon = h.indexOf(':');
        if (colon != -1) h = h.substring(0, colon);
        return h.trim();
    }

    private static String filterFileName(String filterUrl) {
        return "filter_" + Math.abs(filterUrl.hashCode()) + ".txt";
    }

    private static void loadWhitelistFromPrefs(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_WHITELIST, "");
        Set<String> set = new HashSet<>();
        if (!saved.isEmpty()) {
            for (String d : saved.split(",")) {
                d = d.trim().toLowerCase(Locale.ROOT);
                if (!d.isEmpty()) set.add(d);
            }
        }
        whitelistedDomains = set;
    }

    private static byte[] readFileFully(File f) throws Exception {
        try (InputStream is = new FileInputStream(f);
             ByteArrayOutputStream os = new ByteArrayOutputStream((int) Math.min(f.length(), 8_000_000))) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            return os.toByteArray();
        }
    }

    private static void writeAtomically(File dest, byte[] body) {
        try {
            File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(body);
            }
            if (!tmp.renameTo(dest)) {
                if (dest.exists()) dest.delete();
                tmp.renameTo(dest);
            }
        } catch (Exception ignored) {}
    }

    private static byte[] fetchBytes(String urlStr, int connectMs, int readMs) {
        try {
            URLConnection conn = new URL(urlStr).openConnection();
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestProperty("Accept-Encoding", "identity");
            try (InputStream is = conn.getInputStream();
                 ByteArrayOutputStream os = new ByteArrayOutputStream(4_000_000)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                return os.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ========================================================================
    // Parsing
    // ========================================================================

    private static final class Builder {
        final Set<String> blocks = new HashSet<>(1 << 18);
        final Set<String> exceptions = new HashSet<>(1 << 14);
        int parsed = 0;
        int skipped = 0;

        boolean hasAnything() {
            return !blocks.isEmpty() || !exceptions.isEmpty();
        }
    }

    private static void applyBuilder(Builder b) {
        blockedDomains = b.blocks;
        exceptionDomains = b.exceptions;
        decisionCache.evictAll();
    }

    private static void parseFilterLines(BufferedReader reader, Builder b) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            parseLine(line, b);
        }
    }

    /**
     * Extracts a host from one filter line and adds it to the appropriate
     * set. Accepts:
     *
     *   ||ads.example.com^          ABP-style block
     *   @@||trusted.example.com^    ABP-style exception
     *   ads.example.com             bare host (OISD, AdGuard, EasyList)
     *   0.0.0.0 ads.example.com     hosts-file format
     *   127.0.0.1 ads.example.com   hosts-file format
     *   ::1 ads.example.com         hosts-file format
     *
     * Rejects (counts as skipped): lines with '##' or '#@#' (cosmetic),
     * '$' options, paths after the host, wildcards, and one-word non-hosts.
     */
    private static void parseLine(String raw, Builder b) {
        if (raw == null) return;
        String line = raw.trim();
        if (line.isEmpty()) return;
        char c0 = line.charAt(0);
        if (c0 == '!' || c0 == '#' || c0 == '[') return;

        // Cosmetic / HTML-filter lines — not supported in hosts-only mode.
        if (line.indexOf('#') != -1 && line.indexOf("##") != -1) { b.skipped++; return; }
        if (line.indexOf("#@#") != -1) { b.skipped++; return; }
        if (line.indexOf("#?#") != -1) { b.skipped++; return; }
        if (line.indexOf("#$#") != -1) { b.skipped++; return; }

        // Inline comment stripping (hosts files often have "# comment").
        int hash = line.indexOf('#');
        if (hash > 0) {
            line = line.substring(0, hash).trim();
            if (line.isEmpty()) return;
        }

        boolean exception = false;
        if (line.startsWith("@@")) {
            exception = true;
            line = line.substring(2).trim();
        }

        // Strip ABP option tail. Hosts-only ignores options entirely.
        int dollar = line.indexOf('$');
        if (dollar > 0) line = line.substring(0, dollar);

        // ||host^ form.
        if (line.startsWith("||")) {
            line = line.substring(2);
            int caret = line.indexOf('^');
            if (caret != -1) line = line.substring(0, caret);
            int slash = line.indexOf('/');
            if (slash != -1) line = line.substring(0, slash);
            String host = normalizeHost(line);
            if (isPlausibleHost(host)) addHost(b, exception, host);
            else b.skipped++;
            return;
        }

        // Hosts-file format: "0.0.0.0 host" or "127.0.0.1 host" or "::1 host".
        String[] parts = line.split("\\s+");
        if (parts.length >= 2 && isHostsFileOrigin(parts[0])) {
            String host = normalizeHost(parts[1]);
            if (isPlausibleHost(host)) addHost(b, exception, host);
            else b.skipped++;
            return;
        }
        if (parts.length == 1) {
            // Bare host.
            String host = normalizeHost(parts[0]);
            if (isPlausibleHost(host)) addHost(b, exception, host);
            else b.skipped++;
            return;
        }

        b.skipped++;
    }

    private static void addHost(Builder b, boolean exception, String host) {
        if (exception) b.exceptions.add(host);
        else b.blocks.add(host);
        b.parsed++;
    }

    private static boolean isHostsFileOrigin(String token) {
        if (token == null || token.isEmpty()) return false;
        if (token.equals("::1") || token.equals("::")) return true;
        if (token.equals("0.0.0.0") || token.equals("127.0.0.1")) return true;
        return isIpLiteral(token);
    }

    /** Host must have at least one dot and no wildcard/path leftovers. */
    private static boolean isPlausibleHost(String h) {
        if (h == null || h.length() < 3) return false;
        if (h.indexOf('.') == -1) return false;
        if (h.indexOf('*') != -1) return false;
        if (h.indexOf('/') != -1) return false;
        if (h.indexOf(' ') != -1) return false;
        // Reject reverse DNS lookups like "1.0.0.127.in-addr.arpa".
        if (h.endsWith(".in-addr.arpa")) return false;
        return true;
    }

    // ========================================================================
    // Cache (JSON, version 5)
    // ========================================================================

    private static void saveEngineToCache(Context context) {
        try {
            File cacheFile = new File(context.getFilesDir(), "adblock_cache.json");
            File tmpFile = new File(context.getFilesDir(), "adblock_cache.json.tmp");
            JSONObject root = new JSONObject();
            root.put("version", CACHE_VERSION);

            JSONArray blocksArr = new JSONArray();
            for (String d : blockedDomains) blocksArr.put(d);
            root.put("blocks", blocksArr);

            JSONArray exArr = new JSONArray();
            for (String d : exceptionDomains) exArr.put(d);
            root.put("exceptions", exArr);

            root.put("skipped", lastSkippedCount);
            root.put("parsed", lastParsedRuleCount);

            try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
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
            byte[] body = readFileFully(cacheFile);
            JSONObject root = new JSONObject(new String(body, StandardCharsets.UTF_8));
            if (root.optInt("version", 0) < CACHE_VERSION) return false;

            Set<String> blocks = new HashSet<>(1 << 18);
            JSONArray blocksArr = root.optJSONArray("blocks");
            if (blocksArr != null) {
                for (int i = 0; i < blocksArr.length(); i++) blocks.add(blocksArr.getString(i));
            }

            Set<String> exceptions = new HashSet<>(1 << 14);
            JSONArray exArr = root.optJSONArray("exceptions");
            if (exArr != null) {
                for (int i = 0; i < exArr.length(); i++) exceptions.add(exArr.getString(i));
            }

            blockedDomains = blocks;
            exceptionDomains = exceptions;
            lastSkippedCount = root.optInt("skipped", 0);
            lastParsedRuleCount = root.optInt("parsed", 0);
            decisionCache.evictAll();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static final class CacheEntry {
        final boolean blocked;
        final long timestampMs;
        CacheEntry(boolean blocked, long timestampMs) {
            this.blocked = blocked;
            this.timestampMs = timestampMs;
        }
    }
}
