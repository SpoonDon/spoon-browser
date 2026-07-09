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

    // 2. The O(1) Lock-Free Network Interceptor
    public static boolean shouldBlock(String url) {
        if (url == null || blockedDomains.isEmpty()) return false;
        
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return false;
            
            host = host.toLowerCase();
            if (blockedDomains.contains(host)) return true;
            
            int firstDot = host.indexOf('.');
            if (firstDot > 0 && firstDot < host.length() - 1) {
                if (blockedDomains.contains(host.substring(firstDot + 1))) return true;
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
            HashSet<String> newEngineSet = new HashSet<>();

            for (String filterUrl : filterLists) {
                String filename = "filter_" + Math.abs(filterUrl.hashCode()) + ".txt";
                File localFile = new File(context.getFilesDir(), filename);
                
                try {
                    InputStream inputStream;
                    // If forced refresh, download fresh file. Otherwise, use local if it exists.
                    if (!forceRefresh && localFile.exists()) {
                        inputStream = new FileInputStream(localFile);
                    } else {
                        URLConnection conn = new URL(filterUrl).openConnection();
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(5000);
                        inputStream = conn.getInputStream();
                        
                        // Cache the downloaded file locally for offline boots
                        // (You can optionally write code to save the stream to localFile here)
                    }

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim().toLowerCase();
                            if (!line.isEmpty() && !line.startsWith("!") && !line.startsWith("#")) {
                                newEngineSet.add(line);
                            }
                        }
                    }
                } catch (Exception e) {
                    allDownloadsSucceeded = false;
                }
            }

            // Blazing fast Volatile Pointer Swap
            if (!newEngineSet.isEmpty()) {
                blockedDomains = newEngineSet;
            }

            // Advance the 24-hour rolling schedule if downloads succeeded
            if (allDownloadsSucceeded) {
                prefs.edit().putLong(KEY_REFRESH_TIME, System.currentTimeMillis()).apply();
            }

            isUpdating.set(false);
        });
    }
}
