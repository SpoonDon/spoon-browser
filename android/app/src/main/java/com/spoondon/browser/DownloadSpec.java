package com.spoondon.browser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Immutable parameters for a new download.
 *
 * Created by DownloadsController.enqueue() and handed to DownloadEngine.
 * Cookies are captured here (not fetched inside the task) because the
 * download may outlive the WebView that triggered it.
 */
public final class DownloadSpec {

    @NonNull public final String url;
    @NonNull public final String fileName;
    @Nullable public final String mime;
    @Nullable public final String userAgent;
    @Nullable public final String referer;
    @Nullable public final String cookies;

    public DownloadSpec(@NonNull String url,
                        @NonNull String fileName,
                        @Nullable String mime,
                        @Nullable String userAgent,
                        @Nullable String referer,
                        @Nullable String cookies) {
        this.url = url;
        this.fileName = fileName;
        this.mime = mime;
        this.userAgent = userAgent;
        this.referer = referer;
        this.cookies = cookies;
    }
}
