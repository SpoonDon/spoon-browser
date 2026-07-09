package com.spoondon.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;

public class AdBlockEngine {
    // Volatile guarantees instant, lock-free updates across all threads
    private static volatile HashSet<String> blockedDomains = new HashSet<>();
    
    private static final String PREFS_NAME = "SpoonAdBlockPrefs";
    private static final String KEY_DOMAINS = "cached_blocklist";
    
    // YOUR AUTO-UPDATE LINK: Point this to a raw .txt file on your GitHub
    private static final String GITHUB_RAW_URL = "https://raw.githubusercontent.com/SpoonDon/spoon-browser/main/blocklist.txt";

    // Emergency offline fallback domains
    private static final String[] FALLBACK_LIST = {
        "doubleclick.net", "googleadservices.com", "googlesyndication.com",
        "adservice.google.com", "analytics.google.com", "taboola.com", "outbrain.com"
    };

    // 1. Instantly loads the fastest local cache into RAM on startup
    public static void init(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String cached = prefs.getString(KEY_DOMAINS, "");
        
        HashSet<String> initialSet = new HashSet<>();
        if (!cached.isEmpty()) {
            initialSet.addAll(Arrays.asList(cached.split(",")));
        } else {
            initialSet.addAll(Arrays.asList(FALLBACK_LIST));
        }
        blockedDomains = initialSet;
    }

    // 2. The O(1) Interceptor (Zero CPU lag)
    public static boolean shouldBlock(String url) {
        if (url == null || blockedDomains.isEmpty()) return false;
        
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return false;
            
            host = host.toLowerCase();
            
            // Check exact match
            if (blockedDomains.contains(host)) return true;
            
            // Check parent domain (e.g., strips "ads." to check "google.com")
            int firstDot = host.indexOf('.');
            if (firstDot > 0 && firstDot < host.length() - 1) {
                if (blockedDomains.contains(host.substring(firstDot + 1))) return true;
            }
        } catch (Exception ignored) {}
        
        return false;
    }

    // 3. The Auto-Updater (Runs silently in the background)
    public static void syncWithGitHub(Context context, ExecutorService executor) {
        executor.execute(() -> {
            try {
                URL url = new URL(GITHUB_RAW_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    HashSet<String> newDomains = new HashSet<>();
                    StringBuilder cacheBuilder = new StringBuilder();
                    String line;
                    
                    while ((line = reader.readLine()) != null) {
                        String domain = line.trim().toLowerCase();
                        if (!domain.isEmpty() && !domain.startsWith("#") && !domain.startsWith("!")) {
                            newDomains.add(domain);
                            cacheBuilder.append(domain).append(",");
                        }
                    }
                    reader.close();
                    
                    // Instant volatile pointer swap (O(1) update)
                    if (!newDomains.isEmpty()) {
                        blockedDomains = newDomains;
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                               .edit().putString(KEY_DOMAINS, cacheBuilder.toString()).apply();
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                // Fails silently if offline, keeps using the existing RAM cache
            }
        });
    }
}
