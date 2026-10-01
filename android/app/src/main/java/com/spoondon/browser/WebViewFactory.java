package com.spoondon.browser;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Collections;
import java.util.Set;

/**
 * Builds and configures WebViews.
 *
 * 2026-10-01 (vault pill):
 *   - Constructor now takes a {@link VaultPillController}. The SpoonVault
 *     bridge is extended with showPill() / hidePill() so the injected
 *     autosave script can signal password-field presence to native.
 *     The bridge captures its owning WebView at construction so the pill
 *     can validate caller-vs-current-tab before showing.
 *   - No new addJavascriptInterface registration — the existing SpoonVault
 *     name is reused, keeping the bridge surface to four.
 *
 * 2026-09-30 (image long-press fix):
 *   - createImageLongClickListener no longer fires ACTION_VIEW. That was
 *     routing image URLs to the OS default browser — the long-press
 *     sibling of the download redirect bug. It now goes through
 *     DownloadHandler.onDownloadStart so the same Save / System / Cancel
 *     dialog appears and the default Save path uses the in-app engine.
 *
 * Hardening pass (2026-09-30) retained:
 *   - setAllowFileAccess(false), setAllowContentAccess(true).
 *   - MIXED_CONTENT_NEVER_ALLOW.
 *
 * JS bridges registered here:
 *   - AndroidDownloader  : BlobDownloader.saveBase64ToFile
 *   - SpoonVault         : saveCredentials / showPill / hidePill
 *   - SpoonCleartext     : proceed / upgrade / cancel
 *   - SpoonScroll        : setAtTop (pull-to-refresh state)
 *
 * The Vault WebMessageListener (spoonVaultMessage) is origin-scoped to
 * VaultUrls.ORIGIN and handles FETCH_ALL_VAULT_DATA / SAVE_LOGIN /
 * DELETE_LOGIN from the vault.html document.
 */
public class WebViewFactory {

    private final MainActivity activity;
    private final SecureCredentialManager credentials;
    private final PermissionController permissionController;
    private final DownloadHandler downloadHandler;
    private final VaultPillController vaultPillController;

    private final WebViewAssetLoader assetLoader;

    public WebViewFactory(@NonNull MainActivity activity,
                          @NonNull SecureCredentialManager credentials,
                          @NonNull PermissionController permissionController,
                          @NonNull DownloadHandler downloadHandler,
                          @NonNull VaultPillController vaultPillController) {
        this.activity = activity;
        this.credentials = credentials;
        this.permissionController = permissionController;
        this.downloadHandler = downloadHandler;
        this.vaultPillController = vaultPillController;

        this.assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/",
                        new WebViewAssetLoader.AssetsPathHandler(activity))
                .build();
    }

    // ------------------------------------------------------------------------
    // Public factory
    // ------------------------------------------------------------------------

    @NonNull
    public WebView create() {
        WebView webView = new WebView(activity);

        android.widget.LinearLayout.LayoutParams params =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        webView.setLayoutParams(params);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings ws = webView.getSettings();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            ws.setMediaPlaybackRequiresUserGesture(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ws.setOffscreenPreRaster(true);
        }

        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setLoadsImagesAutomatically(true);
        ws.setBlockNetworkImage(false);

        configureSettings(ws);

        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setJavaScriptEnabled(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);

        // Hardening: file:// access off, content:// on.
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            ws.setAllowFileAccessFromFileURLs(false);
            ws.setAllowUniversalAccessFromFileURLs(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        String currentUa = ws.getUserAgentString();
        if (currentUa != null) {
            currentUa = currentUa.replace("; wv", "");
            currentUa = currentUa.replaceFirst("Version/[0-9.]+\\s", "");
            ws.setUserAgentString(currentUa);
        }

        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.setAcceptThirdPartyCookies(webView, false);
            }
        } catch (Exception ignored) {
        }

        webView.setOnLongClickListener(createImageLongClickListener(webView));
        webView.setWebViewClient(new SpoonWebViewClient(activity, assetLoader));
        webView.setWebChromeClient(new SpoonWebChromeClient(activity, permissionController));

        webView.addJavascriptInterface(new BlobDownloader(activity), "AndroidDownloader");
        webView.addJavascriptInterface(new PasswordAutosaveBridge(webView), "SpoonVault");
        webView.addJavascriptInterface(new CleartextBridge(webView), "SpoonCleartext");
        webView.addJavascriptInterface(new ScrollBridge(), "SpoonScroll");

        downloadHandler.attach(webView);

        installVaultMessageListener(webView);

        ViewCompat.setNestedScrollingEnabled(webView, true);
        return webView;
    }

    // ------------------------------------------------------------------------
    // WebSettings configuration
    // ------------------------------------------------------------------------

    private void configureSettings(WebSettings settings) {
        if (settings == null) return;

        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSaveFormData(true);

        android.net.ConnectivityManager connMgr =
                (android.net.ConnectivityManager)
                        activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr != null && connMgr.isActiveNetworkMetered()) {
            settings.setBlockNetworkImage(true);
            settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        } else {
            settings.setBlockNetworkImage(false);
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        }

        // Hardening: file:// access off, content:// on.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING);
        }
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setGeolocationEnabled(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        try {
            android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        } catch (Exception ignored) {
        }

        settings.setUserAgentString(NavigationHelper.MOBILE_UA);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            androidx.webkit.WebSettingsCompat.setForceDark(
                    settings, androidx.webkit.WebSettingsCompat.FORCE_DARK_ON);
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            androidx.webkit.WebSettingsCompat.setForceDarkStrategy(settings,
                    androidx.webkit.WebSettingsCompat
                            .DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING);
        }
    }

    // ------------------------------------------------------------------------
    // Long-press image handling
    // ------------------------------------------------------------------------

    /**
     * Long-press on an image now opens the standard download dialog rather
     * than firing ACTION_VIEW at the OS. The ACTION_VIEW path was leaking
     * images to whichever browser is the system default.
     */
    private View.OnLongClickListener createImageLongClickListener(WebView webView) {
        return v -> {
            WebView.HitTestResult result = webView.getHitTestResult();
            if (result == null) return false;

            int type = result.getType();
            if (type != WebView.HitTestResult.IMAGE_TYPE
                    && type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                return false;
            }

            String imageUrl = result.getExtra();
            if (imageUrl == null || imageUrl.isEmpty()) return false;

            if (downloadHandler != null) {
                String ua = null;
                try {
                    ua = webView.getSettings().getUserAgentString();
                } catch (Exception ignored) {}
                downloadHandler.onDownloadStart(imageUrl, ua, null, "image/*", -1L);
            } else {
                Toast.makeText(activity, "Download handler unavailable",
                        Toast.LENGTH_SHORT).show();
            }
            return true;
        };
    }

    // ------------------------------------------------------------------------
    // Vault WebMessageListener
    // ------------------------------------------------------------------------

    private void installVaultMessageListener(@NonNull WebView webView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;

        Set<String> allowedOrigins = Collections.singleton(VaultUrls.ORIGIN);

        WebViewCompat.addWebMessageListener(webView, "spoonVaultMessage", allowedOrigins,
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
            try {
                String currentUrl = view.getUrl();
                if (!VaultUrls.isVaultUrl(currentUrl)) {
                    return;
                }
                String msg = message.getData();

                if ("FETCH_ALL_VAULT_DATA".equals(msg)) {
                    String all = credentials.getAllCredentialsAsJson();
                    replyProxy.postMessage(all != null ? all : "[]");
                } else if (msg != null && msg.startsWith("{")) {
                    org.json.JSONObject obj = new org.json.JSONObject(msg);
                    String action = obj.optString("action");
                    String host = obj.optString("host");
                    String user = obj.optString("username");

                    if ("SAVE_LOGIN".equals(action)) {
                        credentials.saveCredentials(host, user, obj.optString("password"));
                    } else if ("DELETE_LOGIN".equals(action)) {
                        credentials.deleteCredentials(host, user);
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    // ------------------------------------------------------------------------
    // JS bridges
    // ------------------------------------------------------------------------

    /**
     * Page-facing vault bridge. saveCredentials is unchanged; showPill and
     * hidePill are new, called by the injected autosave script when it
     * detects (or loses) an input[type=password] in the DOM.
     *
     * The bridge captures its owning WebView so VaultPillController can
     * reject the call if the caller is not the current tab. addJavascriptInterface
     * is not origin-scoped, so any page can invoke these methods — the
     * caller check plus the credentials-exist check inside the controller
     * are the actual gates.
     */
    private class PasswordAutosaveBridge {
        private final WebView caller;

        PasswordAutosaveBridge(@NonNull WebView caller) {
            this.caller = caller;
        }

        @android.webkit.JavascriptInterface
        public void saveCredentials(String host, String username, String password) {
            if (credentials != null) {
                credentials.saveCredentials(host, username, password);
            }
        }

        @android.webkit.JavascriptInterface
        public void showPill() {
            if (vaultPillController != null) {
                vaultPillController.onPasswordFieldDetected(caller);
            }
        }

        @android.webkit.JavascriptInterface
        public void hidePill() {
            if (vaultPillController != null) {
                vaultPillController.onPasswordFieldGone();
            }
        }
    }

    /**
     * Reports page scroll state to native so SwipeRefreshLayout only
     * intercepts pull gestures when the document (or an inner scroller)
     * is at the top. Injected by SpoonWebViewClient.injectScrollHook().
     *
     * Always posts to the UI thread — JS bridge methods arrive on a
     * WebView-owned background thread.
     */
    private class ScrollBridge {
        @android.webkit.JavascriptInterface
        public void setAtTop(boolean atTop) {
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (activity.swipeRefresh != null) {
                    activity.swipeRefresh.setEnabled(atTop);
                }
            });
        }
    }
}
