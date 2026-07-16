package com.spoondon.browser;

import android.os.Build;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SpoonWebChromeClient extends WebChromeClient {

    private final MainActivity activity;

    public SpoonWebChromeClient(MainActivity activity) {
        this.activity = activity;
    }

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
        WebView dummyWebView = new WebView(view.getContext());
        dummyWebView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView tempView, android.webkit.WebResourceRequest request) {
                String rawUrl = request.getUrl().toString();
                
                if (rawUrl.contains(" ") && (rawUrl.contains("http://") || rawUrl.contains("https://"))) {
                    int httpIndex = rawUrl.indexOf("http");
                    if (httpIndex != -1) {
                        rawUrl = rawUrl.substring(httpIndex).trim();
                    }
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
        activity.toolbar.setVisibility(View.GONE);
        activity.browserContainer.setVisibility(View.GONE);
        activity.root.addView(activity.customView);
    }

    @Override
    public void onHideCustomView() {
        activity.toolbar.setVisibility(View.VISIBLE);
        activity.browserContainer.setVisibility(View.VISIBLE);
        if (activity.customView != null) {
            activity.root.removeView(activity.customView);
        }
        if (activity.customViewCallback != null) {
            activity.customViewCallback.onCustomViewHidden();
        }
        activity.customView = null;
        activity.customViewCallback = null;
    }

    @Override
    public void onPermissionRequest(final PermissionRequest request) {
        android.net.Uri origin = request.getOrigin();
        String host = (origin != null && origin.getHost() != null) 
            ? origin.getHost().toLowerCase(Locale.ROOT) 
            : "";

        List<String> autoGrantResources = new ArrayList<>();
        List<String> osPermissionsToRequest = new ArrayList<>();

        for (String resource : request.getResources()) {
            if (resource.equals(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) {
                if (host.endsWith("youtube.com") || 
                    host.endsWith("googlevideo.com") || 
                    host.endsWith("twitch.tv") || 
                    host.endsWith("googleusercontent.com") || 
                    host.contains("spotify.com")) {
                    autoGrantResources.add(resource);
                }
            } else if (resource.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                osPermissionsToRequest.add(android.Manifest.permission.CAMERA);
            } else if (resource.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                osPermissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO);
            }
        }

        if (!osPermissionsToRequest.isEmpty()) {
            activity.currentPermissionRequest = request;
            activity.requestWebPermissions(osPermissionsToRequest.toArray(new String[0]));
        } else if (!autoGrantResources.isEmpty()) {
            request.grant(autoGrantResources.toArray(new String[0]));
        } else {
            request.deny();
        }
    }

    @Override
    public void onPermissionRequestCanceled(PermissionRequest request) {
        if (activity.currentPermissionRequest == request) {
            activity.currentPermissionRequest = null;
        }
    }

    @Override
    public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback callback) {
        activity.currentGeolocationOrigin = origin;
        activity.currentGeolocationCallback = callback;
        
        activity.requestWebPermissions(new String[]{
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        });
    }

    @Override
    public void onGeolocationPermissionsHidePrompt() {
        activity.currentGeolocationOrigin = null;
        activity.currentGeolocationCallback = null;
    }

    @Override
    public boolean onShowFileChooser(WebView webView, android.webkit.ValueCallback<android.net.Uri[]> filePathCallback, WebChromeClient.FileChooserParams fileChooserParams) {
        if (activity == null) return false;

        if (activity.mFilePathCallback != null) {
            activity.mFilePathCallback.onReceiveValue(null);
        }
        activity.mFilePathCallback = filePathCallback;

        android.content.Intent intent = fileChooserParams.createIntent();
        try {
            activity.startActivityForResult(intent, MainActivity.FILECHOOSER_RESULTCODE);
        } catch (android.content.ActivityNotFoundException e) {
            activity.mFilePathCallback = null;
            return false;
        }
        return true;
    }
}
