package com.spoondon.browser;

import android.content.Context;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * Renders the Spoon Browser new-tab / home page.
 *
 * Layout (top to bottom):
 *   [ Spoon Browser ]             title
 *   [ search field ]              only when widthDp >= 600
 *   [ bookmark grid ]             up to MAX_TILES tiles; nothing rendered when empty
 *
 * Bookmark tiles are plain {@code <a href>} anchors — tapping one lets
 * WebView handle navigation through the normal URL pipeline. There is no
 * JavaScript bridge: nothing that runs in this page can call native code
 * except the two custom schemes below, both of which flow through
 * SpoonWebViewClient's override hook.
 *
 * Custom schemes:
 *   spoonsearch://<query>     existing search intercept (unchanged)
 *   spoonhome://manager       opens the bookmarks ItemManagerDialog
 *
 * Favicons are embedded as inline data: URLs read synchronously from
 * FaviconStore's disk cache. Cache misses fall back to a letter tile and
 * schedule a background fetch — the favicon appears on the next render.
 * Nothing about a bookmark leaves the device to render this page.
 *
 * 2026-10-01 (home tweaks): title reverted to "Spoon Browser"; the
 * persistent "Bookmarks will appear here" hint was removed — the grid
 * simply renders nothing when there are no bookmarks.
 */
public class HomePageRenderer {

    /** Same search scheme the pre-grid home page already used. */
    public static final String SEARCH_SCHEME = "spoonsearch://";

    /** Custom scheme intercepted by SpoonWebViewClient to open the manager. */
    public static final String HOME_SCHEME = "spoonhome://";

    /** The one action the home page currently routes through HOME_SCHEME. */
    public static final String HOME_ACTION_MANAGER = "manager";

    /** Grid slot budget. When bookmarks exceed this, one slot is traded for a "See all". */
    private static final int MAX_TILES = 12;

    private static final String[] PALETTE = {
            "#3b5bdb", "#2b8a3e", "#c2255c", "#e8590c", "#5f3dc4",
            "#0b7285", "#a61e4d", "#7048e8", "#087f5b", "#d9480f"
    };

    private final BookmarkManager bookmarkManager;
    private final FaviconStore faviconStore;

    public HomePageRenderer(@NonNull Context context,
                            @Nullable BookmarkManager bookmarkManager,
                            @Nullable FaviconStore faviconStore) {
        // Context intentionally unused for now — kept in the signature so
        // future features (theme detection, locale-driven tweaks) don't
        // require a constructor change at every call site.
        this.bookmarkManager = bookmarkManager;
        this.faviconStore = faviconStore;
    }

    /** Load the home page into {@code webView}. Rebuilds the markup each call. */
    public void render(@NonNull WebView webView, float widthDp) {
        webView.loadDataWithBaseURL("about:blank", buildHtml(widthDp),
                "text/html", "UTF-8", null);
    }

    // ------------------------------------------------------------------------

    @NonNull
    private String buildHtml(float widthDp) {
        List<ManagedItem> bookmarks = (bookmarkManager != null)
                ? bookmarkManager.load()
                : Collections.<ManagedItem>emptyList();

        boolean showSearch = widthDp >= 600;

        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
          .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
          .append("<style>")
          .append("html,body{margin:0;padding:0;background:#000;color:#fff;")
          .append("font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;")
          .append("-webkit-user-select:none;user-select:none;}")
          .append(".header{padding:56px 20px 24px;text-align:center;}")
          .append("h1{font-size:42px;margin:0;font-weight:600;letter-spacing:-0.02em;}");

        if (showSearch) {
            sb.append(".search{width:min(72%,560px);margin-top:24px;padding:16px 20px;")
              .append("border:none;border-radius:16px;background:#1a1a1a;color:#fff;")
              .append("font-size:16px;outline:none;}")
              .append(".search:focus{background:#222;}");
        }

        sb.append(".grid{display:grid;")
          .append("grid-template-columns:repeat(auto-fill,minmax(76px,1fr));")
          .append("gap:4px;padding:16px 16px 48px;max-width:640px;margin:0 auto;}")
          .append(".tile{display:flex;flex-direction:column;align-items:center;")
          .append("padding:10px 4px;border-radius:14px;text-decoration:none;color:inherit;")
          .append("-webkit-tap-highlight-color:transparent;}")
          .append(".tile:active{background:#181818;}")
          .append(".icon{width:44px;height:44px;border-radius:12px;")
          .append("display:flex;align-items:center;justify-content:center;")
          .append("font-size:20px;font-weight:600;overflow:hidden;}")
          .append(".icon img{width:100%;height:100%;object-fit:contain;padding:4px;}")
          .append(".label{margin-top:6px;font-size:11px;text-align:center;")
          .append("white-space:nowrap;overflow:hidden;text-overflow:ellipsis;")
          .append("max-width:100%;color:#b0b0b0;}")
          .append(".see-all .icon{background:#1a2547;color:#8fb0ff;font-size:22px;}")
          .append("</style></head><body>");

        // Header: title + optional search
        sb.append("<div class='header'><h1>Spoon Browser</h1>");
        if (showSearch) {
            sb.append("<input id='q' class='search' type='text' ")
              .append("placeholder='Search privately...' autocomplete='off' ")
              .append("autocorrect='off' autocapitalize='off' spellcheck='false'>");
        }
        sb.append("</div>");

        // Grid — only rendered when there are bookmarks to show. No empty
        // hint; a blank home page is cleaner than a permanent placeholder.
        if (!bookmarks.isEmpty()) {
            sb.append("<div class='grid'>");
            int total = bookmarks.size();
            if (total <= MAX_TILES) {
                for (ManagedItem bm : bookmarks) appendTile(sb, bm);
            } else {
                int shown = MAX_TILES - 1;
                for (int i = 0; i < shown; i++) appendTile(sb, bookmarks.get(i));
                appendManageTile(sb, total);
            }
            sb.append("</div>");
        }

        // Search box wiring (unchanged from pre-grid behavior)
        sb.append("<script>")
          .append("function goSearch(){")
          .append("var el=document.getElementById('q');if(!el)return;")
          .append("var q=el.value;if(!q)return;el.blur();")
          .append("window.location.href='").append(SEARCH_SCHEME)
          .append("'+encodeURIComponent(q);}")
          .append("var el=document.getElementById('q');")
          .append("if(el){el.addEventListener('keydown',function(e){")
          .append("if(e.key==='Enter'){e.preventDefault();goSearch();}});}")
          .append("</script></body></html>");

        return sb.toString();
    }

    private void appendTile(@NonNull StringBuilder sb, @NonNull ManagedItem bm) {
        String host = bm.displayHost();
        String dataUrl = (faviconStore != null) ? faviconStore.getCachedDataUrl(host) : null;
        if (dataUrl == null && faviconStore != null) {
            // Schedule a background fetch. The favicon lands on the next render.
            faviconStore.fetchAsync(host, null);
        }
        String letter = !host.isEmpty()
                ? host.substring(0, 1).toUpperCase(java.util.Locale.US)
                : "?";
        String color = pickColor(host);

        sb.append("<a class='tile' href='").append(esc(bm.getUrl())).append("'>")
          .append("<div class='icon' style='background:").append(color).append(";'>");
        if (dataUrl != null) {
            sb.append("<img alt='' src='").append(dataUrl).append("'>");
        } else {
            sb.append(letter);
        }
        sb.append("</div>")
          .append("<div class='label'>").append(esc(bm.displayTitle())).append("</div>")
          .append("</a>");
    }

    private void appendManageTile(@NonNull StringBuilder sb, int total) {
        sb.append("<a class='tile see-all' href='")
          .append(HOME_SCHEME).append(HOME_ACTION_MANAGER).append("'>")
          .append("<div class='icon'>&hellip;</div>")
          .append("<div class='label'>All ").append(total).append("</div>")
          .append("</a>");
    }

    @NonNull
    private static String pickColor(@NonNull String host) {
        if (host.isEmpty()) return PALETTE[0];
        int h = 0;
        for (int i = 0; i < host.length(); i++) h = h * 31 + host.charAt(i);
        return PALETTE[Math.abs(h) % PALETTE.length];
    }

    @NonNull
    private static String esc(@Nullable String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace
