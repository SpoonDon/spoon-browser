package com.spoondon.browser;

public final class BrowserItem {

    public final String title;
    public final String url;
    public final String displayHost; // Caches the domain so the UI thread doesn't have to compute it

    public BrowserItem(String title, String url) {
        this.title = title != null ? title : "Untitled";
        this.url = url != null ? url : "about:blank";

        // OPTIMIZATION: Parse the host once during object creation, not during UI scrolling
        String extractedHost = this.url;
        try {
            String host = android.net.Uri.parse(this.url).getHost();
            if (host != null) {
                extractedHost = host.startsWith("www.") ? host.substring(4) : host;
            }
        } catch (Exception ignored) {}
        
        this.displayHost = extractedHost;
    }
}
