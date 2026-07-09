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
    private static volatile HashSet<String> whitelistedDomains = new HashSet<>();
    private static volatile boolean isEngineEnabled = true;
    
    private static final AtomicBoolean isUpdating = new AtomicBoolean(false);
    private static final String PREFS_NAME = "SpoonAdBlockPrefs";
    private static final String KEY_ENABLED = "adblock_enabled";
    private static final String KEY_WHITELIST = "adblock_whitelist";
    private static final String KEY_REFRESH_TIME = "filter_refresh_time";

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
                        if (line.startsWith("||")) {
                            line = line.substring(2);
                            int cutIndex = line.indexOf('^');
                            if (cutIndex != -1) line = line.substring(0, cutIndex);
                            cutIndex = line.indexOf('/');
                            if (cutIndex != -1) line = line.substring(0, cutIndex);
                            
                            if (!line.isEmpty() && line.contains(".") && !line.contains("*")) {
                                initialSet.add(line);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        blockedDomains = initialSet;
    }

    public static boolean shouldBlock(String url) {
        if (!isEngineEnabled || url == null || blockedDomains.isEmpty()) return false;
        
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return false;
            
            host = host.toLowerCase();
            
            if (whitelistedDomains.contains(host)) return false;
            if (blockedDomains.contains(host)) return true;
            
            int firstDot = host.indexOf('.');
            int lastDot = host.lastIndexOf('.');
            if (firstDot > 0 && firstDot != lastDot) {
                String parentDomain = host.substring(firstDot + 1);
                if (whitelistedDomains.contains(parentDomain)) return false;
                if (blockedDomains.contains(parentDomain)) return true;
            }
        } catch (Exception ignored) {}
        
        return false;
    }

    public static void checkAndRefreshFilters(Context context, ExecutorService executor, List<String> filterLists, boolean forceRefresh) {
        if (filterLists == null || filterLists.isEmpty()) return;
        if (!isUpdating.compareAndSet(false, true)) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        executor.execute(() -> {
            boolean allDownloadsSucceeded = true;
            HashSet<String> newEngineSet = new HashSet<>();

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
                                
                                cutIndex = line.indexOf('/');
                                if (cutIndex != -1) line = line.substring(0, cutIndex);

                                if (!line.isEmpty() && line.contains(".") && !line.contains("*")) {
                                    newEngineSet.add(line);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    allDownloadsSucceeded = false;
                }
            }

            if (!newEngineSet.isEmpty()) {
                blockedDomains = newEngineSet;
            }

            if (allDownloadsSucceeded) {
                prefs.edit().putLong(KEY_REFRESH_TIME, System.currentTimeMillis()).apply();
            }

            isUpdating.set(false);
        });
    }

    public static int getBlocklistSize() {
        return blockedDomains != null ? blockedDomains.size() : 0;
    }
}
