package com.spoondon.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public class AdBlockEngine {
    private static volatile HashSet<String> blockedDomains = new HashSet<>();
    private static volatile HashSet<String> domainRules = new HashSet<>();
    private static volatile java.util.ArrayList<String> pathRules = new java.util.ArrayList<>();
    private static volatile HashSet<String> whitelistedDomains = new HashSet<>();
    private static volatile boolean isEngineEnabled = true;
    private static final AtomicBoolean isUpdating = new AtomicBoolean(false);
    
    private static final String PREFS_NAME = "SpoonAdBlockPrefs";
    private static final String KEY_REFRESH_TIME = "filter_refresh_time";
    private static final long TWENTY_FOUR_HOURS = 24 * 60 * 60 * 1000L;

    // 1. Instantly loads all locally saved/cached rules into RAM on startup
    public static void init(Context context, List<String> filterLists) {
        if (filterLists == null || filterLists.isEmpty()) return;
        
        HashSet<String> initialSet = new HashSet<>();
        for (String filterUrl : filterLists) {
            String filename = "filter_" + Math.abs(filterUrl.hashCode()) + ".txt";
            File localFile = new File(context.getFilesDir(), filename);
            
            if (localFile.exists()) {
                try (InputStream is = new FileInputStream(localFile);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim().toLowerCase();
                        if (!line.isEmpty() && !line.startsWith("!") && !line.startsWith("#")) {
                            initialSet.add(line);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        blockedDomains = initialSet;
    }

    // 2. The Modern Lightning Interceptor (Hybrid Domain & Path Matcher)
    public static boolean shouldBlock(String url) {
        if (!isEngineEnabled || url == null) return false;
        
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return false;
            
            host = host.toLowerCase();
            
            // 1. Whitelist Check
            if (whitelistedDomains.contains(host)) return false;
            
            // 2. Exact Domain Check (Lightning Fast)
            if (domainRules.contains(host)) return true;
            
            // 3. Parent Domain Check (e.g., strips "ads.google.com" to "google.com")
            int firstDot = host.indexOf('.');
            int lastDot = host.lastIndexOf('.');
            if (firstDot > 0 && firstDot != lastDot) {
                String parentDomain = host.substring(firstDot + 1);
                if (whitelistedDomains.contains(parentDomain)) return false;
                if (domainRules.contains(parentDomain)) return true;
            }
            
            // 4. Complex EasyList Path Check (Lightning Browser Fallback)
            String lowerUrl = url.toLowerCase();
            for (int i = 0; i < pathRules.size(); i++) {
                if (lowerUrl.contains(pathRules.get(i))) {
                    return true;
                }
            }
            
        } catch (Exception ignored) {}
        
        return false;
    }
    
    // 3. Handles both Manual Updates (from save) and 24-Hour Auto-Updates
    public static void checkAndRefreshFilters(Context context, ExecutorService executor, List<String> filterLists, boolean forceRefresh) {
        if (filterLists == null || filterLists.isEmpty()) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastRefresh = prefs.getLong(KEY_REFRESH_TIME, 0);
        long currentTime = System.currentTimeMillis();

        // Only run if forced (user clicked save) OR if 24 hours have passed
        if (!forceRefresh && (currentTime - lastRefresh < TWENTY_FOUR_HOURS)) {
            return; 
        }

        // Concurrency Guard: Drop duplicate triggers if a sync is already running
        if (!isUpdating.compareAndSet(false, true)) {
            return;
        }

        executor.execute(() -> {
            boolean allDownloadsSucceeded = true;
            
            // 1. Create temporary holding lists
            HashSet<String> newDomainRules = new HashSet<>();
            java.util.ArrayList<String> newPathRules = new java.util.ArrayList<>();

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

                    // 2. The Parser
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim().toLowerCase();
                            
                            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[") || 
                                line.contains("##") || line.startsWith("@@")) {
                                continue;
                            }

                            if (line.startsWith("||")) line = line.substring(2);
                            
                            int flagIndex = line.indexOf('^');
                            if (flagIndex != -1) line = line.substring(0, flagIndex);
                            
                            flagIndex = line.indexOf('$');
                            if (flagIndex != -1) line = line.substring(0, flagIndex);

                            if (line.isEmpty()) continue;

                            if (line.contains("/") || line.contains("*") || line.contains("?")) {
                                newPathRules.add(line);
                            } else {
                                newDomainRules.add(line);
                            }
                        }
                    }
                } catch (Exception e) {
                    allDownloadsSucceeded = false;
                }
            }

            // 3. The Atomic Pointer Swap
            // This takes the temporary lists we just built and instantly makes them live
            if (!newDomainRules.isEmpty() || !newPathRules.isEmpty()) {
                domainRules = newDomainRules;
                pathRules = newPathRules;
            }

            if (allDownloadsSucceeded) {
                prefs.edit().putLong(KEY_REFRESH_TIME, System.currentTimeMillis()).apply();
            }

            isUpdating.set(false);
        });
    }

    public static int getBlocklistSize() {
        return (domainRules != null ? domainRules.size() : 0) + (pathRules != null ? pathRules.size() : 0);
    }
}
