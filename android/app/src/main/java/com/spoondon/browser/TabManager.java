package com.spoondon.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages browser tabs including creation, switching, closing, and state persistence.
 * Extracted from MainActivity to improve separation of concerns and reduce complexity.
 */
public class TabManager {
    private static final String TAG = "TabManager";
    private static final String KEY_OPEN_TABS = "open_tabs";
    private static final String KEY_CURRENT_TAB = "current_tab";

    private final Context context;
    private final SharedPreferences prefs;
    private final CopyOnWriteArrayList<TabState> tabList;
    private int currentTabPosition;
    private final LinearLayout webViewContainer;
    private final TabListener listener;

    public interface TabListener {
        void onTabChanged(int position);
        void onTabAdded(int position);
        void onTabRemoved(int position);
        void onTabCountChanged(int count);
        void requestTabThumbnail(TabState tab);
    }

    public TabManager(Context context, SharedPreferences prefs, LinearLayout webViewContainer, TabListener listener) {
        this.context = context;
        this.prefs = prefs;
        this.webViewContainer = webViewContainer;
        this.listener = listener;
        this.tabList = new CopyOnWriteArrayList<>();
        this.currentTabPosition = -1;
        
        loadSavedTabs();
    }

    /**
     * Creates a new tab with a configured WebView and switches to it.
     */
    public TabState createNewTab() {
        Log.d(TAG, "Creating new tab");
        
        WebView webView = createWebView();
        TabState newTab = new TabState(webView);
        
        tabList.add(newTab);
        int newPosition = tabList.size() - 1;
        
        if (listener != null) {
            listener.onTabAdded(newPosition);
            listener.onTabCountChanged(tabList.size());
        }
        
        switchToTab(newPosition);
        saveOpenTabs();
        
        return newTab;
    }

    /**
     * Creates a new tab and loads a URL in it.
     */
    public TabState createNewTabWithUrl(String url) {
        TabState tab = createNewTab();
        if (url != null && !url.isEmpty()) {
            tab.getWebView().loadUrl(url);
            tab.setUrl(url);
        }
        return tab;
    }

    /**
     * Switches to the tab at the specified index.
     */
    public void switchToTab(int index) {
        if (index < 0 || index >= tabList.size()) {
            Log.e(TAG, "Invalid tab index: " + index);
            return;
        }

        Log.d(TAG, "Switching to tab " + index);
        
        webViewContainer.removeAllViews();
        
        TabState currentTab = tabList.get(index);
        webViewContainer.addView(currentTab.getWebView());
        
        int previousPosition = currentTabPosition;
        currentTabPosition = index;
        
        if (listener != null && previousPosition != currentTabPosition) {
            listener.onTabChanged(currentTabPosition);
        }
        
        saveCurrentTab();
    }

    /**
     * Closes the tab at the specified index.
     */
    public void closeTab(int index) {
        if (index < 0 || index >= tabList.size()) {
            Log.e(TAG, "Cannot close invalid tab index: " + index);
            return;
        }

        Log.d(TAG, "Closing tab " + index);
        
        boolean wasCurrentTab = (index == currentTabPosition);
        
        TabState tabToClose = tabList.get(index);
        tabToClose.destroy();
        tabList.remove(index);
        
        if (listener != null) {
            listener.onTabRemoved(index);
            listener.onTabCountChanged(tabList.size());
        }
        
        // If we closed the current tab, switch to another one
        if (wasCurrentTab) {
            if (tabList.isEmpty()) {
                currentTabPosition = -1;
                webViewContainer.removeAllViews();
                createNewTab(); // Always keep at least one tab
            } else if (currentTabPosition >= tabList.size()) {
                switchToTab(tabList.size() - 1);
            } else {
                switchToTab(currentTabPosition);
            }
        } else if (index < currentTabPosition) {
            // Adjust current position if we closed a tab before it
            currentTabPosition--;
            saveCurrentTab();
        }
        
        saveOpenTabs();
    }

    /**
     * Closes the current tab.
     */
    public void closeCurrentTab() {
        if (currentTabPosition >= 0) {
            closeTab(currentTabPosition);
        }
    }

    /**
     * Gets the current active tab.
     */
    public TabState getCurrentTab() {
        if (currentTabPosition >= 0 && currentTabPosition < tabList.size()) {
            return tabList.get(currentTabPosition);
        }
        return null;
    }

    /**
     * Gets the current active WebView.
     */
    public WebView getCurrentWebView() {
        TabState currentTab = getCurrentTab();
        return currentTab != null ? currentTab.getWebView() : null;
    }

    /**
     * Gets the current tab position.
     */
    public int getCurrentTabPosition() {
        return currentTabPosition;
    }

    /**
     * Gets all tabs.
     */
    @NonNull
    public List<TabState> getAllTabs() {
        return new ArrayList<>(tabList);
    }

    /**
     * Gets the total number of tabs.
     */
    public int getTabCount() {
        return tabList.size();
    }

    /**
     * Gets the tab at the specified index.
     */
    public TabState getTabAt(int index) {
        if (index >= 0 && index < tabList.size()) {
            return tabList.get(index);
        }
        return null;
    }

    /**
     * Checks if there are any tabs open.
     */
    public boolean hasTabs() {
        return !tabList.isEmpty();
    }

    /**
     * Updates the title of the current tab.
     */
    public void updateCurrentTabTitle(String title) {
        TabState currentTab = getCurrentTab();
        if (currentTab != null) {
            currentTab.setTitle(title);
            saveOpenTabs();
        }
    }

    /**
     * Updates the URL of the current tab.
     */
    public void updateCurrentTabUrl(String url) {
        TabState currentTab = getCurrentTab();
        if (currentTab != null) {
            currentTab.setUrl(url);
            saveOpenTabs();
        }
    }

    /**
     * Sets a thumbnail for the specified tab.
     */
    public void setTabThumbnail(int index, Bitmap thumbnail) {
        if (index >= 0 && index < tabList.size()) {
            tabList.get(index).setThumbnail(thumbnail);
        }
    }

    /**
     * Navigates to the next tab.
     */
    public void navigateToNextTab() {
        if (tabList.size() > 1) {
            int nextIndex = (currentTabPosition + 1) % tabList.size();
            switchToTab(nextIndex);
        }
    }

    /**
     * Navigates to the previous tab.
     */
    public void navigateToPreviousTab() {
        if (tabList.size() > 1) {
            int prevIndex = (currentTabPosition - 1 + tabList.size()) % tabList.size();
            switchToTab(prevIndex);
        }
    }

    /**
     * Builds a list of BrowserItems for displaying tabs in UI.
     */
    @NonNull
    public List<BrowserItem> buildTabItems() {
        List<BrowserItem> items = new ArrayList<>();
        for (TabState tab : tabList) {
            BrowserItem item = new BrowserItem(
                tab.getTitle() != null ? tab.getTitle() : "New Tab",
                tab.getUrl() != null ? tab.getUrl() : "about:blank"
            );
            items.add(item);
        }
        return items;
    }

    /**
     * Cleans up all tabs and resources.
     */
    public void destroy() {
        Log.d(TAG, "Destroying TabManager");
        for (TabState tab : tabList) {
            tab.destroy();
        }
        tabList.clear();
        currentTabPosition = -1;
    }

    //region Private Methods

    private WebView createWebView() {
        WebView webView = new WebView(context);
        webView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ));
        webView.setId(View.generateViewId());
        return webView;
    }

    private void loadSavedTabs() {
        try {
            String savedTabsJson = prefs.getString(KEY_OPEN_TABS, null);
            int savedCurrentTab = prefs.getInt(KEY_CURRENT_TAB, -1);
            
            if (savedTabsJson != null && !savedTabsJson.isEmpty()) {
                // Parse and restore tabs from JSON
                // For now, just create a new tab if no tabs exist
                if (tabList.isEmpty()) {
                    createNewTab();
                }
            } else {
                createNewTab();
            }
            
            if (savedCurrentTab >= 0 && savedCurrentTab < tabList.size()) {
                currentTabPosition = savedCurrentTab;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error loading saved tabs", e);
            createNewTab();
        }
    }

    private void saveOpenTabs() {
        try {
            // Save tab URLs and titles
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (int i = 0; i < tabList.size(); i++) {
                TabState tab = tabList.get(i);
                if (i > 0) sb.append(",");
                sb.append("{\"url\":\"")
                  .append(escapeJson(tab.getUrl()))
                  .append("\",\"title\":\"")
                  .append(escapeJson(tab.getTitle()))
                  .append("\"}");
            }
            sb.append("]");
            
            prefs.edit().putString(KEY_OPEN_TABS, sb.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving open tabs", e);
        }
    }

    private void saveCurrentTab() {
        prefs.edit().putInt(KEY_CURRENT_TAB, currentTabPosition).apply();
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    //endregion
}
