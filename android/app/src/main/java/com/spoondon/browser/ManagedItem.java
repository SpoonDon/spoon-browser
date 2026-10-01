package com.spoondon.browser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Unified row model for the History + Bookmarks managers.
 *
 * Owns only what the manager UI needs: identity, display strings, and
 * pre-computed lowercase keys for fast client-side filtering. The type is
 * fixed at construction so one adapter can serve both lists.
 */
public final class ManagedItem {

    public static final int TYPE_HISTORY  = 0;
    public static final int TYPE_BOOKMARK = 1;

    public final long id;
    public final int type;
    public final long timestamp;

    private String title;
    private String url;

    // Derived, recomputed whenever title/url change.
    private String titleLower = "";
    private String urlLower   = "";
    private String hostLower  = "";

    public ManagedItem(long id, int type, @Nullable String title,
                       @NonNull String url, long timestamp) {
        this.id = id;
        this.type = type;
        this.timestamp = timestamp;
        setTitle(title);
        setUrl(url);
    }

    @Nullable public String getTitle() { return title; }
    @NonNull  public String getUrl()   { return url; }

    public void setTitle(@Nullable String t) {
        this.title = t;
        this.titleLower = (t == null) ? "" : t.toLowerCase();
    }

    public void setUrl(@Nullable String u) {
        this.url = (u == null) ? "" : u;
        this.urlLower = this.url.toLowerCase();
        this.hostLower = extractHost(this.url);
    }

    /** Title if non-blank, else the URL. Never null. */
    @NonNull
    public String displayTitle() {
        return (title == null || title.trim().isEmpty()) ? url : title;
    }

    /** Host portion for the secondary line, or the raw URL if unparseable. */
    @NonNull
    public String displayHost() {
        return hostLower.isEmpty() ? url : hostLower;
    }

    /** True when every non-empty needle appears in title, url, or host. */
    public boolean matches(@NonNull String[] needles) {
        for (String n : needles) {
            if (n.isEmpty()) continue;
            if (!titleLower.contains(n) && !urlLower.contains(n) && !hostLower.contains(n)) {
                return false;
            }
        }
        return true;
    }

    private static String extractHost(String u) {
        try {
            String s = u;
            int scheme = s.indexOf("://");
            if (scheme >= 0) s = s.substring(scheme + 3);
            int slash = s.indexOf('/');
            if (slash >= 0) s = s.substring(0, slash);
            int q = s.indexOf('?');
            if (q >= 0) s = s.substring(0, q);
            if (s.startsWith("www.")) s = s.substring(4);
            return s.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }
}
