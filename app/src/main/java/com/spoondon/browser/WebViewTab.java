package com.spoondon.browser;

import android.graphics.Bitmap;
import android.webkit.WebView;

public class WebViewTab {
    private final WebView webView;
    private String title;
    private Bitmap thumbnail;

    public WebViewTab(WebView webView, String url) {
        this.webView = webView;
        this.title = url;
        this.thumbnail = null;
    }

    public WebView getWebView() {
        return webView;
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
        this.thumbnail = thumbnail;
    }
}
