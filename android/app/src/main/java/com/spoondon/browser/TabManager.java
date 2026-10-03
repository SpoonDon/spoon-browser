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

/**
 * Owns the tab lifecycle: creating, switching, closing, and destroying WebView
 * tabs. Also owns the tab switcher overlay UI (the fullscreen ViewPager2).
 *
 * Extracted from MainActivity (god-object split, slice 1).
 *
 * 2026-09-30 - Session restore added.
 * 2026-10-01 - Thermal tier 2: subscribes to ThermalController, throttles
 *              hidden tabs via injected requestAnimationFrame wrapping when
 *              the device is MODERATE or above, restores on show or cooldown.
 * 2026-10-03 - Thumbnail preview fix:
 *                (a) switchToTab() snapshots the OUTGOING tab before hiding
 *                    it, so every tab that has ever been foregrounded
 *                    carries a valid thumbnail for the switcher.
 *                (b) showTabSwitcher() captures the CURRENT tab
 *                    synchronously (was async via mainHandler.post, which
 *                    raced the ViewPager2 bind and caused the first-open
 *                    black tile), and does so BEFORE pauseTimers().
 *                (c) Tabs that have never been foregrounded (restored-from-
 *                    session, opened-in-background) fall back to a letter
 *                    tile drawn by TabAdapter instead of pure black.
 *
 * Threading: all public methods must be called on the main thread.
 */
public class TabManager implements ThermalController.Listener {

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
    // Thermal throttle JS
    //
    // Wraps requestAnimationFrame so callbacks fire at ~4 Hz instead of the
    // display refresh rate. Real frames still schedule normally; only the
    // callback dispatch is delayed. Idempotent (self-guarded by
    // __spoonRafThrottled) so re-injection is safe.
    //
    // Why this matters: hidden WebViews on Android still run JS and RAF -
    // Chromium does not fully suspend them on onPause(). YouTube, Twitter,
    // and news sites with ad carousels keep animating at 60 fps behind
    // whatever the user is actually reading. This cuts that work by ~15x.
    // ------------------------------------------------------------------------
    private static final String RAF_THROTTLE_JS =
            "javascript:(function() {" +
            "  if (window.__spoonRafThrottled) return;" +
            "  window.__spoonRafOrigRaf = window.requestAnimationFrame;" +
            "  window.__spoonRafOrigCaf = window.cancelAnimationFrame;" +
            "  window.__spoonRafThrottled = true;" +
            "  window.requestAnimationFrame = function(cb) {" +
            "    return setTimeout(function() {" +
            "      try { window.__spoonRafOrigRaf.call(window, cb); } catch(e) {}" +
            "    }, 250);" +
            "  };" +
            "  window.cancelAnimationFrame = function(id) {" +
            "    try { clearTimeout(id); } catch(e) {}" +
            "    try { window.__spoonRafOrigCaf.call(window, id); } catch(e) {}" +
            "  };" +
            "})();";

    private static final String RAF_RESTORE_JS =
            "javascript:(function() {" +
            "  if (!window.__spoonRafThrottled) return;" +
            "  window.__spoonRafThrottled = false;" +
            "  if (window.__spoonRafOrigRaf) window.requestAnimationFrame = window.__spoonRafOrigRaf;" +
            "  if (window.__spoonRafOrigCaf) window.cancelAnimationFrame = window.__spoonRafOrigCaf;" +
            "})();";

    private static final int THUMB_MAX_W = 480;
    private static final int THUMB_MAX_H = 1000;

    // ------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------
    private final MainActivity activity;
    private final ViewGroup browserContainer;
    private final Callbacks callbacks;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ThermalController thermalController;

    private final List<TabState> tabs = new ArrayList<>();
    private int currentPosition = -1;

    private TabAdapter tabAdapter;
    private long nextVisualStateRequestId = 1L;
    private boolean thumbnailCaptureInFlight = false;
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
        this.thermalController = ThermalController.get(activity);
        this.thermalController.addListener(this);
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

        // Snapshot the outgoing tab BEFORE we hide it. At this point the
        // outgoing WebView is still VISIBLE and laid out, so webView.draw()
        // captures real pixels. This is what makes previously-foregrounded
        // tabs show previews in the switcher without ever being re-selected.
        if (currentPosition >= 0
                && currentPosition < tabs.size()
                && currentPosition != index) {
            TabState outgoing = tabs.get(currentPosition);
            WebView outgoingWv = outgoing.getWebView();
            if (outgoingWv != null) {
                String t = outgoingWv.getTitle();
                if (t != null) outgoing.setTitle(t);
                String u = outgoingWv.getUrl();
                if (u != null) outgoing.setUrl(u);
                captureThumbnail(outgoing);
            }
        }

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

        // Re-apply the current thermal throttle state to all tabs. This
        // ensures the just-hidden tab starts throttling and the just-shown
        // tab resumes full speed, using the cached thermal status.
        applyThermalResponse(thermalController.getCurrentStatus());

        WebView active = tabs.get(index).getWebView();
        callbacks.onCurrentTabChanged(active, tabs.get(index));
        callbacks.onTabCountChanged(tabs.size());
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
     */
    public void restoreTabs(@NonNull List<PersistedTab> restored, int activeIndex) {
        if (restored == null || restored.isEmpty()) {
            createNewTab();
            return;
        }

        int count = Math.min(restored.size(), 20);

        for (int i = 0; i < count; i++) {
            PersistedTab p = restored.get(i);
            if (p == null || p.url == null || p.url.isEmpty()) continue;

            createNewTab();
            WebView wv = getCurrentWebView();
            if (wv == null) continue;

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

    // ------------------------------------------------------------------------
    // Thermal response
    // ------------------------------------------------------------------------

    /**
     * Called by ThermalController whenever the OS thermal state changes.
     * Arrives on the main thread. Status values match ThermalController.STATUS_*.
     */
    @Override
    public void onThermalStatusChanged(int status) {
        applyThermalResponse(status);
    }

    /**
     * Iterate the tab list and:
     *   - disable offscreenPreRaster when throttled (saves GPU memory and
     *     CPU spent pre-rendering content the user isn't looking at)
     *   - inject the RAF-throttle JS into hidden tabs when throttled
     *   - restore normal RAF and offscreenPreRaster when the device cools
     *
     * The visible tab is never RAF-throttled - the user is looking at it,
     * and throttling an interactive page is worse than the heat.
     */
    private void applyThermalResponse(int status) {
        boolean throttled = status >= ThermalController.STATUS_MODERATE;
        boolean critical  = status >= ThermalController.STATUS_SEVERE;

        for (int i = 0; i < tabs.size(); i++) {
            TabState tab = tabs.get(i);
            if (tab == null) continue;
            WebView wv = tab.getWebView();
            if (wv == null) continue;

            try {
                WebSettings s = wv.getSettings();
                s.setOffscreenPreRaster(!throttled);
            } catch (Exception ignored) {}

            boolean isVisible = (i == currentPosition);
            boolean shouldThrottleThisTab = throttled && !isVisible;
            applyRafThrottle(wv, shouldThrottleThisTab);

            if (critical && !isVisible) {
                try {
                    wv.setLayerType(View.LAYER_TYPE_NONE, null);
                } catch (Exception ignored) {}
            } else {
                try {
                    wv.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                } catch (Exception ignored) {}
            }
        }
    }

    private void applyRafThrottle(@Nullable WebView wv, boolean throttle) {
        if (wv == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return;
        try {
            wv.evaluateJavascript(throttle ? RAF_THROTTLE_JS : RAF_RESTORE_JS, null);
        } catch (Exception ignored) {}
    }

    /**
     * Tells Chromium's renderer process to lower its own scheduling priority
     * when the tab is not visible. From Tier 1.
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
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------------
    // Renderer death / memory / lifecycle
    // ------------------------------------------------------------------------

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
        try { thermalController.removeListener(this); } catch (Exception ignored) {}

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
        // 1. Snapshot the CURRENT tab synchronously BEFORE pausing timers
        //    or building the adapter. Has to be synchronous - if deferred
        //    to mainHandler.post() (the old behaviour), the ViewPager2
        //    would bind the current row with a null thumbnail first and the
        //    user saw a black tile until the next open.
        TabState currentTab = getCurrentTabState();
        if (currentTab != null) {
            WebView tabWv = currentTab.getWebView();
            if (tabWv != null) {
                String t = tabWv.getTitle();
                currentTab.setTitle(t != null ? t : "New Tab");
                String u = tabWv.getUrl();
                if (u != null) currentTab.setUrl(u);
                captureThumbnail(currentTab);
            }
        }

        // 2. Pause timers while the switcher is open.
        WebView currentWv = getCurrentWebView();
        if (currentWv != null) currentWv.pauseTimers();

        // 3. Lazy build the overlay.
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

        // 4. Fresh adapter so every row binds against the freshest thumbnails.
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

        // 5. NEW: Async capture pass for tabs that still don't have a
        //    thumbnail. Handles restored-from-session tabs and any tab
        //    that was created but immediately switched away from before
        //    Chromium had a chance to produce a frame. Runs one tab at a
        //    time; each preview pops in via notifyItemChanged() as it
        //    becomes available.
        mainHandler.post(this::captureMissingThumbnailsAsync);
    }

    public void hideTabSwitcher() {
        // Cancel any in-flight capture chain.
        thumbnailCaptureInFlight = false;
        if (tabSwitcherOverlay != null) {
            tabSwitcherOverlay.setVisibility(View.GONE);
        }
        WebView wv = getCurrentWebView();
        if (wv != null) wv.resumeTimers();
    }

    // ------------------------------------------------------------------------
    // Async thumbnail capture (postVisualStateCallback)
    //
    // Walks every tab that still lacks a valid thumbnail and captures one
    // at a time, gated on WebView.postVisualStateCallback(). For each tab:
    //
    //   1. Temporarily set the WebView VISIBLE with alpha ~0.01 and, if it
    //      has never been measured (freshly restored tabs sit at width=0,
    //      height=0 while GONE), force a layout pass against the container
    //      bounds. The switcher overlay covers the screen, so the user
    //      never sees the tab flicker.
    //   2. Register a VisualStateCallback. Chromium fires it once the
    //      renderer has produced a visual frame - at which point
    //      WebView.draw() returns real pixels instead of a blank bitmap.
    //   3. Capture, restore visibility/alpha, notify the adapter for that
    //      row, and chain to the next missing tab after a short delay.
    //
    // A 500 ms timeout guards against pages that never reach visual state
    // (about:blank, network errors). Both paths converge on finishCapture().
    // The chain aborts automatically if the user closes the switcher.
    // ------------------------------------------------------------------------
    private void captureMissingThumbnailsAsync() {
        if (thumbnailCaptureInFlight) return;
        if (tabSwitcherOverlay == null
                || tabSwitcherOverlay.getVisibility() != View.VISIBLE) {
            return;
        }

        int targetIndex = -1;
        for (int i = 0; i < tabs.size(); i++) {
            TabState t = tabs.get(i);
            if (t == null) continue;
            Bitmap bmp = t.getThumbnail();
            if (bmp == null || bmp.isRecycled()) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex < 0) return;   // nothing left to do

        final int index = targetIndex;
        final TabState tab = tabs.get(index);
        final WebView wv = tab.getWebView();
        if (wv == null) {
            mainHandler.post(this::captureMissingThumbnailsAsync);
            return;
        }

        thumbnailCaptureInFlight = true;

        final int oldVisibility = wv.getVisibility();
        final float oldAlpha = wv.getAlpha();
        final float captureAlpha = 0.01f;

        // Set up the temporary state. alpha ~0 keeps the WebView invisible
        // to the user while still non-zero, which keeps the compositor
        // happy on all OEM ROMs (some drop alpha=0 surfaces entirely).
        try {
            wv.setAlpha(captureAlpha);
            wv.setVisibility(View.VISIBLE);
        } catch (Exception ignored) {}

        // Force a layout if the WebView has never been measured. GONE
        // children aren't measured, so restored tabs have 0x0 dimensions
        // until we lay them out manually.
        if (wv.getWidth() <= 0 || wv.getHeight() <= 0) {
            View parent = (View) wv.getParent();
            if (parent != null && parent.getWidth() > 0 && parent.getHeight() > 0) {
                int pw = parent.getWidth();
                int ph = parent.getHeight();
                try {
                    wv.measure(
                            View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY));
                    wv.layout(0, 0, pw, ph);
                } catch (Exception ignored) {}
            }
        }

        final long requestId = nextVisualStateRequestId++;

        // Timeout guard.
        final Runnable timeout = () -> {
            if (tab.getThumbnail() == null || tab.getThumbnail().isRecycled()) {
                captureThumbnail(tab);
            }
            finishCapture(tab, wv, oldVisibility, oldAlpha, index);
        };
        mainHandler.postDelayed(timeout, 500);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                wv.postVisualStateCallback(requestId, new WebView.VisualStateCallback() {
                    @Override
                    public void onComplete(long id) {
                        mainHandler.removeCallbacks(timeout);
                        if (tab.getThumbnail() == null || tab.getThumbnail().isRecycled()) {
                            captureThumbnail(tab);
                        }
                        finishCapture(tab, wv, oldVisibility, oldAlpha, index);
                    }
                });
            } catch (Exception e) {
                mainHandler.removeCallbacks(timeout);
                captureThumbnail(tab);
                finishCapture(tab, wv, oldVisibility, oldAlpha, index);
            }
        } else {
            // Dead branch on minSdk 24; kept for defensive parity.
            wv.postOnAnimation(() -> {
                mainHandler.removeCallbacks(timeout);
                if (tab.getThumbnail() == null || tab.getThumbnail().isRecycled()) {
                    captureThumbnail(tab);
                }
                finishCapture(tab, wv, oldVisibility, oldAlpha, index);
            });
        }
    }

    /**
     * Restore the tab's visibility/alpha, notify the adapter, and chain to
     * the next missing tab. The 60 ms gap between tabs keeps the main
     * thread responsive - measure+draw of a full-size WebView is not free.
     */
    private void finishCapture(@NonNull TabState tab,
                               @NonNull WebView wv,
                               int oldVisibility,
                               float oldAlpha,
                               int index) {
        try {
            wv.setAlpha(oldAlpha);
            wv.setVisibility(oldVisibility);
        } catch (Exception ignored) {}

        if (tabAdapter != null
                && tabSwitcherOverlay != null
                && tabSwitcherOverlay.getVisibility() == View.VISIBLE) {
            try {
                tabAdapter.notifyItemChanged(index);
            } catch (Exception ignored) {}
        }

        thumbnailCaptureInFlight = false;

        if (tabSwitcherOverlay != null
                && tabSwitcherOverlay.getVisibility() == View.VISIBLE) {
            mainHandler.postDelayed(this::captureMissingThumbnailsAsync, 60);
        }
    }

    // ------------------------------------------------------------------------
    // Thumbnail capture
    //
    // Synchronous, safe, silent on failure. MUST be called on the main
    // thread. Only captures WebViews that are currently laid out (width
    // and height > 0); a GONE WebView that has never been measured is
    // skipped - that case is handled by TabAdapter's letter-tile fallback
    // and by the async capture pass.
    //
    // Downscaling policy (2026-10-03): previews are decorative; the
    // switcher card is at most ~360dp wide. Capturing the raw WebView
    // size (1080x2000 on a modern phone) as ARGB_8888 costs ~8 MB per
    // tab - 20 tabs = ~160 MB of bitmaps sitting in the heap alongside
    // the WebViews themselves. Instead we fit into MAX_W x MAX_H and use
    // RGB_565 (no alpha - WebView content is opaque). That's ~940 KB per
    // tab, an ~8.5x reduction, with no visible quality loss at switcher
    // card size on a 3x display.
    // ------------------------------------------------------------------------
    private static final int THUMB_MAX_W = 480;
    private static final int THUMB_MAX_H = 1000;

    private void captureThumbnail(@Nullable TabState tab) {
        if (tab == null) return;
        WebView wv = tab.getWebView();
        if (wv == null) return;

        int w = wv.getWidth();
        int h = wv.getHeight();
        if (w <= 0 || h <= 0) return;

        int targetW = w;
        int targetH = h;
        if (targetW > THUMB_MAX_W || targetH > THUMB_MAX_H) {
            float scale = Math.min(
                    (float) THUMB_MAX_W / w,
                    (float) THUMB_MAX_H / h);
            targetW = Math.max(1, Math.round(w * scale));
            targetH = Math.max(1, Math.round(h * scale));
        }

        try {
            Bitmap bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(bmp);
            canvas.scale((float) targetW / w, (float) targetH / h);
            wv.draw(canvas);
            tab.setThumbnail(bmp);
        } catch (Exception ignored) {
            // Swallow - a missing thumbnail is never worth crashing over.
        }
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
