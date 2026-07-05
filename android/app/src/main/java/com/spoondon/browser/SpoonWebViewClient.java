package com.spoondon.browser;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class SpoonWebViewClient extends WebViewClient {

    private final MainActivity activity;

    public SpoonWebViewClient(MainActivity activity) {
        this.activity = activity;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        if (url != null) {
            String urlString = url.toString();
            // Removed synchronized lock: filterEngine is inherently thread-safe
            if (activity.filterEngine.shouldBlock(urlString)) {
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }
        }
        return super.shouldInterceptRequest(view, request);
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (!request.isForMainFrame()) {
            return false;
        }

        Uri url = request.getUrl();
        if (url != null) {
            String urlString = url.toString();

            if (urlString.startsWith("magnet:") || urlString.endsWith(".torrent")) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, url);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    view.getContext().startActivity(intent);
                    return true;
                } catch (Exception e) {
                    Toast.makeText(view.getContext(), "No app found to handle torrent links", Toast.LENGTH_SHORT).show();
                    return true;
                }
            }
        }
        return false;
    }
    // Legacy support for Android 5.0 - 6.0
    @SuppressWarnings("deprecation")
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String urlString) {
        if (urlString != null && (urlString.startsWith("magnet:") || urlString.endsWith(".torrent"))) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(urlString));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                view.getContext().startActivity(intent);
                return true;
            } catch (Exception e) {
                Toast.makeText(view.getContext(), "No app found to handle torrent links", Toast.LENGTH_SHORT).show();
                return true;
            }
        }
        return false;
    }
  
    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().flush();
        }

        if (view == activity.getCurrentWebView() && activity.addressBar != null) {
            activity.addressBar.setText((url == null || url.isEmpty() || url.equals("about:blank")) ? "" : url);
        }
    }

    @Override
    public void doUpdateVisitedHistory(android.webkit.WebView view, String url, boolean isReload) {
        super.doUpdateVisitedHistory(view, url, isReload);
        
        // Pass the data cleanly to MainActivity! No private access errors!
        activity.recordPageVisit(url, view.getTitle());
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);

        // 1. Inject Cosmetic AdBlock Filters
        view.evaluateJavascript(activity.filterEngine.compileCosmeticJavascript(), null);
        List<String> cssBatches = activity.filterEngine.getCosmeticStyleBatches(url);
        for (String cssChunk : cssBatches) {
            String cleanChunk = cssChunk.replace("\\", "\\\\").replace("'", "\\'");
            String injectScript = "javascript:(function() {" +
                    "var style = document.getElementById('spoon-cosmetic-sheets');" +
                    "if (style) { style.appendChild(document.createTextNode('" + cleanChunk + "\\n')); }" +
                    "})()";
            view.evaluateJavascript(injectScript, null);
        }

if (url != null && !url.startsWith("file://") && !url.equals("about:blank")) {
    String secureAutofillScript = "javascript:(function() {" +
        "  if (typeof spoonVaultMessage === 'undefined') return;" +
        "  " +
        "  /* THE BALANCED IMPERSONATOR: Beats React without triggering Cloudflare wipes */" +
        "  function setNativeValue(el, val) {" +
        "    try {" +
        "      var prev = el.value;" +
        "      el.value = val;" +
        "      var tracker = el._valueTracker;" +
        "      if (tracker) tracker.setValue(prev);" +
        "      var desc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');" +
        "      if (desc && desc.set) desc.set.call(el, val);" +
        "      /* ONLY dispatch 'input'. Dispatching 'blur' causes Cloudflare to wipe the fields! */" +
        "      el.dispatchEvent(new Event('input', { bubbles: true }));" +
        "    } catch(e) {" +
        "      el.value = val;" +
        "      el.dispatchEvent(new Event('input', { bubbles: true }));" +
        "    }" +
        "  }" +
        "  " +
        "  var pendingUser = ''; var pendingPass = '';" +
        "  document.addEventListener('input', function(e) {" +
        "    if (e.target && e.target.tagName === 'INPUT') {" +
        "      var t = (e.target.type || 'text').toLowerCase();" +
        "      if (t === 'password') pendingPass = e.target.value;" +
        "      else if (['text', 'email', 'tel'].indexOf(t) !== -1) pendingUser = e.target.value;" +
        "    }" +
        "  }, true);" +
        "  " +
        "  function commitSave() {" +
        "    if (pendingUser && pendingPass) {" +
        "      spoonVaultMessage.postMessage(JSON.stringify({action: 'SAVE_LOGIN', host: '', username: pendingUser, password: pendingPass}));" +
        "    }" +
        "  }" +
        "  " +
        "  document.addEventListener('click', function(e) {" +
        "    var el = e.target;" +
        "    if (el.tagName === 'BUTTON' || (el.tagName === 'INPUT' && (el.type === 'submit' || el.type === 'button')) || el.closest('button')) commitSave();" +
        "  }, true);" +
        "  document.addEventListener('keydown', function(e) { if (e.key === 'Enter') commitSave(); }, true);" +
        "  document.addEventListener('submit', function() { commitSave(); }, true);" +
        "  " +
        "  spoonVaultMessage.onmessage = function(event) {" +
        "    try {" +
        "      var accounts = JSON.parse(event.data);" +
        "      if (!accounts || accounts.length === 0) return;" +
        "      " +
        "      var dropdown = document.getElementById('spoon-vault-dropdown');" +
        "      if (!dropdown) {" +
        "        dropdown = document.createElement('div');" +
        "        dropdown.id = 'spoon-vault-dropdown';" +
        "        dropdown.style.cssText = 'position:absolute; background:#fff; border:1px solid #ccc; border-radius:4px; box-shadow:0 4px 6px rgba(0,0,0,0.2); z-index:2147483647; display:none; max-height:150px; overflow-y:auto; min-width:200px; font-family:sans-serif;';" +
        "        document.body.appendChild(dropdown);" +
        "      }" +
        "      dropdown.innerHTML = '';" +
        "      var currentTarget = null;" +
        "      " +
        "      accounts.forEach(function(acc) {" +
        "        var item = document.createElement('div');" +
        "        item.style.cssText = 'padding:12px; border-bottom:1px solid #eee; color:#222; font-size:16px; cursor:pointer; background:#fff;';" +
        "        item.textContent = acc.username || 'Saved Password';" +
        "        item.onmousedown = function(e) { e.preventDefault(); };" +
        "        item.onclick = function() {" +
        "          document.querySelectorAll('input').forEach(function(i) {" +
        "            var t = (i.type || 'text').toLowerCase();" +
        "            if (['text', 'email', 'tel'].indexOf(t) !== -1 && (!i.value || i === currentTarget)) {" +
        "              setNativeValue(i, acc.username); pendingUser = acc.username;" +
        "            } else if (t === 'password' && (!i.value || i === currentTarget)) {" +
        "              setNativeValue(i, acc.password); pendingPass = acc.password;" +
        "            }" +
        "          });" +
        "          dropdown.style.display = 'none';" +
        "        };" +
        "        dropdown.appendChild(item);" +
        "      });" +
        "      " +
        "      document.addEventListener('focusin', function(e) {" +
        "        var el = e.target;" +
        "        if (el && el.tagName === 'INPUT') {" +
        "          var t = (el.type || 'text').toLowerCase();" +
        "          if (['text', 'email', 'tel', 'password'].indexOf(t) !== -1) {" +
        "            currentTarget = el;" +
        "            var rect = el.getBoundingClientRect();" +
        "            dropdown.style.left = (rect.left + window.scrollX) + 'px';" +
        "            dropdown.style.top = (rect.bottom + window.scrollY + 2) + 'px';" +
        "            dropdown.style.width = Math.max(rect.width, 200) + 'px';" +
        "            dropdown.style.display = 'block';" +
        "          }" +
        "        }" +
        "      });" +
        "      " +
        "      document.addEventListener('click', function(e) {" +
        "        if (e.target.tagName !== 'INPUT' && !dropdown.contains(e.target)) {" +
        "          dropdown.style.display = 'none';" +
        "        }" +
        "      });" +
        "    } catch(e) {}" +
        "  };" +
        "  " +
        "  spoonVaultMessage.postMessage('FETCH_CREDENTIALS');" +
        "})();";
    view.evaluateJavascript(secureAutofillScript, null);
}
    }
}
