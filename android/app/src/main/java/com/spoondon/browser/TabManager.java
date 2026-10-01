package com.spoondon.browser;

import android.content.ComponentCallbacks2;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager2.widget.CompositePageTransformer;
import androidx.viewpager2.widget.MarginPageTransformer;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Owns the tab lifecycle: creating, switching, closing, and destroying WebView
 * tabs. Also owns the tab switcher overlay UI (the fullscreen ViewPager2).
 *
 * Extracted from MainActivity (god-object split, slice 1).
 *
 * 2026-09-30 - Session restore added:
 *   - snapshotForPersistence() returns a List<PersistedTab> for SessionManager
 *     to write on onPause.
 *   - restoreTabs(List<PersistedTab>, int) re-creates the tab list on cold
 *     start and loads each URL. This is the ONLY way tab state is persisted
 *     across process death - we deliberately never touch
 *     WebView.saveState()/restoreState(), which is process-scoped and causes
 *     cold-start crashes.
 *
 * Threading: all public methods must be called on the main thread.
 */
public class TabManager {

    // ------------------------------------------------------------------------
    // Callbacks into the owning Activity
    // ------------------------------------------------------------------------
    public interface Callbacks {
        /** Create and configure a WebView. Called once per new tab. */
        @NonNull
        WebView createConfiguredWebView();

        /** Called after the active tab changes (or becomes null). */
        void onCurrentTabChanged(@Nullable WebView webView, @Nullable TabState state);

        /** Called whenever the number of open tabs changes. */
        void onTabCountChanged(int count);

        /** Called when the user requests a new tab from the switcher "+" button. */
        void onNewTabRequested();

        /** Called when the last tab is closed and the app should exit. */
        void onAllTabsClosed();
    }

    // ------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------
    private final MainActivity activity;
    private final ViewGroup browserContainer;
    private final Callbacks callbacks;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final List<TabState> tabs = new ArrayList<>();
    private int currentPosition = -1;

    private TabAdapter tabAdapter;
    private View tabSwitcherOverlay;

    // ------------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------------
    public TabManager(@NonNull MainActivity activity,
                      @NonNull ViewGroup browserContainer,
                      @NonNull Callbacks callbacks) {
        this.activity = activity;
        this.browserContainer = browserContainer;
        this.callbacks = callbacks;
    }

    // ------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------
    public int getTabCount()       { return tabs.size(); }
    public int getCurrentPosition(){ return currentPosition; }
    public boolean isEmpty()       { return tabs.isEmpty(); }
    public List<TabState> getTabs(){ return tabs; }

    @Nullable
    public WebView getCurrentWebView() {
        if (currentPosition >= 0 && currentPosition < tabs.size()) {
            return tabs.get(currentPosition).getWebView();
        }
        return null;
    }

    @Nullable
    public TabState getCurrentTabState() {
        if (currentPosition >= 0 && currentPosition < tabs.size()) {
            return tabs.get(currentPosition);
        }
        return null;
    }

    public boolean isTabSwitcherVisible() {
        return tabSwitcherOverlay != null
                && tabSwitcherOverlay.getVisibility() == View.VISIBLE;
    }

    // ------------------------------------------------------------------------
    // Tab lifecycle
    // ------------------------------------------------------------------------
    public void createNewTab() {
        createNewTab(false);
    }

    public void createNewTab(boolean isIncognito) {
        WebView webView = callbacks.createConfiguredWebView();

        WebSettings settings = webView.getSettings();
        if (isIncognito) {
            settings.setSaveFormData(false);
            settings.setDomStorageEnabled(false);
            settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        } else {
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        }

        TabState newTab = new TabState(webView, isIncognito);
        tabs.add(newTab);
        currentPosition = tabs.size() - 1;

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        webView.setVisibility(View.GONE);
        browserContainer.addView(webView, params);

        switchToTab(currentPosition);
        callbacks.onTabCountChanged(tabs.size());
    }

    public void switchToTab(int index) {        
        if (index < 0 || index >= tabs.size()) return;
        currentPosition = index;

        for (int i = 0; i < tabs.size(); i++) {
            WebView wv = tabs.get(i).getWebView();
            if (wv == null) continue;
            if (i == index) {
                wv.setVisibility(View.VISIBLE);
                wv.onResume();
                wv.resumeTimers();
                applyRendererPriority(wv, true);
            } else {
                wv.setVisibility(View.GONE);
                wv.onPause();
                applyRendererPriority(wv, false);
            }
        }

        WebView active = tabs.get(index).getWebView();
        callbacks.onCurrentTabChanged(active, tabs.get(index));
        callbacks.onTabCountChanged(tabs.size());
    }

    /**
     * Tells Chromium's renderer process to lower its own scheduling priority
     * when the tab is not visible. Without this, background tabs continue to
     * run JS timers, CSS animations, and media at full priority - a major
     * source of sustained thermal load on multi-tab sessions.
     *
     * API 26+. The waivedWhenNotVisible flag is Chromium's own hint that the
     * renderer may be killed outright under memory pressure; we set it true
     * for hidden tabs because our TabManager already handles renderer death
     * via handleDeadRenderProcess().
     */
    private void applyRendererPriority(@NonNull WebView webView, boolean foreground) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            if (foreground) {
                webView.setRendererPriorityPolicy(
                        WebView.RENDERER_PRIORITY_IMPORTANT, false);
            } else {
                webView.setRendererPriorityPolicy(
                        WebView.RENDERER_PRIORITY_WAIVED, true);
            }
        } catch (Exception ignored) {
            // Some OEM WebView builds throw on this; not worth crashing over.
        }
    }

    public void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;

        if (tabs.size() == 1) {
            callbacks.onAllTabsClosed();
            return;
        }

        TabState tabToRemove = tabs.get(index);
        WebView wvToDestroy = tabToRemove.getWebView();

        if (tabToRemove.isIncognito() && wvToDestroy != null) {
            wvToDestroy.clearCache(true);
            wvToDestroy.clearFormData();
            wvToDestroy.clearHistory();
            WebStorage.getInstance().deleteAllData();
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
        }

        if (wvToDestroy != null) {
            browserContainer.removeView(wvToDestroy);
            wvToDestroy.removeAllViews();
            wvToDestroy.destroy();
        }

        // Properly destroy TabState - recycles the thumbnail bitmap.
        // Previously leaked because this was never called.
        tabToRemove.destroy();

        tabs.remove(index);

        if (tabAdapter != null) {
            tabAdapter.notifyItemRemoved(index);
            tabAdapter.notifyItemRangeChanged(0, tabs.size());
        }

        if (index < currentPosition) {
            currentPosition--;
        } else if (currentPosition >= tabs.size()) {
            currentPosition = tabs.size() - 1;
        }

        switchToTab(currentPosition);
        callbacks.onTabCountChanged(tabs.size());
    }


    // ------------------------------------------------------------------------
    // Session restore
    // ------------------------------------------------------------------------

    /**
     * Re-create tabs from a persisted list, loading each URL. Called from
     * MainActivity.onCreate when savedInstanceState is null and the
     * SessionManager returns a non-empty list.
     *
     * Each restored tab is created via createNewTab(), so it inherits the
     * standard WebView settings, JS bridges, and download handler. We then
     * apply the desktop-UA preference BEFORE loadUrl so the first request
     * goes out with the correct User-Agent. After that, normal page
     * lifecycle takes over (SpoonWebViewClient.onPageStarted re-applies
     * the UA idempotently).
     *
     * Failure modes deliberately not handled here:
     *   - Bad URL -> WebView's own network error page. No crash.
     *   - Corrupt list -> SessionManager.loadPersistedTabs() already
     *     wiped it and returned an empty list, so this method is only
     *     called with valid entries.
     *   - Renderer death during load -> handleDeadRenderProcess fires
     *     and drops the tab. Restore is idempotent, so a subsequent
     *     cold start just re-attempts.
     */
    public void restoreTabs(@NonNull List<PersistedTab> restored, int activeIndex) {
        if (restored == null || restored.isEmpty()) {
            createNewTab();
            return;
        }

        // Defensive cap. SessionManager enforces MAX_TABS on write and
        // read, but a manual prefs edit could produce a longer list.
        int count = Math.min(restored.size(), 20);

        for (int i = 0; i < count; i++) {
            PersistedTab p = restored.get(i);
            if (p == null || p.url == null || p.url.isEmpty()) continue;

            createNewTab();
            WebView wv = getCurrentWebView();
            if (wv == null) continue;

            // Apply desktop UA before loadUrl so the initial request
            // carries the correct User-Agent.
            try {
                String host = android.net.Uri.parse(p.url).getHost();
                if (host != null) {
                    boolean desktop = NavigationHelper.isDesktopHostEnabled(activity, host);
                    NavigationHelper.applyDesktopUa(wv, desktop, activity);
                }
            } catch (Exception ignored) {}

            wv.loadUrl(p.url);
        }

        if (activeIndex >= 0 && activeIndex < tabs.size()) {
            switchToTab(activeIndex);
        } else if (!tabs.isEmpty()) {
            switchToTab(tabs.size() - 1);
        }
    }

    /**
     * Snapshot the current tab list for SessionManager to write to disk.
     * Filters out incognito tabs, blank pages, and the vault URL.
     *
     * Called on the main thread from SessionManager.persistTabs() during
     * Activity.onPause. Never blocks - reads only cached WebView fields.
     */
    @NonNull
    public List<PersistedTab> snapshotForPersistence() {
        List<PersistedTab> out = new ArrayList<>();
        for (TabState tab : tabs) {
            if (tab == null || tab.isIncognito()) continue;
            WebView wv = tab.getWebView();
            if (wv == null) continue;

            String url = wv.getUrl();
            if (url == null || url.isEmpty()) continue;
            if (url.equals("about:blank")) continue;
            if (VaultUrls.isVaultUrl(url)) continue;

            String title = wv.getTitle();
            out.add(new PersistedTab(url, title != null ? title : ""));

            if (out.size() >= 20) break;
        }
        return out;
    }

    /** Called by SpoonWebViewClient when the renderer dies. */
    public void handleDeadRenderProcess(WebView deadWebView) {
        if (deadWebView == null) return;
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).getWebView() == deadWebView) {
                browserContainer.removeView(deadWebView);
                deadWebView.removeAllViews();
                deadWebView.destroy();
                Toast.makeText(activity,
                        "Tab closed automatically to free up device memory.",
                        Toast.LENGTH_LONG).show();
                closeTab(i);
                break;
            }
        }
    }

    /** Called from MainActivity.onTrimMemory. */
    public void onTrimMemory(int level) {
        if (level != ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                && level != ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            return;
        }
        for (TabState tab : tabs) {
            if (tab == null) continue;
            WebView wv = tab.getWebView();
            if (wv != null && wv.getVisibility() == View.GONE) {
                wv.clearCache(false);
            }
        }
    }

    /** Called from MainActivity.onDestroy. */
    public void destroyAll() {
        for (TabState tab : tabs) {
            if (tab == null) continue;
            WebView wv = tab.getWebView();
            if (wv != null) {
                if (wv.getParent() instanceof ViewGroup) {
                    ((ViewGroup) wv.getParent()).removeView(wv);
                }
                wv.stopLoading();
                wv.setWebChromeClient(null);
                wv.setWebViewClient(null);
                wv.destroy();
            }
            tab.destroy();
        }
        tabs.clear();
    }

    /** Called from MainActivity.onPause. */
    public void pauseActiveTab() {
        WebView wv = getCurrentWebView();
        if (wv != null) {
            wv.onPause();
            wv.pauseTimers();
        }
    }

    /** Called from MainActivity.onResume. */
    public void resumeActiveTab() {
        WebView wv = getCurrentWebView();
        if (wv != null) {
            wv.onResume();
            wv.resumeTimers();
        }
    }

    public void openUrlInNewTab(String url) {
        mainHandler.post(() -> {
            createNewTab();
            WebView newTab = getCurrentWebView();
            if (newTab != null) newTab.loadUrl(url);
            Toast.makeText(activity, "Opened in new tab", Toast.LENGTH_SHORT).show();
        });
    }

    // ------------------------------------------------------------------------
    // Tab switcher overlay
    // ------------------------------------------------------------------------
    public void showTabSwitcher() {
        WebView currentWv = getCurrentWebView();
        if (currentWv != null) currentWv.pauseTimers();

        // Snapshot the active tab for its switcher card.
        TabState currentTab = getCurrentTabState();
        if (currentTab != null) {
            WebView tabWv = currentTab.getWebView();
            if (tabWv != null) {
                currentTab.setTitle(tabWv.getTitle() != null ? tabWv.getTitle() : "New Tab");
                currentTab.setUrl(tabWv.getUrl() != null ? tabWv.getUrl() : "");
                captureWebViewSnapshotAsync(tabWv, bitmap -> {
                    currentTab.setThumbnail(bitmap);
                    if (tabAdapter != null) tabAdapter.notifyItemChanged(currentPosition);
                });
            }
        }

        if (tabSwitcherOverlay == null) {
            LayoutInflater inflater = LayoutInflater.from(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            tabSwitcherOverlay = inflater.inflate(R.layout.layout_tab_switcher, root, false);
            root.addView(tabSwitcherOverlay);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                tabSwitcherOverlay.setOnApplyWindowInsetsListener((v, insets) -> {
                    View titleBar = v.findViewById(R.id.tabTitleBar);
                    if (titleBar != null) {
                        int statusBarHeight = insets.getSystemWindowInsetTop();
                        ViewGroup.LayoutParams params = titleBar.getLayoutParams();
                        params.height = (int) (56 * activity.getResources()
                                .getDisplayMetrics().density) + statusBarHeight;
                        titleBar.setLayoutParams(params);
                        titleBar.setPadding(
                                titleBar.getPaddingLeft(),
                                statusBarHeight,
                                titleBar.getPaddingRight(),
                                titleBar.getPaddingBottom());
                    }
                    return insets;
                });
                tabSwitcherOverlay.requestApplyInsets();
            }

            tabSwitcherOverlay.findViewById(R.id.btnNewTab).setOnClickListener(v -> {
                callbacks.onNewTabRequested();
                hideTabSwitcher();
            });
            tabSwitcherOverlay.findViewById(R.id.btnBackToBrowser).setOnClickListener(v ->
                    hideTabSwitcher());
        }

        tabAdapter = new TabAdapter(tabs, new TabAdapter.OnTabActionListener() {
            @Override
            public void onTabSelected(int position) {
                switchToTab(position);
                hideTabSwitcher();
            }

            @Override
            public void onTabClosed(int position) {
                closeTab(position);
                if (tabSwitcherOverlay != null) {
                    ViewPager2 pager = tabSwitcherOverlay.findViewById(R.id.tabsViewPager);
                    if (pager != null) pager.post(pager::requestTransform);
                }
                if (tabs.isEmpty()) {
                    callbacks.onNewTabRequested();
                    hideTabSwitcher();
                }
            }
        });

        ViewPager2 pager = tabSwitcherOverlay.findViewById(R.id.tabsViewPager);
        pager.setAdapter(tabAdapter);
        pager.setOffscreenPageLimit(3);

        CompositePageTransformer transformer = new CompositePageTransformer();
        transformer.addTransformer(new MarginPageTransformer(24));
        transformer.addTransformer((page, position) -> {
            float r = 1 - Math.abs(position);
            page.setScaleY(0.85f + r * 0.15f);
            page.setAlpha(0.5f + r * 0.5f);
        });
        pager.setPageTransformer(transformer);
        pager.setCurrentItem(currentPosition, false);

        tabSwitcherOverlay.setVisibility(View.VISIBLE);
    }

    public void hideTabSwitcher() {
        if (tabSwitcherOverlay != null) {
            tabSwitcherOverlay.setVisibility(View.GONE);
        }
        WebView wv = getCurrentWebView();
        if (wv != null) wv.resumeTimers();
    }

    // ------------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------------
    private void captureWebViewSnapshotAsync(WebView webView, Consumer<Bitmap> callback) {
        if (webView == null) {
            callback.accept(null);
            return;
        }
        mainHandler.post(() -> {
            try {
                int w = webView.getWidth();
                int h = webView.getHeight();
                if (w <= 0 || h <= 0) {
                    callback.accept(null);
                    return;
                }
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                webView.draw(canvas);
                callback.accept(bmp);
            } catch (Exception e) {
                callback.accept(null);
            }
        });
    }

    // ------------------------------------------------------------------------
    // Legacy persistence helper (kept for backward compat - no callers)
    // ------------------------------------------------------------------------
    public List<String> getNonIncognitoUrls() {
        List<String> urls = new ArrayList<>();
        for (TabState tab : tabs) {
            if (tab.isIncognito()) continue;
            WebView wv = tab.getWebView();
            String url = wv != null ? wv.getUrl() : null;
            if (url != null && !url.isEmpty() && !url.equals("about:blank")) {
                urls.add(url);
            }
        }
        return urls;
    }
}
