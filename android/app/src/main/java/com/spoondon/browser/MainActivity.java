package com.spoondon.browser;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Build;
import android.os.Message;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.TypedValue;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import android.text.TextUtils;
import android.app.AlertDialog;

import android.webkit.DownloadListener;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SafeBrowsingResponse;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebView.WebViewTransport;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebMessagePortCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Collections;

public class MainActivity extends AppCompatActivity {
    SecureCredentialManager secureCredentialManager;
    public BrowserDatabaseHelper dbHelper;
    private android.view.View findInPageBar = null;
    private static final String PREFS_NAME = "spoon_browser";
    private static final String KEY_BOOKMARKS = "bookmarks";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_PAGE_TITLES = "page_titles";
    private static final String KEY_FILTER_LISTS = "filter_lists";
    private static final String KEY_FILTER_REFRESH_TIME = "filter_refresh_time";
    private static final String KEY_OPEN_TABS = "open_tabs";
    private static final String KEY_CURRENT_TAB = "current_tab";
    private static final int MAX_HISTORY = 500;
    public android.webkit.PermissionRequest currentPermissionRequest;
    public android.webkit.GeolocationPermissions.Callback currentGeolocationCallback;
    public String currentGeolocationOrigin;
    private androidx.activity.result.ActivityResultLauncher<String[]> webPermissionLauncher;
    public android.webkit.ValueCallback<android.net.Uri[]> mFilePathCallback;
    public static final int FILECHOOSER_RESULTCODE = 100;

    AutoCompleteTextView addressBar;
    private SuggestionAdapter addressBarAdapter;
    private String cachedHomeHtml = null;
    LinearLayout root;
    LinearLayout browserContainer;
    private TextView tabIndicator;
    LinearLayout toolbar;
    private Button forwardButton;
    private Button prevTabButton;
    private Button nextTabButton;
    private Button newTabButton;
    private Button menuButton;
    private Button tabBadgeButton;
    View customView;
    WebChromeClient.CustomViewCallback customViewCallback;
    private ValueCallback<Uri[]> fileChooserCallback;
    private ActivityResultLauncher<String> filePickerLauncher;
    private ActivityResultLauncher<String> passwordImportLauncher;    
    private androidx.activity.result.ActivityResultLauncher<String> exportCsvLauncher;
    public final java.util.concurrent.ExecutorService backgroundExecutor = java.util.concurrent.Executors.newFixedThreadPool(4);           
    private SharedPreferences prefs;
    
    private boolean suppressSuggestions = false;
    private boolean clearSessionOnExit = false;
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private boolean isDesktopMode = false;
    private final CopyOnWriteArrayList<String> filterLists = new CopyOnWriteArrayList<>();
    private final HashSet<String> blockedDomains = new HashSet<>();
    private final HashSet<String> rawFilterRules = new HashSet<>();
    private java.util.List<TabState> tabList = new java.util.ArrayList<>();
    private int currentTabPosition = -1;
    private TabAdapter tabAdapter;
    private android.view.View tabSwitcherOverlay;
    private androidx.recyclerview.widget.RecyclerView tabsRecyclerView;    
    private android.view.ViewGroup webViewContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_NoActionBar);
        android.webkit.WebView.enableSlowWholeDocumentDraw();
        
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        exportCsvLauncher = registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv"),uri -> {
            if (uri != null && secureCredentialManager != null) {
                backgroundExecutor.execute(() -> {
                    try {
                        java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                        if (os != null) {
                            String rawJson = secureCredentialManager.getAllCredentialsAsCsv();
                            os.write(rawJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            os.close();
                            runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, "Passwords exported successfully", android.widget.Toast.LENGTH_LONG).show());
                        }
                    } catch (Exception e) {
                        runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, "Export failed", android.widget.Toast.LENGTH_SHORT).show());
                    }
                });
            }
        });
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(android.graphics.Color.BLACK);
        }

        secureCredentialManager = new SecureCredentialManager(this);
        dbHelper = BrowserDatabaseHelper.getInstance(this);
        
        migrateLegacyBookmarksToDatabase();

        webPermissionLauncher = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                if (currentPermissionRequest != null) {
                    java.util.List<String> granted = new java.util.ArrayList<>();
                    Boolean cam = result.get(android.Manifest.permission.CAMERA);
                    Boolean mic = result.get(android.Manifest.permission.RECORD_AUDIO);
                    if (cam != null && cam) {
                        granted.add(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE);
                    }
                    if (mic != null && mic) {
                        granted.add(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE);
                    }
                    if (!granted.isEmpty()) {
                        currentPermissionRequest.grant(granted.toArray(new String[0]));
                    } else {
                        currentPermissionRequest.deny();
                    }
                    currentPermissionRequest = null;
                }
                if (currentGeolocationCallback != null) {
                    Boolean fine = result.get(android.Manifest.permission.ACCESS_FINE_LOCATION);
                    Boolean coarse = result.get(android.Manifest.permission.ACCESS_COARSE_LOCATION);
                    if ((fine != null && fine) || (coarse != null && coarse)) {
                        currentGeolocationCallback.invoke(currentGeolocationOrigin, true, false);
                    } else {
                        currentGeolocationCallback.invoke(currentGeolocationOrigin, false, false);
                    }
                    currentGeolocationCallback = null;
                    currentGeolocationOrigin = null;
                }
            }
        );

        passwordImportLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                        if (secureCredentialManager.importFromCSVStream(is)) {
                            android.widget.Toast.makeText(this, "Passwords imported successfully", android.widget.Toast.LENGTH_SHORT).show();
                        } else {
                            android.widget.Toast.makeText(this, "Failed to parse passwords.csv", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        android.widget.Toast.makeText(this, "Error opening file stream", android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            }
        );

        filePickerLauncher = registerForActivityResult(
           new ActivityResultContracts.GetContent(),
                uri -> {
                    if (fileChooserCallback != null) {
                        fileChooserCallback.onReceiveValue(uri != null ? new Uri[]{uri} : null);
                        fileChooserCallback = null;
                    }
                }
        );

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        
        isDesktopMode = prefs.getBoolean("isDesktopMode", false);
        setupRootLayout();
        createToolbarViews();
        setupToolbarListeners();
        setupMenuButton();

        if (root != null) {
            if (toolbar != null) root.addView(toolbar);
            if (browserContainer != null) root.addView(browserContainer);
            
            getWindow().setFlags(
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            );
            
            setContentView(root);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                root.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
                if (browserContainer != null) {
                    browserContainer.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
                }
            }
        }

        loadSavedData();

        if (backgroundExecutor != null) {
            backgroundExecutor.execute(() -> {
                AdBlockEngine.init(MainActivity.this, filterLists);
                AdBlockEngine.checkAndRefreshFilters(MainActivity.this, backgroundExecutor, filterLists, false);
            });
        }

        if (backgroundExecutor != null && dbHelper != null) {
            backgroundExecutor.execute(() -> {
                try {
                    dbHelper.cleanupOldHistory(90);
                } catch (Exception ignored) {}
            });
        }

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (tabSwitcherOverlay != null && tabSwitcherOverlay.getVisibility() == android.view.View.VISIBLE) {
                    hideTabSwitcher();
                    return;
                }                
                android.webkit.WebView activeWebView = getCurrentWebView();
                if (activeWebView != null && activeWebView.canGoBack()) {
                    activeWebView.goBack();
                    return;
                }                
                if (tabList.size() > 1) {
                    closeTab(currentTabPosition);
                } else {
                    showExitConfirmationDialog();
                }
            }
        });
    }

    private void migrateLegacyBookmarksToDatabase() {
        android.content.SharedPreferences legacyPrefs = getSharedPreferences("spoon_bookmarks", android.content.Context.MODE_PRIVATE);
        String legacyData = legacyPrefs.getString("bookmarks", null);

        if (legacyData == null || legacyData.equals("[]")) {
            return;
        }

        if (backgroundExecutor != null) {
            backgroundExecutor.execute(() -> {
                try {
                    org.json.JSONArray bookmarksArray = new org.json.JSONArray(legacyData);
                    
                    for (int i = 0; i < bookmarksArray.length(); i++) {
                        org.json.JSONObject bookmark = bookmarksArray.optJSONObject(i);
                        if (bookmark != null) {
                            String title = bookmark.optString("title", "Untitled");
                            String url = bookmark.optString("url", "");

                            if (!url.isEmpty() && dbHelper != null) {
                                dbHelper.addBookmark(url, title);
                            }
                        }
                    }

                    legacyPrefs.edit().clear().apply();

                } catch (Exception ignored) {
                }
            });
        }
    }

    public void executeSafely(Runnable task) {
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown() && !backgroundExecutor.isTerminated()) {
            try {
                backgroundExecutor.execute(task);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                e.printStackTrace();
            }
        }
    }

    private void showExitConfirmationDialog() {
        if (this.isFinishing() || this.isDestroyed()) return;

        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Exit Browser")
            .setMessage("Are you sure you want to exit the browser?")
            .setPositiveButton("Exit", (dialog, which) -> {
                clearSessionOnExit = false; 
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    android.webkit.CookieManager.getInstance().flush();
                }
                android.content.SharedPreferences prefs = getSharedPreferences("browser_prefs", MODE_PRIVATE);
                if (prefs != null) {
                    prefs.edit().remove("open_tabs").remove("current_tab").apply();
                }
                finishAndRemoveTask();
            })
            .setNegativeButton("Cancel", null)
            .show();
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
        setIntent(new android.content.Intent());     
        WebView activeWebView = getCurrentWebView();            
        if (activeWebView != null) {        
            activeWebView.onResume();        
            activeWebView.resumeTimers();    
        }
    }
    
    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;

        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            String urlToLoad = intent.getData().toString();
            
            if (tabList.isEmpty()) {
                createNewTab();
            }
            
            if (addressBar != null) {
                addressBar.setText(urlToLoad);
            }
            openUrl(urlToLoad);
            
            setIntent(new Intent());
            
        } else if (intent.getAction() != null) {
            setIntent(new Intent());
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        
        try {
            if (!clearSessionOnExit) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    android.webkit.CookieManager.getInstance().flush();
                }
            }
        } catch (Exception ignored) {}

        WebView activeWebView = getCurrentWebView();
        if (activeWebView != null) {
            activeWebView.onPause();
            activeWebView.pauseTimers();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        
        try {
            if (clearSessionOnExit) {
                android.webkit.WebStorage.getInstance().deleteAllData();
                android.webkit.CookieManager.getInstance().removeAllCookies(null);
                android.webkit.CookieManager.getInstance().flush();
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        try {
            if (!clearSessionOnExit) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    android.webkit.CookieManager.getInstance().flush();
                }
            }
        } catch (Exception ignored) {}

        if (backgroundExecutor != null) {
            backgroundExecutor.shutdownNow();
        }

        if (tabList != null) {
            for (int i = 0; i < tabList.size(); i++) {
                android.webkit.WebView wv = tabList.get(i).getWebView();
                if (wv != null) {
                    if (wv.getParent() instanceof android.view.ViewGroup) {
                        ((android.view.ViewGroup) wv.getParent()).removeView(wv);
                    }
                    wv.stopLoading();
                    wv.setWebChromeClient(null);
                    wv.setWebViewClient(null);
                    wv.destroy();
                }
            }
            tabList.clear();
        }

        super.onDestroy();
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
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f
        );
        browserContainer.setLayoutParams(browserParams);
    }

    private void loadSavedData() {
        String savedFilterLists = prefs.getString(KEY_FILTER_LISTS, "");
        if (!savedFilterLists.isEmpty()) {
            for (String filter : savedFilterLists.split("\\n")) {
                if (!filterLists.contains(filter)) {
                    filterLists.add(filter);
                }
            }
        }

        if (!filterLists.isEmpty()) {
            long lastRefresh = prefs.getLong(KEY_FILTER_REFRESH_TIME, 0);
            if (System.currentTimeMillis() - lastRefresh > 24L * 60 * 60 * 1000) {
                AdBlockEngine.checkAndRefreshFilters(this, backgroundExecutor, filterLists, true);
            }
        }
        AdBlockEngine.checkAndRefreshFilters(this, backgroundExecutor, filterLists, true);
        
        createNewTab();
        showHome();
    }
        
    private void showSavedPasswordsDialog() {
        if (secureCredentialManager == null) return;
        
        backgroundExecutor.execute(() -> {
            java.io.File vaultFile = new java.io.File(getFilesDir(), "secure_vault.dat");
            java.util.ArrayList<String> hosts = new java.util.ArrayList<>();
            if (vaultFile.exists()) {
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(vaultFile), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.contains("_user=")) {
                            String host = line.split("_user=")[0];
                            if (!hosts.contains(host)) {
                                hosts.add(host);
                            }
                        }
                    }
                } catch (Exception e) { e.printStackTrace(); }
            }

            if (hosts.isEmpty()) {
                runOnUiThread(() -> {
                    if (!MainActivity.this.isFinishing() && !MainActivity.this.isDestroyed()) {
                        new AlertDialog.Builder(this)
                            .setTitle("Saved Passwords")
                            .setMessage("No passwords saved yet.")
                            .setPositiveButton("OK", null)
                            .show();
                    }
                });
                return;
            }

            java.util.ArrayList<String> displayList = new java.util.ArrayList<>();
            for (String h : hosts) {
                String user = secureCredentialManager.getUsername(h);
                displayList.add(h + " (" + user + ")");
            }

            runOnUiThread(() -> {
                if (MainActivity.this.isFinishing() || MainActivity.this.isDestroyed()) return;

                ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, displayList);
                ListView listView = new ListView(this);
                listView.setAdapter(adapter);

                AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Saved Passwords")
                    .setView(listView)
                    .setPositiveButton("Close", null)
                    .create();

                listView.setOnItemClickListener((parent, view, position, id) -> {
                    String selectedHost = hosts.get(position);
                    
                    backgroundExecutor.execute(() -> {
                        String user = secureCredentialManager.getUsername(selectedHost);
                        String pass = secureCredentialManager.getPassword(selectedHost);
                        
                        runOnUiThread(() -> {
                            if (MainActivity.this.isFinishing() || MainActivity.this.isDestroyed()) return;

                            new AlertDialog.Builder(this)
                                .setTitle(selectedHost)
                                .setMessage("Username: " + user + "\nPassword: " + pass)
                                .setNegativeButton("Delete", (d, w) -> {
                                    backgroundExecutor.execute(() -> {
                                        secureCredentialManager.clearCredentials(selectedHost);
                                        runOnUiThread(() -> {
                                            if (!MainActivity.this.isFinishing() && !MainActivity.this.isDestroyed()) {
                                                Toast.makeText(this, "Credentials deleted", Toast.LENGTH_SHORT).show();
                                                dialog.dismiss();
                                                showSavedPasswordsDialog();
                                            }
                                        });
                                    });
                                })
                                .setPositiveButton("OK", null)
                                .show();
                        });
                    });
                });

                dialog.show();
            });
        });
    }

    private void setupMenuButton() {
        menuButton.setOnClickListener(v -> {
            android.content.Context wrapper = new android.view.ContextThemeWrapper(this, android.R.style.Widget_Material_Light_PopupMenu);
            android.widget.PopupMenu popup = new android.widget.PopupMenu(wrapper, menuButton, android.view.Gravity.END);
            
            popup.getMenu().add("New Tab");
            popup.getMenu().add("Reload");
            popup.getMenu().add("Downloads");
            popup.getMenu().add("Find in Page");
            popup.getMenu().add("Bookmarks");
            popup.getMenu().add("Add Bookmark");
            popup.getMenu().add("History");
            popup.getMenu().add("Clear History");
            popup.getMenu().add("Clear Cache");
            popup.getMenu().add("Filter Lists");
           
            boolean isFilterEnabled = AdBlockEngine.checkIsEngineEnabled(this);
            popup.getMenu().add(isFilterEnabled ? "Disable Filterlists" : "Enable Filterlists");
            
            String currentHost = "";        
            if (currentTabPosition >= 0 && currentTabPosition < tabList.size()) {            
                android.webkit.WebView activeWebView = tabList.get(currentTabPosition).getWebView();
                
                if (activeWebView != null && activeWebView.getUrl() != null) {
                    currentHost = android.net.Uri.parse(activeWebView.getUrl()).getHost();
                }
            }
            
            boolean isCurrentSiteDesktop = false;
            if (currentHost != null && !currentHost.isEmpty()) {
                isCurrentSiteDesktop = getSharedPreferences("browser_prefs", MODE_PRIVATE)
                    .getStringSet("desktop_sites", new java.util.HashSet<>()).contains(currentHost);
            }
            
            popup.getMenu().add(isCurrentSiteDesktop ? "Desktop Site [ON]" : "Desktop Site [OFF]");
            popup.getMenu().add("Passwords");
            popup.getMenu().add("🔑 Vault (Copy)");
            popup.getMenu().add("About");
            popup.getMenu().add("Startup Animation");
            popup.getMenu().add("Exit");
            
            popup.setOnMenuItemClickListener(item -> {
                String title = item.getTitle().toString();
                switch (title) {
                    case "New Tab":
                        createNewTab();
                        showHome();
                        return true;
                    case "Reload":
                        if (getCurrentWebView() != null) getCurrentWebView().reload();
                        return true;
                                        
                    case "Downloads":
                        try {
                            android.content.Intent intent = new android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS);
                            intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                        } catch (android.content.ActivityNotFoundException e) {
                            android.widget.Toast.makeText(this, "No download manager found", android.widget.Toast.LENGTH_SHORT).show();
                        }
                        return true;

                    case "Find in Page":
                        showFindInPageDialog();
                        return true;

                    case "Bookmarks":
                        showBookmarks();
                        return true;
                        
                    case "Add Bookmark":
                        android.webkit.WebView wv = getCurrentWebView();
                        if (wv != null && wv.getUrl() != null && dbHelper != null) {
                            dbHelper.addBookmark(wv.getUrl(), wv.getTitle());
                            android.widget.Toast.makeText(MainActivity.this, "Bookmark saved", android.widget.Toast.LENGTH_SHORT).show();
                        }
                        return true;
                        
                    case "History":
                        showHistoryDialog();
                        return true;
                        
                    case "Clear History":
                        if (dbHelper != null) dbHelper.clearHistory();
                        android.widget.Toast.makeText(MainActivity.this, "History cleared", android.widget.Toast.LENGTH_SHORT).show();
                        return true;
                        
                    case "Clear Cache":
                        if (getCurrentWebView() != null) getCurrentWebView().clearCache(true);
                        android.widget.Toast.makeText(this, "Cache cleared", android.widget.Toast.LENGTH_SHORT).show();
                        return true;
                        
                    case "Filter Lists":
                        showFilterListsDialog();
                        return true;
                        
                    case "Disable Filterlists":
                    case "Enable Filterlists":
                        boolean currentState = AdBlockEngine.checkIsEngineEnabled(MainActivity.this);
                        AdBlockEngine.setEngineEnabled(MainActivity.this, !currentState);
                        
                        android.widget.Toast.makeText(MainActivity.this, 
                            !currentState ? "Filterlists Enabled" : "Filterlists Disabled", 
                            android.widget.Toast.LENGTH_SHORT).show();
                            
                        android.webkit.WebView currentWv = getCurrentWebView();
                        if (currentWv != null) {
                            currentWv.reload();
                        }
                        return true;
                        
                    case "Desktop Site [OFF]":
                    case "Desktop Site [ON]":
                        toggleDesktopMode();
                        return true;
                        
                    case "Passwords":
                        String[] options = {"Saved Passwords", "Import from CSV", "Export to CSV"};
                        new android.app.AlertDialog.Builder(this)
                            .setTitle("Password Management")
                            .setItems(options, (dialog, which) -> {
                                if (which == 0) {
                                    createNewTab();
                                    openUrl("file:///android_asset/vault.html");
                                } else if (which == 1) {
                                    passwordImportLauncher.launch("text/*");
                                } else if (which == 2) {
                                    exportCsvLauncher.launch("spoon_passwords.csv");
                                }
                            })
                            .show();
                        return true;
                        
                    case "🔑 Vault (Copy)":
                        showVaultForCurrentSite();
                        return true;

                    case "Startup Animation":
                        android.content.SharedPreferences splashPrefs = getSharedPreferences("browser_prefs", MODE_PRIVATE);
                        boolean isCurrentlyEnabled = splashPrefs.getBoolean("show_splash_screen", true);
                        splashPrefs.edit().putBoolean("show_splash_screen", !isCurrentlyEnabled).apply();
                        
                        String statusMsg = !isCurrentlyEnabled ? "Startup Animation Enabled" : "Startup Animation Disabled";
                        android.widget.Toast.makeText(MainActivity.this, statusMsg, android.widget.Toast.LENGTH_SHORT).show();
                        return true;    
                        
                    case "About":
                        showAbout();
                        return true;
                        
                    case "Exit":
                        clearSessionOnExit = false; 
                        
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                            android.webkit.CookieManager.getInstance().flush();
                        }
                        
                        if (prefs != null) {
                            prefs.edit().remove("open_tabs").remove("current_tab").apply();
                        }
                        
                        finishAndRemoveTask();
                        return true;
                }
                return false;
            });
            popup.show();
        });
    }
    
    private void showFindInPageDialog() {
        android.webkit.WebView webView = getCurrentWebView();
        if (webView == null) return;

        android.view.ViewGroup rootLayout = findViewById(android.R.id.content);

        if (findInPageBar != null) rootLayout.removeView(findInPageBar);

        android.widget.LinearLayout barLayout = new android.widget.LinearLayout(this);
        barLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        barLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        barLayout.setPadding(20, 10, 20, 10);
        barLayout.setElevation(10f);

        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setCornerRadius(24);
        shape.setColor(android.graphics.Color.parseColor("#B3121212"));
        shape.setStroke(2, android.graphics.Color.parseColor("#333333"));
        barLayout.setBackground(shape);

        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Find...");
        input.setSingleLine(true);
        input.setTextColor(android.graphics.Color.WHITE);
        input.setHintTextColor(android.graphics.Color.GRAY);
        input.setBackground(null);
        android.widget.LinearLayout.LayoutParams inputParams = new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        barLayout.addView(input, inputParams);

        android.widget.TextView countText = new android.widget.TextView(this);
        countText.setText("0/0");
        countText.setTextColor(android.graphics.Color.LTGRAY);
        countText.setPadding(15, 0, 15, 0);
        barLayout.addView(countText);

        android.widget.Button prevBtn = new android.widget.Button(this);
        prevBtn.setText("∧");
        prevBtn.setTextColor(android.graphics.Color.WHITE);
        prevBtn.setBackground(null);
        
        android.widget.Button nextBtn = new android.widget.Button(this);
        nextBtn.setText("∨");
        nextBtn.setTextColor(android.graphics.Color.WHITE);
        nextBtn.setBackground(null);

        android.widget.Button closeBtn = new android.widget.Button(this);
        closeBtn.setText("X");
        closeBtn.setTextColor(android.graphics.Color.parseColor("#FF5555"));
        closeBtn.setBackground(null);

        barLayout.addView(prevBtn);
        barLayout.addView(nextBtn);
        barLayout.addView(closeBtn);

        webView.setFindListener((activeMatchOrdinal, numberOfMatches, isDoneCounting) -> {
            countText.setText(numberOfMatches == 0 ? "0/0" : (activeMatchOrdinal + 1) + "/" + numberOfMatches);
        });

        final android.os.Handler searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable[] searchRunnable = new Runnable[1];

        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchRunnable[0] != null) searchHandler.removeCallbacks(searchRunnable[0]);
                searchRunnable[0] = () -> webView.findAllAsync(s.toString());
                searchHandler.postDelayed(searchRunnable[0], 300);
            }
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        prevBtn.setOnClickListener(v -> webView.findNext(false));
        nextBtn.setOnClickListener(v -> webView.findNext(true));
        closeBtn.setOnClickListener(v -> {
            webView.clearMatches();
            rootLayout.removeView(barLayout);
            findInPageBar = null;
        });

        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = android.view.Gravity.TOP;
        params.setMargins(20, 40, 20, 0);
        
        rootLayout.addView(barLayout, params);
        findInPageBar = barLayout;
        
        input.requestFocus();
    }
    
    private void setupToolbarListeners() {
        
        tabIndicator.setOnLongClickListener(null);
        tabIndicator.setLongClickable(false);
        tabIndicator.setOnClickListener(v -> showTabSwitcher());

        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                (event != null && event.getAction() == android.view.KeyEvent.ACTION_DOWN && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                
                navigate(); 
                
                addressBar.clearFocus();
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(addressBar.getWindowToken(), 0);
                return true;
            }
            return false;
        });

        addressBar.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                
                navigate(); 
                
                addressBar.clearFocus();
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(addressBar.getWindowToken(), 0);
                return true;
            }
            return false;
        });

        forwardButton.setOnClickListener(v -> {
            android.webkit.WebView webView = getCurrentWebView();
            if (webView != null && webView.canGoForward()) {
                webView.goForward();
            }
        });

        newTabButton.setOnClickListener(v -> {
            createNewTab();
            showHome();
        });

        prevTabButton.setOnClickListener(v -> {
            if (tabList.size() > 1) {    
                int previous = currentTabPosition - 1;    
                if (previous < 0) {        
                    previous = tabList.size() - 1;    
                }
                switchToTab(previous);
            }
        });

        nextTabButton.setOnClickListener(v -> {
            if (tabList.size() > 1) {
                int next = (currentTabPosition + 1) % tabList.size();
                switchToTab(next);
            }
        });
    }

    private void createToolbarViews() {
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(10), dp(12), dp(10));
        toolbar.setBackgroundColor(Color.parseColor("#141414"));
        
        forwardButton = makeButton("→");
        prevTabButton = makeButton("◀");
        nextTabButton = makeButton("▶");
        newTabButton = makeButton("+");
        menuButton = makeButton("☰");
        
        menuButton.setOnClickListener(view -> {
            android.widget.PopupMenu popupMenu = new android.widget.PopupMenu(MainActivity.this, menuButton);
            
            popupMenu.getMenu().add("Global History");
            popupMenu.getMenu().add("🔑 Vault / Autofill");
            
            popupMenu.setOnMenuItemClickListener(menuItem -> {
                String title = menuItem.getTitle().toString();
                
                if (title.equals("🔑 Vault / Autofill")) {
                    
                    showVaultForCurrentSite();
                    return true;
                } 
                else if (title.equals("Global History")) {
                    
                    showHistoryDialog();
                    return true;
                }
                return false;
            });
            
            popupMenu.show();
        });

        int screenWidth = getScreenWidthDp();
        if (screenWidth < 600) {
            
            forwardButton.setVisibility(View.GONE);
            prevTabButton.setVisibility(View.GONE);
            nextTabButton.setVisibility(View.GONE);
            newTabButton.setVisibility(View.GONE);
        } else {
            
            forwardButton.setVisibility(View.VISIBLE);
            prevTabButton.setVisibility(View.VISIBLE);
            nextTabButton.setVisibility(View.VISIBLE);
            newTabButton.setVisibility(View.VISIBLE);
        }

        tabIndicator = new TextView(this);
        tabIndicator.setTextColor(Color.WHITE);
        tabIndicator.setTextSize(14);
        tabIndicator.setPadding(dp(8), 0, dp(8), 0);
        
        if (screenWidth < 360) {
            tabIndicator.setVisibility(View.GONE);
        }

        addressBar = new AutoCompleteTextView(this);
        addressBar.setHint("Search or enter address");
        addressBar.setTextColor(Color.WHITE);
        addressBar.setHintTextColor(Color.GRAY);
        addressBar.setSingleLine(true);
        addressBar.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        
        addressBar.setThreshold(1);
        
        android.graphics.drawable.GradientDrawable customAddressBg = new android.graphics.drawable.GradientDrawable();
        customAddressBg.setColor(android.graphics.Color.parseColor("#222222"));
        customAddressBg.setCornerRadius(dp(20));
        addressBar.setBackground(customAddressBg);

        addressBar.setPadding(dp(16), dp(8), dp(16), dp(8));

        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        addressParams.setMargins(dp(6), 0, dp(6), 0);
        addressBar.setLayoutParams(addressParams);
        
        final boolean[] justGainedFocus = {false};
        addressBar.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                
                addressBar.post(() -> {
                    if (addressBar.getText() != null) {
                        addressBar.selectAll();
                    }
                });
                justGainedFocus[0] = true;
            } else {
                justGainedFocus[0] = false;
            }
        });

        addressBar.setOnClickListener(view -> {
            
            if (!justGainedFocus[0]) {
                int cursorPosition = addressBar.getSelectionStart();
                if (cursorPosition >= 0) {
                    addressBar.setSelection(cursorPosition);
                }
            }
            
            justGainedFocus[0] = false;
        });

        addressBarAdapter = new SuggestionAdapter(this, new java.util.ArrayList<>());

        addressBar.post(() -> {
            try {
                addressBar.setAdapter(addressBarAdapter);
                addressBar.setDropDownBackgroundResource(R.drawable.bg_dropdown_rounded);
                addressBar.setDropDownVerticalOffset(dp(8));               
            } catch (Exception ignored) {}
        });

        addressBar.setOnItemClickListener((parent, view, position, id) -> {
            String rawItem = null;
            if (view instanceof TextView) {
                rawItem = ((TextView) view).getText().toString();
            } else if (view instanceof ViewGroup) {
                for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                    View child = ((ViewGroup) view).getChildAt(i);
                    if (child instanceof TextView) {
                        rawItem = ((TextView) child).getText().toString();
                        break;
                    }
                }
            }
            if (rawItem == null) {
                try {
                    rawItem = (String) parent.getItemAtPosition(position);
                } catch (Exception e) {
                    rawItem = addressBar.getText().toString();
                }
            }
            if (rawItem == null || rawItem.isEmpty()) return;

            String cleanUrl = rawItem;
            if (rawItem.contains("http://") || rawItem.contains("https://")) {
                int httpIndex = rawItem.indexOf("http://");
                int httpsIndex = rawItem.indexOf("https://");
                int startUrl = (httpIndex != -1 && httpsIndex != -1) ? Math.min(httpIndex, httpsIndex) : (httpIndex != -1 ? httpIndex : httpsIndex);
                cleanUrl = rawItem.substring(startUrl).trim();
            }

            suppressSuggestions = true;
            addressBar.setText(cleanUrl);
            addressBar.setSelection(cleanUrl.length());
            try {
                if (addressBar.isAttachedToWindow()) {
                    addressBar.dismissDropDown();
                }
            } catch (Exception ignored) {}

            addressBar.post(() -> {
                navigate();
                suppressSuggestions = false;
            });
        });

        addressBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (suppressSuggestions) return;
                
                updateAddressBarSuggestions(s.toString().trim());
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
            
        });

        GradientDrawable addressBg = new GradientDrawable();
        addressBg.setColor(Color.parseColor("#262626"));
        addressBg.setCornerRadius(dp(20));
        addressBar.setBackground(addressBg);
        addressBar.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        inputParams.setMargins(dp(8), 0, dp(8), 0);
        addressBar.setLayoutParams(inputParams);

        float widthDp = getResources().getConfiguration().screenWidthDp;

        if (widthDp >= 600) {
            
            toolbar.addView(forwardButton);
            toolbar.addView(prevTabButton);
            toolbar.addView(tabIndicator);
            toolbar.addView(nextTabButton);
            toolbar.addView(newTabButton);
            toolbar.addView(addressBar);
            toolbar.addView(menuButton);
        } else {
            
            if (addressBar != null && addressBar.getParent() instanceof ViewGroup) {
                ((ViewGroup) addressBar.getParent()).removeView(addressBar);
            }
            if (menuButton != null && menuButton.getParent() instanceof ViewGroup) {
                ((ViewGroup) menuButton.getParent()).removeView(menuButton);
            }

            forwardButton.setVisibility(View.GONE);
            prevTabButton.setVisibility(View.GONE);
            tabIndicator.setVisibility(View.GONE);
            nextTabButton.setVisibility(View.GONE);
            newTabButton.setVisibility(View.GONE);

            tabBadgeButton = new Button(this);
            int tabCount = (tabList != null) ? tabList.size() : 1;
            tabBadgeButton.setText(String.valueOf(tabCount));
            tabBadgeButton.setTextColor(Color.WHITE);
            tabBadgeButton.setTextSize(12);
            tabBadgeButton.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tabBadgeButton.setGravity(Gravity.CENTER);
            
            tabBadgeButton.setPadding(0, 0, 0, 0);
            tabBadgeButton.setIncludeFontPadding(false);

            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(Color.TRANSPARENT);
            badgeBg.setStroke(dp(2), Color.parseColor("#CCCCCC"));
            badgeBg.setCornerRadius(dp(6));
            tabBadgeButton.setBackground(badgeBg);

            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(36), dp(36));
            badgeParams.setMargins(dp(6), 0, dp(6), 0);
            tabBadgeButton.setLayoutParams(badgeParams);

            tabBadgeButton.setOnClickListener(v -> {
                showTabSwitcher();
            });

            if (menuButton != null) {
                menuButton.setPadding(0, 0, 0, 0);
                menuButton.setIncludeFontPadding(false);
            }

            if (addressBar != null && addressBar.getParent() == null) {
                toolbar.addView(addressBar);
            }
            if (tabBadgeButton != null && tabBadgeButton.getParent() == null) {
                toolbar.addView(tabBadgeButton);
            }
            if (menuButton != null && menuButton.getParent() == null) {
                toolbar.addView(menuButton);
            }
        }
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#2a2a2a"));
        bg.setCornerRadius(dp(12));
        button.setBackground(bg);

        int buttonSize = getToolbarButtonSize();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(buttonSize, buttonSize);
        params.setMargins(dp(3), 0, dp(3), 0);
        button.setLayoutParams(params);

        return button;
    }

    private int getToolbarButtonSize() {
        int width = getResources().getConfiguration().screenWidthDp;
        if (width < 400) return dp(36);
        else if (width < 600) return dp(42);
        return dp(46);
    }

    private int getScreenWidthDp() {
        return getResources().getConfiguration().screenWidthDp;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()
        );
    }
    
    private void configureWebSettings(android.webkit.WebSettings settings) {
        if (settings == null) return;

        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);

        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSaveFormData(true);
        settings.setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);

        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            settings.setLayoutAlgorithm(android.webkit.WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING);
        }
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setGeolocationEnabled(false);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true); 
        }
        
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                cm.setAcceptThirdPartyCookies(getCurrentWebView(), true); 
            }
        } catch (Exception ignored) {}
        
        settings.setUserAgentString(MOBILE_UA);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
    }
    
    private View.OnLongClickListener createImageLongClickListener(WebView webView) {
        return v -> {
            WebView.HitTestResult result = webView.getHitTestResult();
            if (result != null && (result.getType() == WebView.HitTestResult.IMAGE_TYPE
                    || result.getType() == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(result.getExtra())));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Cannot download image", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            return false;
        };
    }

    private WebView createConfiguredWebView() {
        WebView webView = new WebView(this);
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        webView.setLayoutParams(webParams);

        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);

        android.webkit.WebSettings webSettings = webView.getSettings();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
            webSettings.setMediaPlaybackRequiresUserGesture(false);
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            webSettings.setOffscreenPreRaster(true);
        }

        webSettings.setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setBlockNetworkImage(false);

        configureWebSettings(webSettings);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            webSettings.setSafeBrowsingEnabled(true);
        }

        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setJavaScriptEnabled(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setLayoutAlgorithm(android.webkit.WebSettings.LayoutAlgorithm.NORMAL);
        webSettings.setAllowFileAccess(true);
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
            webSettings.setAllowFileAccessFromFileURLs(false);
            webSettings.setAllowUniversalAccessFromFileURLs(false);
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            webSettings.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        String currentUserAgent = webSettings.getUserAgentString();
        if (currentUserAgent != null) {
            currentUserAgent = currentUserAgent.replace("; wv", "");
            currentUserAgent = currentUserAgent.replaceFirst("Version/[0-9.]+\\s", "");
            webSettings.setUserAgentString(currentUserAgent);
        }

        android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        webView.setOnLongClickListener(createImageLongClickListener(webView));
        webView.setWebViewClient(new SpoonWebViewClient(this));
        webView.setWebChromeClient(new SpoonWebChromeClient(this));

        BlobDownloader downloaderBridge = new BlobDownloader(this);
        webView.addJavascriptInterface(downloaderBridge, "AndroidDownloader");
        
        webView.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                if (url == null) return;

                if (url.startsWith("blob:")) {
                    String safeDisposition = contentDisposition != null ? contentDisposition.replace("'", "\\'") : "";
                    String safeMime = mimeType != null ? mimeType.replace("'", "\\'") : "";
                    
                    String script = "javascript:(function() {" +
                        "var url = '" + url + "';" +
                        "var blob = window.spoonBlobStore ? window.spoonBlobStore[url] : null;" +
                        
                        "function processBlob(b) {" +
                            "var mime = b.type || '" + safeMime + "';" +
                            "var filename = '" + safeDisposition + "'.match(/filename=[\"']?([^\"';]+)[\"']?/i);" +
                            "filename = filename ? filename[1] : 'downloaded_file';" +
                            "if (filename === 'downloaded_file') {" +
                                "if (mime.includes('json')) filename += '.json';" +
                                "else if (mime.includes('pdf')) filename += '.pdf';" +
                                "else if (mime.includes('text')) filename += '.txt';" +
                                "else filename += '.bin';" +
                            "}" +
                            "var reader = new FileReader();" +
                            "reader.readAsDataURL(b);" +
                            "reader.onloadend = function() {" +
                                "AndroidDownloader.saveBase64ToFile(reader.result, mime, filename);" +
                            "}" +
                        "}" +
                        
                        "if (blob) {" +
                            "processBlob(blob);" + 
                        "} else {" +
                            "fetch(url).then(function(r){return r.blob();}).then(processBlob).catch(function(e) {" +
                                "alert('Blob download failed. The site bypassed the memory hook.');" +
                            "});" +
                        "}" +
                    "})()";
                    
                    webView.evaluateJavascript(script, null);
                    android.widget.Toast.makeText(MainActivity.this, "Extracting file...", android.widget.Toast.LENGTH_SHORT).show();
                    return; 
                }

                try {
                    String targetFileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
                    String lowerUrl = url.toLowerCase();

                    boolean isActuallyPdf = (mimeType != null && mimeType.equalsIgnoreCase("application/pdf")) ||
                                            lowerUrl.contains(".pdf") ||
                                            (contentDisposition != null && contentDisposition.toLowerCase().contains(".pdf"));

                    if (targetFileName.endsWith(".bin") || targetFileName.equals("downloadfile")) {
                        if (isActuallyPdf) {
                            targetFileName = targetFileName.replace(".bin", "").replace("downloadfile", "download") + ".pdf";
                        } else {
                            String extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                            if (extension != null) {
                                targetFileName = targetFileName.replace(".bin", "").replace("downloadfile", "download") + "." + extension;
                            }
                        }
                    }

                    if (isActuallyPdf && !targetFileName.toLowerCase().endsWith(".pdf")) {
                        targetFileName += ".pdf";
                    }

                    if (targetFileName.startsWith(".")) {
                        targetFileName = targetFileName.substring(1) + ".txt";
                    }

                    if (lowerUrl.contains(".md") && !targetFileName.endsWith(".md")) targetFileName += ".md";
                    if (lowerUrl.contains(".json") && !targetFileName.endsWith(".json")) targetFileName += ".json";

                    targetFileName = targetFileName.replace("/", "_").replace("\\", "_");

                    String safeMimeType = (mimeType == null || mimeType.isEmpty()) ? "application/octet-stream" : mimeType;
                    
                    if (isActuallyPdf && safeMimeType.equals("application/octet-stream")) {
                        safeMimeType = "application/pdf";
                    }

                    final String finalTargetFileName = targetFileName;
                    final String finalSafeMimeType = safeMimeType;

                    new android.app.AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Download File")
                            .setMessage("Do you want to download " + finalTargetFileName + "?")
                            .setPositiveButton("Download", (dialog, item) -> {
                                try {
                                    android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(android.net.Uri.parse(url));
                                    
                                    String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
                                    if (cookies != null) request.addRequestHeader("Cookie", cookies);
                                    if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);
                                    
                                    request.setMimeType(finalSafeMimeType);
                                    
                                    request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, finalTargetFileName);
                                    request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                                    request.setTitle(finalTargetFileName);
                                    
                                    android.app.DownloadManager manager = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                                    if (manager != null) {
                                        manager.enqueue(request);
                                        android.widget.Toast.makeText(MainActivity.this, "Download started...", android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                } catch (Exception e) {
                                    triggerExternalDownload(url, finalSafeMimeType);
                                }
                            })
                            .setNeutralButton("External Only", (dialog, item) -> triggerExternalDownload(url, finalSafeMimeType))
                            .setNegativeButton("Cancel", null)
                            .show();

                } catch (Exception err) {
                    android.widget.Toast.makeText(MainActivity.this, "Download manager initialization failed", android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
        
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER)) {
            java.util.Set<String> allowedOrigins = java.util.Collections.singleton("*");
            
            androidx.webkit.WebViewCompat.addWebMessageListener(webView, "spoonVaultMessage", allowedOrigins,
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                    try {
                        String currentUrl = view.getUrl();
                        if (currentUrl == null || !currentUrl.startsWith("file:///android_asset/vault.html")) {
                            return;
                        }

                        String msg = message.getData();

                        if ("FETCH_ALL_VAULT_DATA".equals(msg)) {
                            String allAccounts = secureCredentialManager.getAllCredentialsAsJson();
                            replyProxy.postMessage(allAccounts != null ? allAccounts : "[]");
                        } else if (msg != null && msg.startsWith("{")) {
                            org.json.JSONObject obj = new org.json.JSONObject(msg);
                            String action = obj.optString("action");
                            String host = obj.optString("host");
                            String user = obj.optString("username");
                            
                            if ("SAVE_LOGIN".equals(action)) {
                                String pass = obj.optString("password");
                                secureCredentialManager.saveCredentials(host, user, pass);
                            } else if ("DELETE_LOGIN".equals(action)) {
                                secureCredentialManager.deleteCredentials(host, user);
                            }
                        }
                    } catch (Exception e) {
                    }
                }
            );
        }
                
        return webView;
    }

    public void handleDeadRenderProcess(android.webkit.WebView deadWebView) {
        if (deadWebView == null) return;
        
        for (int i = 0; i < tabList.size(); i++) {
            if (tabList.get(i).getWebView() == deadWebView) {
                if (browserContainer != null) {
                    browserContainer.removeView(deadWebView);
                }
                
                deadWebView.removeAllViews();
                deadWebView.destroy();
                
                android.widget.Toast.makeText(this, "Tab closed automatically to free up device memory.", android.widget.Toast.LENGTH_LONG).show();
                closeTab(i);
                break;
            }
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || 
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            
            for (TabState tab : tabList) {
                if (tab != null && tab.getWebView() != null) {
                    android.webkit.WebView wv = tab.getWebView();
                    if (wv.getVisibility() == android.view.View.GONE) {
                        wv.clearCache(false);
                    }
                }
            }
        }
    }
        
    private void createNewTab() {
        WebView webView = createConfiguredWebView();
        webView.addJavascriptInterface(new PasswordAutosaveBridge(), "SpoonVault");

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB) {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        webView.setDrawingCacheEnabled(false);

        android.webkit.WebSettings settings = webView.getSettings();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
        settings.setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);

        TabState newTab = new TabState(webView);
        tabList.add(newTab);
        currentTabPosition = tabList.size() - 1;

        if (browserContainer != null) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            );
            
            webView.setVisibility(View.GONE);
            browserContainer.addView(webView, params);
        }
        
        switchToTab(currentTabPosition);
    }

    private void updateTabCountersUI() {
        if (tabList == null || tabList.isEmpty()) {
            if (tabIndicator != null) tabIndicator.setText("0/0");
            if (tabBadgeButton != null) tabBadgeButton.setText("0/0");
            return;
        }
        
        String counterText = (currentTabPosition + 1) + "/" + tabList.size();
        
        if (tabIndicator != null) tabIndicator.setText(counterText);
        if (tabBadgeButton != null) tabBadgeButton.setText(counterText);
    }

    public void openUrlInNewTab(String url) {
        runOnUiThread(() -> {
            
            createNewTab(); 
            
            android.webkit.WebView newTab = tabList.get(currentTabPosition).getWebView();
            newTab.loadUrl(url);
            
            android.widget.Toast.makeText(MainActivity.this, "Opened in new tab", android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    public void switchToTab(int index) {
        if (index < 0 || index >= tabList.size()) return;
        currentTabPosition = index;

        for (int i = 0; i < tabList.size(); i++) {
            android.webkit.WebView wv = tabList.get(i).getWebView();
            if (wv != null) {
                if (i == index) {
                    wv.setVisibility(android.view.View.VISIBLE);
                    wv.onResume();
                    wv.resumeTimers();
                    
                    String url = wv.getUrl();
                    if (addressBar != null) {
                        addressBar.setText((url == null || url.isEmpty() || "about:blank".equals(url)) ? "" : url);
                    }
                } else {
                    wv.setVisibility(android.view.View.GONE);
                    wv.onPause();
                }
            }
        }
        
        updateTabCountersUI();
    }

    public void closeTab(int index) {
        if (index < 0 || index >= tabList.size()) return;

        if (tabList.size() == 1) {
            showExitConfirmationDialog();
            return; 
        }

        TabState tabToRemove = tabList.get(index);
        android.webkit.WebView wvToDestroy = tabToRemove.getWebView();

        if (wvToDestroy != null) {
            if (browserContainer != null) {
                browserContainer.removeView(wvToDestroy);
            }
            wvToDestroy.removeAllViews();
            wvToDestroy.destroy();
        }

        tabList.remove(index);
        
        if (tabAdapter != null) {
            tabAdapter.notifyItemRemoved(index);
            tabAdapter.notifyItemRangeChanged(0, tabList.size());
        }

        if (index < currentTabPosition) {
            currentTabPosition--;
        } 
        else if (currentTabPosition >= tabList.size()) {
            currentTabPosition = tabList.size() - 1;
        }
        
        switchToTab(currentTabPosition);
    }
    
    private ArrayList<BrowserItem> buildTabItems() {
        ArrayList<BrowserItem> items = new ArrayList<>();
        for (TabState tabState : tabList) {    
            WebView webView = tabState.getWebView();
            if (webView == null) continue;
            String url = webView.getUrl();
            String title = webView.getTitle();
            if (title == null || title.isEmpty()) {
                title = (url == null || url.isEmpty() || url.equals("about:blank")) ? "New Tab" : url;
            }
            items.add(new BrowserItem(title, url));
        }
        return items;
    }

    private void showTabSwitcher() {
        android.webkit.WebView currentWv = getCurrentWebView();
        if (currentWv != null) {
            currentWv.pauseTimers(); 
        }
        
        if (currentTabPosition >= 0 && currentTabPosition < tabList.size()) {
            TabState currentTab = tabList.get(currentTabPosition);
            android.webkit.WebView tabWv = currentTab.getWebView();
            
            if (tabWv != null) {
                currentTab.setTitle(tabWv.getTitle() != null ? tabWv.getTitle() : "New Tab");
                currentTab.setUrl(tabWv.getUrl() != null ? tabWv.getUrl() : "");
                
                captureWebViewSnapshotAsync(tabWv, bitmap -> {
                    currentTab.setThumbnail(bitmap);
                    if (tabAdapter != null) {
                        tabAdapter.notifyItemChanged(currentTabPosition);
                    }
                });
            }
        }

        if (tabSwitcherOverlay == null) {
            android.view.LayoutInflater inflater = android.view.LayoutInflater.from(this);
            android.view.ViewGroup root = findViewById(android.R.id.content);
            tabSwitcherOverlay = inflater.inflate(R.layout.layout_tab_switcher, root, false);
            root.addView(tabSwitcherOverlay);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT_WATCH) {
                tabSwitcherOverlay.setOnApplyWindowInsetsListener((v, insets) -> {
                    android.view.View titleBar = v.findViewById(R.id.tabTitleBar);
                    if (titleBar != null) {
                        int statusBarHeight = insets.getSystemWindowInsetTop();
                        android.view.ViewGroup.LayoutParams params = titleBar.getLayoutParams();
                        params.height = (int) (56 * getResources().getDisplayMetrics().density) + statusBarHeight;
                        titleBar.setLayoutParams(params);
                        titleBar.setPadding(
                            titleBar.getPaddingLeft(), 
                            statusBarHeight, 
                            titleBar.getPaddingRight(), 
                            titleBar.getPaddingBottom()
                        );
                    }
                    return insets;
                });
                tabSwitcherOverlay.requestApplyInsets();
            }

            tabSwitcherOverlay.findViewById(R.id.btnNewTab).setOnClickListener(v -> {
                createNewTab();
                showHome();
                hideTabSwitcher();
            });

            tabSwitcherOverlay.findViewById(R.id.btnBackToBrowser).setOnClickListener(v -> hideTabSwitcher());
        }

        tabAdapter = new TabAdapter(tabList, new TabAdapter.OnTabActionListener() {
            @Override
            public void onTabSelected(int position) {
                switchToTab(position);
                hideTabSwitcher();
            }

            @Override
            public void onTabClosed(int position) {
                closeTab(position);
                
                if (tabSwitcherOverlay != null) {
                    androidx.viewpager2.widget.ViewPager2 tabsViewPager = tabSwitcherOverlay.findViewById(R.id.tabsViewPager);
                    if (tabsViewPager != null) {
                        tabsViewPager.post(() -> tabsViewPager.requestTransform());
                    }
                }
                
                if (tabList.isEmpty()) {
                    createNewTab();
                    showHome();
                    hideTabSwitcher();
                }
            }
        });

        androidx.viewpager2.widget.ViewPager2 tabsViewPager = tabSwitcherOverlay.findViewById(R.id.tabsViewPager);
        tabsViewPager.setAdapter(tabAdapter);
        
        tabsViewPager.setOffscreenPageLimit(3);
        androidx.viewpager2.widget.CompositePageTransformer transformer = new androidx.viewpager2.widget.CompositePageTransformer();
        transformer.addTransformer(new androidx.viewpager2.widget.MarginPageTransformer(24));
        transformer.addTransformer((page, position) -> {
            float r = 1 - Math.abs(position);
            page.setScaleY(0.85f + r * 0.15f);
            page.setAlpha(0.5f + r * 0.5f);
        });
        tabsViewPager.setPageTransformer(transformer);

        tabsViewPager.setCurrentItem(currentTabPosition, false);

        if (webViewContainer != null) webViewContainer.setVisibility(android.view.View.GONE);
        tabSwitcherOverlay.setVisibility(android.view.View.VISIBLE);
    }

    private void showHistoryDialog() {
        if (dbHelper == null) return;
        
        java.util.List<String[]> historyData = dbHelper.getAllHistory();
        
        if (historyData.isEmpty()) {
            android.widget.Toast.makeText(this, "History is empty", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        java.util.ArrayList<BrowserItem> items = new java.util.ArrayList<>();
        for (String[] entry : historyData) {
            String url = entry[0];
            String title = entry[1];
            items.add(new BrowserItem(title != null && !title.isEmpty() ? title : url, url));
        }

        BrowserItemAdapter adapter = new BrowserItemAdapter(this, items);
        android.widget.ListView listView = new android.widget.ListView(this);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, which, id) -> openUrl(items.get(which).url));
        listView.setOnItemLongClickListener((parent, view, which, id) -> {
            String[] options = {"Open in New Tab", "Add Bookmark"};
            new android.app.AlertDialog.Builder(this).setItems(options, (dialog, item) -> {
                if (item == 0) {
                    createNewTab();
                    openUrl(items.get(which).url);
                } else if (item == 1) {
                    String url = items.get(which).url;
                    dbHelper.addBookmark(url, items.get(which).title);
                    android.widget.Toast.makeText(MainActivity.this, "Bookmark added", android.widget.Toast.LENGTH_SHORT).show();
                }
            }).show();
            return true;
        });

        new android.app.AlertDialog.Builder(this).setTitle("Global History").setView(listView).show();
    }
  
    private void openUrl(String url) {
        android.webkit.WebView wv = getCurrentWebView();
        if (wv != null && url != null) {
            String query = url.trim();
            if (query.isEmpty()) return;

            if (query.startsWith("about:") || query.startsWith("file:") || query.startsWith("javascript:") || query.startsWith("data:")) {
                wv.loadUrl(query);
                return;
            }

            if (android.util.Patterns.WEB_URL.matcher(query).matches() || (query.contains(".") && !query.contains(" "))) {
                if (!query.startsWith("http://") && !query.startsWith("https://")) {
                    query = "https://" + query;
                }
                wv.loadUrl(query);
            } else {
                String searchUrl = "https://www.google.com/search?q=" + android.net.Uri.encode(query);
                wv.loadUrl(searchUrl);
            }
        }
    }

    private String getAppVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    public void saveHistory() {
        
    }
    
    static class ContentFilterEngine {
        
        public final java.util.Set<String> blockDomains = new java.util.concurrent.ConcurrentHashMap<>().newKeySet();
        public final java.util.Set<String> whitelistDomains = new java.util.concurrent.ConcurrentHashMap<>().newKeySet();

        public final java.util.List<String> blockPatterns = new java.util.concurrent.CopyOnWriteArrayList<>();
        public final java.util.List<String> whitelistPatterns = new java.util.concurrent.CopyOnWriteArrayList<>();

        public final java.util.Set<String> globalCosmeticSelectors = new java.util.concurrent.ConcurrentHashMap<>().newKeySet();
        public final java.util.Map<String, java.util.Set<String>> siteCosmeticSelectors = new java.util.concurrent.ConcurrentHashMap<>();

        public void clear() {
            blockDomains.clear();
            whitelistDomains.clear();
            blockPatterns.clear();
            whitelistPatterns.clear();
            globalCosmeticSelectors.clear();
            siteCosmeticSelectors.clear();
        }

        public void addRule(String rule) {
            if (rule == null || rule.isEmpty()) return;
            
            if (rule.contains("##")) {
                int index = rule.indexOf("##");
                String domainPart = rule.substring(0, index).trim();
                String selector = rule.substring(index + 2).trim();
                
                if (selector.isEmpty() || selector.contains("{") || selector.contains("(")) return;

                if (domainPart.isEmpty()) {
                    
                    globalCosmeticSelectors.add(selector);
                } else {
                    
                    String[] domains = domainPart.split(",");
                    for (String domain : domains) {
                        domain = domain.trim().toLowerCase();
                        if (!domain.isEmpty() && !domain.startsWith("~")) {
                            siteCosmeticSelectors.computeIfAbsent(domain, k -> new java.util.concurrent.ConcurrentHashMap<>().newKeySet()).add(selector);
                        }
                    }
                }
                return;
            }

            boolean isWhitelist = rule.startsWith("@@");
            String pattern = isWhitelist ? rule.substring(2) : rule;

            int optionIdx = pattern.indexOf('$');
            if (optionIdx != -1) {
                pattern = pattern.substring(0, optionIdx);
            }

            pattern = pattern.replace("||", "").replace("^", "").trim().toLowerCase();
            if (pattern.isEmpty()) return;

            if (isWhitelist) {
                if (!pattern.contains("*") && !pattern.contains("/") && !pattern.contains("?")) {
                    whitelistDomains.add(pattern);
                } else {
                    whitelistPatterns.add(pattern);
                }
            } else {
                if (!pattern.contains("*") && !pattern.contains("/") && !pattern.contains("?")) {
                    blockDomains.add(pattern);
                } else {
                    blockPatterns.add(pattern);
                }
            }
        }

        public String compileCosmeticJavascript() {
            return "javascript:(function() {" +
                    "var id = 'spoon-cosmetic-sheets';" +
                    "var style = document.getElementById(id);" +
                    "if (!style) {" +
                    "  style = document.createElement('style');" +
                    "  style.id = id;" +
                    "  document.head.appendChild(style);" +
                    "}" +
                    "})()";
        }

        public java.util.List<String> getCosmeticStyleBatches(String urlString) {
            java.util.List<String> batches = new java.util.ArrayList<>();
            java.util.Set<String> activeSelectors = new java.util.HashSet<>(globalCosmeticSelectors);

            String host = null;
            if (urlString != null) {
                try {
                    android.net.Uri uri = android.net.Uri.parse(urlString.toLowerCase());
                    host = uri.getHost();
                } catch (Exception ignored) {}
            }

            if (host != null) {
                String tempHost = host;
                while (tempHost != null && tempHost.contains(".")) {
                    java.util.Set<String> siteRules = siteCosmeticSelectors.get(tempHost);
                    if (siteRules != null) {
                        activeSelectors.addAll(siteRules);
                    }
                    int nextDot = tempHost.indexOf('.');
                    if (nextDot != -1 && nextDot < tempHost.length() - 1) {
                        tempHost = tempHost.substring(nextDot + 1);
                    } else {
                        break;
                    }
                }
            }

            if (activeSelectors.isEmpty()) {
                batches.add(".ad-box, .ad-banner, .adsbygoogle, [id^=\"google_ads_\"], .ad-container, #carbonads { display: none !important; height: 0px !important; }");
                return batches;
            }

            StringBuilder currentBatch = new StringBuilder();
            int ruleCount = 0;

            for (String selector : activeSelectors) {
                String cleanSelector = selector.replace("'", "\\'").replace("\n", "").trim();
                if (cleanSelector.isEmpty()) continue;

                if (ruleCount > 0) currentBatch.append(", ");
                currentBatch.append(cleanSelector);
                ruleCount++;

                if (ruleCount >= 250) {
                    currentBatch.append(" { display: none !important; height: 0px !important; margin: 0px !important; padding: 0px !important; }");
                    batches.add(currentBatch.toString());
                    currentBatch = new StringBuilder();
                    ruleCount = 0;
                }
            }

            if (ruleCount > 0) {
                currentBatch.append(" { display: none !important; height: 0px !important; margin: 0px !important; padding: 0px !important; }");
                batches.add(currentBatch.toString());
            }

            return batches;
        }

        public boolean shouldBlock(String urlString) {
            if (urlString == null) return false;
            String lowerUrl = urlString.toLowerCase();

            String host = null;
            try {
                android.net.Uri uri = android.net.Uri.parse(lowerUrl);
                host = uri.getHost();
            } catch (Exception ignored) {}

            if (host != null && whitelistDomains.contains(host)) return false;

            for (String pattern : whitelistPatterns) {
                if (matchPattern(lowerUrl, pattern)) return false;
            }

            if (host != null) {
                String tempHost = host;
                while (tempHost != null && tempHost.contains(".")) {
                    if (blockDomains.contains(tempHost)) return true;
                    int nextDot = tempHost.indexOf('.');
                    if (nextDot != -1 && nextDot < tempHost.length() - 1) {
                        tempHost = tempHost.substring(nextDot + 1);
                    } else {
                        break;
                    }
                }
            }

            for (String pattern : blockPatterns) {
                if (matchPattern(lowerUrl, pattern)) return true;
            }

            return false;
        }

        private boolean matchPattern(String url, String pattern) {
            if (pattern.contains("*")) {
                String[] parts = pattern.split("\\*");
                int lastIdx = 0;
                for (String part : parts) {
                    if (part.isEmpty()) continue;
                    int idx = url.indexOf(part, lastIdx);
                    if (idx == -1) return false;
                    lastIdx = idx + part.length();
                }
                return true;
            }
            return url.contains(pattern);
        }
    }

    final ContentFilterEngine filterEngine = new ContentFilterEngine();
    private final java.util.concurrent.atomic.AtomicBoolean isFilterUpdating = new java.util.concurrent.atomic.AtomicBoolean(false);

    public void recordPageVisit(String url, String title) {
        
        runOnUiThread(() -> {
            if (addressBarAdapter != null) {
                addressBarAdapter.notifyDataSetChanged();
            }
        });
    }
    
    private void saveFilterLists() {
        prefs.edit().putString(KEY_FILTER_LISTS, TextUtils.join("\n", filterLists)).apply();

        AdBlockEngine.checkAndRefreshFilters(this, backgroundExecutor, filterLists, true);
    }

    private void saveOpenTabs() {
        ArrayList<String> safeUrls = new ArrayList<>();
        for (TabState tabState : tabList) {    
            WebView webView = tabState.getWebView();
            String url = webView.getUrl();
            if (url != null && !url.isEmpty() && !url.equals("about:blank")) {
                safeUrls.add(url);
            }
        }

        backgroundExecutor.execute(() -> {
            prefs.edit().putString(KEY_OPEN_TABS, TextUtils.join("\n", safeUrls)).apply();
        });
    }

    private void saveCurrentTab() {
        prefs.edit().putInt(KEY_CURRENT_TAB, currentTabPosition).apply();
    }

    private void showFilterListsDialog() {
        EditText input = new EditText(this);
        new AlertDialog.Builder(this)
                .setTitle("Subscribe Filter List")
                .setMessage("Subscribed: " + filterLists.size() + "\n\nEnter filter list URL")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty() && !filterLists.contains(url)) {
                        filterLists.add(url);
                        AdBlockEngine.checkAndRefreshFilters(this, backgroundExecutor, filterLists, true);
                        saveFilterLists();
                    }
                })
                .setNeutralButton("More", (d, w) -> showFilterListOptions())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showFilterListOptions() {
        String[] options = {"View Subscriptions", "Add Custom Filter List", "Update All Subscriptions"};
        
        new AlertDialog.Builder(this)
                .setTitle("Filter Lists")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showSubscribedFilterLists();
                        
                    } else if (which == 1) {
                        
                        android.widget.EditText input = new android.widget.EditText(this);
                        input.setHint("https://...");
                        
                        new AlertDialog.Builder(this)
                                .setTitle("Add Filter List")
                                .setView(input)
                                .setPositiveButton("Add", (d, w) -> {
                                    String url = input.getText().toString().trim();
                                    if (!url.isEmpty() && !filterLists.contains(url)) {
                                        filterLists.add(url);
                                        saveFilterLists();
                                        Toast.makeText(this, "Downloading list...", Toast.LENGTH_SHORT).show();
                                        
                                        AdBlockEngine.checkAndRefreshFilters(MainActivity.this, backgroundExecutor, filterLists, true);
                                    }
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                                
                    } else if (which == 2) {
                        
                        if (filterLists.isEmpty()) {
                            Toast.makeText(this, "No lists to update", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Updating filter lists in background...", Toast.LENGTH_SHORT).show();
                            
                            AdBlockEngine.checkAndRefreshFilters(MainActivity.this, backgroundExecutor, filterLists, true);
                        }
                    }
                })
                .show();
    }

    private void showSubscribedFilterLists() {
        if (filterLists.isEmpty()) {
            Toast.makeText(this, "No filter lists subscribed", Toast.LENGTH_SHORT).show();
            return;
        }

        ListView listView = new ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, filterLists);
        listView.setAdapter(adapter);

        listView.setOnItemLongClickListener((parent, view, which, id) -> {
            String url = filterLists.get(which);
            new AlertDialog.Builder(this)
                    .setTitle("Remove Filter List")
                    .setMessage(url)
                    .setPositiveButton("Remove", (d, w) -> {
                        filterLists.remove(url);
                        adapter.notifyDataSetChanged();
                        saveFilterLists();
                        AdBlockEngine.removeFilterList(MainActivity.this, url, filterLists, backgroundExecutor);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });

        new AlertDialog.Builder(this).setTitle("Subscribed Filter Lists").setView(listView).setPositiveButton("OK", null).show();
    }

    private void showAbout() {
        com.google.android.material.bottomsheet.BottomSheetDialog bottomSheet = 
            new com.google.android.material.bottomsheet.BottomSheetDialog(this);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(64, 64, 64, 80);
        root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(android.graphics.Color.parseColor("#1C1C1E")); 

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher); 
        android.widget.LinearLayout.LayoutParams iconParams = new android.widget.LinearLayout.LayoutParams(180, 180);
        iconParams.setMargins(0, 32, 0, 16); 
        root.addView(icon, iconParams);

        android.widget.TextView version = new android.widget.TextView(this);
        version.setText("Version " + getAppVersion());
        version.setTextSize(14);
        version.setTextColor(android.graphics.Color.parseColor("#8E8E93"));
        version.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        android.widget.LinearLayout.LayoutParams versionParams = new android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        versionParams.setMargins(0, 0, 0, 48);
        root.addView(version, versionParams);

        android.widget.LinearLayout statsContainer = new android.widget.LinearLayout(this);
        statsContainer.setOrientation(android.widget.LinearLayout.VERTICAL);
        statsContainer.setBackgroundColor(android.graphics.Color.parseColor("#2C2C2E")); 
        
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        shape.setCornerRadii(new float[] { 32, 32, 32, 32, 32, 32, 32, 32 });
        shape.setColor(android.graphics.Color.parseColor("#2C2C2E"));
        statsContainer.setBackground(shape);

        android.widget.LinearLayout.LayoutParams statsParams = new android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        statsParams.setMargins(32, 0, 32, 48);

        String webViewVer = "Unknown";
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.content.pm.PackageInfo pi = android.webkit.WebView.getCurrentWebViewPackage();
            if (pi != null) webViewVer = pi.versionName;
        }

        statsContainer.addView(createStatRow("WebView Engine", webViewVer, true));
        statsContainer.addView(createStatRow("Tabs Open", String.valueOf(tabList.size()), true));
        statsContainer.addView(createStatRow("Bookmarks Saved", String.valueOf(dbHelper != null ? dbHelper.getBookmarkCount() : 0), true));
        statsContainer.addView(createStatRow("History Items", String.valueOf(dbHelper != null ? dbHelper.getHistoryCount() : 0), true));
        
        android.view.View adblockRow = createStatRow("AdBlock Rules", "Loading...", false);
        statsContainer.addView(adblockRow);

        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable updater = new Runnable() {
            @Override
            public void run() {
                if (bottomSheet.isShowing()) {
                    android.widget.TextView txt = adblockRow.findViewWithTag("AdBlock Rules");
                    if (txt != null) {
                        txt.setText(String.valueOf(AdBlockEngine.getBlocklistSize()));
                    }
                    handler.postDelayed(this, 1000); 
                }
            }
        };
        handler.postDelayed(updater, 1000);

        root.addView(statsContainer, statsParams);

        android.widget.TextView signature = new android.widget.TextView(this);
        signature.setText("Built one green commit at a time.\nDesigned to evolve dynamically with Android WebView.\n\n- with love, Plaban.");
        signature.setTextSize(13);
        signature.setGravity(android.view.Gravity.CENTER);
        signature.setTextColor(android.graphics.Color.parseColor("#636366")); 
        signature.setLineSpacing(0, 1.2f);
        root.addView(signature);

        bottomSheet.setContentView(root);
        
        android.view.View bottomSheetInternal = bottomSheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheetInternal != null) {
            bottomSheetInternal.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }

        bottomSheet.show();
    }

    private android.view.View createStatRow(String labelText, String valueText, boolean drawDivider) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setPadding(40, 32, 40, 32);

        android.widget.TextView label = new android.widget.TextView(this);
        label.setText(labelText);
        label.setTextColor(android.graphics.Color.WHITE);
        label.setTextSize(15);
        android.widget.LinearLayout.LayoutParams labelParams = new android.widget.LinearLayout.LayoutParams(
            0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        row.addView(label, labelParams);

        android.widget.TextView value = new android.widget.TextView(this);
        value.setText(valueText);
        value.setTextColor(android.graphics.Color.parseColor("#8E8E93"));
        value.setTextSize(15);
        value.setTag(labelText);
        value.setGravity(android.view.Gravity.END);
        row.addView(value);

        if (drawDivider) {
            android.widget.LinearLayout wrapper = new android.widget.LinearLayout(this);
            wrapper.setOrientation(android.widget.LinearLayout.VERTICAL);
            wrapper.addView(row);
            
            android.view.View divider = new android.view.View(this);
            divider.setBackgroundColor(android.graphics.Color.parseColor("#3A3A3C"));
            android.widget.LinearLayout.LayoutParams divParams = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 2);
            divParams.setMargins(40, 0, 0, 0); 
            wrapper.addView(divider, divParams);
            
            return wrapper;
        }

        return row;
    }

    private android.view.View createStatRow(String labelText, String valueText, boolean drawDivider) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setPadding(40, 32, 40, 32);

        android.widget.TextView label = new android.widget.TextView(this);
        label.setText(labelText);
        label.setTextColor(android.graphics.Color.WHITE);
        label.setTextSize(15);
        android.widget.LinearLayout.LayoutParams labelParams = new android.widget.LinearLayout.LayoutParams(
            0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        row.addView(label, labelParams);

        android.widget.TextView value = new android.widget.TextView(this);
        value.setText(valueText);
        value.setTextColor(android.graphics.Color.parseColor("#8E8E93"));
        value.setTextSize(15);
        value.setTag(labelText);
        value.setGravity(android.view.Gravity.END);
        row.addView(value);

        if (drawDivider) {
            android.widget.LinearLayout wrapper = new android.widget.LinearLayout(this);
            wrapper.setOrientation(android.widget.LinearLayout.VERTICAL);
            wrapper.addView(row);
            
            android.view.View divider = new android.view.View(this);
            divider.setBackgroundColor(android.graphics.Color.parseColor("#3A3A3C"));
            android.widget.LinearLayout.LayoutParams divParams = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 2);
            divParams.setMargins(40, 0, 0, 0);
            wrapper.addView(divider, divParams);
            
            return wrapper;
        }

        return row;
    }

    private void showBookmarks() {
        if (dbHelper == null) return;
        
        java.util.List<String> bookmarkUrls = dbHelper.getAllBookmarksUrls();
        
        if (bookmarkUrls.isEmpty()) {
            android.widget.Toast.makeText(this, "No bookmarks saved", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        java.util.ArrayList<BrowserItem> items = new java.util.ArrayList<>();
        for (String url : bookmarkUrls) {
            items.add(new BrowserItem(url, url)); 
        }

        BrowserItemAdapter adapter = new BrowserItemAdapter(this, items);
        android.widget.ListView listView = new android.widget.ListView(this);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, which, id) -> openUrl(items.get(which).url));
        listView.setOnItemLongClickListener((parent, view, which, id) -> {
            String[] options = {"Open", "Open in New Tab", "Remove Bookmark"};
            new android.app.AlertDialog.Builder(this).setItems(options, (dialog, item) -> {
                if (item == 0) {
                    openUrl(items.get(which).url);
                } else if (item == 1) {
                    createNewTab();
                    openUrl(items.get(which).url);
                } else if (item == 2) {
                    dbHelper.removeBookmark(items.get(which).url);
                    android.widget.Toast.makeText(MainActivity.this, "Bookmark removed", android.widget.Toast.LENGTH_SHORT).show();
                }
            }).show();
            return true;
        });

        new android.app.AlertDialog.Builder(this).setTitle("Bookmarks").setView(listView).show();
    }

    private void showHome() {
        if (cachedHomeHtml == null) {
            float widthDp = getResources().getConfiguration().screenWidthDp;

            StringBuilder sb = new StringBuilder();
            sb.append("<html>")
              .append("<body style='margin:0;background:#000;color:white;font-family:sans-serif;text-align:center;'>")
              .append("<div style='padding-top:20%;'>")
              .append("<h1 style='font-size:48px;margin-bottom:40px;'>Spoon Browser</h1>");

            if (widthDp >= 600) {
                sb.append("<input id='q' type='text' placeholder='Search privately...' style='width:72%;padding:20px;border:none;border-radius:18px;background:#1f1f1f;color:white;font-size:18px;outline:none;'/>");
            }

            sb.append("</div>")
              .append("<script>")
              .append("function goSearch(){")
              .append("  var inputEl = document.getElementById('q');")
              .append("  var q = inputEl.value;")
              .append("  if(!q) return;")
              .append("  inputEl.blur();")
              .append("  inputEl.value = '';")
              .append("  inputEl.placeholder = 'Searching...';")
              .append("  window.location.href='spoonsearch://' + encodeURIComponent(q);")
              .append("}")
              .append("var inputEl = document.getElementById('q');")
              .append("if(inputEl) {")
              .append("  inputEl.addEventListener('keydown',function(e){")
              .append("    if(e.key==='Enter'){goSearch();}")
              .append("  });")
              .append("}")
              .append("</script>")
              .append("</body></html>");

            cachedHomeHtml = sb.toString();
        }

        WebView wv = getCurrentWebView();
        if (wv != null) {
            wv.loadDataWithBaseURL("about:blank", cachedHomeHtml, "text/html", "UTF-8", null);
        }
    }
    
    public WebView getCurrentWebView() {
        if (currentTabPosition >= 0 && currentTabPosition < tabList.size()) {
            return tabList.get(currentTabPosition).getWebView();
        }
        return null;
    }

    public void updateTabBadgeCount() {
        if (tabBadgeButton != null && tabList != null) {
           
            runOnUiThread(() -> {
                tabBadgeButton.setText(String.valueOf(tabList.size()));
            });
        }
    }

    private void updateAddressBarSuggestions(String query) {
        if (query == null || query.trim().isEmpty()) {
            addressBarAdapter.clear();
            addressBarAdapter.notifyDataSetChanged();
            try {
                if (addressBar != null && addressBar.isAttachedToWindow()) {
                    addressBar.dismissDropDown();
                }
            } catch (Exception ignored) {}
            return;
        }

        if (dbHelper == null) return;

        executeSafely(() -> {
            java.util.List<String[]> rawResults = dbHelper.getMatchingHistory(query); 
            
            java.util.List<Suggestion> cleanSuggestions = new java.util.ArrayList<>();
            java.util.Set<String> addedUrls = new java.util.HashSet<>();
            java.util.Set<String> addedHosts = new java.util.HashSet<>();

            for (String[] row : rawResults) {
                String rawUrl = row[0];
                try {
                    android.net.Uri uri = android.net.Uri.parse(rawUrl);
                    String host = uri.getHost();
                    if (host != null) {
                        String cleanHost = host.replaceFirst("^www\\.", "");
                        if (cleanHost.toLowerCase().contains(query.toLowerCase()) && !addedHosts.contains(cleanHost)) {
                            cleanSuggestions.add(new Suggestion(cleanHost, cleanHost));
                            addedHosts.add(cleanHost);
                            addedUrls.add(cleanHost);
                        }
                    }
                } catch (Exception ignored) {}
            }

            int deepLinkLimit = 3; 
            int currentDeepLinks = 0;
            
            for (String[] row : rawResults) {
                if (currentDeepLinks >= deepLinkLimit) break;
                
                String rawUrl = row[0];
                String title = (row[1] != null && !row[1].isEmpty()) ? row[1] : rawUrl;
                String displayUrl = rawUrl.replaceFirst("^https?://(www\\.)?", "");
                
                if (!addedUrls.contains(displayUrl) && !addedHosts.contains(displayUrl)) {
                    cleanSuggestions.add(new Suggestion(title, displayUrl));
                    addedUrls.add(displayUrl);
                    currentDeepLinks++;
                }
            }

            runOnUiThread(() -> {
                addressBarAdapter.clear();
                addressBarAdapter.addAll(cleanSuggestions);
                addressBarAdapter.notifyDataSetChanged();

                try {
                    if (addressBar != null && addressBar.isAttachedToWindow()) {
                        if (addressBarAdapter.getCount() > 0) {
                            addressBar.showDropDown();
                        } else {
                            addressBar.dismissDropDown();
                        }
                    } else if (addressBar != null) {
                        addressBar.post(() -> {
                            try {
                                if (addressBar.isAttachedToWindow()) {
                                    if (addressBarAdapter.getCount() > 0) addressBar.showDropDown();
                                    else addressBar.dismissDropDown();
                                }
                            } catch (Exception ignored) {}
                        });
                    }
                } catch (Exception ignored) {}
            });
        });
    }

    private void showVaultForCurrentSite() {
        
        android.webkit.WebView webView = getCurrentWebView();
        
        if (webView == null || webView.getUrl() == null) {
            android.widget.Toast.makeText(this, "No valid page loaded", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        String currentUrl = webView.getUrl();
        String host = android.net.Uri.parse(currentUrl).getHost();
        if (host == null) return;
                
        String cleanHost = host.toLowerCase().trim().replaceFirst("^www\\.", "");
        
        String accountsJson = secureCredentialManager.getAllAccountsForHost(cleanHost);
        
        if (accountsJson == null || accountsJson.equals("[]")) {
            android.widget.Toast.makeText(this, "No saved passwords for " + cleanHost, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            org.json.JSONArray accountsArray = new org.json.JSONArray(accountsJson);
                        
            android.app.Dialog dialog = new android.app.Dialog(this);
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            
            android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.setPadding(dp(16), dp(16), dp(16), dp(16));
            
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(android.graphics.Color.parseColor("#1F1F1F"));
            bg.setCornerRadius(dp(12));
            layout.setBackground(bg);

            android.widget.TextView title = new android.widget.TextView(this);
            title.setText("Vault: " + cleanHost);
            title.setTextColor(android.graphics.Color.WHITE);
            title.setTextSize(16);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setPadding(0, 0, 0, dp(12));
            layout.addView(title);

            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);

            for (int i = 0; i < accountsArray.length(); i++) {
                org.json.JSONObject acc = accountsArray.getJSONObject(i);
                String user = acc.optString("username", "");
                String pass = acc.optString("password", "");

                android.widget.Button userBtn = new android.widget.Button(this);
                userBtn.setText("Copy ID: " + user);
                userBtn.setAllCaps(false);
                userBtn.setTextColor(android.graphics.Color.WHITE);
                userBtn.setBackgroundColor(android.graphics.Color.parseColor("#333333"));
                userBtn.setOnClickListener(v -> {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("username", user));
                    android.widget.Toast.makeText(this, "ID Copied", android.widget.Toast.LENGTH_SHORT).show();
                });
                layout.addView(userBtn);

                android.widget.Button passBtn = new android.widget.Button(this);
                passBtn.setText("Copy Password");
                passBtn.setAllCaps(false);
                passBtn.setTextColor(android.graphics.Color.WHITE);
                passBtn.setBackgroundColor(android.graphics.Color.parseColor("#333333"));
                android.widget.LinearLayout.LayoutParams btnParams = new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                btnParams.setMargins(0, dp(6), 0, dp(12));
                passBtn.setLayoutParams(btnParams);
                
                passBtn.setOnClickListener(v -> {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("password", pass));
                    android.widget.Toast.makeText(this, "Password Copied", android.widget.Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });
                layout.addView(passBtn);
            }

            dialog.setContentView(layout);

            android.view.Window window = dialog.getWindow();
            if (window != null) {
                window.setGravity(android.view.Gravity.TOP | android.view.Gravity.END);
                android.view.WindowManager.LayoutParams params = window.getAttributes();
                params.x = dp(10);
                params.y = dp(70);
                window.setAttributes(params);
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
            
            dialog.show();

        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Vault error", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void navigate() {
        String input = addressBar.getText().toString().trim();
        if (input.isEmpty()) return;

        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(addressBar.getWindowToken(), 0);
        addressBar.clearFocus();

        backgroundExecutor.execute(() -> {
            String lowerInput = input.toLowerCase();
            if ((lowerInput.startsWith("javascript:") ||
                 lowerInput.startsWith("file:") ||
                 lowerInput.startsWith("content:") ||
                 lowerInput.startsWith("intent:")) &&
                !lowerInput.equals("file:///android_asset/vault.html")) {
                
                runOnUiThread(() -> Toast.makeText(this, "Blocked unsafe URL", Toast.LENGTH_SHORT).show());
                return;
            }

            String url;
            if (lowerInput.equals("file:///android_asset/vault.html")) {
                url = "file:///android_asset/vault.html";
            } else if (input.contains(".") && !input.contains(" ")) {
                if (input.startsWith("http://") || input.startsWith("https://")) {
                    url = input;
                } else {
                    boolean isIpAddress = input.matches("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?(?:/.*)?$");
                    
                    boolean isLocalRouter = lowerInput.endsWith("tplinkwifi.net") || 
                                            lowerInput.endsWith("routerlogin.net") || 
                                            lowerInput.endsWith("tendawifi.com") || 
                                            lowerInput.endsWith("asusrouter.com") ||
                                            lowerInput.endsWith("mwlogin.net") ||
                                            lowerInput.endsWith("pi.hole") ||
                                            lowerInput.endsWith(".local");

                    url = (isIpAddress || isLocalRouter) ? "http://" + input : "https://" + input;
                }
            } else {
                url = "https://search.brave.com/search?q=" + android.net.Uri.encode(input);
            }
            
            runOnUiThread(() -> openUrl(url));
        });
    }

    private void captureWebViewSnapshotAsync(android.webkit.WebView webView, android.webkit.ValueCallback<android.graphics.Bitmap> callback) {
        if (webView == null || webView.getWidth() == 0 || webView.getHeight() == 0) {
            callback.onReceiveValue(null);
            return;
        }

        try {
            int targetWidth = (int) (webView.getWidth() * 0.3f);
            int targetHeight = (int) (webView.getHeight() * 0.3f);
            
            if (targetWidth <= 0 || targetHeight <= 0) {
                callback.onReceiveValue(null);
                return;
            }

            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(targetWidth, targetHeight, android.graphics.Bitmap.Config.RGB_565);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                int[] location = new int[2];
                webView.getLocationInWindow(location);
                android.graphics.Rect rect = new android.graphics.Rect(
                        location[0], 
                        location[1],
                        location[0] + webView.getWidth(), 
                        location[1] + webView.getHeight()
                );

                android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                android.view.PixelCopy.request(getWindow(), rect, bitmap, copyResult -> {
                    if (copyResult == android.view.PixelCopy.SUCCESS) {
                        callback.onReceiveValue(bitmap);
                    } else {
                        fallbackCanvasCapture(webView, targetWidth, targetHeight, callback);
                    }
                }, handler);
            } else {
                fallbackCanvasCapture(webView, targetWidth, targetHeight, callback);
            }
        } catch (Exception e) {
            callback.onReceiveValue(null);
        }
    }

    private void fallbackCanvasCapture(android.webkit.WebView webView, int width, int height, android.webkit.ValueCallback<android.graphics.Bitmap> callback) {
        try {
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            canvas.scale(0.3f, 0.3f);
            webView.draw(canvas);
            callback.onReceiveValue(bitmap);
        } catch (OutOfMemoryError | Exception e) {
            System.gc();
            callback.onReceiveValue(null);
        }
    }
    
    private void hideTabSwitcher() {
        if (tabSwitcherOverlay != null) {
            tabSwitcherOverlay.setVisibility(android.view.View.GONE);
        }
        if (webViewContainer != null) {
            webViewContainer.setVisibility(android.view.View.VISIBLE);
        }
        switchToTab(currentTabPosition);
        WebView currentWv = getCurrentWebView();
        if (currentWv != null) {
            currentWv.resumeTimers(); 
        }
    }
    
    public void triggerExternalDownload(String url, String mimeType) {
        try {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            if (mimeType != null && !mimeType.isEmpty()) {
                intent.setDataAndType(android.net.Uri.parse(url), mimeType);
            } else {
                intent.setData(android.net.Uri.parse(url));
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);

            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(android.content.Intent.createChooser(intent, "Download File via..."));
            } else {
                android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, url);
                startActivity(android.content.Intent.createChooser(shareIntent, "Send link to external downloader..."));
            }
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Could not launch external app", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
    
    public void triggerManualDownload(String url, String mimeType) {
        String targetFileName = android.webkit.URLUtil.guessFileName(url, null, mimeType);
        if (targetFileName.endsWith(".bin") || targetFileName.equals("downloadfile")) {
            String extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            if (extension != null) {
                targetFileName = targetFileName.replace(".bin", "").replace("downloadfile", "download") + "." + extension;
            }
        }
        
        final String finalFileName = targetFileName;
        
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Download Media")
            .setMessage("Do you want to download " + finalFileName + "?")
            .setPositiveButton("Download", (dialog, item) -> {
                try {
                    android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(android.net.Uri.parse(url));
                    String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
                    if (cookies != null) request.addRequestHeader("Cookie", cookies);
                    request.setMimeType(mimeType);
                    request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, finalFileName);
                    request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setTitle(finalFileName);
                    
                    android.app.DownloadManager manager = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (manager != null) {
                        manager.enqueue(request);
                        android.widget.Toast.makeText(this, "Download started...", android.widget.Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    triggerExternalDownload(url, mimeType);
                }
            })
            .setNeutralButton("External Only", (dialog, item) -> triggerExternalDownload(url, mimeType))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void toggleDesktopMode() {        
        if (currentTabPosition < 0 || currentTabPosition >= tabList.size()) return;
        android.webkit.WebView activeWebView = tabList.get(currentTabPosition).getWebView();
        
        if (activeWebView == null || activeWebView.getUrl() == null) return;

        String host = android.net.Uri.parse(activeWebView.getUrl()).getHost();
        if (host == null) return;

        android.content.SharedPreferences syncPrefs = getSharedPreferences("browser_prefs", MODE_PRIVATE);
        
        java.util.Set<String> desktopSites = new java.util.HashSet<>(
            syncPrefs.getStringSet("desktop_sites", new java.util.HashSet<>())
        );

        if (desktopSites.contains(host)) {
            desktopSites.remove(host); 
            android.widget.Toast.makeText(this, "Mobile mode for " + host, android.widget.Toast.LENGTH_SHORT).show();
        } else {
            desktopSites.add(host);
            android.widget.Toast.makeText(this, "Desktop mode for " + host, android.widget.Toast.LENGTH_SHORT).show();
        }

        syncPrefs.edit().putStringSet("desktop_sites", desktopSites).apply();
        
        String currentUrl = activeWebView.getUrl();

        if (desktopSites.contains(host)) {
            activeWebView.getSettings().setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
            activeWebView.getSettings().setLoadWithOverviewMode(true);
            activeWebView.getSettings().setUseWideViewPort(true);
            
            if (currentUrl.contains("youtube.com") && !currentUrl.contains("app=desktop")) {
                activeWebView.loadUrl(currentUrl + (currentUrl.contains("?") ? "&" : "?") + "app=desktop");
            } else {
                activeWebView.reload();
            }
        } else {
            String defaultUA = android.webkit.WebSettings.getDefaultUserAgent(this);
            if (defaultUA != null) {
                defaultUA = defaultUA.replace("; wv", "").replaceFirst("Version/[0-9.]+\\s", "");
                activeWebView.getSettings().setUserAgentString(defaultUA);
            } else {
                activeWebView.getSettings().setUserAgentString("Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
            }
            activeWebView.getSettings().setLoadWithOverviewMode(false);
            activeWebView.getSettings().setUseWideViewPort(false);

            if (currentUrl.contains("youtube.com") && currentUrl.contains("app=desktop")) {
                activeWebView.loadUrl(currentUrl.replace("app=desktop", "app=m"));
            } else {
                activeWebView.reload();
            }
        }
    }
    
    private boolean isPhishingRisk(String host) {
        if (host == null) return false;
        String clean = host.toLowerCase().trim();
        if (clean.startsWith("www.")) clean = clean.substring(4);

        String[] protectedDomains = {"google.com", "paypal.com", "facebook.com", "amazon.com", "netflix.com", "github.com"};
        
        for (String target : protectedDomains) {
            if (clean.equals(target)) return false;

            int distance = getLevenshteinDistance(clean, target);
            if (distance > 0 && distance <= 2) {
                return true;
            }

            if (clean.contains(target.replace(".com", "")) && !clean.endsWith("." + target) && !clean.equals(target)) {
                return true; 
            }
        }
        return false;
    }
    
    private int getLevenshteinDistance(String s, String t) {
        if (s == null || t == null) return 0;
        int[] p = new int[s.length() + 1];
        int[] d = new int[s.length() + 1];
        for (int i = 0; i <= s.length(); i++) p[i] = i;
        for (int j = 1; j <= t.length(); j++) {
            d[0] = j;
            for (int i = 1; i <= s.length(); i++) {
                int match = (s.charAt(i - 1) == t.charAt(j - 1)) ? 0 : 1;
                d[i] = Math.min(Math.min(d[i - 1] + 1, p[i] + 1), p[i - 1] + match);
            }
            int[] placeholder = p; p = d; d = placeholder;
        }
        return p[s.length()];
    }

    public class PasswordAutosaveBridge {
        @android.webkit.JavascriptInterface
        public void saveCredentials(String username, String password) {
            if (username == null || username.isEmpty() || password == null || password.isEmpty()) return;
            
            runOnUiThread(() -> {
                if (secureCredentialManager != null) {
                    
                    String currentUrl = "";
                    if (currentTabPosition >= 0 && currentTabPosition < tabList.size()) {    
                        android.webkit.WebView activeWebView = tabList.get(currentTabPosition).getWebView();
                        if (activeWebView != null && activeWebView.getUrl() != null) {
                            currentUrl = activeWebView.getUrl();
                        }
                    }
                    
                    if (!currentUrl.isEmpty()) {
                        String host = android.net.Uri.parse(currentUrl).getHost();
                        
                        secureCredentialManager.saveCredentials(host, username, password);
                        android.widget.Toast.makeText(MainActivity.this, "Password Autosaved for: " + host, android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }

    public void requestWebPermissions(String[] permissions) {
        if (webPermissionLauncher != null) {
            webPermissionLauncher.launch(permissions);
        }
    }

    private void loadUrlOrSearch(String input) {
        String query = input.trim();
        if (query.isEmpty()) return;

        if (android.util.Patterns.WEB_URL.matcher(query).matches() || (query.contains(".") && !query.contains(" "))) {
            if (!query.startsWith("http://") && !query.startsWith("https://")) {
                query = "https://" + query;
            }
            getCurrentWebView().loadUrl(query);
        } else {
            String searchUrl = "https://www.google.com/search?q=" + android.net.Uri.encode(query);
            getCurrentWebView().loadUrl(searchUrl);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (mFilePathCallback == null) return;
            
            android.net.Uri[] results = null;
            if (resultCode == android.app.Activity.RESULT_OK) {
                if (data != null) {
                    String dataString = data.getDataString();
                    if (dataString != null) {
                        results = new android.net.Uri[]{android.net.Uri.parse(dataString)};
                    } else if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new android.net.Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
            }
            mFilePathCallback.onReceiveValue(results);
            mFilePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }
}

    class Suggestion {
        public String title;
        public String url;

        public Suggestion(String title, String url) {
            this.title = title;
            this.url = url;
        }

        @Override
        public String toString() {
            return url;
        }
    }

    class SuggestionAdapter extends android.widget.ArrayAdapter<Suggestion> {
        public SuggestionAdapter(android.content.Context context, java.util.List<Suggestion> items) {
            super(context, 0, items);
        }

        @Override
        public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
            if (convertView == null) {
                convertView = android.view.LayoutInflater.from(getContext()).inflate(R.layout.item_suggestion, parent, false);
            }

            Suggestion item = getItem(position);
            if (item != null) {
                android.widget.TextView titleView = convertView.findViewById(R.id.suggestion_title);
                android.widget.TextView urlView = convertView.findViewById(R.id.suggestion_url);

                titleView.setText(item.title);
                urlView.setText(item.url);
            }

            return convertView;
        }

        @Override
        public android.widget.Filter getFilter() {
            return new android.widget.Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    FilterResults results = new FilterResults();
                    results.count = getCount();
                    return results;
                }
                @Override
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    notifyDataSetChanged();
                }
            };
        }
    }
