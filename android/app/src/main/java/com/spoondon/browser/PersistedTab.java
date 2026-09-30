package com.spoondon.browser;

/**
 * Lightweight, serializable representation of a single tab, used ONLY
 * for cross-process persistence in {@link SessionManager}.
 *
 * Deliberately NOT a {@link TabState}:
 *   - TabState holds a live WebView reference (process-scoped, never
 *     serialize this) and a thumbnail Bitmap (can be MBs, never write
 *     this to disk).
 *   - PersistedTab holds only the two strings we need to re-create the
 *     tab on cold start: the page URL and its title at save time.
 *
 * We intentionally do NOT persist:
 *   - Desktop / mobile UA flag   (re-derived from prefs at restore time
 *     via NavigationHelper.isDesktopHostEnabled)
 *   - Scroll position            (would require JS bridge on every page)
 *   - Form input / DOM state     (WebView exposes no API for this)
 *   - Back / forward history     (would require WebView.saveState, which
 *     is the process-scoped API that caused earlier cold-start crashes)
 *
 * Created 2026-09-30 as part of the session-restore feature.
 */
public class PersistedTab {

    /** Page URL. Never null when written; may be empty for a blank tab. */
    public String url;

    /** Page title at save time. Pre-populates the tab switcher during load. */
    public String title;

    public PersistedTab() {
        this.url = "";
        this.title = "";
    }

    public PersistedTab(String url, String title) {
        this.url = url != null ? url : "";
        this.title = title != null ? title : "";
    }
}
