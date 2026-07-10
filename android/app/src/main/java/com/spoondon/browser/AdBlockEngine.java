package com.spoondon.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.LruCache;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public class AdBlockEngine {
    private static volatile HashSet<String> blockedDomains = new HashSet<>();
    private static volatile HashMap<String, ArrayList<String>> scopedPathRules = new HashMap<>();
    private static volatile HashSet<String> whitelistedDomains = new HashSet<>();
    private static volatile boolean isEngineEnabled = true;
    
    private static final AtomicBoolean isUpdating = new AtomicBoolean(false);
    private static final String PREFS_NAME = "SpoonAdBlockPrefs";
    private static final String KEY_ENABLED = "adblock_enabled";
    private static final String KEY_WHITELIST = "adblock_whitelist";
    private static final String KEY_REFRESH_TIME = "filter_refresh_time";

    // QUICK WIN: small LRU cache for decisions to avoid re-parsing the same URL repeatedly.
    // Uses android.util.LruCache for simpler memory control and eviction.
    // Added lightweight TTL wrapper: entries older than DECISION_CACHE_TTL_MS are treated as stale.
    private static final int DECISION_CACHE_MAX = 2000;
    // TTL for cache entries (10 minutes)
    private static final long DECISION_CACHE_TTL_MS = 10 * 60 * 1000L;

    private static final LruCache<String, CacheEntry> decisionCache = new LruCache<>(DECISION_CACHE_MAX);

    // cache entry holds the decision and the timestamp when it was inserted
    private static final class CacheEntry {
        final boolean blocked;
        final long timestampMs;
        CacheEntry(boolean blocked, long timestampMs) {
            this.blocked = blocked;
            this.timestampMs = timestampMs;
        }
    }

    public static void init(Context context, List<String> filterLists) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        isEngineEnabled = prefs.getBoolean(KEY_ENABLED, true);
        
        String savedWhitelist = prefs.getString(KEY_WHITELIST, "");
        HashSet<String> whiteSet = new HashSet<>();
        if (!savedWhitelist.isEmpty()) {
            for (String domain : savedWhitelist.split(",")) {
                whiteSet.add(domain.trim().toLowerCase());
            }
        }
        whitelistedDomains = whiteSet;

        if (filterLists == null || filterLists.isEmpty()) return;
        HashSet<String> initialDomains = new HashSet<>();
        HashMap<String, ArrayList<String>> initialPaths = new HashMap<>();

        for (String filterUrl : filterLists) {
            String filename = "filter_" + Math.abs(filterUrl.hashCode()) + ".txt";
            File localFile = new File(context.getFilesDir(), filename);
            if (localFile.exists()) {
                try (InputStream is = new FileInputStream(localFile);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                    parseFilterLines(reader, initialDomains, initialPaths);
                } catch (Exception ignored) {}
            }
        }
        blockedDomains = initialDomains;
        scopedPathRules = initialPaths;

        // clear decision cache on init to avoid using stale decisions from previous runs
        synchronized (decisionCache) {
            decisionCache.evictAll();
        }
    }

    public static boolean shouldBlock(String url) {
        if (!isEngineEnabled || url == null) return false;

        String key = url.toLowerCase();

        // Check TTL-aware cache
        synchronized (decisionCache) {
            CacheEntry cached = decisionCache.get(key);
            if (cached != null) {
                if (System.currentTimeMillis() - cached.timestampMs <= DECISION_CACHE_TTL_MS) {
                    return cached.blocked;
                } else {
                    // stale entry -> remove
                    decisionCache.remove(key);
                }
            }
        }

        boolean blocked = false;

        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) {
                blocked = false;
            } else {
                host = host.toLowerCase();

                if (whitelistedDomains.contains(host)) {
                    blocked = false;
                } else if (blockedDomains.contains(host)) {
                    blocked = true;
                } else {
                    // 2. Check Parent Domain Block
                    int firstDot = host.indexOf('.');
                    int lastDot = host.lastIndexOf('.');
                    String parentDomain = null;
                    if (firstDot > 0 && firstDot != lastDot) {
                        parentDomain = host.substring(firstDot + 1);
                        if (whitelistedDomains.contains(parentDomain)) {
                            blocked = false;
                        } else if (blockedDomains.contains(parentDomain)) {
                            blocked = true;
                        }
                    }

                    // 3. High-Performance Scoped Path Evaluation
                    if (!blocked) {
                        String pathAndQuery = uri.getPath();
                        if (pathAndQuery == null) pathAndQuery = "";
                        if (uri.getQuery() != null) {
                            pathAndQuery += "?" + uri.getQuery();
                        }
                        pathAndQuery = pathAndQuery.toLowerCase();

                        if (!pathAndQuery.isEmpty()) {
                            // Check specific host rules
                            if (checkPathMatch(host, pathAndQuery)) {
                                blocked = true;
                            }

                            // Check parent domain host rules if applicable
                            if (!blocked && parentDomain != null && checkPathMatch(parentDomain, pathAndQuery)) {
                                blocked = true;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            blocked = false;
        }

        // Store decision with timestamp
        synchronized (decisionCache) {
            decisionCache.put(key, new CacheEntry(blocked, System.currentTimeMillis()));
        }

        return blocked;
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

    public static void checkAndRefreshFilters(Context context, ExecutorService executor, List<String> filterLists, boolean forceRefresh) {
        if (filterLists == null || filterLists.isEmpty()) return;
        if (!isUpdating.compareAndSet(false, true)) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        executor.execute(() -> {
            boolean allDownloadsSucceeded = true;
            HashSet<String> newDomains = new HashSet<>();
            HashMap<String, ArrayList<String>> newPaths = new HashMap<>();

            for (String filterUrl : filterLists) {
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

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                        parseFilterLines(reader, newDomains, newPaths);
                    }
                } catch (Exception e) {
                    allDownloadsSucceeded = false;
                }
            }

            if (!newDomains.isEmpty() || !newPaths.isEmpty()) {
                blockedDomains = newDomains;
                scopedPathRules = newPaths;

                // clear cache when rules change so we don't rely on stale decisions
                synchronized (decisionCache) {
                    decisionCache.evictAll();
                }
            }

            if (allDownloadsSucceeded) {
                prefs.edit().putLong(KEY_REFRESH_TIME, System.currentTimeMillis()).apply();
            }

            isUpdating.set(false);
        });
    }

    /**
     * Remove a specific filter list and clean up its cached rules.
     * This fixes the "ghost rules" bug where disabled lists remained active.
     * 
     * @param context Android context for file access
     * @param filterUrl URL of the filter list to remove
     * @param executor Executor for background cleanup
     */
    public static void removeFilterList(Context context, String filterUrl, ExecutorService executor) {
        if (filterUrl == null || filterUrl.isEmpty()) return;

        // Run cleanup in background to avoid blocking UI
        executor.execute(() -> {
            // Delete the cached filter file
            String filename = "filter_" + Math.abs(filterUrl.hashCode()) + ".txt";
            File localFile = new File(context.getFilesDir(), filename);
            if (localFile.exists()) {
                boolean deleted = localFile.delete();
                if (!deleted) {
                    android.util.Log.w("AdBlockEngine", "Failed to delete filter file: " + filename);
                }
            }

            // Clear decision cache so we don't use stale blocking decisions
            synchronized (decisionCache) {
                decisionCache.evictAll();
            }
        });
    }

    /**
     * Clear all filter lists completely.
     * Deletes all cached files and resets in-memory rules.
     * 
     * @param context Android context for file access
     * @param executor Executor for background cleanup
     */
    public static void clearAllFilterLists(Context context, ExecutorService executor) {
        executor.execute(() -> {
            // Delete all cached filter files
            File filesDir = context.getFilesDir();
            File[] files = filesDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.getName().startsWith("filter_") && file.getName().endsWith(".txt")) {
                        boolean deleted = file.delete();
                        if (!deleted) {
                            android.util.Log.w("AdBlockEngine", "Failed to delete: " + file.getName());
                        }
                    }
                }
            }

            // Clear all in-memory rules
            blockedDomains = new HashSet<>();
            scopedPathRules = new HashMap<>();

            // Clear decision cache
            synchronized (decisionCache) {
                decisionCache.evictAll();
            }
        });
    }

    private static void parseFilterLines(BufferedReader reader, HashSet<String> domainSet, HashMap<String, ArrayList<String>> pathMap) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim().toLowerCase();
            
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[") || 
                line.contains("##") || line.startsWith("@@")) {
                continue;
            }

            if (line.startsWith("||")) {
                line = line.substring(2);
                
                int cutIndex = line.indexOf('^');
                if (cutIndex != -1) line = line.substring(0, cutIndex);
                
                cutIndex = line.indexOf('$');
                if (cutIndex != -1) line = line.substring(0, cutIndex);

                if (line.isEmpty()) continue;

                int slashIndex = line.indexOf('/');
                if (slashIndex != -1) {
                    String host = line.substring(0, slashIndex).trim();
                    String pathRule = line.substring(slashIndex).trim();
                    
                    if (!host.isEmpty() && !pathRule.isEmpty() && host.contains(".") && !host.contains("*")) {
                        if (!pathMap.containsKey(host)) {
                            pathMap.put(host, new ArrayList<>());
                        }
                        pathMap.get(host).add(pathRule);
                    }
                } else {
                    if (line.contains(".") && !line.contains("*")) {
                        domainSet.add(line);
                    }
                }
            }
        }
    }

    public static int getBlocklistSize() {
        int totalRules = (blockedDomains != null ? blockedDomains.size() : 0);
        if (scopedPathRules != null) {
            for (ArrayList<String> list : scopedPathRules.values()) {
                totalRules += list.size();
            }
        }
        return totalRules;
    }
}
