package com.spoondon.browser;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Collections;
import java.util.Set;

/**
 * Builds and configures WebViews.
 *
 * Hardening pass (2026-09-30):
 *   - setAllowFileAccess(false): file:// browsing is no longer supported.
 *     The vault moved to WebViewAssetLoader over a synthetic HTTPS origin,
 *     the home page uses loadDataWithBaseURL, and downloads go through
 *     DownloadManager. Nothing in the app needs file:// access anymore,
 *     and file:// is a well-known WebView escape vector.
 *   - setAllowContentAccess(true) is retained — file-chooser (input
 *     type=file) and CSV import/export rely on content:// URIs.
 */
public class WebViewFactory {

    private final MainActivity activity;
    private final SecureCredentialManager credentials;
    private final PermissionController permissionController;
    private final DownloadHandler downloadHandler;

    private final WebViewAssetLoader assetLoader;

    public WebViewFactory(@NonNull MainActivity activity,
                          @NonNull SecureCredentialManager credentials,
                          @NonNull PermissionController permissionController,
                          @NonNull DownloadHandler downloadHandler) {
        this.activity = activity;
        this.credentials = credentials;
        this.permissionController = permissionController;
        this.downloadHandler = downloadHandler;

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
        webView.addJavascriptInterface(new PasswordAutosaveBridge(), "SpoonVault");
        webView.addJavascriptInterface(new CleartextBridge(webView), "SpoonCleartext");

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

    private View.OnLongClickListener createImageLongClickListener(WebView webView) {
        return v -> {
            WebView.HitTestResult result = webView.getHitTestResult();
            if (result != null
                    && (result.getType() == WebView.HitTestResult.IMAGE_TYPE
                        || result.getType() == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                try {
                    activity.startActivity(
                            new Intent(Intent.ACTION_VIEW, Uri.parse(result.getExtra())));
                } catch (Exception e) {
                    Toast.makeText(activity, "Cannot download image",
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            return false;
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
    // JS bridge
    // ------------------------------------------------------------------------

    private class PasswordAutosaveBridge {
        @android.webkit.JavascriptInterface
        public void saveCredentials(String host, String username, String password) {
            if (credentials != null) {
                credentials.saveCredentials(host, username, password);
            }
        }
    }
}
