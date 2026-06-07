package com.spoondon.browser;

import android.webkit.WebView;

import java.util.ArrayList;

public class TabManager {

    private final ArrayList<WebView> tabs = new ArrayList<>();
    private int currentTab = 0;

    public void addTab(WebView webView) {
        tabs.add(webView);
        currentTab = tabs.size() - 1;
    }

    public void removeCurrentTab() {

        if (tabs.size() <= 1) {
            return;
        }

        tabs.remove(currentTab);

        if (currentTab >= tabs.size()) {
            currentTab = tabs.size() - 1;
        }
    }

    public WebView getCurrentTab() {
        return tabs.get(currentTab);
    }

    public int getCurrentIndex() {
        return currentTab;
    }

    public int getCount() {
        return tabs.size();
    }

    public void nextTab() {

        if (tabs.size() <= 1) {
            return;
        }

        currentTab = (currentTab + 1) % tabs.size();
    }

    public void previousTab() {

        if (tabs.size() <= 1) {
            return;
        }

        currentTab--;

        if (currentTab < 0) {
            currentTab = tabs.size() - 1;
        }
    }

    public void switchTo(int index) {

        if (index < 0 || index >= tabs.size()) {
            return;
        }

        currentTab = index;
    }
}
