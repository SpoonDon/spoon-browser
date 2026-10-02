package com.spoondon.browser;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Map;

/**
 * Spoon's WebViewClient. Handles:
 *   - AdBlock network interception (via AdBlockEngine.shouldBlock)
 *   - Vault URL short-circuit (VaultUrls)
 *   - Home-page custom scheme interception (spoonhome://)
 *   - Cleartext policy three-tier decision (whitelist / session / interstitial)
 *   - URL cleaning (tracking params)
 *   - Blob-URL download hook
 *   - WebRTC IP leak sanitizer
 *   - Cosmetic CSS injection
 *   - Password autosave injection (multi-step + shadow-DOM aware)
 *   - Vault pill signaling (MutationObserver reports password-field presence)
 *   - Pull-to-refresh scroll hook (SpoonScroll bridge)
 *   - History recording (with dedup)
 *
 * 2026-10-01 (vault pill): buildAutosaveScript now also reports password-field
 * presence to native via SpoonVault.showPill / SpoonVault.hidePill. A
 * MutationObserver watches the DOM so SPAs that inject login forms after load
 * still trigger the pill. onPageStarted also calls
 * VaultPillController.onPageNavigated() so stale pill state never survives a
 * navigation.
 *
 * 2026-10-01 (bookmarks grid): spoonhome:// interceptor opens the bookmark
 * manager. The interceptor is scoped to a single known action.
 */
public class SpoonWebViewClient extends WebViewClient {
    private final MainActivity activity;
    private final WebViewAssetLoader assetLoader;
    /**
     * Cached top-frame URL. Updated on the UI thread in onPageStarted /
     * doUpdateVisitedHistory so that shouldInterceptRequest (which runs on
     * a background thread) can consult it without calling WebView methods.
     */
    private volatile String cachedMainFrameUrl;
    private String lastRecordedHistoryUrl = "";
    private long lastRecordedHistoryTime = 0;

    public SpoonWebViewClient(@NonNull MainActivity activity,
                              @NonNull WebViewAssetLoader assetLoader) {
        this.activity = activity;
        this.assetLoader = assetLoader;
    }

    // ------------------------------------------------------------------------
    // AdBlock interception
    // ------------------------------------------------------------------------

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WebResourceResponse assetResponse = assetLoader.shouldInterceptRequest(request.getUrl());
            if (assetResponse != null) {
                return assetResponse;
            }

            if (request.isForMainFrame() || !AdBlockEngine.hasRules()) {
                return super.shouldInterceptRequest(view, request);
            }

            String url = request.getUrl().toString();
            String host = request.getUrl().getHost();
            if (host != null) {
                String lowerHost = host.toLowerCase(Locale.ROOT);
                if (lowerHost.contains("youtube.com") ||
                        lowerHost.contains("googlevideo.com") ||
                        lowerHost.contains("search.brave.com") ||
                        lowerHost.contains("duckduckgo.com")) {
                    return super.shouldInterceptRequest(view, request);
                }
            }

            int resourceType = classifyResource(request);
            String sourceHost = getCachedSourceHost();

            if (AdBlockEngine.shouldBlock(url, resourceType, sourceHost)) {
                return new WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        new ByteArrayInputStream(new byte[0])
                );
            }
        }
        return super.shouldInterceptRequest(view, request);
    }

    /**
     * Background-safe source host lookup. Reads the cached top-frame URL
     * that was stashed on the UI thread in onPageStarted /
     * doUpdateVisitedHistory. Never touches WebView.
     */
    @Nullable
    private String getCachedSourceHost() {
        String u = cachedMainFrameUrl;
        if (u == null) return null;
        try {
            return Uri.parse(u).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static int classifyResource(WebResourceRequest request) {
        if (request.isForMainFrame()) return AdBlockEngine.TYPE_DOCUMENT;

        Map<String, String> headers = request.getRequestHeaders();
        String accept = headers != null ? headers.get("Accept") : null;
        if (accept == null) return AdBlockEngine.TYPE_OTHER;

        String a = accept.toLowerCase(Locale.ROOT);
        if (a.contains("text/css")) return AdBlockEngine.TYPE_STYLESHEET;
        if (a.startsWith("image/")) return AdBlockEngine.TYPE_IMAGE;
        if (a.contains("javascript")) return AdBlockEngine.TYPE_SCRIPT;
        if (a.startsWith("font/")) return AdBlockEngine.TYPE_FONT;
        if (a.startsWith("video/") || a.startsWith("audio/")) return AdBlockEngine.TYPE_MEDIA;
        if (a.contains("json") || a.contains("xml")) return AdBlockEngine.TYPE_XHR;
        if (a.startsWith("text/html")) return AdBlockEngine.TYPE_SUBDOCUMENT;
        return AdBlockEngine.TYPE_OTHER;
    }

    // ------------------------------------------------------------------------
    // URL loading
    // ------------------------------------------------------------------------

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return handleUrlLoading(view, request.getUrl().toString());
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String urlString) {
        return handleUrlLoading(view, urlString);
    }

    private boolean handleUrlLoading(WebView view, String url) {
        if (url == null) return false;

        if (VaultUrls.isVaultUrl(url)) return false;

        url = cleanUrl(url);

        // Reject page-initiated file:// and content:// navigations. Local
        // file access is disabled on the WebView (see WebViewFactory), so
        // these could not load anyway — intercepting here prevents the
        // fallthrough into the intent:// handler below, which would
        // otherwise try to open the URI in an external app.
        if (url.startsWith("file:") || url.startsWith("content:")) {
            return true;
        }

        // Home-page custom scheme. Only one action is defined; anything
        // else is discarded. Emitted by the bookmark grid's "See all" tile.
        if (url.startsWith(HomePageRenderer.HOME_SCHEME)) {
            String action = url.substring(HomePageRenderer.HOME_SCHEME.length());
            if (HomePageRenderer.HOME_ACTION_MANAGER.equals(action)) {
                BookmarkManager bm = activity.getBookmarkManager();
                if (bm != null) bm.showBookmarks();
            }
            return true;
        }

        if (url.startsWith("spoonsearch://")) {
            try {
                String query = java.net.URLDecoder.decode(url.substring(14), "UTF-8");
                view.loadUrl(activity.getSearchUrlFor(query));
            } catch (Exception ignored) {}
            return true;
        }

        String cleanUrl = url.split("\\?")[0].split("#")[0].toLowerCase(Locale.ROOT);
        if (cleanUrl.matches(".*\\.(mp4|webm|mkv|avi|mov|flv|wmv|ts|apk|zip|rar|7z|pdf|iso|dmg|exe|msi|tar|gz|md|json|csv)$")) {
            String mime = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(
                            android.webkit.MimeTypeMap.getFileExtensionFromUrl(cleanUrl));
            if (mime == null) mime = "application/octet-stream";
            activity.triggerManualDownload(url, mime);
            return true;
        }

        // Cleartext policy — three tiers.
        if (url.startsWith("http://")) {
            String rawHost = extractHostFromHttpUrl(url);

            if (CleartextPolicy.isCleartextAllowed(view.getContext(), rawHost)) {
                return false;
            }
            if (CleartextInterstitial.isApprovedForSession(rawHost)) {
                return false;
            }

            view.loadDataWithBaseURL(
                    "about:blank",
                    CleartextInterstitial.buildHtml(url),
                    "text/html",
                    "UTF-8",
                    null);
            return true;
        }

        if (url.contains(" ") && (url.contains("http://") || url.contains("https://"))) {
            int httpIndex = url.indexOf("http");
            if (httpIndex != -1) {
                view.loadUrl(url.substring(httpIndex).trim());
                return true;
            }
        }

        if (url.startsWith("intent://")) {
            try {
                android.content.Context context = view.getContext();
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                if (intent != null) {
                    if (intent.getPackage() != null
                            && intent.getPackage().equals(context.getPackageName())) {
                        return true;
                    }
                    android.content.pm.PackageManager pm = context.getPackageManager();
                    android.content.pm.ResolveInfo info = pm.resolveActivity(
                            intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
                    if (info != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    } else {
                        String fallbackUrl = intent.getStringExtra("browser_fallback_url");
                        if (fallbackUrl != null
                                && (fallbackUrl.startsWith("http://")
                                    || fallbackUrl.startsWith("https://"))) {
                            view.loadUrl(fallbackUrl);
                        }
                    }
                    return true;
                }
            } catch (Exception e) {
                return true;
            }
        }
                
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            try {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    view.getContext().startActivity(intent);
                    // Cancel any pending navigation. Without this, WebView
                    // can remain stuck in a "loading" state after an
                    // external app takes over the URI (magnet:, market://,
                    // tel:, mailto:, custom app schemes, etc.), which
                    // breaks back/forward/reload/scroll until the tab is
                    // recreated.
                    view.stopLoading();
                    return true;
                }
            } catch (Exception e) {
                Toast.makeText(view.getContext(), "No app found to handle this link",
                        Toast.LENGTH_SHORT).show();
                view.stopLoading();
                return true;
            }
        }

        return false;
    }

    private static String extractHostFromHttpUrl(String url) {
        try {
            String remaining = url.substring("http://".length());
            int slashIndex = remaining.indexOf('/');
            String rawHost = (slashIndex != -1) ? remaining.substring(0, slashIndex) : remaining;
            if (rawHost.contains(":")) rawHost = rawHost.split(":")[0];
            return rawHost.trim();
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------------
    // Page lifecycle
    // ------------------------------------------------------------------------

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        cachedMainFrameUrl = url;

        boolean vaultPage = VaultUrls.isVaultUrl(url);

        if (activity.swipeRefresh != null) {
            // Optimistic default; the injected scroll hook (injectScrollHook)
            // will correct this as soon as the new document reports its
            // scroll position. On the vault page we always disable.
            activity.swipeRefresh.setEnabled(!vaultPage);
        }

        // Pill is page-scoped. Clear any residual state from the previous
        // document before the new one starts injecting its own signals.
        VaultPillController pill = activity.getVaultPillController();
        if (pill != null) pill.onPageNavigated();

        injectBlobHook(view);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            String gpcScript = "javascript:(function() { " +
                    "try { " +
                    "  Object.defineProperty(navigator, 'globalPrivacyControl', { " +
                    "    get: function() { return true; }, " +
                    "    configurable: false, " +
                    "    enumerable: true " +
                    "  }); " +
                    "} catch(e) {} " +
                    "})();";
            view.evaluateJavascript(gpcScript, null);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().flush();
        }

        if (view == activity.getCurrentWebView()) {
            activity.setAddressBarText(
                    (url == null || url.isEmpty() || url.equals("about:blank")) ? "" : url);
        }

        if (url != null && !url.isEmpty() && !url.equals("about:blank")) {
            String host = Uri.parse(url).getHost();
            if (host != null) {
                boolean desktop = activity.isDesktopHostEnabled(host);
                NavigationHelper.applyDesktopUa(view, desktop, activity);
            }
        }
        activity.updateScreenShield();
    }

    @Override
    public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        super.doUpdateVisitedHistory(view, url, isReload);
        if (url != null) cachedMainFrameUrl = url;
        injectBlobHook(view);

        if (url == null || url.isEmpty() || url.equals("about:blank")) return;

        if (VaultUrls.isVaultUrl(url)) return;

        if (activity.getCurrentTabState() != null && activity.getCurrentTabState().isIncognito()) {
            return;
        }

        if (!isReload && !url.contains("cdn-cgi/challenge")) {
            long currentTime = System.currentTimeMillis();
            Uri currentUri = Uri.parse(url);
            Uri lastUri = Uri.parse(lastRecordedHistoryUrl);

            String currentHost = currentUri.getHost() != null
                    ? currentUri.getHost().replaceFirst("^www\\.", "") : "";
            String currentPath = currentUri.getPath() != null ? currentUri.getPath() : "";
            String lastHost = lastUri.getHost() != null
                    ? lastUri.getHost().replaceFirst("^www\\.", "") : "";
            String lastPath = lastUri.getPath() != null ? lastUri.getPath() : "";

            boolean isSameCorePage = currentHost.equals(lastHost) && currentPath.equals(lastPath);
            boolean isRapidFire = (currentTime - lastRecordedHistoryTime) < 1500;

            if (activity.dbHelper != null) {
                if (isSameCorePage && isRapidFire) return;

                lastRecordedHistoryUrl = url;
                lastRecordedHistoryTime = currentTime;

                final String finalUrl = cleanUrl(url);
                final String finalTitle = view.getTitle();

                activity.getBackgroundExecutor().execute(() -> {
                    try {
                        activity.dbHelper.addHistory(finalUrl, finalTitle);
                    } catch (Exception ignored) {}
                });
            }
        }
    }

    public void injectBlobHook(WebView view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            view.evaluateJavascript(
                    "javascript:(function() {" +
                            "   if (window.spoonBlobHooked) return;" +
                            "   window.spoonBlobHooked = true;" +
                            "   window.spoonBlobStore = {};" +
                            "   var origCreate = window.URL.createObjectURL;" +
                            "   window.URL.createObjectURL = function(blob) {" +
                            "       var url = origCreate.call(window.URL, blob);" +
                            "       window.spoonBlobStore[url] = blob;" +
                            "       return url;" +
                            "   };" +
                            "   var origClick = HTMLAnchorElement.prototype.click;" +
                            "   HTMLAnchorElement.prototype.click = function() {" +
                            "       if (this.href && this.href.startsWith('blob:')) {" +
                            "           var blob = window.spoonBlobStore[this.href];" +
                            "           if (blob) {" +
                            "               var mime = blob.type;" +
                            "               var filename = this.download || 'downloaded_file';" +
                            "               var reader = new FileReader();" +
                            "               reader.readAsDataURL(blob);" +
                            "               reader.onloadend = function() {" +
                            "                   AndroidDownloader.saveBase64ToFile(reader.result, mime, filename);" +
                            "               };" +
                            "               return;" +
                            "           }" +
                            "       }" +
                            "       return origClick.apply(this, arguments);" +
                            "   };" +
                            "})();", null);
        }
    }

    /**
     * Reports scroll position back to native so SwipeRefreshLayout only
     * intercepts the pull gesture when the page is actually at the top.
     */
    public void injectScrollHook(WebView view) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return;
        view.evaluateJavascript(
                "javascript:(function() {" +
                "  if (window.__spoonScrollHooked) return;" +
                "  window.__spoonScrollHooked = true;" +
                "  var lastAtTop = true;" +
                "  function isAtTop(e) {" +
                "    try {" +
                "      if (window.scrollY > 1) return false;" +
                "      var de = document.scrollingElement || document.documentElement;" +
                "      if (de && de.scrollTop > 1) return false;" +
                "      var t = e && e.target;" +
                "      while (t && t !== document.body && t !== document.documentElement) {" +
                "        if (t.scrollTop > 1) return false;" +
                "        t = t.parentElement;" +
                "      }" +
                "    } catch(err) {}" +
                "    return true;" +
                "  }" +
                "  function report(e) {" +
                "    var atTop = isAtTop(e);" +
                "    if (atTop === lastAtTop) return;" +
                "    lastAtTop = atTop;" +
                "    try { SpoonScroll.setAtTop(atTop); } catch(err) {}" +
                "  }" +
                "  document.addEventListener('scroll', report, true);" +
                "  window.addEventListener('scroll', report, {passive: true});" +
                "})();",
                null);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        String webrtcSanitizer = "javascript:(function() {" +
                "if (window.RTCPeerConnection) {" +
                "  var OrigPC = window.RTCPeerConnection;" +
                "  window.RTCPeerConnection = function(config, constraints) {" +
                "    var pc = new OrigPC(config, constraints);" +
                "    var origCreateOffer = pc.createOffer;" +
                "    pc.createOffer = function(opts) {" +
                "      return origCreateOffer.call(pc, opts).then(function(offer) {" +
                "        var ipRegex = new RegExp('([0-9]{1,3}\\\\.){3}[0-9]{1,3}', 'g');" +
                "        offer.sdp = offer.sdp.replace(ipRegex, '0.0.0.0');" +
                "        return offer;" +
                "      });" +
                "    };" +
                "    return pc;" +
                "  };" +
                "  window.RTCPeerConnection.prototype = OrigPC.prototype;" +
                "}" +
                "})();";
        view.evaluateJavascript(webrtcSanitizer, null);
        super.onPageFinished(view, url);
        if (activity.swipeRefresh != null) activity.swipeRefresh.setRefreshing(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().flush();
        }

        if (url == null || url.isEmpty() || url.equals("about:blank")) return;
        if (VaultUrls.isVaultUrl(url)) return;

        String cosmeticCss = AdBlockEngine.getCosmeticCss(url);
        if (!cosmeticCss.isEmpty()) {
            String cleanCss = cosmeticCss.replace("\\", "\\\\")
                    .replace("'", "\\'").replace("\"", "\\\"");
            String injectScript = "javascript:(function() {" +
                    "var style = document.createElement('style');" +
                    "style.type = 'text/css';" +
                    "style.innerHTML = '" + cleanCss + "';" +
                    "document.head.appendChild(style);" +
                    "})();";
            view.evaluateJavascript(injectScript, null);
        }

        view.evaluateJavascript(buildAutosaveScript(), null);
        injectScrollHook(view);
    }

    // ------------------------------------------------------------------------
    // Password autosave injection
    // ------------------------------------------------------------------------

    /**
     * Multi-step-login and shadow-DOM aware autosave, plus vault pill signal.
     *
     * Handles:
     *   - Google / Microsoft / Amazon / banks: username and password entered
     *     on different page loads of the SAME origin (sessionStorage bridge).
     *   - Material / Web Components frameworks: input elements are wrapped
     *     inside element.shadowRoot and invisible to plain
     *     document.querySelector. deepQuery() walks the shadow tree.
     *   - SPA transitions where no form submit or button click fires: a
     *     2-second sweep saves once the password field loses focus.
     *   - Vault pill: a MutationObserver fires SpoonVault.showPill() when
     *     an input[type=password] appears and SpoonVault.hidePill() when
     *     the last one is removed. The native bridge validates that the
     *     caller WebView is the current tab before showing.
     *
     * Guards:
     *   - window.__spoonAutosaveAttached prevents duplicate listener
     *     attachment on repeat onPageFinished calls.
     *   - lastSavedSig suppresses redundant saves of the same tuple.
     *   - capture:true so we see events even when the page calls
     *     stopPropagation (Google's sign-in JS does this).
     *   - pillState prevents redundant showPill/hidePill crossings.
     */
    @NonNull
    private static String buildAutosaveScript() {
        return "javascript:(function() {" +
                "if (window.__spoonAutosaveAttached) return;" +
                "window.__spoonAutosaveAttached = true;" +
                "var SS_KEY = '__spoon_pending_user__';" +
                "var host = window.location.hostname || '';" +
                "var lastKnownUser = '';" +
                "var lastSavedSig = '';" +
                "try { lastKnownUser = sessionStorage.getItem(SS_KEY) || ''; } catch(e) {}" +
                // --- pill signaling ---
                "function hasPassword() {" +
                "  try { return !!document.querySelector('input[type=password]'); }" +
                "  catch(e) { return false; }" +
                "}" +
                "var pillState = false;" +
                "function syncPill() {" +
                "  var now = hasPassword();" +
                "  if (now === pillState) return;" +
                "  pillState = now;" +
                "  try {" +
                "    if (now) SpoonVault.showPill();" +
                "    else SpoonVault.hidePill();" +
                "  } catch(e) {}" +
                "}" +
                "function deepQuery(sel, root) {" +
                "  root = root || document;" +
                "  try { var direct = root.querySelector(sel); if (direct) return direct; } catch(e) {}" +
                "  var all = root.querySelectorAll('*');" +
                "  for (var i = 0; i < all.length; i++) {" +
                "    var sr = all[i].shadowRoot;" +
                "    if (sr) { var hit = deepQuery(sel, sr); if (hit) return hit; }" +
                "  }" +
                "  return null;" +
                "}" +
                "function inputFromEvent(e) {" +
                "  var path = e.composedPath ? e.composedPath() : [e.target];" +
                "  for (var i = 0; i < path.length; i++) {" +
                "    var n = path[i];" +
                "    if (n && n.tagName === 'INPUT') return n;" +
                "  }" +
                "  return e.target;" +
                "}" +
                "function rememberUser(v) {" +
                "  if (!v) return;" +
                "  lastKnownUser = v;" +
                "  try { sessionStorage.setItem(SS_KEY, v); } catch(e) {}" +
                "}" +
                "function forgetUser() {" +
                "  lastKnownUser = '';" +
                "  try { sessionStorage.removeItem(SS_KEY); } catch(e) {}" +
                "}" +
                "function readUserBox() {" +
                "  return deepQuery('input[type=email]')" +
                "    || deepQuery('input[name=username]')" +
                "    || deepQuery('input[name=login]')" +
                "    || deepQuery('input[name=identifier]')" +
                "    || deepQuery('input[autocomplete=username]')" +
                "    || deepQuery('input[type=text]');" +
                "}" +
                "function readPassBox() {" +
                "  return deepQuery('input[type=password]');" +
                "}" +
                "function trySave() {" +
                "  try {" +
                "    var passBox = readPassBox();" +
                "    if (!passBox || !passBox.value) return;" +
                "    var userBox = readUserBox();" +
                "    var finalUser = (userBox && userBox.value) ? userBox.value : lastKnownUser;" +
                "    if (!finalUser) return;" +
                "    var sig = host + '|' + finalUser + '|' + passBox.value;" +
                "    if (sig === lastSavedSig) return;" +
                "    lastSavedSig = sig;" +
                "    SpoonVault.saveCredentials(host, finalUser, passBox.value);" +
                "    forgetUser();" +
                "  } catch(e) {}" +
                "}" +
                "document.addEventListener('input', function(e) {" +
                "  var t = inputFromEvent(e);" +
                "  if (!t || t.tagName !== 'INPUT') return;" +
                "  var type = (t.type || '').toLowerCase();" +
                "  var name = (t.name || '').toLowerCase();" +
                "  if (type === 'email' || type === 'text' ||" +
                "      name === 'username' || name === 'identifier' || name === 'login') {" +
                "    rememberUser(t.value);" +
                "  }" +
                "}, true);" +
                "document.addEventListener('submit', function() { trySave(); }, true);" +
                "document.addEventListener('click', function(e) {" +
                "  var path = e.composedPath ? e.composedPath() : [e.target];" +
                "  for (var i = 0; i < path.length; i++) {" +
                "    var n = path[i];" +
                "    if (!n || !n.tagName) continue;" +
                "    var tag = n.tagName.toUpperCase();" +
                "    if (tag === 'BUTTON' || tag === 'INPUT' ||" +
                "        (n.getAttribute && n.getAttribute('role') === 'button')) {" +
                "      trySave();" +
                "      return;" +
                "    }" +
                "  }" +
                "}, true);" +
                "document.addEventListener('keydown', function(e) {" +
                "  if (e.key === 'Enter') trySave();" +
                "}, true);" +
                "window.addEventListener('pagehide', trySave);" +
                "window.addEventListener('beforeunload', trySave);" +
                // --- observer drives pill for SPA-rendered logins ---
                "try {" +
                "  var mo = new MutationObserver(function() { syncPill(); });" +
                "  mo.observe(document.documentElement || document, " +
                "    { childList: true, subtree: true });" +
                "} catch(e) {}" +
                "syncPill();" +
                // Only run the periodic sweep when a password field exists.
                // Pages without a login form (the ~90% majority) never wake
                // the CPU on a timer - this is the primary thermal fix.
                "if (document.querySelector('input[type=password]')) {" +
                "  if (window.__spoonAutosaveSweep) clearInterval(window.__spoonAutosaveSweep);" +
                "  window.__spoonAutosaveSweep = setInterval(function() {" +
                "    try {" +
                "      var pb = readPassBox();" +
                "      if (pb && pb.value && !pb.matches(':focus')) trySave();" +
                "    } catch(e) {}" +
                "  }, 2000);" +
                "}" +
                "})();";
    }

    // ------------------------------------------------------------------------
    // Error handling
    // ------------------------------------------------------------------------

    @Override
    public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
        if (activity != null && view != null) {
            activity.handleDeadRenderProcess(view);
        }
        return true;
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request,
                                android.webkit.WebResourceError error) {
        super.onReceivedError(view, request, error);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (request.isForMainFrame()) {
                handleNetworkError(view, error.getErrorCode());
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        super.onReceivedError(view, errorCode, description, failingUrl);
        if (failingUrl != null && failingUrl.equals(view.getUrl())) {
            handleNetworkError(view, errorCode);
        }
    }

    private void handleNetworkError(WebView view, int errorCode) {
        if (errorCode == ERROR_HOST_LOOKUP
                || errorCode == ERROR_CONNECT
                || errorCode == ERROR_TIMEOUT) {

            String originalUrl = view.getUrl();
            String retryJs = "window.location.reload()";
            if (originalUrl != null && !originalUrl.startsWith("data:")) {
                String safeUrl = originalUrl.replace("'", "\\'");
                retryJs = "window.location.href='" + safeUrl + "'";
            }

            String errorHtml = "<html><body style='display:flex;justify-content:center;align-items:center;height:100vh;background-color:#202124;font-family:sans-serif;color:#e8eaed;text-align:center;padding:20px;'>" +
                    "<div><svg width='64' height='64' viewBox='0 0 24 24' fill='none' stroke='#e8eaed' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'>" +
                    "<path d='M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z'/>" +
                    "<line x1='12' y1='9' x2='12' y2='13'/><line x1='12' y1='17' x2='12.01' y2='17'/></svg>" +
                    "<h2 style='margin-top:20px;margin-bottom:10px;'>No Connection</h2>" +
                    "<p style='color:#9aa0a6;'>Check your internet connection or the IP address and try again.</p>" +
                    "<button onclick='" + retryJs + "' style='margin-top:20px;padding:12px 24px;background:#8ab4f8;color:#202124;border:none;border-radius:8px;font-size:16px;font-weight:bold;cursor:pointer;'>Retry</button>" +
                    "</div>" +
                    "</body></html>";

            view.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null);
            Toast.makeText(view.getContext(), "Offline or Unreachable",
                    Toast.LENGTH_SHORT).show();
        }

        if (activity.swipeRefresh != null) activity.swipeRefresh.setRefreshing(false);
    }

    @Override
    public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler,
                                   android.net.http.SslError error) {
        String url = error.getUrl();
        if (url != null) {
            try {
                Uri uri = Uri.parse(url);
                String host = uri.getHost();
                if (CleartextPolicy.isCleartextAllowed(view.getContext(), host)) {
                    handler.proceed();
                    return;
                }
            } catch (Exception ignored) {}
        }
        handler.cancel();
        Toast.makeText(view.getContext(), "SSL Certificate Error Blocked",
                Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------------
    // URL cleaning (tracking params)
    // ------------------------------------------------------------------------

    private static final java.util.Set<String> EXACT_TRACKERS = new java.util.HashSet<>(java.util.Arrays.asList(
            "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "twclid",
            "igshid", "si", "mc_cid", "mc_eid", "zanpid", "yclid", "utm_source", "utm_medium",
            "utm_campaign", "utm_term", "utm_content", "utm_id"
    ));

    private static boolean isTracker(String key) {
        if (key == null) return false;
        String lowerKey = key.toLowerCase(Locale.ROOT);
        if (lowerKey.startsWith("utm_")
                || lowerKey.startsWith("oly_")
                || lowerKey.startsWith("vero_")
                || lowerKey.startsWith("trk_")) return true;
        return EXACT_TRACKERS.contains(lowerKey);
    }

    public static String cleanUrl(String url) {
        if (url == null || !url.contains("?")) return url;
        try {
            int queryStart = url.indexOf('?');
            String baseUrl = url.substring(0, queryStart);
            String queryAndFragment = url.substring(queryStart + 1);

            String fragment = "";
            int fragmentStart = queryAndFragment.indexOf('#');
            if (fragmentStart != -1) {
                fragment = queryAndFragment.substring(fragmentStart);
                queryAndFragment = queryAndFragment.substring(0, fragmentStart);
            }

            if (queryAndFragment.isEmpty()) return url;

            String[] pairs = queryAndFragment.split("&");
            StringBuilder cleanQuery = new StringBuilder();

            for (String pair : pairs) {
                int idx = pair.indexOf("=");
                String key = (idx > 0) ? pair.substring(0, idx) : pair;
                if (!isTracker(key)) {
                    if (cleanQuery.length() > 0) cleanQuery.append("&");
                    cleanQuery.append(pair);
                }
            }

            if (cleanQuery.length() == 0) return baseUrl + fragment;
            return baseUrl + "?" + cleanQuery + fragment;
        } catch (Exception e) {
            return url;
        }
    }
}
