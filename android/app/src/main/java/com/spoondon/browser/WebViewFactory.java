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
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Set;

/**
 * Builds and configures WebViews.
 *
 * Owns everything that used to live in MainActivity's WebView factory:
 *   - WebSettings configuration (mobile-vs-desktop, mixed content, dark mode)
 *   - SpoonWebViewClient + SpoonWebChromeClient installation
 *   - JavaScript bridge injection (BlobDownloader, PasswordAutosaveBridge)
 *   - Download listener wiring
 *   - The vault WebMessageListener (restricted to vault.html)
 *   - Long-press image handling
 *
 * Extracted from MainActivity (god-object split, slice 6).
 * Security batch A (2026-09-30): MIXED_CONTENT_COMPATIBILITY_MODE →
 * MIXED_CONTENT_NEVER_ALLOW. Cleartext is now enforced at the network
 * layer via res/xml/network_security_config.xml; this setting makes the
 * intent explicit and closes the https→http subresource path.
 */
public class WebViewFactory {

    private final MainActivity activity;
    private final SecureCredentialManager credentials;
    private final PermissionController permissionController;
    private final DownloadHandler downloadHandler;

    public WebViewFactory(@NonNull MainActivity activity,
                          @NonNull SecureCredentialManager credentials,
                          @NonNull PermissionController permissionController,
                          @NonNull DownloadHandler downloadHandler) {
        this.activity = activity;
        this.credentials = credentials;
        this.permissionController = permissionController;
        this.downloadHandler = downloadHandler;
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

        // Overrides applied after configureSettings() — these mirror the
        // pre-refactor behaviour where these calls came after the base config.
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setJavaScriptEnabled(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        ws.setAllowFileAccess(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            ws.setAllowFileAccessFromFileURLs(false);
            ws.setAllowUniversalAccessFromFileURLs(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Security batch A — was COMPATIBILITY_MODE, now NEVER_ALLOW.
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
        webView.setWebViewClient(new SpoonWebViewClient(activity));
        webView.setWebChromeClient(new SpoonWebChromeClient(activity, permissionController));

        webView.addJavascriptInterface(new BlobDownloader(activity), "AndroidDownloader");
        webView.addJavascriptInterface(new PasswordAutosaveBridge(), "SpoonVault");

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

        settings.setAllowFileAccess(true);
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
            // Security batch A — NEVER_ALLOW, mirrors the NSC base-config.
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

    /**
     * Registers {@code spoonVaultMessage} on the WebView, restricted to
     * {@code file:///android_asset/vault.html}.
     *
     * Origin check is enforced inside the listener: even though the origin
     * whitelist is "*", the handler rejects any message whose current URL is
     * not the vault page.
     */
    private void installVaultMessageListener(@NonNull WebView webView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;

        Set<String> allowedOrigins = java.util.Collections.singleton("*");
        WebViewCompat.addWebMessageListener(webView, "spoonVaultMessage", allowedOrigins,
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
            try {
                String currentUrl = view.getUrl();
                if (currentUrl == null
                        || !currentUrl.startsWith("file:///android_asset/vault.html")) {
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

    /**
     * Only exposes {@code saveCredentials}. {@code getUsername} /
     * {@code getPassword} were removed in the security pass —
     * addJavascriptInterface is NOT origin-scoped, so any page in any
     * WebView could otherwise read stored credentials for any host.
     */
    private class PasswordAutosaveBridge {
        @android.webkit.JavascriptInterface
        public void saveCredentials(String host, String username, String password) {
            if (credentials != null) {
                credentials.saveCredentials(host, username, password);
            }
        }
    }
}
