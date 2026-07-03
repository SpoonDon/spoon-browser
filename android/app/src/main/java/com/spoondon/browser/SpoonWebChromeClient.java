package com.spoondon.browser;

import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SpoonWebChromeClient extends WebChromeClient {

    private final MainActivity activity;

    public SpoonWebChromeClient(MainActivity activity) {
        this.activity = activity;
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

        List<String> allowedResources = new ArrayList<>();

        for (String resource : request.getResources()) {
            // Strict Firewall Rules: YouTube DRM allowed, absolute privacy for camera/mic hooks
            if (resource.equals(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) {
                if (host.endsWith("youtube.com") || 
                    host.endsWith("googlevideo.com") || 
                    host.endsWith("twitch.tv") || 
                    host.endsWith("spotify.com")) {
                    allowedResources.add(resource);
                }
            }
        }

        if (!allowedResources.isEmpty()) {
            request.grant(allowedResources.toArray(new String[0]));
        } else {
            request.deny();
        }
    }
}
