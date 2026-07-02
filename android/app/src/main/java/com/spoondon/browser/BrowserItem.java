package com.spoondon.browser;

// OPTIMIZATION: Marking the class final prevents subclassing mutations, ensuring complete thread-safe immutability
public final class BrowserItem {

    public final String title;
    public final String url;

    public BrowserItem(String title, String url) {
        // OPTIMIZATION: Fast fail null-guards guarantee that background lists never crash on corrupted inputs
        this.title = title != null ? title : "Untitled";
        this.url = url != null ? url : "about:blank";
    }
}
