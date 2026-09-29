package com.spoondon.browser;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Owns every WebView permission flow:
 *   - HTML5 video/audio capture (getUserMedia)
 *   - Geolocation prompt
 *   - EME / Widevine (DRM) requests for known media origins
 *   - File chooser (input type="file")
 *
 * Extracted from MainActivity (god-object split, slice 4).
 *
 * This class talks directly to SpoonWebChromeClient via getters instead of
 * exposing mutable public fields on MainActivity.
 *
 * Threading: all public methods must be called on the main thread.
 */
public class PermissionController {

    // ------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------
    private final MainActivity activity;

    private final ActivityResultLauncher<String[]> webPermissionLauncher;
    private final ActivityResultLauncher<Intent> fileChooserLauncher;

    @Nullable private PermissionRequest currentPermissionRequest;
    @Nullable private GeolocationPermissions.Callback currentGeolocationCallback;
    @Nullable private String currentGeolocationOrigin;

    @Nullable private ValueCallback<Uri[]> mFilePathCallback;

    // ------------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------------
    public PermissionController(@NonNull MainActivity activity) {
        this.activity = activity;

        this.webPermissionLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                this::onWebPermissionResult);

        this.fileChooserLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> onFileChooserResult(result.getResultCode(), result.getData()));
    }

    // ------------------------------------------------------------------------
    // Called by SpoonWebChromeClient — HTML5 permissions
    // ------------------------------------------------------------------------
    public void onPermissionRequest(@NonNull PermissionRequest request) {
        Uri origin = request.getOrigin();
        String host = (origin != null && origin.getHost() != null)
                ? origin.getHost().toLowerCase(Locale.ROOT)
                : "";

        List<String> autoGrantResources = new ArrayList<>();
        List<String> osPermissionsToRequest = new ArrayList<>();

        for (String resource : request.getResources()) {
            if (resource.equals(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) {
                // Auto-grant EME (Widevine) on well-known DRM origins.
                if (host.endsWith("youtube.com")
                        || host.endsWith("googlevideo.com")
                        || host.endsWith("twitch.tv")
                        || host.endsWith("googleusercontent.com")
                        || host.contains("spotify.com")) {
                    autoGrantResources.add(resource);
                }
            } else if (resource.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                osPermissionsToRequest.add(Manifest.permission.CAMERA);
            } else if (resource.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                osPermissionsToRequest.add(Manifest.permission.RECORD_AUDIO);
            }
        }

        if (!osPermissionsToRequest.isEmpty()) {
            currentPermissionRequest = request;
            webPermissionLauncher.launch(osPermissionsToRequest.toArray(new String[0]));
        } else if (!autoGrantResources.isEmpty()) {
            request.grant(autoGrantResources.toArray(new String[0]));
        } else {
            request.deny();
        }
    }

    public void onPermissionRequestCanceled(@NonNull PermissionRequest request) {
        if (currentPermissionRequest == request) {
            currentPermissionRequest = null;
        }
    }

    // ------------------------------------------------------------------------
    // Called by SpoonWebChromeClient — geolocation
    // ------------------------------------------------------------------------
    public void onGeolocationPermissionRequest(@NonNull String origin,
                                               @NonNull GeolocationPermissions.Callback callback) {
        currentGeolocationOrigin = origin;
        currentGeolocationCallback = callback;
        webPermissionLauncher.launch(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        });
    }

    public void onGeolocationPermissionHidePrompt() {
        currentGeolocationOrigin = null;
        currentGeolocationCallback = null;
    }

    // ------------------------------------------------------------------------
    // Called by SpoonWebChromeClient — file chooser
    // ------------------------------------------------------------------------
    @Nullable
    public ValueCallback<Uri[]> getFilePathCallback() {
        return mFilePathCallback;
    }

    /**
     * @return true if a file chooser was launched; false if the intent failed.
     */
    public boolean showFileChooser(@NonNull ValueCallback<Uri[]> filePathCallback,
                                   @NonNull WebChromeClient.FileChooserParams fileChooserParams) {
        // Cancel any pending request from a prior chooser.
        if (mFilePathCallback != null) {
            mFilePathCallback.onReceiveValue(null);
        }
        mFilePathCallback = filePathCallback;

        Intent intent = fileChooserParams.createIntent();
        try {
            fileChooserLauncher.launch(intent);
            return true;
        } catch (android.content.ActivityNotFoundException e) {
            mFilePathCallback = null;
            return false;
        }
    }

    // ------------------------------------------------------------------------
    // Activity result handlers
    // ------------------------------------------------------------------------
    private void onWebPermissionResult(Map<String, Boolean> result) {
        if (currentPermissionRequest != null) {
            List<String> granted = new ArrayList<>();
            Boolean cam = result.get(Manifest.permission.CAMERA);
            Boolean mic = result.get(Manifest.permission.RECORD_AUDIO);
            if (cam != null && cam) granted.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
            if (mic != null && mic) granted.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE);

            if (!granted.isEmpty()) {
                currentPermissionRequest.grant(granted.toArray(new String[0]));
            } else {
                currentPermissionRequest.deny();
            }
            currentPermissionRequest = null;
        }

        if (currentGeolocationCallback != null) {
            Boolean fine = result.get(Manifest.permission.ACCESS_FINE_LOCATION);
            Boolean coarse = result.get(Manifest.permission.ACCESS_COARSE_LOCATION);
            boolean granted = (fine != null && fine) || (coarse != null && coarse);
            currentGeolocationCallback.invoke(currentGeolocationOrigin, granted, false);
            currentGeolocationCallback = null;
            currentGeolocationOrigin = null;
        }
    }

    private void onFileChooserResult(int resultCode, @Nullable Intent data) {
        if (mFilePathCallback == null) return;
        Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        mFilePathCallback.onReceiveValue(results);
        mFilePathCallback = null;
    }
}
