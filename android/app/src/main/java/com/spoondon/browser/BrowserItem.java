package com.spoondon.browser;

import android.graphics.Bitmap;

public class BrowserItem {

    public final Bitmap icon;
    public final String title;
    public final String url;

    public BrowserItem(
            Bitmap icon,
            String title,
            String url) {

        this.icon = icon;
        this.title = title;
        this.url = url;
    }
}
