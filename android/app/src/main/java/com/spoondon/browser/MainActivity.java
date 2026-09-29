package com.spoondon.browser;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
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
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrator for Spoon Browser.
 *
 * Post-refactor responsibilities (this file only):
 *   - Build the view tree (root / toolbar / browserWrapper / progressBar).
 *   - Wire every collaborator together (tabManager, menuController, ...).
 *   - Own the toolbar widgets until ToolbarController is wired in.
 *   - Own the download listener until DownloadHandler is wired in.
 *   - Own the vault dialog until VaultController is wired in.
 *   - Own the filter-list dialog until AdBlockController is wired in.
 *   - Expose the small public surface that SpoonWebChromeClient,
 *     SpoonWebViewClient, MenuController, and TabManager need.
 *
 * Everything else lives in a dedicated collaborator.
 */
public class MainActivity extends AppCompatActivity {

    // ------------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------------
    private static final String PREFS_NAME = "spoon_browser";
    private static final String KEY_FILTER_LISTS = "filter_lists";
    private static final String KEY_FILTER_REFRESH_TIME = "filter_refresh_time";
    private static final String KEY_OPEN_TABS = "open_tabs";
    private static final String KEY_CURRENT_TAB = "current_tab";

    // ------------------------------------------------------------------------
    // Collaborators
    // ------------------------------------------------------------------------
    SecureCredentialManager secureCredentialManager;
    public BrowserDatabaseHelper dbHelper;

    private PermissionController permissionController;
    private SessionManager sessionManager;
    private TabManager tabManager;
    private MenuController menuController;
    private HistoryController historyController;

    // ------------------------------------------------------------------------
    // View fields
    // ------------------------------------------------------------------------
    LinearLayout root;
    LinearLayout browserContainer;
    FrameLayout browserWrapper;           // the weighted child of `root`
    androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh;
    public ProgressBar progressBar;

    // Legacy public fields kept for API compatibility with older call sites.
    public LinearLayout addressContainer;
    public TextView securityIcon;

    LinearLayout toolbar;
    private AutoCompleteTextView addressBar;
    private SuggestionAdapter addressBarAdapter;
    private TextView tabIndicator;
    private Button forwardButton;
    private Button prevTabButton;
    private Button nextTabButton;
    private Button newTabButton;
    private Button menuButton;
    private Button tabBadgeButton;

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
    private boolean suppressSuggestions = false;
    private boolean isDesktopMode = false;

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

        // -- Collaborators (order matters) -----------------------------------
        secureCredentialManager = new SecureCredentialManager(this);
        dbHelper = BrowserDatabaseHelper.getInstance(this);
        permissionController = new PermissionController(this);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sessionManager = new SessionManager(this);
        isDesktopMode = prefs.getBoolean("isDesktopMode", false);

        // -- View tree -------------------------------------------------------
        setupRootLayout();
        createToolbarViews();
        setupToolbarListeners();

        // Clipboard listener (vault auto-clear).
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

        // -- Assemble the view tree -----------------------------------------
        if (root != null) {
            if (toolbar != null) root.addView(toolbar);

            browserWrapper = new FrameLayout(this);
            LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            browserWrapper.setLayoutParams(wrapperParams);

            if (swipeRefresh != null) browserWrapper.addView(swipeRefresh);

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
                root.setImportantForAutofill(
                        View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
                if (browserContainer != null) {
                    browserContainer.setImportantForAutofill(
                            View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
                }
            }
        }

        // -- Wire collaborators now that the view tree exists ----------------
        wireControllers();
        historyController.migrateLegacyBookmarksToDatabase();
        loadSavedData();

        // -- Background warm-up ---------------------------------------------
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
    // Wiring
    // ========================================================================

    private void wireControllers() {
        // --- TabManager ----------------------------------------------------
        tabManager = new TabManager(this, browserContainer, new TabManager.Callbacks() {
            @NonNull
            @Override
            public WebView createConfiguredWebView() {
                return MainActivity.this.createConfiguredWebView();
            }

            @Override
            public void onCurrentTabChanged(@Nullable WebView webView,
                                            @Nullable TabState state) {
                updateAddressBarForTab(webView, state);
                updateScreenShield();
            }

            @Override
            public void onTabCountChanged(int count) {
                updateTabCountersUI(count);
            }

            @Override
            public void onNewTabRequested() {
                tabManager.createNewTab();
                showHome();
            }

            @Override
            public void onAllTabsClosed() {
                sessionManager.showExitConfirmationDialog();
            }
        });

        // --- HistoryController ---------------------------------------------
        historyController = new HistoryController(
                this, dbHelper, backgroundExecutor,
                new HistoryController.Callbacks() {
                    @Override
                    public void onNavigate(@NonNull String url) {
                        openUrl(url);
                    }

                    @Override
                    public void openInNewTab(@NonNull String url) {
                        tabManager.openUrlInNewTab(url);
                    }
                });

        // --- MenuController ------------------------------------------------
        menuController = new MenuController(this, prefs, new MenuController.Callbacks() {
            @Override public void newTab(boolean incognito) {
                tabManager.createNewTab(incognito);
                showHome();
            }

            @Override public void reload() {
                WebView wv = tabManager.getCurrentWebView();
                if (wv != null) wv.reload();
            }

            @Override public void openDownloads() { openSystemDownloads(); }

            @Override public void findInPage() {
                menuController.showFindInPageDialog(tabManager.getCurrentWebView());
            }

            @Override public void showBookmarks() { historyController.showBookmarks(); }

            @Override public void addBookmark() {
                WebView wv = tabManager.getCurrentWebView();
                if (wv != null) historyController.addBookmark(wv.getUrl(), wv.getTitle());
            }

            @Override public void showHistory() { historyController.showHistoryDialog(); }

            @Override public void clearHistory() { historyController.clearHistory(); }

            @Override public void clearCache() {
                WebView wv = tabManager.getCurrentWebView();
                if (wv != null) wv.clearCache(true);
                Toast.makeText(MainActivity.this, "Cache cleared",
                        Toast.LENGTH_SHORT).show();
            }

            @Override public void showFilterLists() { showFilterListsDialog(); }

            @Override public void toggleFilterEngine() {
                boolean on = AdBlockEngine.checkIsEngineEnabled(MainActivity.this);
                AdBlockEngine.setEngineEnabled(MainActivity.this, !on);
                Toast.makeText(MainActivity.this,
                        !on ? "Filterlists Enabled" : "Filterlists Disabled",
                        Toast.LENGTH_SHORT).show();
                WebView wv = tabManager.getCurrentWebView();
                if (wv != null) wv.reload();
            }

            @Override public void toggleDesktopMode() {
                NavigationHelper.toggleDesktopMode(
                        MainActivity.this,
                        tabManager.getCurrentWebView(),
                        getCurrentHost(),
                        () -> Toast.makeText(MainActivity.this,
                                "No site loaded", Toast.LENGTH_SHORT).show());
            }

            @Override public boolean isDesktopEnabledForCurrentSite() {
                return NavigationHelper.isDesktopHostEnabled(
                        MainActivity.this, getCurrentHost());
            }

            @Override public void showSavedPasswords() { showSavedPasswordsDialog(); }

            @Override public void importPasswords() {
                passwordImportLauncher.launch("text/*");
            }

            @Override public void exportPasswords() {
                exportCsvLauncher.launch("spoon_passwords.csv");
            }

            @Override public void showVaultForCurrentSite() {
                MainActivity.this.showVaultForCurrentSite();
            }

            @Override public void toggleStartupAnimation() {
                toggleStartupAnimationInternal();
            }

            @Override public void exit() { sessionManager.exitNow(); }

            @Nullable @Override public WebView getCurrentWebView() {
                return tabManager.getCurrentWebView();
            }

            @NonNull @Override public Context getContext() { return MainActivity.this; }

            @NonNull @Override public SharedPreferences getPreferences() { return prefs; }
        });

        // Wire the buttons that need a controller reference.
        if (menuButton != null) {
            menuButton.setOnClickListener(v -> menuController.showMainMenu(v));
        }
        if (tabIndicator != null) {
            tabIndicator.setOnClickListener(v -> tabManager.showTabSwitcher());
        }
        if (tabBadgeButton != null) {
            tabBadgeButton.setOnClickListener(v -> tabManager.showTabSwitcher());
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

    private void updateAddressBarForTab(@Nullable WebView webView, @Nullable TabState state) {
        if (addressBar == null) return;

        String url = webView != null ? webView.getUrl() : null;
        addressBar.setText(
                (url == null || url.isEmpty() || "about:blank".equals(url)) ? "" : url);

        if (state != null && state.isIncognito()) {
            addressBar.setBackgroundColor(Color.parseColor("#3c1f40"));
            addressBar.setHint("Incognito Search or URL");
        } else {
            addressBar.setBackgroundColor(Color.parseColor("#222222"));
            addressBar.setHint("Search or enter address");
        }

        if (webView != null) {
            String host = webView.getUrl() != null
                    ? Uri.parse(webView.getUrl()).getHost() : null;
            boolean desktop = NavigationHelper.isDesktopHostEnabled(this, host
