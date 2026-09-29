package com.spoondon.browser;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrator for Spoon Browser.
 *
 * Post-refactor responsibilities (this file only):
 *   - Wire collaborators together.
 *   - Own the Activity-level lifecycle (onCreate/onPause/onStop/onDestroy).
 *   - Own the layout skeleton (root / toolbar / browserWrapper / progressBar).
 *   - Expose the small public surface required by SpoonWebChromeClient,
 *     SpoonWebViewClient, MenuController, and TabManager.
 *
 * Everything else lives in a dedicated collaborator.
 */
public class MainActivity extends AppCompatActivity {

    // ------------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------------
    private static final String PREFS_NAME = "spoon_browser";

    // ------------------------------------------------------------------------
    // Collaborators
    // ------------------------------------------------------------------------
    SecureCredentialManager secureCredentialManager;
    public BrowserDatabaseHelper dbHelper;

    private PermissionController permissionController;
    private SessionManager sessionManager;
    private TabManager tabManager;
    private ToolbarController toolbarController;
    private MenuController menuController;
    private HistoryController historyController;
    private VaultController vaultController;
    private AdBlockController adBlockController;
    private DownloadHandler downloadHandler;
    private WebViewFactory webViewFactory;
    private HomePageRenderer homePageRenderer;
    private SuggestionProvider suggestionProvider;

    // ------------------------------------------------------------------------
    // View fields
    // ------------------------------------------------------------------------
    LinearLayout root;
    LinearLayout browserContainer;
    FrameLayout browserWrapper;                // the weighted child of `root`
    androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh;
    public ProgressBar progressBar;

    // Legacy public fields kept for API compatibility with older call sites.
    public LinearLayout addressContainer;
    public android.widget.TextView securityIcon;

    // Fullscreen video state — read/written by SpoonWebChromeClient.
    View customView;
    WebChromeClient.CustomViewCallback customViewCallback;

    // ------------------------------------------------------------------------
    // Misc state
    // ------------------------------------------------------------------------
    private SharedPreferences prefs;
    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(4);
    private ActivityResultLauncher<String> passwordImportLauncher;
    private ActivityResultLauncher<String> exportCsvLauncher;

    private final CopyOnWriteArrayList<String> filterLists = new CopyOnWriteArrayList<>();

    // Clipboard auto-clear (vault protection).
    private ClipboardManager clipboardManager;
    private android.os.Handler clipboardHandler;
    private Runnable clipboardClearRunnable;
    private ClipboardManager.OnPrimaryClipChangedListener clipChangedListener;

    // ========================================================================
    // Lifecycle
    // ========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_NoActionBar);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // -- Collaborators that don't need views -----------------------------
        secureCredentialManager = new SecureCredentialManager(this);
        dbHelper = BrowserDatabaseHelper.getInstance(this);
        permissionController = new PermissionController(this);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sessionManager = new SessionManager(this);

        // -- File pickers ----------------------------------------------------
        exportCsvLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/csv"),
                uri -> {
                    if (uri == null || secureCredentialManager == null) return;
                    backgroundExecutor.execute(() -> {
                        try {
                            java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                            if (os != null) {
                                String csv = secureCredentialManager.getAllCredentialsAsCsv();
                                os.write(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                os.close();
                                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                        "Passwords exported successfully",
                                        Toast.LENGTH_LONG).show());
                            }
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                    "Export failed", Toast.LENGTH_SHORT).show());
                        }
                    });
                });

        passwordImportLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) return;
                    try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                        if (secureCredentialManager.importFromCSVStream(is)) {
                            Toast.makeText(this, "Passwords imported successfully",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Failed to parse passwords.csv",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, "Error opening file stream",
                                Toast.LENGTH_SHORT).show();
                    }
                });

        // -- Clipboard watcher -----------------------------------------------
        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboardHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        clipboardClearRunnable = () -> {
            if (clipboardManager == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboardManager.clearPrimaryClip();
            } else {
                clipboardManager.setPrimaryClip(ClipData.newPlainText("", ""));
            }
        };
        clipChangedListener = () -> {
            if (isVaultActive()) {
                clipboardHandler.removeCallbacks(clipboardClearRunnable);
                clipboardHandler.postDelayed(clipboardClearRunnable, 60_000);
            }
        };
        if (clipboardManager != null) {
            clipboardManager.addPrimaryClipChangedListener(clipChangedListener);
        }

        // -- View tree skeleton ----------------------------------------------
        setupRootLayout();

        // -- Collaborators that need the view tree ---------------------------
        buildAndWireControllers();

        // -- Assemble the full layout ----------------------------------------
        assembleLayout();

        // -- Startup data ----------------------------------------------------
        historyController.migrateLegacyBookmarksToDatabase();
        loadFilterListsIntoMemory();
        tabManager.createNewTab();
        showHome();

        // -- Background warm-up ----------------------------------------------
        backgroundExecutor.execute(() -> {
            AdBlockEngine.init(MainActivity.this, filterLists);
            AdBlockEngine.checkAndRefreshFilters(
                    MainActivity.this, backgroundExecutor, filterLists, false);
        });
        backgroundExecutor.execute(() -> {
            try {
                if (dbHelper != null) dbHelper.cleanupOldHistory(90);
            } catch (Exception ignored) {
            }
        });

        // -- Back handling ---------------------------------------------------
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (tabManager.isTabSwitcherVisible()) {
                    tabManager.hideTabSwitcher();
                    return;
                }
                WebView active = tabManager.getCurrentWebView();
                if (active != null && active.canGoBack()) {
                    active.goBack();
                    return;
                }
                if (tabManager.getTabCount() > 1) {
                    tabManager.closeTab(tabManager.getCurrentPosition());
                } else {
                    sessionManager.showExitConfirmationDialog();
                }
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        handleIncomingIntent(getIntent());
        setIntent(new Intent());
        if (tabManager != null) tabManager.resumeActiveTab();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sessionManager != null) sessionManager.onPause();
        if (tabManager != null) tabManager.pauseActiveTab();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (sessionManager != null) sessionManager.onStop();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (tabManager != null) tabManager.onTrimMemory(level);
    }

    @Override
    protected void onDestroy() {
        if (sessionManager != null) sessionManager.onDestroy();
        if (backgroundExecutor != null) backgroundExecutor.shutdownNow();
        if (tabManager != null) tabManager.destroyAll();

        if (clipboardManager != null && clipChangedListener != null) {
            clipboardManager.removePrimaryClipChangedListener(clipChangedListener);
        }
        if (clipboardHandler != null) {
            clipboardHandler.removeCallbacks(clipboardClearRunnable);
        }
        super.onDestroy();
    }

    // ========================================================================
    // Layout assembly
    // ========================================================================

    private void setupRootLayout() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, systemBars.top, 0, 0);
            return windowInsets;
        });

        browserContainer = new LinearLayout(this);
        browserContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams browserParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        browserContainer.setLayoutParams(browserParams);

        swipeRefresh = new androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this);
        swipeRefresh.setColorSchemeColors(Color.parseColor("#8ab4f8"));
        swipeRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#202124"));
        swipeRefresh.addView(browserContainer);
        swipeRefresh.setOnRefreshListener(() -> {
            WebView wv = getCurrentWebView();
            if (wv != null) wv.reload();
            else swipeRefresh.setRefreshing(false);
        });
        swipeRefresh.setOnChildScrollUpCallback((parent, child) -> {
            WebView wv = getCurrentWebView();
            if (wv != null) return wv.getScrollY() > 0;
            return true;
        });
    }

    private void assembleLayout() {
        root.addView(toolbarController.getRootView());

        browserWrapper = new FrameLayout(this);
        LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        browserWrapper.setLayoutParams(wrapperParams);

        browserWrapper.addView(swipeRefresh);

        progressBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 8);
        progressParams.gravity = Gravity.TOP;
        progressBar.setLayoutParams(progressParams);
        progressBar.setIndeterminate(false);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);

        android.graphics.drawable.Drawable pd = progressBar.getProgressDrawable();
        if (pd != null) {
            pd.setColorFilter(new android.graphics.PorterDuffColorFilter(
                    Color.parseColor("#8ab4f8"),
                    android.graphics.PorterDuff.Mode.SRC_IN));
        }
        browserWrapper.addView(progressBar);
        root.addView(browserWrapper);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);

        setContentView(root);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            browserContainer.setImportantForAutofill(
                    View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
    }

    // ========================================================================
    // Controller wiring
    // ========================================================================

    private void buildAndWireControllers() {
        // ---- ToolbarController ---------------------------------------------
        toolbarController = new ToolbarController(this, new ToolbarController.Callbacks() {
            @Override public void onNavigate(@NonNull String input) { openUrl(input); }

            @Override public void onForward() {
                WebView wv = getCurrentWebView();
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

            @Override public void onShowTabSwitcher() { tabManager.showTabSwitcher(); }

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

        // ---- DownloadHandler (referenced by WebViewFactory) ----------------
        downloadHandler = new DownloadHandler(this, this::getCurrentWebView);

        // ---- WebViewFactory -----------------------------------------------
        webViewFactory = new WebViewFactory(
                this, secureCredentialManager, permissionController, downloadHandler);

        // ---- HomePageRenderer ---------------------------------------------
        homePageRenderer = new HomePageRenderer();

        // ---- SuggestionProvider -------------------------------------------
        suggestionProvider = new SuggestionProvider(dbHelper);

        // ---- TabManager ----------------------------------------------------
        tabManager = new TabManager(this, browserContainer, new TabManager.Callbacks() {
            @NonNull @Override public WebView createConfiguredWebView() {
                return webViewFactory.create();
            }

            @Override public void onCurrentTabChanged(@Nullable WebView webView,
                                                      @Nullable TabState state) {
                applyTabToToolbar(webView, state);
                updateScreenShield();
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
                sessionManager.showExitConfirmationDialog();
            }
        });

        // ---- HistoryController --------------------------------------------
        historyController = new HistoryController(
                this, dbHelper, backgroundExecutor,
                new HistoryController.Callbacks() {
                    @Override public void onNavigate(@NonNull String url) { openUrl(url); }

                    @Override public void openInNewTab(@NonNull String url) {
                        tabManager.openUrlInNewTab(url);
                    }
                });

        // ---- VaultController ----------------------------------------------
        vaultController = new VaultController(
                this, secureCredentialManager, backgroundExecutor,
                new VaultController.Callbacks() {
                    @Nullable @Override public String getCurrentHost() {
                        return MainActivity.this.getCurrentHost();
                    }

                    @Override public void copyToClipboard(@NonNull String value,
                                                          @NonNull String message) {
                        if (clipboardManager != null) {
                            clipboardManager.setPrimaryClip(
                                    ClipData.newPlainText("spoon_copy", value));
                        }
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });

        // ---- AdBlockController --------------------------------------------
        adBlockController = new AdBlockController(
                this, filterLists, backgroundExecutor, prefs);

        // ---- MenuController -----------------------------------------------
        menuController = new MenuController(this, prefs, new MenuController.Callbacks() {
            @Override public void newTab(boolean incognito) {
                tabManager.createNewTab(incognito);
                showHome();
            }

            @Override public void reload() {
                WebView wv = getCurrentWebView();
                if (wv != null) wv.reload();
            }

            @Override public void openDownloads() { openSystemDownloads(); }

            @Override public void findInPage() {
                menuController.showFindInPageDialog(tabManager.getCurrentWebView());
            }

            @Override public void showBookmarks() { historyController.showBookmarks(); }

            @Override public void addBookmark() {
                WebView wv = getCurrentWebView();
                if (wv != null) historyController.addBookmark(wv.getUrl(), wv.getTitle());
            }

            @Override public void showHistory() { historyController.showHistoryDialog(); }

            @Override public void clearHistory() { historyController.clearHistory(); }

            @Override public void clearCache() {
                WebView wv = getCurrentWebView();
                if (wv != null) wv.clearCache(true);
                Toast.makeText(MainActivity.this, "Cache cleared",
                        Toast.LENGTH_SHORT).show();
            }

            @Override public void showFilterLists() {
                adBlockController.showFilterListsDialog();
            }

            @Override public void toggleFilterEngine() {
                adBlockController.toggleEngine(getCurrentWebView());
            }

            @Override public void toggleDesktopMode() {
                NavigationHelper.toggleDesktopMode(
                        MainActivity.this,
                        getCurrentWebView(),
                        getCurrentHost(),
                        () -> Toast.makeText(MainActivity.this,
                                "No site loaded", Toast.LENGTH_SHORT).show());
            }

            @Override public boolean isDesktopEnabledForCurrentSite() {
                return NavigationHelper.isDesktopHostEnabled(
                        MainActivity.this, getCurrentHost());
            }

            @Override public void showSavedPasswords() {
                vaultController.showSavedPasswordsDialog();
            }

            @Override public void importPasswords() {
                passwordImportLauncher.launch("text/*");
            }

            @Override public void exportPasswords() {
                exportCsvLauncher.launch("spoon_passwords.csv");
            }

            @Override public void showVaultForCurrentSite() {
                vaultController.showVaultForCurrentSite();
            }

            @Override public void toggleStartupAnimation() {
                toggleStartupAnimationInternal();
            }

            @Override public void exit() { sessionManager.exitNow(); }

            @Nullable @Override public WebView getCurrentWebView() {
                return MainActivity.this.getCurrentWebView();
            }

            @NonNull @Override public Context getContext() { return MainActivity.this; }

            @NonNull @Override public SharedPreferences getPreferences() { return prefs; }
        });
    }

    private void applyTabToToolbar(@Nullable WebView webView, @Nullable TabState state) {
        String url = webView != null ? webView.getUrl() : null;
        toolbarController.setAddress(url);
        toolbarController.setIncognito(state != null && state.isIncognito());

        if (webView != null && webView.getUrl() != null) {
            String host = Uri.parse(webView.getUrl()).getHost();
            boolean desktop = NavigationHelper.isDesktopHostEnabled(this, host);
            NavigationHelper.applyDesktopUa(webView, desktop);
        }
    }

    private void toggleStartupAnimationInternal() {
        SharedPreferences sp = getSharedPreferences("browser_prefs", MODE_PRIVATE);
        boolean enabled = sp.getBoolean("show_splash_screen", true);
        sp.edit().putBoolean("show_splash_screen", !enabled).apply();
        Toast.makeText(this,
                !enabled ? "Startup Animation Enabled" : "Startup Animation Disabled",
                Toast.LENGTH_SHORT).show();
    }

    // ========================================================================
    // Incoming intents
    // ========================================================================

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;

        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            String urlToLoad = intent.getData().toString();
            if (tabManager.isEmpty()) tabManager.createNewTab();
            toolbarController.setAddress(urlToLoad);
            openUrl(urlToLoad);
            setIntent(new Intent());
        } else if (intent.getAction() != null) {
            setIntent(new Intent());
        }
    }

    // ========================================================================
    // Public API surface used by other files
    // ========================================================================

    public void openUrlInNewTab(String url) {
        if (tabManager != null) tabManager.openUrlInNewTab(url);
    }

    public void handleDeadRenderProcess(WebView deadWebView) {
        if (tabManager != null) tabManager.handleDeadRenderProcess(deadWebView);
    }

    public int getTabCount() {
        return tabManager != null ? tabManager.getTabCount() : 0;
    }

    public int getBookmarkCount() {
        return dbHelper != null ? dbHelper.getBookmarkCount() : 0;
    }

    public int getHistoryCount() {
        return dbHelper != null ? dbHelper.getHistoryCount() : 0;
    }

    public WebView getCurrentWebView() {
        return tabManager != null ? tabManager.getCurrentWebView() : null;
    }

    public TabState getCurrentTabState() {
        return tabManager != null ? tabManager.getCurrentTabState() : null;
    }

    public void updateTabBadgeCount() {
        if (tabManager != null) {
            toolbarController.setTabCounter(
                    tabManager.getCurrentPosition(), tabManager.getTabCount());
        }
    }

    // ---- Delegators for SpoonWebViewClient -----------------------------

    /** Called by SpoonWebViewClient when a spoonsearch:// link fires. */
    public String getSearchUrlFor(String query) {
        return menuController != null ? menuController.getSearchUrlFor(query) : "";
    }

    /** Called by SpoonWebViewClient when a known file extension is hit. */
    public void triggerManualDownload(String url, String mime) {
        if (downloadHandler != null) downloadHandler.triggerExternalDownload(url, mime);
    }

    /** Called by SpoonWebViewClient in onPageStarted to set per-host UA. */
    public boolean isDesktopHostEnabled(String host) {
        return NavigationHelper.isDesktopHostEnabled(this, host);
    }

    /** Called by SpoonWebViewClient for background history writes. */
    public ExecutorService getBackgroundExecutor() {
        return backgroundExecutor;
    }

    /** Called by SpoonWebViewClient in onPageStarted to update the address bar. */
    public void setAddressBarText(String url) {
        if (toolbarController != null) toolbarController.setAddress(url);
    }

    // ========================================================================
    // Fullscreen video support (called by SpoonWebChromeClient)
    // ========================================================================

    public void setToolbarVisible(boolean visible) {
        View bar = toolbarController.getRootView();
        if (bar != null) bar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public void setBrowserVisible(boolean visible) {
        // Must target the weighted child of `root` — hiding browserContainer
        // would collapse its content but leave the wrapper's weight intact.
        if (browserWrapper != null) {
            browserWrapper.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    public void attachFullscreenView(View view) {
        if (view == null || root == null) return;
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(view, params);
    }

    public void detachFullscreenView(View view) {
        if (view == null || root == null) return;
        root.removeView(view);
    }

    // ========================================================================
    // Vault / screen shield
    // ========================================================================

    public boolean isVaultActive() {
        WebView wv = getCurrentWebView();
        return wv != null && wv.getUrl() != null
                && wv.getUrl().startsWith("file:///android_asset/vault.html");
    }

    public void updateScreenShield() {
        if (isVaultActive()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    public void executeSafely(Runnable task) {
        if (backgroundExecutor != null
                && !backgroundExecutor.isShutdown()
                && !backgroundExecutor.isTerminated()) {
            try {
                backgroundExecutor.execute(task);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                e.printStackTrace();
            }
        }
    }

    // ========================================================================
    // Navigation
    // ========================================================================

    public void openUrl(String url) {
        WebView wv = getCurrentWebView();
        if (wv == null || url == null) return;
        NavigationHelper.openUrl(wv, url, this, menuController::getSearchUrlFor);
    }

    private String getCurrentHost() {
        WebView wv = getCurrentWebView();
        if (wv == null || wv.getUrl() == null) return null;
        return Uri.parse(wv.getUrl()).getHost();
    }

    // ========================================================================
    // Startup helpers
    // ========================================================================

    private void loadFilterListsIntoMemory() {
        String saved = prefs.getString(AdBlockController.KEY_FILTER_LISTS, "");
        if (!saved.isEmpty()) {
            for (String line : saved.split("\\n")) {
                if (!line.isEmpty() && !filterLists.contains(line)) {
                    filterLists.add(line);
                }
            }
        }
        if (!filterLists.isEmpty()) {
            long last = prefs.getLong(AdBlockController.KEY_FILTER_REFRESH_TIME, 0);
            if (System.currentTimeMillis() - last > 24L * 60 * 60 * 1000) {
                AdBlockEngine.checkAndRefreshFilters(
                        this, backgroundExecutor, filterLists, true);
            }
        }
    }

    // ========================================================================
    // Home page
    // ========================================================================

    private void showHome() {
        WebView wv = getCurrentWebView();
        if (wv == null || homePageRenderer == null) return;
        homePageRenderer.render(wv, getResources().getConfiguration().screenWidthDp);
    }

    // ========================================================================
    // Utilities
    // ========================================================================

    private void openSystemDownloads() {
        try {
            Intent intent = new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "No download manager found", Toast.LENGTH_SHORT).show();
        }
    }
}
