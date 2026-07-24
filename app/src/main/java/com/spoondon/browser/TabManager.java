package com.spoondon.browser;

import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.webkit.WebViewCompat;

import java.util.ArrayList;
import java.util.List;

public class TabManager {
    private final MainActivity activity;
    private final ViewGroup container;
    private final List<WebViewTab> tabs;
    private int currentTabIndex = -1;

    public TabManager(MainActivity activity, ViewGroup container) {
        this.activity = activity;
        this.container = container;
        this.tabs = new ArrayList<>();
    }

    public void createNewTab(String url) {
        WebView webView = ((MainActivity) activity).createConfiguredWebView(); // Access via interface or cast
        // Note: In real implementation, createConfiguredWebView should be accessible or passed in
        // For this snippet, assuming access to the method in MainActivity
        
        WebViewTab tab = new WebViewTab(webView, url);
        tabs.add(tab);
        switchToTab(tabs.size() - 1);
    }

    public void switchToTab(int index) {
        if (index < 0 || index >= tabs.size()) return;

        // Hide current
        if (currentTabIndex >= 0 && currentTabIndex < tabs.size()) {
            tabs.get(currentTabIndex).getWebView().setVisibility(android.view.View.GONE);
        }

        currentTabIndex = index;
        WebView currentWebView = tabs.get(currentTabIndex).getWebView();
        currentWebView.setVisibility(android.view.View.VISIBLE);
        
        // Ensure it's attached
        if (currentWebView.getParent() == null) {
            container.addView(currentWebView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
        }
        
        // Update URL bar
        String url = currentWebView.getUrl();
        if (url != null && activity.addressBar != null) {
            activity.addressBar.setText(url);
        }
    }

    public void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;

        WebViewTab tabToClose = tabs.remove(index);
        removeWebView(tabToClose.getWebView());

        if (tabs.isEmpty()) {
            currentTabIndex = -1;
            createNewTab("https://www.google.com");
        } else {
            if (index == currentTabIndex) {
                currentTabIndex = Math.max(0, index - 1);
            } else if (index < currentTabIndex) {
                currentTabIndex--;
            }
            switchToTab(currentTabIndex);
        }
    }

    // Recommended Proper Cleanup Order
    private void removeWebView(WebView wvToDestroy) {
        if (wvToDestroy == null) return;
        
        wvToDestroy.stopLoading();
        wvToDestroy.clearCache(true);
        wvToDestroy.removeAllViews();
        
        // Remove from parent
        if (wvToDestroy.getParent() != null) {
            ((ViewGroup) wvToDestroy.getParent()).removeView(wvToDestroy);
        }
        
        wvToDestroy.destroy();
    }

    public WebView getCurrentWebView() {
        if (currentTabIndex >= 0 && currentTabIndex < tabs.size()) {
            return tabs.get(currentTabIndex).getWebView();
        }
        return null;
    }

    public void destroyAllTabs() {
        for (WebViewTab tab : tabs) {
            removeWebView(tab.getWebView());
        }
        tabs.clear();
        currentTabIndex = -1;
    }

    public void saveState(Bundle outState) {
        // Implementation for state saving using WebViewCompat.saveState
        if (currentTabIndex >= 0 && currentTabIndex < tabs.size()) {
            Bundle webviewState = new Bundle();
            WebView current = tabs.get(currentTabIndex).getWebView();
            // Using WebViewCompat for newer Android versions compatibility
            WebViewCompat.saveState(current, webviewState);
            outState.putBundle("current_tab_state", webviewState);
            outState.putInt("current_tab_index", currentTabIndex);
            outState.putString("current_url", current.getUrl());
        }
    }

    public void restoreState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            int index = savedInstanceState.getInt("current_tab_index", 0);
            String url = savedInstanceState.getString("current_url", "https://www.google.com");
            createNewTab(url);
            // Further restoration logic would go here
        }
    }
}
