package com.spoondon.browser;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thin Activity shell for Spoon Browser.
 *
 * 2026-09-30: added POST_NOTIFICATIONS launcher. DownloadsController
 * requests the permission the first time a download starts.
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "spoon_browser";

    // Core state
    private SharedPreferences prefs;
    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(4);
    private SecureCredentialManager secureCredentialManager;
    public BrowserDatabaseHelper dbHelper;
    private PermissionController permissionController;
    private SessionManager sessionManager;
    private AppWiring wiring;
    private final CopyOnWriteArrayList<String> filterLists = new CopyOnWriteArrayList<>();

    // View tree
    LinearLayout root;
    LinearLayout browserContainer;
    FrameLayout browserWrapper;
    androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh;
    public ProgressBar progressBar;

    // Fullscreen video state
    View customView;
    WebChromeClient.CustomViewCallback customViewCallback;

    // File pickers + clipboard
    private ActivityResultLauncher<String> passwordImportLauncher;
    private ActivityResultLauncher<String> exportCsvLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private ClipboardManager clipboardManager;
    private android.os.Handler clipboardHandler;
    private Runnable clipboardClearRunnable;
    private ClipboardManager.OnPrimaryClipChangedListener clipChangedListener;

    private boolean notificationPermissionRequested = false;

    // ========================================================================
    // Lifecycle
    // ========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_NoActionBar);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        secureCredentialManager = new SecureCredentialManager(this);
        dbHelper = BrowserDatabaseHelper.getInstance(this);
        permissionController = new PermissionController(this);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sessionManager = new SessionManager(this);

        // Notification permission launcher must be registered before
        // onStart, so do it here before wiring.initialize().
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    // Silent — the download proceeds either way, the
                    // notification just won't be visible on API 33+.
                });

        registerFilePickers();
        registerClipboardWatcher();
        setupRootLayout();

        wiring = new AppWiring(
                this,
                browserContainer,
                secureCredentialManager,
                dbHelper,
                permissionController,
                prefs,
                backgroundExecutor,
                filterLists);
        wiring.initialize();
        wiring.registerDownloadsReceiver();

        assembleLayout();

        wiring.getHistoryController().migrateLegacyBookmarksToDatabase();
        loadFilterListsIntoMemory();
        wiring.getTabManager().createNewTab();
        wiring.showHome();

        warmUpBackgroundWork();
        installBackHandler();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override public void onResume() {
        super.onResume();
        handleIncomingIntent(getIntent());
        setIntent(new Intent());
        if (wiring != null) wiring.getTabManager().resumeActiveTab();
    }

    @Override protected void onPause() {
        super.onPause();
        if (sessionManager != null) sessionManager.onPause();
        if (wiring != null) wiring.getTabManager().pauseActiveTab();
    }

    @Override protected void onStop() {
        super.onStop();
        if (sessionManager != null) sessionManager.onStop();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (wiring != null) wiring.getTabManager().onTrimMemory(level);
    }

    @Override protected void onDestroy() {
        if (wiring != null) wiring.unregisterDownloadsReceiver();
        if (sessionManager != null) sessionManager.onDestroy();
        if (backgroundExecutor != null) backgroundExecutor.shutdownNow();
        if (wiring != null) wiring.getTabManager().destroyAll();

        if (clipboardManager != null && clipChangedListener != null) {
            clipboardManager.removePrimaryClipChangedListener(clipChangedListener);
        }
        if (clipboardHandler != null) {
            clipboardHandler.removeCallbacks(clipboardClearRunnable);
        }
        super.onDestroy();
    }

    // ========================================================================
    // Setup
    // ========================================================================

    private void registerFilePickers() {
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
    }

    private void registerClipboardWatcher() {
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
    }

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
        root.addView(wiring.getToolbarController().getRootView());

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

    private void warmUpBackgroundWork() {
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
    }

    private void installBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                TabManager tabs = wiring.getTabManager();
                if (tabs.isTabSwitcherVisible()) {
                    tabs.hideTabSwitcher();
                    return;
                }
                WebView active = tabs.getCurrentWebView();
                if (active != null && active.canGoBack()) {
                    active.goBack();
                    return;
                }
                if (tabs.getTabCount() > 1) {
                    tabs.closeTab(tabs.getCurrentPosition());
                } else {
                    sessionManager.showExitConfirmationDialog();
                }
            }
        });
    }

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

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;

        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            String urlToLoad = intent.getData().toString();
            TabManager tabs = wiring.getTabManager();
            if (tabs.isEmpty()) tabs.createNewTab();
            wiring.getToolbarController().setAddress(urlToLoad);
            openUrl(urlToLoad);
            setIntent(new Intent());
        } else if (intent.getAction() != null) {
            setIntent(new Intent());
        }
    }

    // ========================================================================
    // Notification permission
    // ========================================================================

    /**
     * Request POST_NOTIFICATIONS on API 33+ so DownloadManager progress
     * notifications appear. Only asks once per process lifetime. Silently
     * no-ops below API 33.
     */
    public void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (notificationPermissionRequested) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationPermissionRequested = true;
        try {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } catch (Exception ignored) {
        }
    }

    // ========================================================================
    // Public API
    // ========================================================================

    public void openUrl(@Nullable String url) {
        WebView wv = getCurrentWebView();
        if (wv == null || url == null) return;
        NavigationHelper.openUrl(wv, url, this, wiring::getSearchUrlFor);
    }

    public void openVault() {
        TabManager tabs = wiring.getTabManager();
        tabs.createNewTab();
        WebView wv = tabs.getCurrentWebView();
        if (wv != null) {
            wv.loadUrl(VaultUrls.HTML);
        }
    }

    @Nullable public String getCurrentHost() {
        WebView wv = getCurrentWebView();
        if (wv == null || wv.getUrl() == null) return null;
        return Uri.parse(wv.getUrl()).getHost();
    }

    @Nullable public WebView getCurrentWebView() {
        return wiring != null ? wiring.getTabManager().getCurrentWebView() : null;
    }

    @Nullable public TabState getCurrentTabState() {
        return wiring != null ? wiring.getTabManager().getCurrentTabState() : null;
    }

    public void openUrlInNewTab(String url) {
        if (wiring != null) wiring.getTabManager().openUrlInNewTab(url);
    }

    public void handleDeadRenderProcess(WebView deadWebView) {
        if (wiring != null) wiring.getTabManager().handleDeadRenderProcess(deadWebView);
    }

    public int getTabCount() {
        return wiring != null ? wiring.getTabManager().getTabCount() : 0;
    }

    public int getBookmarkCount() {
        return dbHelper != null ? dbHelper.getBookmarkCount() : 0;
    }

    public int getHistoryCount() {
        return dbHelper != null ? dbHelper.getHistoryCount() : 0;
    }

    public void updateTabBadgeCount() {
        if (wiring == null) return;
        TabManager tabs = wiring.getTabManager();
        wiring.getToolbarController().setTabCounter(
                tabs.getCurrentPosition(), tabs.getTabCount());
    }

    public void showHome() {
        if (wiring != null) wiring.showHome();
    }

    public void showExitConfirmationDialog() {
        if (sessionManager != null) sessionManager.showExitConfirmationDialog();
    }

    public void exitBrowser() {
        if (sessionManager != null) sessionManager.exitNow();
    }

    public void launchPasswordImport() {
        if (passwordImportLauncher != null) passwordImportLauncher.launch("text/*");
    }

    public void launch
