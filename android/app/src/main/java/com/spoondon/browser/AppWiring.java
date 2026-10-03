package com.spoondon.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * Constructs and wires every Spoon Browser collaborator.
 *
 * 2026-10-01 (save-history-as-bookmark):
 *   - Both ItemManagerDialog.Callbacks implementations now implement
 *     onSaveAsBookmark(ManagedItem). The history dialog routes the item
 *     to BookmarkManager.addBookmark(); the bookmark dialog's override
 *     is a no-op because the option is only shown when
 *     item.type == TYPE_HISTORY.
 *
 * 2026-10-01 (vault pill): adds VaultPillController. browserWrapper is a
 * constructor param so the pill can attach before initialize() builds
 * the WebViewFactory that pushes the SpoonVault bridge to each WebView.
 *
 * 2026-10-01 (home bookmarks grid): adds FaviconStore; HomePageRenderer
 * takes BookmarkManager + FaviconStore.
 *
 * 2026-09-30 (downloads redirect fix): triggerManualDownload routes
 * through DownloadHandler.onDownloadStart.
 */
public class AppWiring {

    // ------------------------------------------------------------------------
    // Injected dependencies
    // ------------------------------------------------------------------------
    private final MainActivity activity;
    private final LinearLayout browserContainer;
    private final FrameLayout browserWrapper;
    private final SecureCredentialManager credentials;
    private final BrowserDatabaseHelper dbHelper;
    private final PermissionController permissionController;
    private final SharedPreferences prefs;
    private final ExecutorService backgroundExecutor;
    private final CopyOnWriteArrayList<String> filterLists;

    // ------------------------------------------------------------------------
    // Controllers
    // ------------------------------------------------------------------------
    private DownloadsController downloadsController;
    private DownloadHandler downloadHandler;
    private WebViewFactory webViewFactory;
    private FaviconStore faviconStore;
    private HomePageRenderer homePageRenderer;
    private SuggestionProvider suggestionProvider;
    private TabManager tabManager;
    private HistoryController historyController;
    private BookmarkManager bookmarkManager;
    private VaultPillController vaultPillController;
    private VaultController vaultController;
    private AdBlockController adBlockController;
    private ToolbarController toolbarController;
    private MenuController menuController;

    public AppWiring(@NonNull MainActivity activity,
                     @NonNull LinearLayout browserContainer,
                     @NonNull FrameLayout browserWrapper,
                     @NonNull SecureCredentialManager credentials,
                     @NonNull BrowserDatabaseHelper dbHelper,
                     @NonNull PermissionController permissionController,
                     @NonNull SharedPreferences prefs,
                     @NonNull ExecutorService backgroundExecutor,
                     @NonNull CopyOnWriteArrayList<String> filterLists) {
        this.activity = activity;
        this.browserContainer = browserContainer;
        this.browserWrapper = browserWrapper;
        this.credentials = credentials;
        this.dbHelper = dbHelper;
        this.permissionController = permissionController;
        this.prefs = prefs;
        this.backgroundExecutor = backgroundExecutor;
        this.filterLists = filterLists;
    }

    // ========================================================================
    // Construction
    // ========================================================================

    public void initialize() {
        // ---- DownloadsController (needed by DownloadHandler) -------------
        downloadsController = new DownloadsController(activity);

        // ---- DownloadHandler ----------------------------------------------
        downloadHandler = new DownloadHandler(
                activity, activity::getCurrentWebView, downloadsController);

        // ---- VaultPillController -----------------------------------------
        vaultPillController = new VaultPillController(
                activity, credentials, activity::getCurrentWebView);
        vaultPillController.setHost(browserWrapper);

        // ---- WebViewFactory ----------------------------------------------
        webViewFactory = new WebViewFactory(
                activity, credentials, permissionController, downloadHandler,
                vaultPillController);

        suggestionProvider = new SuggestionProvider(dbHelper);

        // ---- TabManager ---------------------------------------------------
        tabManager = new TabManager(activity, browserContainer, new TabManager.Callbacks() {
            @NonNull @Override public WebView createConfiguredWebView() {
                return webViewFactory.create();
            }

            @Override public void onCurrentTabChanged(@Nullable WebView webView,
                                                      @Nullable TabState state) {
                applyTabToToolbar(webView, state);
                activity.updateScreenShield();
                if (vaultPillController != null) vaultPillController.onPageNavigated();
            }

            @Override public void onTabCountChanged(int count) {
                toolbarController.setTabCounter(
                        tabManager.getCurrentPosition(), count);
            }

            @Override public void onNewTabRequested() {
                tabManager.createNewTab();
                showHome();
            }

            @Override public void onAllTabsClosed() {
                activity.showExitConfirmationDialog();
            }
        });

        // ---- HistoryController -------------------------------------------
        // The "Save as Bookmark" callback is what makes a long-press on a
        // history row able to promote the entry into the bookmark store.
        // BookmarkManager is assigned later in this method, but the
        // anonymous class accesses the field (not a captured value), so
        // by the time the user actually triggers this callback the field
        // is non-null.
        historyController = new HistoryController(
                activity, dbHelper, backgroundExecutor,
                new HistoryController.Callbacks() {
                    @Override public void onNavigate(@NonNull String url) {
                        activity.openUrl(url);
                    }

                    @Override public void openInNewTab(@NonNull String url) {
                        tabManager.openUrlInNewTab(url);
                    }

                    @Override public void onSaveAsBookmark(@NonNull ManagedItem item) {
                        if (bookmarkManager != null) {
                            bookmarkManager.addBookmark(item.getUrl(), item.getTitle());
                        }
                    }
                });

        // ---- BookmarkManager ---------------------------------------------
        bookmarkManager = new BookmarkManager(
                activity, dbHelper,
                new ItemManagerDialog.Callbacks() {
                    @Override public void onNavigate(@NonNull String url) {
                        activity.openUrl(url);
                    }

                    @Override public void openInNewTab(@NonNull String url) {
                        tabManager.openUrlInNewTab(url);
                    }

                    @Override public void onSaveAsBookmark(@NonNull ManagedItem item) {
                        // Not reachable: the option only appears for
                        // TYPE_HISTORY rows. Kept as a no-op so the
                        // interface contract is satisfied.
                    }
                });
        bookmarkManager.setOnChangedListener(this::refreshHomeIfVisible);

        // ---- FaviconStore + HomePageRenderer ------------------------------
        faviconStore = new FaviconStore(activity);
        homePageRenderer = new HomePageRenderer(activity, bookmarkManager, faviconStore);

        // ---- VaultController ---------------------------------------------
        vaultController = new VaultController(
                activity, credentials, backgroundExecutor,
                new VaultController.Callbacks() {
                    @Nullable @Override public String getCurrentHost() {
                        return activity.getCurrentHost();
                    }

                    @Override public void copyToClipboard(@NonNull String value,
                                                          @NonNull String message) {
                        activity.copyToClipboard(value, message);
                    }
                });

        // ---- AdBlockController -------------------------------------------
        adBlockController = new AdBlockController(
                activity, filterLists, backgroundExecutor, prefs);

        // ---- ToolbarController -------------------------------------------
        toolbarController = new ToolbarController(activity,
                new ToolbarController.Callbacks() {
            @Override public void onNavigate(@NonNull String input) {
                activity.openUrl(input);
            }

            @Override public void onForward() {
                WebView wv = tabManager.getCurrentWebView();
                if (wv != null && wv.canGoForward()) wv.goForward();
            }

            @Override public void onPreviousTab() {
                if (tabManager.getTabCount() <= 1) return;
                int prev = tabManager.getCurrentPosition() - 1;
                if (prev < 0) prev = tabManager.getTabCount() - 1;
                tabManager.switchToTab(prev);
            }

            @Override public void onNextTab() {
                if (tabManager.getTabCount() <= 1) return;
                int next = (tabManager.getCurrentPosition() + 1) % tabManager.getTabCount();
                tabManager.switchToTab(next);
            }

            @Override public void onNewTab() {
                tabManager.createNewTab();
                showHome();
            }

            @Override public void onShowTabSwitcher() {
                tabManager.showTabSwitcher();
            }

            @Override public void onMenuClicked(@NonNull View anchor) {
                menuController.showMainMenu(anchor);
            }

            @NonNull @Override
            public List<ToolbarController.Suggestion> fetchSuggestions(@NonNull String query) {
                return suggestionProvider.fetch(query);
            }

            @NonNull @Override public Executor getBackgroundExecutor() {
                return backgroundExecutor;
            }
        });

        // ---- MenuController ----------------------------------------------
        menuController = new MenuController(activity, prefs, new MenuController.Callbacks() {
            @Override public void newTab(boolean incognito) {
                tabManager.createNewTab(incognito);
                showHome();
            }

            @Override public void reload() {
                WebView wv = tabManager.getCurrentWebView();
                if (wv != null) wv.reload();
            }

            @Override public void showDownloads() {
                downloadsController.showDownloadsDialog();
            }

            @Override public void findInPage() {
                menuController.showFindInPageDialog(tabManager.getCurrentWebView());
            }

            @Override public void showBookmarks() { bookmarkManager.showBookmarks(); }

            @Override public void addBookmark() {
                WebView wv = tabManager.getCurrentWebView();
                if (wv != null) bookmarkManager.addBookmark(wv.getUrl(), wv.getTitle());
            }

            @Override public void showHistory() { historyController.showHistoryDialog(); }

            @Override public void clearHistory() { historyController.clearHistory(); }

            @Override public void clearCache() {
                WebView wv = tabManager.getCurrentWebView();
                if (wv != null) wv.clearCache(true);
                Toast.makeText(activity, "Cache cleared", Toast.LENGTH_SHORT).show();
            }

            @Override public void showFilterLists() {
                adBlockController.showFilterListsDialog();
            }

            @Override public void toggleFilterEngine() {
                adBlockController.toggleEngine(tabManager.getCurrentWebView());
            }

            @Override public void showSiteAllowlist() {    
                adBlockController.showSiteAllowlistDialog();
            }

            @Override public void toggleDesktopMode() {
                NavigationHelper.toggleDesktopMode(
                        activity,
                        tabManager.getCurrentWebView(),
                        activity.getCurrentHost(),
                        () -> Toast.makeText(activity,
                                "No site loaded", Toast.LENGTH_SHORT).show());
            }

            @Override public boolean isDesktopEnabledForCurrentSite() {
                return NavigationHelper.isDesktopHostEnabled(
                        activity, activity.getCurrentHost());
            }

            @Override public void showVault() {
                activity.openVault();
            }

            @Override public void showSavedPasswords() {
                vaultController.showSavedPasswordsDialog();
            }

            @Override public void importPasswords() {
                activity.launchPasswordImport();
            }

            @Override public void exportPasswords() {
                activity.launchPasswordExport();
            }

            @Override public void showVaultForCurrentSite() {
                vaultController.showVaultForCurrentSite();
            }

            @Override public void toggleStartupAnimation() {
                activity.toggleStartupAnimation();
            }

            @Override public void exit() { activity.exitBrowser(); }

            @Nullable @Override public WebView getCurrentWebView() {
                return tabManager.getCurrentWebView();
            }

            @NonNull @Override public Context getContext() { return activity; }

            @NonNull @Override public SharedPreferences getPreferences() { return prefs; }
        });

        // Phase 2.5: pull subscription state from AdBlockPreferences and
        // hand it to the engine. Also runs one-time migration from the
        // legacy spoon_browser.filter_lists key on first launch.        
        adBlockController.syncEngineWithPrefs();
    }
        
    public void maybeRunAutoUpdate() {
        AdBlockPreferences prefs = AdBlockPreferences.get(activity);
        if (!prefs.isAutoUpdateDue()) return;
        if (prefs.enabledListUrls().isEmpty()) return;

        adBlockController.refreshAll();
        adBlockController.cacheEngineStats();
    }

    // ========================================================================
    // Operations the controllers delegate to
    // ========================================================================

    private void applyTabToToolbar(@Nullable WebView webView, @Nullable TabState state) {
        String url = webView != null ? webView.getUrl() : null;
        toolbarController.setAddress(url);
        toolbarController.setIncognito(state != null && state.isIncognito());

        if (webView != null && webView.getUrl() != null) {
            String host = android.net.Uri.parse(webView.getUrl()).getHost();
            boolean desktop = NavigationHelper.isDesktopHostEnabled(activity, host);
            NavigationHelper.applyDesktopUa(webView, desktop, activity);
        }
    }

    public void showHome() {
        WebView wv = tabManager.getCurrentWebView();
        if (wv == null) return;
        homePageRenderer.render(wv, activity.getResources().getConfiguration().screenWidthDp);
    }

    private void refreshHomeIfVisible() {
        if (tabManager == null) return;
        WebView wv = tabManager.getCurrentWebView();
        if (wv == null) return;
        String url = wv.getUrl();
        if (url == null || url.isEmpty() || "about:blank".equals(url)) {
            showHome();
        }
    }

    public String getSearchUrlFor(String query) {
        return menuController != null ? menuController.getSearchUrlFor(query) : "";
    }

    public void triggerManualDownload(String url, String mime) {
        if (downloadHandler == null || url == null) return;

        String ua = null;
        try {
            WebView wv = tabManager != null ? tabManager.getCurrentWebView() : null;
            if (wv != null) ua = wv.getSettings().getUserAgentString();
        } catch (Exception ignored) {}

        String safeMime = (mime == null || mime.isEmpty())
                ? "application/octet-stream"
                : mime;

        downloadHandler.onDownloadStart(url, ua, null, safeMime, -1L);
    }

    // ========================================================================
    // Download receiver lifecycle
    // ========================================================================

    public void registerDownloadsReceiver() {
        if (downloadsController != null) downloadsController.register();
    }

    public void unregisterDownloadsReceiver() {
        if (downloadsController != null) downloadsController.unregister();
    }

    // ========================================================================
    // Getters for MainActivity
    // ========================================================================

    @NonNull public ToolbarController getToolbarController() { return toolbarController; }
    @NonNull public TabManager getTabManager() { return tabManager; }
    @NonNull public HistoryController getHistoryController() { return historyController; }
    @NonNull public BookmarkManager getBookmarkManager() { return bookmarkManager; }
    @NonNull public VaultPillController getVaultPillController() { return vaultPillController; }
    @NonNull public AdBlockController getAdBlockController() { return adBlockController; }
    @NonNull public MenuController getMenuController() { return menuController; }
    @NonNull public VaultController getVaultController() { return vaultController; }
    @NonNull public WebViewFactory getWebViewFactory() { return webViewFactory; }
    @NonNull public DownloadsController getDownloadsController() { return downloadsController; }
}
