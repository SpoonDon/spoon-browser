package com.spoondon.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.webkit.WebView;
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
 * 2026-10-01 (history + bookmark managers): adds BookmarkManager alongside
 * HistoryController. Both share the manager dialog infrastructure
 * ({@link ItemManagerDialog}) but own separate persistence concerns.
 * The MenuController "Bookmarks" / "Add Bookmark" callbacks now route to
 * BookmarkManager instead of HistoryController.
 *
 * 2026-09-30 (downloads redirect fix): triggerManualDownload no longer
 * calls DownloadHandler.triggerExternalDownload directly. It now routes
 * through DownloadHandler.onDownloadStart so the user sees the standard
 * download dialog and the in-app OkHttp engine is used.
 */
public class AppWiring {

    // ------------------------------------------------------------------------
    // Injected dependencies
    // ------------------------------------------------------------------------
    private final MainActivity activity;
    private final LinearLayout browserContainer;
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
    private HomePageRenderer homePageRenderer;
    private SuggestionProvider suggestionProvider;
    private TabManager tabManager;
    private HistoryController historyController;
    private BookmarkManager bookmarkManager;
    private VaultController vaultController;
    private AdBlockController adBlockController;
    private ToolbarController toolbarController;
    private MenuController menuController;

    public AppWiring(@NonNull MainActivity activity,
                     @NonNull LinearLayout browserContainer,
                     @NonNull SecureCredentialManager credentials,
                     @NonNull BrowserDatabaseHelper dbHelper,
                     @NonNull PermissionController permissionController,
                     @NonNull SharedPreferences prefs,
                     @NonNull ExecutorService backgroundExecutor,
                     @NonNull CopyOnWriteArrayList<String> filterLists) {
        this.activity = activity;
        this.browserContainer = browserContainer;
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

        webViewFactory = new WebViewFactory(
                activity, credentials, permissionController, downloadHandler);

        homePageRenderer = new HomePageRenderer();
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
        // Both managers share the same navigation callbacks shape. History
        // uses its own sub-interface so any existing code that implemented
        // HistoryController.Callbacks still compiles unchanged.
        historyController = new HistoryController(
                activity, dbHelper, backgroundExecutor,
                new HistoryController.Callbacks() {
                    @Override public void onNavigate(@NonNull String url) {
                        activity.openUrl(url);
                    }

                    @Override public void openInNewTab(@NonNull String url) {
                        tabManager.openUrlInNewTab(url);
                    }
                });

        // ---- BookmarkManager ---------------------------------------------
        // Same shared UX (ItemManagerDialog), independent persistence path.
        // "Add Bookmark" and "Bookmarks" menu entries route here now.
        bookmarkManager = new BookmarkManager(
                activity, dbHelper,
                new ItemManagerDialog.Callbacks() {
                    @Override public void onNavigate(@NonNull String url) {
                        activity.openUrl(url);
                    }

                    @Override public void openInNewTab(@NonNull String url) {
                        tabManager.openUrlInNewTab(url);
                    }
                });

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

            // 2026-10-01: Bookmarks menu item now routes to BookmarkManager
            // (was HistoryController.showBookmarks()).
            @Override public void showBookmarks() { bookmarkManager.showBookmarks(); }

            // 2026-10-01: Add Bookmark now routes to BookmarkManager
            // (was HistoryController.addBookmark, the legacy passthrough).
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

    public String getSearchUrlFor(String query) {
        return menuController != null ? menuController.getSearchUrlFor(query) : "";
    }

    /**
     * Called from SpoonWebViewClient when a navigation URL ends in a
     * known download extension, and from MainActivity.triggerManualDownload.
     *
     * 2026-09-30 fix: previously this called DownloadHandler.triggerExternalDownload
     * directly, which fired ACTION_VIEW and handed the URL to the OS default
     * browser (the "downloads open in another browser" bug). Now it routes
     * through DownloadHandler.onDownloadStart so the user sees the standard
     * Save / External / Cancel dialog, and the default Save path uses the
     * in-app OkHttp DownloadEngine.
     */
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
    @NonNull public AdBlockController getAdBlockController() { return adBlockController; }
    @NonNull public MenuController getMenuController() { return menuController; }
    @NonNull public VaultController getVaultController() { return vaultController; }
    @NonNull public WebViewFactory getWebViewFactory() { return webViewFactory; }
    @NonNull public DownloadsController getDownloadsController() { return downloadsController; }
}
