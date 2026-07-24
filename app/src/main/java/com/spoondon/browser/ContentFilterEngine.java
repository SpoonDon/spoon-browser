package com.spoondon.browser;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ContentFilterEngine {
    private static ContentFilterEngine instance;
    private final List<String> domainFilters;
    private final List<String> cosmeticFilters;
    
    // Optimized Patterns
    private static final Pattern DOMAIN_PATTERN = Pattern.compile("^\\|\\|([a-zA-Z0-9.-]+)\\^");
    private static final Pattern COSMETIC_PATTERN = Pattern.compile("^(.+?)##(.+)");

    private ContentFilterEngine() {
        domainFilters = new ArrayList<>();
        cosmeticFilters = new ArrayList<>();
    }

    public static synchronized ContentFilterEngine getInstance() {
        if (instance == null) {
            instance = new ContentFilterEngine();
        }
        return instance;
    }

    public void loadFilters(Context context) {
        // Simulate loading from assets or raw resources
        // In real app, read from assets/filters.txt
        domainFilters.add("doubleclick.net");
        domainFilters.add("adservice.google.com");
    }

    public boolean shouldBlockRequest(String url) {
        for (String domain : domainFilters) {
            if (url.contains(domain)) {
                return true;
            }
        }
        return false;
    }

    public List<String> getCosmeticFilters() {
        return cosmeticFilters;
    }
}
