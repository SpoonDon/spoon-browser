package com.spoondon.browser;

import android.net.Uri;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Produces address-bar autocomplete suggestions from browsing history.
 *
 * The strategy is two-pass:
 *   1. Distinct host matches — {@code "git"} surfaces {@code github.com}
 *      once, no matter how many GitHub URLs are in history.
 *   2. Up to three deep-link entries whose URL prefix-matches the query
 *      but whose host did not already dominate pass 1.
 *
 * Runs on a background thread (caller's executor); the returned list is
 * safe to hand to the UI thread.
 *
 * Extracted from MainActivity (god-object split, slice 6).
 */
public class SuggestionProvider {

    private static final int MAX_DEEP_LINKS = 3;

    private final BrowserDatabaseHelper dbHelper;

    public SuggestionProvider(@NonNull BrowserDatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    @NonNull
    public List<ToolbarController.Suggestion> fetch(@NonNull String query) {
        List<ToolbarController.Suggestion> result = new ArrayList<>();
        if (query.isEmpty() || dbHelper == null) return result;

        List<String[]> rawResults = dbHelper.getMatchingHistory(query);
        Set<String> addedUrls = new HashSet<>();
        Set<String> addedHosts = new HashSet<>();

        // Pass 1: distinct hosts that contain the query.
        String lowerQuery = query.toLowerCase();
        for (String[] row : rawResults) {
            try {
                Uri uri = Uri.parse(row[0]);
                String host = uri.getHost();
                if (host == null) continue;
                String cleanHost = host.replaceFirst("^www\\.", "");
                if (cleanHost.toLowerCase().contains(lowerQuery)
                        && !addedHosts.contains(cleanHost)) {
                    result.add(new ToolbarController.Suggestion(cleanHost, cleanHost));
                    addedHosts.add(cleanHost);
                    addedUrls.add(cleanHost);
                }
            } catch (Exception ignored) {
            }
        }

        // Pass 2: up to MAX_DEEP_LINKS deduped deep links.
        int deepLinks = 0;
        for (String[] row : rawResults) {
            if (deepLinks >= MAX_DEEP_LINKS) break;
            String rawUrl = row[0];
            String title = (row[1] != null && !row[1].isEmpty()) ? row[1] : rawUrl;
            String display = rawUrl.replaceFirst("^https?://(www\\.)?", "");
            if (!addedUrls.contains(display) && !addedHosts.contains(display)) {
                result.add(new ToolbarController.Suggestion(title, display));
                addedUrls.add(display);
                deepLinks++;
            }
        }

        return result;
    }
}
