package com.spoondon.browser;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Button;
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
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 *   - Own the WebView factory (config + bridge injection).
 *   - Own the home page HTML.
 *   - Expose the small public surface required by SpoonWebChromeClient,
 *     SpoonWebViewClient, MenuController, TabManager, and ToolbarController.
 *
 * Everything else lives in a dedicated collaborator. See REFACTORING.md
 * for the full slice plan.
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
    private final HashSet<String> blockedDomains = new HashSet<>();
    private final HashSet<String> rawFilterRules = new HashSet<>();

    private String cachedHomeHtml = null;

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
        // ---- ToolbarController (references tabManager lazily via `this`) ---
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
                return fetchSuggestionsInternal(query);
            }

            @NonNull @Override public Executor getBackgroundExecutor() {
                return backgroundExecutor;
            }
        });

        // ---- TabManager ----------------------------------------------------
        tabManager = new TabManager(this, browserContainer, new TabManager.Callbacks() {
            @NonNull @Override public WebView createConfiguredWebView() {
                return MainActivity.this.createConfiguredWebView();
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

        // ---- DownloadHandler (attached to each WebView on creation) -------
        downloadHandler = new DownloadHandler(this, this::getCurrentWebView);

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
    // WebView factory
    // ========================================================================

    private void configureWebSettings(android.webkit.WebSettings settings) {
        if (settings == null) return;

        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSaveFormData(true);

        android.net.ConnectivityManager connMgr =
                (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr != null && connMgr.isActiveNetworkMetered()) {
            settings.setBlockNetworkImage(true);
            settings.setCacheMode(android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK);
        } else {
            settings.setBlockNetworkImage(false);
            settings.setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
        }

        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            settings.setLayoutAlgorithm(
                    android.webkit.WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING);
        }
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setGeolocationEnabled(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(
                    android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        try {
            android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        } catch (Exception ignored) {
        }

        settings.setUserAgentString(NavigationHelper.MOBILE_UA);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            androidx.webkit.WebSettingsCompat.setForceDark(
                    settings, androidx.webkit.WebSettingsCompat.FORCE_DARK_ON);
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            androidx.webkit.WebSettingsCompat.setForceDarkStrategy(settings,
                    androidx.webkit.WebSettingsCompat
                            .DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING);
        }
    }

    private View.OnLongClickListener createImageLongClickListener(WebView webView) {
        return v -> {
            WebView.HitTestResult result = webView.getHitTestResult();
            if (result != null
                    && (result.getType() == WebView.HitTestResult.IMAGE_TYPE
                        || result.getType() == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(result.getExtra())));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Cannot download image",
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            return false;
        };
    }

    @NonNull
    private WebView createConfiguredWebView() {
        WebView webView = new WebView(this);
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        webView.setLayoutParams(webParams);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        android.webkit.WebSettings ws = webView.getSettings();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            ws.setMediaPlaybackRequiresUserGesture(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ws.setOffscreenPreRaster(true);
        }
        ws.setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
        ws.setLoadsImagesAutomatically(true);
        ws.setBlockNetworkImage(false);

        configureWebSettings(ws);

        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setJavaScriptEnabled(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setLayoutAlgorithm(android.webkit.WebSettings.LayoutAlgorithm.NORMAL);
        ws.setAllowFileAccess(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            ws.setAllowFileAccessFromFileURLs(false);
            ws.setAllowUniversalAccessFromFileURLs(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ws.setMixedContentMode(
                    android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        String currentUa = ws.getUserAgentString();
        if (currentUa != null) {
            currentUa = currentUa.replace("; wv", "");
            currentUa = currentUa.replaceFirst("Version/[0-9.]+\\s", "");
            ws.setUserAgentString(currentUa);
        }

        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.setAcceptThirdPartyCookies(webView, false);
            }
        } catch (Exception ignored) {
        }

        webView.setOnLongClickListener(createImageLongClickListener(webView));
        webView.setWebViewClient(new SpoonWebViewClient(this));
        webView.setWebChromeClient(new SpoonWebChromeClient(this, permissionController));

        webView.addJavascriptInterface(new BlobDownloader(this), "AndroidDownloader");
        webView.addJavascriptInterface(new PasswordAutosaveBridge(), "SpoonVault");

        downloadHandler.attach(webView);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Set<String> allowedOrigins = java.util.Collections.singleton("*");
            WebViewCompat.addWebMessageListener(webView, "spoonVaultMessage", allowedOrigins,
                    (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                try {
                    String currentUrl = view.getUrl();
                    if (currentUrl == null
                            || !currentUrl.startsWith("file:///android_asset/vault.html")) {
                        return;
                    }
                    String msg = message.getData();

                    if ("FETCH_ALL_VAULT_DATA".equals(msg)) {
                        String all = secureCredentialManager.getAllCredentialsAsJson();
                        replyProxy.postMessage(all != null ? all : "[]");
                    } else if (msg != null && msg.startsWith("{")) {
                        org.json.JSONObject obj = new org.json.JSONObject(msg);
                        String action = obj.optString("action");
                        String host = obj.optString("host");
                        String user = obj.optString("username");

                        if ("SAVE_LOGIN".equals(action)) {
                            secureCredentialManager.saveCredentials(
                                    host, user, obj.optString("password"));
                        } else if ("DELETE_LOGIN".equals(action)) {
                            secureCredentialManager.deleteCredentials(host, user);
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        }

        ViewCompat.setNestedScrollingEnabled(webView, true);
        return webView;
    }

    // ========================================================================
    // Home page
    // ========================================================================

    private void showHome() {
        if (cachedHomeHtml == null) {
            float widthDp = getResources().getConfiguration().screenWidthDp;

            StringBuilder sb = new StringBuilder();
            sb.append("<html><body style='margin:0;background:#000;color:white;")
              .append("font-family:sans-serif;text-align:center;'>")
              .append("<div style='padding-top:20%;'>")
              .append("<h1 style='font-size:48px;margin-bottom:40px;'>Spoon Browser</h1>");

            if (widthDp >= 600) {
                sb.append("<input id='q' type='text' placeholder='Search privately...' ")
                  .append("style='width:72%;padding:20px;border:none;border-radius:18px;")
                  .append("background:#1f1f1f;color:white;font-size:18px;outline:none;'/>");
            }

            sb.append("</div><script>")
              .append("function goSearch(){")
              .append("var el=document.getElementById('q');var q=el.value;")
              .append("if(!q)return;el.blur();el.value='';el.placeholder='Searching...';")
              .append("window.location.href='spoonsearch://'+encodeURIComponent(q);}")
              .append("var el=document.getElementById('q');")
              .append("if(el){el.addEventListener('keydown',function(e){")
              .append("if(e.key==='Enter'){goSearch();}});}")
              .append("</script></body></html>");

            cachedHomeHtml = sb.toString();
        }

        WebView wv = getCurrentWebView();
        if (wv != null) {
            wv.loadDataWithBaseURL("about:blank", cachedHomeHtml, "text/html", "UTF-8", null);
        }
    }

    // ========================================================================
    // Autocomplete suggestions
    // ========================================================================

    @NonNull
    private List<ToolbarController.Suggestion> fetchSuggestionsInternal(@NonNull String query) {
        List<ToolbarController.Suggestion> result = new ArrayList<>();
        if (query.isEmpty() || dbHelper == null) return result;

        List<String[]> rawResults = dbHelper.getMatchingHistory(query);
        Set<String> addedUrls = new HashSet<>();
        Set<String> addedHosts = new HashSet<>();

        // Pass 1: distinct hosts that match the query string.
        String lowerQuery = query.toLowerCase();
        for (String[] row : rawResults) {
            try {
                Uri uri = Uri.parse(row[0]);
                String host = uri.getHost();
                if (host == null) continue;
                String cleanHost = host.replaceFirst("^www\\.", "");
                if (cleanHost.toLowerCase().contains(lowerQuery)
                        && !addedHosts.contains(cleanHost)) {
                    result.add(new ToolbarController.Suggestion(cleanHost, cleanHost));
                    addedHosts.add(cleanHost);
                    addedUrls.add(cleanHost);
                }
            } catch (Exception ignored) {
            }
        }

        // Pass 2: up to three deep-link history entries.
        int deepLinks = 0;
        for (String[] row : rawResults) {
            if (deepLinks >= 3) break;
            String rawUrl = row[0];
            String title = (row[1] != null && !row[1].isEmpty()) ? row[1] : rawUrl;
            String display = rawUrl.replaceFirst("^https?://(www\\.)?", "");
            if (!addedUrls.contains(display) && !addedHosts.contains(display)) {
                result.add(new ToolbarController.Suggestion(title, display));
                addedUrls.add(display);
                deepLinks++;
            }
        }

        return result;
    }

    // ========================================================================
    // JS bridge (SpoonVault)
    // ========================================================================

    /**
     * Only exposes saveCredentials. getUsername / getPassword were removed —
     * addJavascriptInterface is NOT origin-scoped, so any page in any WebView
     * could read stored credentials for any host. The vault UI reads them
     * via the WebMessageListener, which is restricted to vault.html.
     */
    private class PasswordAutosaveBridge {
        @android.webkit.JavascriptInterface
        public void saveCredentials(String host, String username, String password) {
            if (secureCredentialManager != null) {
                secureCredentialManager.saveCredentials(host, username, password);
            }
        }
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

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
