package com.spoondon.browser;

import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

/**
 * WebChromeClient bridge.
 *
 * Slice 4 change: permission callbacks are now delegated to
 * {@link PermissionController} instead of reaching into MainActivity's
 * public fields.
 */
public class SpoonWebChromeClient extends WebChromeClient {

    private final MainActivity activity;
    private final PermissionController permissionController;

    public SpoonWebChromeClient(MainActivity activity,
                                PermissionController permissionController) {
        this.activity = activity;
        this.permissionController = permissionController;
    }

    // ------------------------------------------------------------------------
    // Window creation (target="_blank" and window.open)
    // ------------------------------------------------------------------------
    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog,
                                  boolean isUserGesture, Message resultMsg) {
        WebView dummyWebView = new WebView(view.getContext());
        dummyWebView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView tempView,
                                                    android.webkit.WebResourceRequest request) {
                String rawUrl = request.getUrl().toString();
                if (rawUrl.contains(" ")
                        && (rawUrl.contains("http://") || rawUrl.contains("https://"))) {
                    int idx = rawUrl.indexOf("http");
                    if (idx != -1) rawUrl = rawUrl.substring(idx).trim();
                }
                activity.openUrlInNewTab(rawUrl);
                return true;
            }
        });

        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        transport.setWebView(dummyWebView);
        resultMsg.sendToTarget();
        return true;
    }

    // ------------------------------------------------------------------------
    // Fullscreen video
    // ------------------------------------------------------------------------
    @Override
    public void onShowCustomView(View view, CustomViewCallback callback) {
        if (activity.customView != null) {
            callback.onCustomViewHidden();
            return;
        }
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
        activity.customView = view;
        activity.customViewCallback = callback;
        activity.customView.setKeepScreenOn(true);
        activity.setToolbarVisible(false);
        activity.setBrowserVisible(false);
        activity.attachFullscreenView(activity.customView);
    }

    @Override
    public void onHideCustomView() {
        activity.setToolbarVisible(true);
        activity.setBrowserVisible(true);
        if (activity.customView != null) {
            activity.detachFullscreenView(activity.customView);
        }
        if (activity.customViewCallback != null) {
            activity.customViewCallback.onCustomViewHidden();
        }
        activity.customView = null;
        activity.customViewCallback = null;
    }

    // ------------------------------------------------------------------------
    // HTML5 permissions — delegated
    // ------------------------------------------------------------------------
    @Override
    public void onPermissionRequest(PermissionRequest request) {
        permissionController.onPermissionRequest(request);
    }

    @Override
    public void onPermissionRequestCanceled(PermissionRequest request) {
        permissionController.onPermissionRequestCanceled(request);
    }

    // ------------------------------------------------------------------------
    // Geolocation — delegated
    // ------------------------------------------------------------------------
    @Override
    public void onGeolocationPermissionsShowPrompt(
            String origin, android.webkit.GeolocationPermissions.Callback callback) {
        permissionController.onGeolocationPermissionRequest(origin, callback);
    }

    @Override
    public void onGeolocationPermissionsHidePrompt() {
        permissionController.onGeolocationPermissionHidePrompt();
    }

    // ------------------------------------------------------------------------
    // File chooser — delegated
    // ------------------------------------------------------------------------
    @Override
    public boolean onShowFileChooser(WebView webView,
                                     android.webkit.ValueCallback<android.net.Uri[]> filePathCallback,
                                     FileChooserParams fileChooserParams) {
        return permissionController.showFileChooser(filePathCallback, fileChooserParams);
    }
}
