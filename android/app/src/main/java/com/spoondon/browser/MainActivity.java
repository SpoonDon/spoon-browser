package com.spoondon.browser;

// Core Android System, OS, and Lifecycle
import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Build;
import android.os.Message;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.TypedValue;

// Android Graphics, Themes, and Windowing
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

// Android View Framework and Native UI Widgets
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

// Android WebKit (Core Browser Engine Dependencies)
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

// AndroidX Jetpack Components (Activity, Window Insets, Contracts)
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

// Java Standard Core Utilities & I/O Packages
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.CopyOnWriteArrayList;

public class MainActivity extends AppCompatActivity {
    SecureCredentialManager secureCredentialManager;

    private static final String PREFS_NAME = "spoon_browser";
    private static final String KEY_BOOKMARKS = "bookmarks";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_PAGE_TITLES = "page_titles";
    private static final String KEY_FILTER_LISTS = "filter_lists";
    private static final String KEY_FILTER_REFRESH_TIME = "filter_refresh_time";
    private static final String KEY_OPEN_TABS = "open_tabs";
    private static final String KEY_CURRENT_TAB = "current_tab";
    private static final int MAX_HISTORY = 500;

    AutoCompleteTextView addressBar;
    private ArrayAdapter<String> addressBarAdapter;
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

    private final java.util.concurrent.ExecutorService backgroundExecutor = java.util.concurrent.Executors.newFixedThreadPool(4);
    private final CopyOnWriteArrayList<WebView> tabs = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> bookmarks = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> history = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.ConcurrentHashMap<String, String> pageTitles = new java.util.concurrent.ConcurrentHashMap<>();
    private SharedPreferences prefs;
    private int currentTab = 0;
    private boolean suppressSuggestions = false;
    private boolean clearSessionOnExit = false;
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private boolean isDesktopMode = false;
    private final CopyOnWriteArrayList<String> filterLists = new CopyOnWriteArrayList<>();
    private final HashSet<String> blockedDomains = new HashSet<>();
    private final HashSet<String> rawFilterRules = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_NoActionBar);
        android.webkit.WebView.enableSlowWholeDocumentDraw();
        
        super.onCreate(savedInstanceState);
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(android.graphics.Color.BLACK);
        }

        secureCredentialManager = new SecureCredentialManager(this);
 
        passwordImportLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                        if (secureCredentialManager.importFromCSVStream(is)) {
                            Toast.makeText(this, "Passwords imported successfully", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Failed to parse passwords.csv", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(this, "Error opening file stream", Toast.LENGTH_SHORT).show();
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
        // Safely load your desktop mode preference here where context is fully ready
        isDesktopMode = prefs.getBoolean("isDesktopMode", false);
        setupRootLayout();
        createToolbarViews();
        setupToolbarListeners();
        setupMenuButton();
        setupBackButtonHandler();

        if (root != null) {
            if (toolbar != null) root.addView(toolbar);
            if (browserContainer != null) root.addView(browserContainer);
            setContentView(root);

            // FIX: Prevent Autofill from spawning a rogue popup window before the layout pass finishes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
                if (browserContainer != null) {
                    browserContainer.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
                }
            }
        }

        loadSavedData();

        checkAndAutoUpdateFilters();
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
        
        // OPTIMIZATION: Wake up the active WebView engine and resume its internal rendering loops
        WebView activeWebView = getCurrentWebView();
        if (activeWebView != null) {
            activeWebView.onResume();
            activeWebView.resumeTimers();
        }
    }
    
    private void handleIncomingIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            String urlToLoad = intent.getData().toString();
            
            createNewTab(); 
            if (addressBar != null) {
                addressBar.setText(urlToLoad);
            }
            openUrl(urlToLoad);
            setIntent(new Intent()); 
        } else if (intent != null && intent.getAction() != null) {
            // Only spawn a fresh home tab if the browser engine has no open tabs running
            if (tabs == null || tabs.isEmpty()) {
                createNewTab();
                showHome();
            }
            setIntent(new Intent());
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        
        // OPTIMIZATION: Suspend active media streams, CSS animations, and JS intervals.
        // This resets HTML5 Page Visibility targets, completely fixing the YouTube main stream loading bug.
        WebView activeWebView = getCurrentWebView();
        if (activeWebView != null) {
            activeWebView.onPause();
            activeWebView.pauseTimers();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        
        // Let onStop handle the ultimate decision: Clear completely OR Save completely
        try {
            if (clearSessionOnExit) {
                android.webkit.WebStorage.getInstance().deleteAllData();
                android.webkit.CookieManager.getInstance().removeAllCookies(null);
                android.webkit.CookieManager.getInstance().flush();
            } else {
                // Instantly commit active forum/site login session states to permanent flash storage 
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    android.webkit.CookieManager.getInstance().flush();
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        backgroundExecutor.shutdownNow();
    }

        private boolean restoreSession() {
        try {
            String savedTabs = prefs.getString(KEY_OPEN_TABS, "");
            if (savedTabs == null || savedTabs.isEmpty()) {
                return false;
            }
            String[] urls = savedTabs.split("\n");
            int count = 0;
            String firstValid = null;

            for (String raw : urls) {
                if (raw == null) continue;
                String url = raw.trim();
                if (url.isEmpty() || url.equals("about:blank")) {
                    continue;
                }
                if (firstValid == null) {
                    firstValid = url;
                }

                if (count >= 3) break;

                try {
                    WebView webView = createConfiguredWebView();
                    tabs.add(webView);

                    if (browserContainer != null) {
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        );
                        webView.setVisibility(View.GONE);
                        if (android.os.Build.VERSION.SDK_INT >= 11) webView.onResume();
                        browserContainer.addView(webView, params);
                    }

                    webView.loadUrl(url);
                    count++;
                } catch (Exception e) {
                    android.util.Log.e("SpoonBrowser", "Failed to restore tab stream: " + url, e);
                }
            }

            if (firstValid == null) {
                return false;
            }

            runOnUiThread(() -> {
                try {
                    if (tabs.isEmpty()) {
                        WebView webView = createConfiguredWebView();
                        tabs.add(webView);
                    }
                    currentTab = 0;
                    WebView current = tabs.get(0);
                    if (browserContainer != null) {
                        browserContainer.removeAllViews();
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        );
                        browserContainer.addView(current, params);
                        current.setVisibility(View.VISIBLE);
                    }
                    updateTabIndicator();
                } catch (Exception e) {
                    android.util.Log.e("SpoonBrowser", "Failed UI thread layout adjustment", e);
                }
            });

            return true;
        } catch (Exception e) {
            android.util.Log.e("SpoonBrowser", "Failed to restore session safely", e);
            return false;
        }
    }



    private void setupRootLayout() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // Apply window insets cleanly without shrinking side dimensions
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, systemBars.top, 0, 0); // Only pad the status bar area
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
        String savedHistory = prefs.getString(KEY_HISTORY, "");
        if (!savedHistory.isEmpty()) {
            for (String item : savedHistory.split("\\n")) {
                history.add(item);
                while (history.size() > MAX_HISTORY) {
                    history.remove(0);
                }
            }
        }

        String savedBookmarks = prefs.getString(KEY_BOOKMARKS, "");
        if (!savedBookmarks.isEmpty()) {
            for (String bookmark : savedBookmarks.split("\\n")) {
                if (!bookmarks.contains(bookmark)) {
                    bookmarks.add(bookmark);
                }
            }
        }

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
                refreshFilterLists();
            }
        }

        String savedPageTitles = prefs.getString(KEY_PAGE_TITLES, "");
        if (!savedPageTitles.isEmpty()) {
            for (String item : savedPageTitles.split("\\n")) {
                String[] parts = item.split("\\|", 2);
                if (parts.length == 2) {
                    pageTitles.put(parts[0], parts[1]);
                }
            }
        }
        rebuildBlockedDomains();
    }

    private void setupBackButtonHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView webView = getCurrentWebView();
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else if (tabs.size() > 1) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setMessage("Close this tab?")
                            .setPositiveButton("Close", (d, w) -> closeTab(currentTab))
                            .setNegativeButton("Cancel", null)
                            .show();
                } else {
                    finishAndRemoveTask();
                }
            }
        });
    }
    
    private void showSavedPasswordsDialog() {
        if (secureCredentialManager == null) return;
        
        backgroundExecutor.execute(() -> {
            // 1. Offload file disk parsing safely to background worker thread
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

            // 2. Switch back to the UI thread if no passwords exist to paint the empty alert dialog
            if (hosts.isEmpty()) {
                runOnUiThread(() -> {
                    new AlertDialog.Builder(this)
                        .setTitle("Saved Passwords")
                        .setMessage("No passwords saved yet.")
                        .setPositiveButton("OK", null)
                        .show();
                });
                return;
            }

            // 3. Process background decryption operations before attempting view rendering
            java.util.ArrayList<String> displayList = new java.util.ArrayList<>();
            for (String h : hosts) {
                String user = secureCredentialManager.getUsername(h);
                displayList.add(h + " (" + user + ")");
            }

            // 4. Safely return back to the main layout channel to build and show the dialog boxes
            runOnUiThread(() -> {
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
                    
                    // Offload individual account key extraction to a worker thread
                    backgroundExecutor.execute(() -> {
                        String user = secureCredentialManager.getUsername(selectedHost);
                        String pass = secureCredentialManager.getPassword(selectedHost);
                        
                        runOnUiThread(() -> {
                            new AlertDialog.Builder(this)
                                .setTitle(selectedHost)
                                .setMessage("Username: " + user + "\nPassword: " + pass)
                                .setNegativeButton("Delete", (d, w) -> {
                                    // Handle safe asynchronous database cleanup entries
                                    backgroundExecutor.execute(() -> {
                                        secureCredentialManager.clearCredentials(selectedHost);
                                        runOnUiThread(() -> {
                                            Toast.makeText(this, "Credentials deleted", Toast.LENGTH_SHORT).show();
                                            dialog.dismiss();
                                            showSavedPasswordsDialog(); // Safe refresh loop recursion
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
        Context wrapper = new ContextThemeWrapper(this, android.R.style.Widget_Material_Light_PopupMenu);
        PopupMenu popup = new PopupMenu(wrapper, menuButton, Gravity.END);
            popup.getMenu().add("New Tab");
            popup.getMenu().add("Reload");
            popup.getMenu().add("Bookmarks");
            popup.getMenu().add("Add Bookmark");
            popup.getMenu().add("History");
            popup.getMenu().add("Clear History");
            popup.getMenu().add("Clear Cache");
            popup.getMenu().add("Filter Lists");
            popup.getMenu().add(isDesktopMode ? "Desktop Site [ON]" : "Desktop Site [OFF]");
            popup.getMenu().add("About");
            popup.getMenu().add("Passwords");
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
                    case "Bookmarks":
                        showBookmarks();
                        return true;
                    case "Add Bookmark":
                        WebView wv = getCurrentWebView();
                        String url = wv != null ? wv.getUrl() : null;
                        if (url != null && !url.isEmpty() && !bookmarks.contains(url)) {
                            bookmarks.add(url);
                            saveBookmarks();
                            Toast.makeText(this, "Bookmark saved", Toast.LENGTH_SHORT).show();
                        }
                        return true;
                    case "History":
                        showHistoryDialog();
                        return true;
                    case "Clear History":
                        history.clear();
                        saveHistory();
                        return true;
                    case "Clear Cache":
                        if (getCurrentWebView() != null) getCurrentWebView().clearCache(true);
                        Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show();
                        return true;
                    case "Filter Lists":
                        showFilterListsDialog();
                        return true;
                    case "About":
                        showAbout();
                        return true;
                    case "Desktop Site [OFF]":
                    case "Desktop Site [ON]":
                        toggleDesktopMode();
                        return true;    
                    case "Passwords":
                        String[] options = {"Saved Passwords", "Import from CSV", "Export to CSV"};
                        new AlertDialog.Builder(this)
                            .setTitle("Password Management")
                            .setItems(options, (dialog, which) -> {
                                if (which == 0) {
                                    openUrl("file:///android_asset/vault.html");
                                } else if (which == 1) {
                                    passwordImportLauncher.launch("text/*");
                                } else if (which == 2) {
                                    if (secureCredentialManager != null) {
                                        java.io.File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                                        java.io.File csvFile = new java.io.File(downloadDir, "passwords.csv");
                                        if (secureCredentialManager.exportToCSV(MainActivity.this)) {
                                            Toast.makeText(this, "Passwords exported to Downloads/passwords.csv", Toast.LENGTH_LONG).show();
                                        } else {
                                            Toast.makeText(this, "Failed to export passwords", Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                }
                            })
                            .show();
                        return true;
case "Exit":
                        finishAndRemoveTask();
                        return true;
                }
                return false;
            });
            popup.show();
        });
    }
    private void setupToolbarListeners() {
        // Feature Removed: Disabled long press behavior entirely
        tabIndicator.setOnLongClickListener(null);
        tabIndicator.setLongClickable(false);
        tabIndicator.setOnClickListener(v -> showTabSwitcher());

        addressBar.setOnKeyListener((v, keyCode, event) -> {

            if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                navigate();
                return true;
            }
            return false;
        });

        forwardButton.setOnClickListener(v -> {
            WebView webView = getCurrentWebView();
            if (webView != null && webView.canGoForward()) {
                webView.goForward();
            }
        });

        newTabButton.setOnClickListener(v -> {
            createNewTab();
            showHome();
        });

        prevTabButton.setOnClickListener(v -> {
            if (tabs.size() > 1) {
                int previous = currentTab - 1;
                if (previous < 0) {
                    previous = tabs.size() - 1;
                }
                switchToTab(previous);
            }
        });

        nextTabButton.setOnClickListener(v -> {
            if (tabs.size() > 1) {
                int next = (currentTab + 1) % tabs.size();
                switchToTab(next);
            }
        });
    }

    private void createToolbarViews() {
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(10), dp(12), dp(10)); // Increased padding for touch targets
        toolbar.setBackgroundColor(Color.parseColor("#141414")); // Sleek modern dark mode accent

        forwardButton = makeButton("→");
        prevTabButton = makeButton("◀");
        nextTabButton = makeButton("▶");
        newTabButton = makeButton("+");
        menuButton = makeButton("☰");

        // Responsive UI Logic for Phones vs Tablets
        int screenWidth = getScreenWidthDp();
        if (screenWidth < 600) {
            // True Mobile Mode: Hide back/forward/tabs to maximize space for the URL bar
            forwardButton.setVisibility(View.GONE);
            prevTabButton.setVisibility(View.GONE);
            nextTabButton.setVisibility(View.GONE);
            newTabButton.setVisibility(View.GONE);
        } else {
            // Tablet Mode: Ensure everything is explicitly visible when rotated
            forwardButton.setVisibility(View.VISIBLE);
            prevTabButton.setVisibility(View.VISIBLE);
            nextTabButton.setVisibility(View.VISIBLE);
            newTabButton.setVisibility(View.VISIBLE);
        }

        tabIndicator = new TextView(this);
        tabIndicator.setTextColor(Color.WHITE);
        tabIndicator.setTextSize(14);
        tabIndicator.setPadding(dp(8), 0, dp(8), 0);
        
        // Hide tab counter on small phones if it crowds the bar
        if (screenWidth < 360) {
            tabIndicator.setVisibility(View.GONE);
        }

        addressBar = new AutoCompleteTextView(this);
        addressBar.setHint("Search or enter address");
        addressBar.setTextColor(Color.WHITE);
        addressBar.setHintTextColor(Color.GRAY);
        addressBar.setSingleLine(true);
        addressBar.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        // Avoid immediate popup on focus/startup — threshold 1 reduces race conditions.
        addressBar.setThreshold(1);
        
        // Modernized Address Bar Background styling - Isolated unique variable name
        android.graphics.drawable.GradientDrawable customAddressBg = new android.graphics.drawable.GradientDrawable();
        customAddressBg.setColor(android.graphics.Color.parseColor("#222222"));
        customAddressBg.setCornerRadius(dp(20)); // Perfectly rounded capsule shape
        addressBar.setBackground(customAddressBg);

        addressBar.setPadding(dp(16), dp(8), dp(16), dp(8));

        // CRITICAL FIX: Give address bar dynamic layout weight so it stretches elegantly
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        addressParams.setMargins(dp(6), 0, dp(6), 0);
        addressBar.setLayoutParams(addressParams);
        
        // Smart URL Selection Logic: Clear on first tap, edit on second tap
        final boolean[] justGainedFocus = {false};
        addressBar.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                // Let the UI layout complete, then select all text so one backspace drops it entirely
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
            // If the user already tapped once to select it, a second tap should drop the cursor right at their finger
            if (!justGainedFocus[0]) {
                int cursorPosition = addressBar.getSelectionStart();
                if (cursorPosition >= 0) {
                    addressBar.setSelection(cursorPosition);
                }
            }
            // Reset flag on subsequent click sequences
            justGainedFocus[0] = false;
        });

        // Keep your existing adapter and text watcher setups underneath...
        addressBarAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, new ArrayList<>()) {

                    @Override
            public android.widget.Filter getFilter() {
                return new android.widget.Filter() {
                    @Override
                    protected FilterResults performFiltering(CharSequence constraint) {
                        FilterResults results = new FilterResults();
                        if (constraint != null) {
                            results.count = 1;
                        }
                        return results;
                    }
                    @Override
                    protected void publishResults(CharSequence constraint, FilterResults results) {
                        notifyDataSetChanged();
                    }
                };
            }
        };

        // Defer adapter attachment until after layout to reduce early-popup races.
        addressBar.post(() -> {
            try {
                addressBar.setAdapter(addressBarAdapter);
                // STYLING ADDITION: Apply matching dark, rounded design to the dropdown panel itself
                addressBar.setDropDownBackgroundDrawable(new android.graphics.drawable.GradientDrawable() {{
                    setColor(android.graphics.Color.parseColor("#1F1F1F")); // Subtle contrast dark accent
                    setCornerRadius(dp(16));                               // Premium rounded corner profiles
                }});
                addressBar.setDropDownVerticalOffset(dp(4));               // Floating gap separation
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

            // Clean the input string: extract the actual URL if it contains a label or CSS scrap
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
                updateAddressBarSuggestions(s.toString());
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
            // Explicitly declared android.text.Editable signature package target to avoid any lookup failures
        });

        GradientDrawable addressBg = new GradientDrawable();
        addressBg.setColor(Color.parseColor("#262626"));
        addressBg.setCornerRadius(dp(20));
        addressBar.setBackground(addressBg);
        addressBar.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        inputParams.setMargins(dp(8), 0, dp(8), 0);
        addressBar.setLayoutParams(inputParams);

        // Calculate device width profile dynamically
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        float widthDp = metrics.widthPixels / metrics.density;

        if (widthDp >= 600) {
            // TABLET LAYOUT: Show all individual controls side-by-side
            toolbar.addView(forwardButton);
            toolbar.addView(prevTabButton);
            toolbar.addView(tabIndicator);
            toolbar.addView(nextTabButton);
            toolbar.addView(newTabButton);
            toolbar.addView(addressBar);
            toolbar.addView(menuButton);
        } else {
            // MOBILE LAYOUT: Pure minimalism
            // SAFEST PRE-EMPTIVE FIX: Force detach mobile elements from any previous parent trees to prevent layout crashes
            if (addressBar != null && addressBar.getParent() instanceof ViewGroup) {
                ((ViewGroup) addressBar.getParent()).removeView(addressBar);
            }
            if (menuButton != null && menuButton.getParent() instanceof ViewGroup) {
                ((ViewGroup) menuButton.getParent()).removeView(menuButton);
            }

            // Hide the redundant desktop buttons entirely
            forwardButton.setVisibility(View.GONE);
            prevTabButton.setVisibility(View.GONE);
            tabIndicator.setVisibility(View.GONE);
            nextTabButton.setVisibility(View.GONE);
            newTabButton.setVisibility(View.GONE);

            // Create a gorgeous modern Tab Switcher Badge [ 1 ]
            tabBadgeButton = new Button(this);
            int tabCount = (tabs != null) ? tabs.size() : 1;
            tabBadgeButton.setText(String.valueOf(tabCount));
            tabBadgeButton.setTextColor(Color.WHITE);
            tabBadgeButton.setTextSize(12); // Slightly lowered font size to sit comfortably inside the box
            tabBadgeButton.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tabBadgeButton.setGravity(Gravity.CENTER);
            
            // CRITICAL FIX: Strip default Android button paddings to keep text perfectly centered
            tabBadgeButton.setPadding(0, 0, 0, 0);
            tabBadgeButton.setIncludeFontPadding(false);

            // Give it a sleek rounded border look
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(Color.TRANSPARENT);
            badgeBg.setStroke(dp(2), Color.parseColor("#CCCCCC"));
            badgeBg.setCornerRadius(dp(6));
            tabBadgeButton.setBackground(badgeBg);

            // Expand touch target to 36dp x 36dp for smaller screens so it registers clicks easily
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(36), dp(36));
            badgeParams.setMargins(dp(6), 0, dp(6), 0);
            tabBadgeButton.setLayoutParams(badgeParams);

            // DIRECT FIX: Fire your browser's native tab layout window directly!
            tabBadgeButton.setOnClickListener(v -> {
                showTabSwitcher();
            });

            // MENU OVERLAY ALIGNMENT FIX: Strip implicit button boundaries so the icon is perfectly squared
            if (menuButton != null) {
                menuButton.setPadding(0, 0, 0, 0);
                menuButton.setIncludeFontPadding(false);
            }

            // Cleanly attach views only if they don't already have an assigned parent layout
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
    
    private void configureWebSettings(WebSettings settings) {
        if (settings == null) return;

        // 1. Core Web & Javascript Capabilities
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);

        // 2. HTML5 Storage & Security Engines (Fixes Cloudflare loops & f95zone)
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSaveFormData(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 3. File & Local Storage Access Configuration
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        // 4. Layout & UI Controls Optimization
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING);
        }
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setGeolocationEnabled(false);

        // 5. Media Engine Initialization (Fixes YouTube Video Playback Loops)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }

        // 6. Cross-Origin Contexts & Mixed Security Handshakes
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true); 
        }
        
        // 7. Thread-Safe Session Authentication Cookie Synchronizers
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
        } catch (Exception ignored) {}
    
     // 8. User-Agent Profile Engine Matrix
     boolean forceDesktop = false;
     try {
         if (prefs != null) {
             forceDesktop = prefs.getBoolean("isDesktopMode", false);
         }
     } catch (Exception e) {
         forceDesktop = false;
     }

        if (forceDesktop) {
            settings.setUserAgentString(DESKTOP_UA);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
        } else {
            settings.setUserAgentString(MOBILE_UA);
            settings.setUseWideViewPort(false);
            settings.setLoadWithOverviewMode(false);
        }
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

        // Configure default native web settings
        configureWebSettings(webView.getSettings());

        // Native Anti-Tracking: Managed third-party block state with a fallback path for enterprise forums
        android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            // Change to true if a specific site demands cross-domain handshake keys to authenticate
            cookieManager.setAcceptThirdPartyCookies(webView, true); 
        }

        webView.addJavascriptInterface(new SecureSpoonBridge(webView, secureCredentialManager), "SpoonVault");
        webView.setWebChromeClient(new SpoonWebChromeClient(this));
        webView.setOnLongClickListener(createImageLongClickListener(webView));
        webView.setWebViewClient(new SpoonWebViewClient(this));

        
        // Initialize bridge references for javascript memory space blobs and base64 strings natively
        BlobDownloader downloaderBridge = new BlobDownloader(this);
        webView.addJavascriptInterface(downloaderBridge, "AndroidDownloader");

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                if (url == null) return;
                
                try {
                    String targetFileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);

                    // 1. Asynchronous extraction path for javascript memory blobs
                    if (url.startsWith("blob:")) {
                        String extractionScript = "javascript:(function() {" +
                                "  var request = new XMLHttpRequest();" +
                                "  request.open('GET', '" + url + "', true);" +
                                "  request.responseType = 'blob';" +
                                "  request.onload = function() {" +
                                "    if (this.status === 200) {" +
                                "      var dataReader = new FileReader();" +
                                "      dataReader.readAsDataURL(this.response);" +
                                "      dataReader.onloadend = function() {" +
                                "        AndroidDownloader.saveBase64ToFile(dataReader.result, '" + mimeType + "', '" + targetFileName + "');" +
                                "      };" +
                                "    }" +
                                "  };" +
                                "  request.send();" +
                                "})();";
                        webView.evaluateJavascript(extractionScript, null);
                        return;
                    } 
                    
                    // 2. Direct decoding path for Base64 data URIs
                    if (url.startsWith("data:")) {
                        downloaderBridge.saveBase64ToFile(url, mimeType, targetFileName);
                        return;
                    }

                    // 3. Combined Dual-Downloader Options Selection for standard HTTP/HTTPS file links
                    CharSequence[] options = new CharSequence[]{"Native Browser Downloader", "External App (ADM/1DM/System chooser)"};
                    
                    new android.app.AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Download File")
                        .setItems(options, (dialog, item) -> {
                            if (item == 0) {
                                // --- OPTION 1: NATIVE DOWNLOADER ---
                                try {
                                    android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(android.net.Uri.parse(url));
                                    String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
                                    request.addRequestHeader("Cookie", cookies);
                                    request.addRequestHeader("User-Agent", userAgent);
                                    request.setMimeType(mimeType);
                                    
                                    request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, targetFileName);
                                    request.allowScanningByMediaScanner();
                                    request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                                    request.setTitle(targetFileName);
                                    request.setDescription("Downloading file...");

                                    android.app.DownloadManager manager = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                                    if (manager != null) {
                                        manager.enqueue(request);
                                        Toast.makeText(MainActivity.this, "Download started natively...", Toast.LENGTH_SHORT).show();
                                    }
                                } catch (Exception e) {
                                    Toast.makeText(MainActivity.this, "Native download failed. Switching to external fallback.", Toast.LENGTH_SHORT).show();
                                    triggerExternalDownload(url, mimeType);
                                }
                            } else {
                                // --- OPTION 2: EXTERNAL DOWNLOADER ---
                                triggerExternalDownload(url, mimeType);
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();

                } catch (Exception err) {
                    Toast.makeText(MainActivity.this, "Download manager initialization failed", Toast.LENGTH_SHORT).show();
                }
            }
        });

        return webView;
    }

    private void createNewTab() {
        WebView webView = createConfiguredWebView();

        // Force full hardware GPU pipeline rendering
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB) {
            webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
        }
        webView.setDrawingCacheEnabled(false);

        tabs.add(webView);
        currentTab = tabs.size() - 1;

        if (browserContainer != null) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            );
            // Natively attach to layout tree immediately, default to hidden
            webView.setVisibility(View.GONE);
            if (android.os.Build.VERSION.SDK_INT >= 11) webView.onResume();
            browserContainer.addView(webView, params);
        }
        
        switchToTab(currentTab);
    }

    private void switchToTab(int index) {
        if (tabs == null || index < 0 || index >= tabs.size()) return;

        currentTab = index;
        saveCurrentTab();
        updateTabIndicator();
        updateTabBadgeCount();

        if (browserContainer != null) {
            // High-Performance Visibility Toggle Pattern
            for (int i = 0; i < tabs.size(); i++) {
                WebView wv = tabs.get(i);
                if (wv != null) {
                    if (i == index) {
                        wv.setVisibility(View.VISIBLE);
                        // Prevent background rendering freezes by requesting window focus
                        wv.onResume();
                        wv.resumeTimers();
                        
                        String url = wv.getUrl();
                        if (addressBar != null) {
                            addressBar.setText((url == null || url.isEmpty() || "about:blank".equals(url)) ? "" : url);
                        }
                    } else {
                        wv.setVisibility(View.GONE);
                        // Pause inactive tabs to preserve battery and CPU limits natively
                        wv.onPause();
                    }
                }
            }
        }
        if (addressBar != null) {
            try {
                if (addressBar.isAttachedToWindow()) {
                    addressBar.dismissDropDown();
                }
            } catch (Exception ignored) {}
            addressBar.clearFocus();
        }

    }


    private void closeTab(int index) {
        if (tabs.size() == 1) {
            clearSessionOnExit = true;
            prefs.edit().remove(KEY_OPEN_TABS).remove(KEY_CURRENT_TAB).apply();
            finishAndRemoveTask();
            return;
        }

        WebView webView = tabs.get(index);
        String url = webView.getUrl();
        if (url != null) {
            synchronized (pageTitles) {
                pageTitles.remove(url);
            }
            savePageTitles();
        }

        if (browserContainer != null) {
            browserContainer.removeView(webView);
        }
        tabs.remove(index);

        // Comprehensive WebView Memory Teardown Sequence (Point #5)
        webView.stopLoading();
        webView.setDownloadListener(null);
        webView.setWebChromeClient(null);
        webView.setWebViewClient(null);
        
        webView.clearHistory();
        webView.clearCache(true);
        webView.loadUrl("about:blank");
        webView.removeAllViews();
        
        // Final explicit teardown to signal Chromium to release its native layer RAM immediately
        webView.destroy();
        webView = null;

        saveOpenTabs();
        if (currentTab >= tabs.size()) {
            currentTab = tabs.size() - 1;
        }
        switchToTab(currentTab);
    }

    private void updateTabIndicator() {
        if (tabIndicator != null) {
            tabIndicator.setText((currentTab + 1) + "/" + tabs.size());
        }
    }

    private ArrayList<BrowserItem> buildTabItems() {
        ArrayList<BrowserItem> items = new ArrayList<>();
        synchronized (pageTitles) {
            for (WebView webView : tabs) {
                String url = webView.getUrl();
                String title = url != null ? pageTitles.get(url) : null;
                if (title == null || title.isEmpty()) {
                    title = (url == null || url.isEmpty()) ? "New Tab" : url;
                }
                items.add(new BrowserItem(title, url));
            }
        }
        return items;
    }

    private void showTabSwitcher() {
        if (tabs == null || tabs.isEmpty()) return;

        // 1. Create a vertical container layout (Using UI Overhaul's improved styling)
        LinearLayout dialogContainer = new LinearLayout(this);
        dialogContainer.setOrientation(LinearLayout.VERTICAL);
        dialogContainer.setBackgroundColor(Color.parseColor("#141414"));
        dialogContainer.setPadding(dp(16), dp(12), dp(16), dp(16));

        // 2. Build a responsive instruction hint banner
        TextView hintTextView = new TextView(this);
        boolean isTablet = getResources().getConfiguration().smallestScreenWidthDp >= 600;
        hintTextView.setText(isTablet ? "💡 Tip: Long-press an item to close it / Tap to switch views" : "💡 Tip: Long-press a tab item to instantly close it");
        hintTextView.setTextColor(Color.parseColor("#8A8A8A"));
        hintTextView.setTextSize(13);
        hintTextView.setGravity(Gravity.CENTER_HORIZONTAL);
        hintTextView.setPadding(0, 0, 0, dp(14));
        hintTextView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        dialogContainer.addView(hintTextView);

        // 3. Configure the main ListView
        ListView listView = new ListView(this);
        listView.setDivider(new ColorDrawable(Color.parseColor("#252525")));
        listView.setDividerHeight(dp(1));
        dialogContainer.addView(listView);

        // 4. Extract string titles
        ArrayList<String> tabTitles = new ArrayList<>();
        for (WebView webView : tabs) {
            String title = (webView != null) ? webView.getTitle() : null;
            tabTitles.add((title == null || title.isEmpty()) ? (webView != null && webView.getUrl() != null ? webView.getUrl() : "New Tab") : title);
        }

        ArrayAdapter<String> tabAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, tabTitles);
        listView.setAdapter(tabAdapter);

        // 5. Build the dialog
        final AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Active Tabs")
                .setView(dialogContainer)
                .create();

        // 6. Navigation click actions
        listView.setOnItemClickListener((parent, view, position, id) -> {
            currentTab = position;
            if (tabs != null && position < tabs.size()) switchToTab(position);
            dialog.dismiss();
        });

        // 7. Unified long-press action (Your Ad-Blocker logic)
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (tabs != null && position < tabs.size()) {
                closeTab(position);
                tabTitles.remove(position);
                if (currentTab >= tabs.size()) currentTab = Math.max(0, tabs.size() - 1);
                tabAdapter.notifyDataSetChanged();
                if (!tabs.isEmpty()) switchToTab(currentTab);
            }
            dialog.dismiss();
            if (tabs != null && !tabs.isEmpty()) showTabSwitcher();
            return true;
        });

        dialog.show();
    }

    private ArrayList<BrowserItem> buildHistoryItems() {
        ArrayList<BrowserItem> items = new ArrayList<>();
        synchronized (pageTitles) {
            for (int i = 0; i < history.size(); i++) {
                String url = history.get(history.size() - 1 - i);
                String title = pageTitles.get(url);
                items.add(new BrowserItem(title != null && !title.isEmpty() ? title : url, url));
            }
        }
        return items;
    }

    private ArrayList<BrowserItem> buildBookmarkItems() {
        ArrayList<BrowserItem> items = new ArrayList<>();
        synchronized (pageTitles) {
            for (String url : bookmarks) {
                String title = pageTitles.get(url);
                items.add(new BrowserItem(title != null && !title.isEmpty() ? title : url, url));
            }
        }
        return items;
    }

    private void showHistoryDialog() {
        if (history.isEmpty()) return;
        ArrayList<BrowserItem> items = buildHistoryItems();
        BrowserItemAdapter adapter = new BrowserItemAdapter(this, items);
        ListView listView = new ListView(this);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, which, id) -> openUrl(items.get(which).url));
        listView.setOnItemLongClickListener((parent, view, which, id) -> {
            String[] options = {"Open in New Tab", "Add Bookmark"};
            new AlertDialog.Builder(this).setItems(options, (dialog, item) -> {
                if (item == 0) {
                    createNewTab();
                    openUrl(items.get(which).url);
                } else if (item == 1) {
                    String url = items.get(which).url;
                    if (!bookmarks.contains(url)) {
                        bookmarks.add(url);
                        saveBookmarks();
                        Toast.makeText(this, "Bookmark added", Toast.LENGTH_SHORT).show();
                    }
                }
            }).show();
            return true;
        });

        new AlertDialog.Builder(this).setTitle("History").setView(listView).show();
    }

    WebView getCurrentWebView() {
        if (tabs.isEmpty() || currentTab < 0 || currentTab >= tabs.size()) return null;
        return tabs.get(currentTab);
    }

    private void openUrl(String url) {
        WebView wv = getCurrentWebView();
        if (wv != null) wv.loadUrl(url);
    }

    private String getAppVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void saveBookmarks() {
        backgroundExecutor.execute(() -> {
            prefs.edit().putString(KEY_BOOKMARKS, TextUtils.join("\n", bookmarks)).apply();
        });
    }

    private void saveHistory() {
        backgroundExecutor.execute(() -> {
            prefs.edit().putString(KEY_HISTORY, TextUtils.join("\n", history)).apply();
        });
    }

    
    static class ContentFilterEngine {
        // Fast direct domain lookup buckets
        public final java.util.Set<String> blockDomains = new java.util.concurrent.ConcurrentHashMap<>().newKeySet();
        public final java.util.Set<String> whitelistDomains = new java.util.concurrent.ConcurrentHashMap<>().newKeySet();

        // Complex substring/wildcard fallback paths
        public final java.util.List<String> blockPatterns = new java.util.concurrent.CopyOnWriteArrayList<>();
        public final java.util.List<String> whitelistPatterns = new java.util.concurrent.CopyOnWriteArrayList<>();

        // Site-Specific and Global Cosmetic Cache Buckets
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
            
            // Handle true cosmetic rules (e.g., cnn.com##.ad-box or ##.ad-banner)
            if (rule.contains("##")) {
                int index = rule.indexOf("##");
                String domainPart = rule.substring(0, index).trim();
                String selector = rule.substring(index + 2).trim();
                
                if (selector.isEmpty() || selector.contains("{") || selector.contains("(")) return;

                if (domainPart.isEmpty()) {
                    // Global rule applies to everything
                    globalCosmeticSelectors.add(selector);
                } else {
                    // Site-specific rule (Handles comma-separated domains if present)
                    String[] domains = domainPart.split(",");
                    for (String domain : domains) {
                        domain = domain.trim().toLowerCase();
                        if (!domain.isEmpty() && !domain.startsWith("~")) { // Skip exception rules for simplicity
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

        // Extracts targeted rules matching the specific domain currently running
        public java.util.List<String> getCosmeticStyleBatches(String urlString) {
            java.util.List<String> batches = new java.util.ArrayList<>();
            java.util.Set<String> activeSelectors = new java.util.HashSet<>(globalCosmeticSelectors);

            // Extract host domain to fetch specific rules
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


    private void refreshFilterLists() {
        // Concurrency Guard: Drop duplicate network requests if a sync is already running
        if (!isFilterUpdating.compareAndSet(false, true)) {
            return; 
        }

        backgroundExecutor.execute(() -> {
            boolean allDownloadsSucceeded = true;
            ContentFilterEngine newEngine = new ContentFilterEngine();
            
            // process custom user filters exclusively
            if (filterLists != null && !filterLists.isEmpty()) {
                for (String filterUrl : filterLists) {
                    try {
                        String filename = "filter_" + Math.abs(filterUrl.hashCode()) + ".txt";
                        java.io.File localFile = new java.io.File(getFilesDir(), filename);
                        java.io.InputStream inputStream;

                        boolean isNetworkFetch = !localFile.exists();

                        if (!isNetworkFetch) {
                            inputStream = new java.io.FileInputStream(localFile);
                        } else {
                            java.net.URLConnection conn = new java.net.URL(filterUrl).openConnection();
                            conn.setConnectTimeout(5000);
                            conn.setReadTimeout(5000);
                            inputStream = conn.getInputStream();
                        }

                        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(inputStream))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                line = line.trim();
                                if (line.isEmpty() || line.startsWith("!")) continue;
                                newEngine.addRule(line);
                            }
                        }
                    } catch (Exception e) {
                        android.util.Log.e("SpoonBlocker", "Filter load failed: " + filterUrl);
                        allDownloadsSucceeded = false; // Trigger offline protection path
                    }
                }
            }

            // Atomic memory-space swap
            synchronized (filterEngine) {
                filterEngine.clear();
                filterEngine.blockDomains.addAll(newEngine.blockDomains);
                filterEngine.whitelistDomains.addAll(newEngine.whitelistDomains);
                filterEngine.blockPatterns.addAll(newEngine.blockPatterns);
                filterEngine.whitelistPatterns.addAll(newEngine.whitelistPatterns);
                filterEngine.globalCosmeticSelectors.addAll(newEngine.globalCosmeticSelectors);

                for (java.util.Map.Entry<String, java.util.Set<String>> entry : newEngine.siteCosmeticSelectors.entrySet()) {
                    filterEngine.siteCosmeticSelectors.computeIfAbsent(entry.getKey(), k -> new java.util.concurrent.ConcurrentHashMap<>().newKeySet()).addAll(entry.getValue());
                }
            }

            // Rolling Timer Reset: Only advance the 24-hour auto schedule if downloads actually succeed 
            // or if the list is completely empty. Prevents offline lockouts.
            if (allDownloadsSucceeded || filterLists.isEmpty()) {
                prefs.edit().putLong(KEY_FILTER_REFRESH_TIME, System.currentTimeMillis()).apply();
            }

            isFilterUpdating.set(false);
        });
    }
    
    private void checkAndAutoUpdateFilters() {
        long lastRefresh = prefs.getLong(KEY_FILTER_REFRESH_TIME, 0);
        long currentTime = System.currentTimeMillis();
        long twentyFourHours = 24 * 60 * 60 * 1000L; // 86,400,000 ms

        // Fire the compiler automatically only when the 24-hour rolling delta has expired
        if (currentTime - lastRefresh >= twentyFourHours) {
            refreshFilterLists();
        }
    }

    private void rebuildBlockedDomains() {
        refreshFilterLists();
    }

    private boolean isBlockedDomain(String host) {
        if (host == null) return false;
        synchronized (filterEngine) {
            return filterEngine.shouldBlock("http://" + host);
        }
    }

    private void saveFilterLists() {
        prefs.edit().putString(KEY_FILTER_LISTS, TextUtils.join("\n", filterLists)).apply();
        rebuildBlockedDomains();

    }

    private void saveOpenTabs() {
        backgroundExecutor.execute(() -> {
            ArrayList<String> urls = new ArrayList<>();
            for (WebView tab : tabs) {
                String url = tab.getUrl();
                if (url != null && !url.isEmpty() && !url.equals("about:blank")) {
                    urls.add(url);
                }
            }
            prefs.edit().putString(KEY_OPEN_TABS, TextUtils.join("\n", urls)).apply();
        });
    }

    private void saveCurrentTab() {
        prefs.edit().putInt(KEY_CURRENT_TAB, currentTab).apply();
    }

    private void savePageTitles() {
        StringBuilder builder = new StringBuilder();
        synchronized (pageTitles) {
            for (String url : pageTitles.keySet()) {
                builder.append(url).append("|").append(pageTitles.get(url)).append("\n");
            }
        }
        prefs.edit().putString(KEY_PAGE_TITLES, builder.toString()).apply();
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
                        refreshFilterLists();
                        saveFilterLists();
                    }
                })
                .setNeutralButton("More", (d, w) -> showFilterListOptions())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showFilterListOptions() {
        String[] options = {"View Subscriptions", "Add EasyList", "Add EasyPrivacy", "Update All Subscriptions"};
        new AlertDialog.Builder(this)
                .setTitle("Filter Lists")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showSubscribedFilterLists();
                    } else if (which == 1) {
                        String url = "https://easylist.to/easylist/easylist.txt";
                        if (!filterLists.contains(url)) {
                            filterLists.add(url);
                            refreshFilterLists();
                            saveFilterLists();
                        }
                    } else if (which == 2) {
                        String url = "https://easylist.to/easylist/easyprivacy.txt";
                        if (!filterLists.contains(url)) {
                            filterLists.add(url);
                            refreshFilterLists();
                            saveFilterLists();
                        }
                    } else if (which == 3) {
                        if (filterLists.isEmpty()) {
                            Toast.makeText(this, "No lists to update", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Updating filter lists in background...", Toast.LENGTH_SHORT).show();
                            backgroundExecutor.execute(() -> {
                                boolean totalSuccess = true;
                                for (String listUrl : filterLists) {
                                    try {
                                        java.net.URL urlObj = new java.net.URL(listUrl);
                                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) urlObj.openConnection();
                                        conn.setConnectTimeout(10000);
                                        conn.setReadTimeout(10000);
                                        if (conn.getResponseCode() == 200) {
                                            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
                                            StringBuilder sb = new StringBuilder();
                                            String line;
                                            while ((line = reader.readLine()) != null) {
                                                sb.append(line).append("\n");
                                            }
                                            reader.close();
                                            // Save the downloaded rules safely locally to match your naming pattern
                                            String filename = "filter_" + Math.abs(listUrl.hashCode()) + ".txt";
                                            java.io.File file = new java.io.File(getFilesDir(), filename);
                                            java.io.FileWriter writer = new java.io.FileWriter(file);
                                            writer.write(sb.toString());
                                            writer.close();
                                        } else {
                                            totalSuccess = false;
                                        }
                                    } catch (Exception e) {
                                        totalSuccess = false;
                                    }
                                }
                                boolean finalSuccess = totalSuccess;
                                runOnUiThread(() -> {
                                    saveFilterLists(); // re-triggers rebuildBlockedDomains natively
                                    if (finalSuccess) {
                                        Toast.makeText(this, "All filter lists updated successfully!", Toast.LENGTH_LONG).show();
                                    } else {
                                        Toast.makeText(this, "Updates finished, but some lists failed to download", Toast.LENGTH_LONG).show();
                                    }
                                });
                            });
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
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });

        new AlertDialog.Builder(this).setTitle("Subscribed Filter Lists").setView(listView).setPositiveButton("OK", null).show();
    }

    private void showAbout() {
        synchronized (filterEngine) {

            String webViewVer = "Unknown";
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                android.content.pm.PackageInfo pi = android.webkit.WebView.getCurrentWebViewPackage();
                if (pi != null) webViewVer = pi.versionName;
            }

            new AlertDialog.Builder(this)
                    .setTitle("Spoon Browser")
                    .setMessage("Version: " + getAppVersion() + "\n"
                            + "Engine: WebView " + webViewVer + "\n\n"
                            + "Tabs: " + tabs.size() + "\nBookmarks: " + bookmarks.size()
                            + "\nHistory: " + history.size() + "\nBlocked Rules: " + filterEngine.blockPatterns.size()
                            + "\n\nBuilt one green commit at a time.\nDesigned to evolve dynamically with Android WebView.\n\n- with love, Plaban.")
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void showBookmarks() {
        if (bookmarks.isEmpty()) {
            Toast.makeText(this, "No bookmarks saved", Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayList<BrowserItem> items = buildBookmarkItems();
        BrowserItemAdapter adapter = new BrowserItemAdapter(this, items);
        ListView listView = new ListView(this);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, which, id) -> openUrl(items.get(which).url));
        listView.setOnItemLongClickListener((parent, view, which, id) -> {
            String[] options = {"Open", "Open in New Tab", "Remove Bookmark"};
            new AlertDialog.Builder(this).setItems(options, (dialog, item) -> {
                if (item == 0) {
                    openUrl(items.get(which).url);
                } else if (item == 1) {
                    createNewTab();
                    openUrl(items.get(which).url);
                } else if (item == 2) {
                    bookmarks.remove(items.get(which).url);
                    saveBookmarks();
                }
            }).show();
            return true;
        });

        new AlertDialog.Builder(this).setTitle("Bookmarks").setView(listView).show();
    }

    private void showHome() {
        // High-Performance Lazy Initialization String Caching (Preserved!)
        if (cachedHomeHtml == null) {
            // Check screen width once during initialization
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(metrics);
            float widthDp = metrics.widthPixels / metrics.density;

            StringBuilder sb = new StringBuilder();
            sb.append("<html>")
              .append("<body style='margin:0;background:#000;color:white;font-family:sans-serif;text-align:center;'>")
              .append("<div style='padding-top:20%;'>")
              .append("<h1 style='font-size:48px;margin-bottom:40px;'>Spoon Browser</h1>");

            // Only add the input block to the cached memory string if it's a tablet
            if (widthDp >= 600) {
                sb.append("<input id='q' type='text' placeholder='Search privately...' style='width:72%;padding:20px;border:none;border-radius:18px;background:#1f1f1f;color:white;font-size:18px;outline:none;'/>");
            }

            sb.append("</div>")
              .append("<script>")
              .append("function goSearch(){")
              .append("  var q=document.getElementById(\"q\").value;")
              .append("  window.location.href='https://duckduckgo.com/?q='+encodeURIComponent(q);")
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

    public void updateTabBadgeCount() {
        if (tabBadgeButton != null && tabs != null) {
            // Run on UI thread to guarantee instant rendering updates
            runOnUiThread(() -> {
                tabBadgeButton.setText(String.valueOf(tabs.size()));
            });
        }
    }

    private void updateAddressBarSuggestions(String query) {
        addressBarAdapter.clear();
        if (query == null || query.trim().isEmpty()) {
            try {
                if (addressBar != null && addressBar.isAttachedToWindow()) {
                    addressBar.dismissDropDown();
                }
            } catch (Exception ignored) {}
            return;
        }

        String lower = query.toLowerCase();
        HashSet<String> seen = new HashSet<>();
        int count = 0;

        for (int i = history.size() - 1; i >= 0 && count < 5; i--) {
            String url = history.get(i);
            if (url == null) continue;

            String host = null;
            try {
                host = Uri.parse(url).getHost();
                if (host != null && host.startsWith("www.")) {
                    host = host.substring(4);
                }
            } catch (Exception ignored) {}

            boolean matchesUrl = url.toLowerCase().contains(lower);
            boolean matchesHost = host != null && host.toLowerCase().contains(lower);

            if (!matchesUrl && !matchesHost) continue;
            if (!seen.add(url)) continue;

            addressBarAdapter.add(url);
            count++;
        }

        addressBarAdapter.notifyDataSetChanged();

        try {
            if (addressBar != null && addressBar.isAttachedToWindow()) {
                if (addressBarAdapter.getCount() > 0) {
                    addressBar.showDropDown();
                } else {
                    addressBar.dismissDropDown();
                }
            } else if (addressBar != null) {
                // Post a safe attempt after layout if not attached yet.
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
    }

    private void navigate() {
        String input = addressBar.getText().toString().trim();
        if (input.isEmpty()) return;

        String lowerInput = input.toLowerCase();
        if ((lowerInput.startsWith("javascript:") ||
             lowerInput.startsWith("file:") ||
             lowerInput.startsWith("content:") ||
             lowerInput.startsWith("intent:")) && 
            !lowerInput.equals("file:///android_asset/vault.html")) {

            Toast.makeText(this, "Blocked unsafe URL", Toast.LENGTH_SHORT).show();
            return;
        }

        String url;
        if (lowerInput.equals("file:///android_asset/vault.html")) {
            url = "file:///android_asset/vault.html";
        } else if (input.contains(".") && !input.contains(" ")) {
            url = (input.startsWith("http://") || input.startsWith("https://")) ? input : "https://" + input;
        } else {
            url = "https://duckduckgo.com/?q=" + Uri.encode(input);
        }

        openUrl(url);

    }
    
    private void triggerExternalDownload(String url, String mimeType) {
        try {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            if (mimeType != null && !mimeType.isEmpty()) {
                intent.setDataAndType(android.net.Uri.parse(url), mimeType);
            } else {
                intent.setData(android.net.Uri.parse(url));
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            android.content.Intent chooser = android.content.Intent.createChooser(intent, "Download File via...");
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(chooser);
        } catch (Exception e) {
            try {
                android.content.Intent fallback = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                fallback.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(fallback);
            } catch (Exception fatal) {
                Toast.makeText(MainActivity.this, "No download handler found on device", Toast.LENGTH_SHORT).show();
            }
        }
    }

 private void toggleDesktopMode() {
     isDesktopMode = !isDesktopMode;

     // Save the new state permanently so it survives app restarts
     if (prefs != null) {
         prefs.edit().putBoolean("isDesktopMode", isDesktopMode).apply();
     }
    
        if (currentTab >= 0 && currentTab < tabs.size()) {
            WebView activeWebView = tabs.get(currentTab);
            if (activeWebView != null) {
                configureWebSettings(activeWebView.getSettings());
                activeWebView.reload();
            }
        }
    }

    // Phishing & Typosquatting Detection Engine
    private boolean isPhishingRisk(String host) {
        if (host == null) return false;
        String clean = host.toLowerCase().trim();
        if (clean.startsWith("www.")) clean = clean.substring(4);
        
        // Protect high-value targets from lookalikes
        String[] protectedDomains = {"google.com", "paypal.com", "facebook.com", "amazon.com", "netflix.com", "github.com"};
        
        for (String target : protectedDomains) {
            if (clean.equals(target)) return false; // Exact match is perfectly safe
            
            // Check if the domain is a subtle lookalike variance
            int distance = getLevenshteinDistance(clean, target);
            if (distance > 0 && distance <= 2) {
                return true; // Dangerously close variation detected!
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

}
