package com.spoondon.browser;

import android.graphics.Bitmap;
import android.webkit.WebView;
import java.util.UUID;

public class TabState {
    private final String id;
    private WebView webView;
    private String url;
    private String title;
    private Bitmap thumbnail;

    public TabState(WebView webView) {
        this.id = UUID.randomUUID().toString();
        this.webView = webView;
        this.url = "about:blank";
        this.title = "New Tab";
    }

    public String getId() {
        return id;
    }

    public WebView getWebView() {
        return webView;
    }

    public void setWebView(WebView webView) {
        this.webView = webView;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Bitmap getThumbnail() {
        return thumbnail;
    }

    public void setThumbnail(Bitmap thumbnail) {
        if (this.thumbnail != null && !this.thumbnail.isRecycled()) {
            this.thumbnail.recycle();
        }
        this.thumbnail = thumbnail;
    }

    public void destroy() {
        if (thumbnail != null && !thumbnail.isRecycled()) {
            thumbnail.recycle();
            thumbnail = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }
}
