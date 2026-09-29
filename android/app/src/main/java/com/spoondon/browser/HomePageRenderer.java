package com.spoondon.browser;

import android.webkit.WebView;

import androidx.annotation.NonNull;

/**
 * Renders the Spoon Browser new-tab / home page.
 *
 * The HTML is width-sensitive (a search input only appears on tablet-width
 * layouts), so the renderer caches the generated markup on first use and
 * reuses it for the process lifetime. Rotation to a different width class
 * reuses the cached variant — same behaviour as the pre-refactor inline
 * version, which only checked width on the very first call.
 *
 * Extracted from MainActivity (god-object split, slice 6).
 */
public class HomePageRenderer {

    private String cachedHtml;

    /**
     * Load the home page into {@code webView}.
     *
     * @param widthDp current screen width in dp — controls whether the
     *                inline search field is rendered.
     */
    public void render(@NonNull WebView webView, float widthDp) {
        if (cachedHtml == null) {
            cachedHtml = buildHtml(widthDp);
        }
        webView.loadDataWithBaseURL("about:blank", cachedHtml, "text/html", "UTF-8", null);
    }

    // ------------------------------------------------------------------------

    @NonNull
    private String buildHtml(float widthDp) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='margin:0;background:#000;color:white;")
          .append("font-family:sans-serif;text-align:center;'>")
          .append("<div style='padding-top:20%;'>")
          .append("<h1 style='font-size:48px;margin-bottom:40px;'>Spoon Browser</h1>");

        if (widthDp >= 600) {
            sb.append("<input id='q' type='text' placeholder='Search privately...' ")
              .append("style='width:72%;padding:20px;border:none;border-radius:18px;")
              .append("background:#1f1f1f;color:white;font-size:18px;outline:none;'/>");
        }

        sb.append("</div><script>")
          .append("function goSearch(){")
          .append("var el=document.getElementById('q');var q=el.value;")
          .append("if(!q)return;el.blur();el.value='';el.placeholder='Searching...';")
          .append("window.location.href='spoonsearch://'+encodeURIComponent(q);}")
          .append("var el=document.getElementById('q');")
          .append("if(el){el.addEventListener('keydown',function(e){")
          .append("if(e.key==='Enter'){goSearch();}});}")
          .append("</script></body></html>");

        return sb.toString();
    }
}
