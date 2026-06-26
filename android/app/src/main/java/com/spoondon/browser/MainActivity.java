package com.spoondon.browser;

import android.os.Build;
import android.webkit.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;
import android.text.TextWatcher;
import android.text.Editable;
import android.net.Uri;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.os.Message;
import android.webkit.WebView.WebViewTransport;
import android.webkit.WebResourceResponse;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.SafeBrowsingResponse;
import android.webkit.WebResourceRequest;
import android.widget.Filter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.PopupMenu;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import android.webkit.ValueCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.webkit.RenderProcessGoneDetail;

public class MainActivity extends AppCompatActivity {
    private SecureCredentialManager secureCredentialManager;

    private static final String PREFS_NAME = "spoon_browser";
    private static final String KEY_BOOKMARKS = "bookmarks";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_PAGE_TITLES = "page_titles";
    private static final String KEY_FILTER_LISTS = "filter_lists";
    private static final String KEY_FILTER_REFRESH_TIME = "filter_refresh_time";
    private static final String KEY_OPEN_TABS = "open_tabs";
    private static final String KEY_CURRENT_TAB = "current_tab";
    private static final int MAX_HISTORY = 500;

    private AutoCompleteTextView addressBar;
    private ArrayAdapter<String> addressBarAdapter;
    private String cachedHomeHtml = null;
    private LinearLayout root;
    private LinearLayout browserContainer;
    private TextView tabIndicator;
    private LinearLayout toolbar;
    private Button forwardButton;
    private Button prevTabButton;
    private Button nextTabButton;
    private Button newTabButton;
    private Button menuButton;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ValueCallback<Uri[]> fileChooserCallback;
    private ActivityResultLauncher<String> filePickerLauncher;

    private final CopyOnWriteArrayList<WebView> tabs = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> bookmarks = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> history = new CopyOnWriteArrayList<>();
    private final HashMap<String, String> pageTitles = new HashMap<>();
    private SharedPreferences prefs;
    private int currentTab = 0;
    private boolean suppressSuggestions = false;
    private boolean clearSessionOnExit = false;

    private final CopyOnWriteArrayList<String> filterLists = new CopyOnWriteArrayList<>();
    private final HashSet<String> blockedDomains = new HashSet<>();
    private final HashSet<String> rawFilterRules = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Force the engine to warm up connection sockets early globally
        android.webkit.WebView.enableSlowWholeDocumentDraw();
        
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        secureCredentialManager = new SecureCredentialManager(this);
        // ... rest of your initialization


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
        try {
            stopService(new Intent(this, BackgroundMediaService.class));
        } catch (Exception e) {
            e.printStackTrace();
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
            createNewTab();
            showHome();
            setIntent(new Intent());
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        try {
            Intent serviceIntent = new Intent(this, BackgroundMediaService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        if (tabs != null) {
            for (WebView w : tabs) {
                if (w != null) {
                    try {
                        w.stopLoading();
                        w.clearHistory();
                        w.clearCache(true);
                        w.loadUrl("about:blank");
                        w.destroy();
                    } catch (Exception e) {
                        android.util.Log.e("SpoonBrowser", "Error cleaning up WebView instance", e);
                    }
                }
            }
        }

        // Global Storage & Cookie Trimming (Merged Point #7)
        try {
            android.webkit.WebStorage.getInstance().deleteAllData();
            if (clearSessionOnExit) {
                android.webkit.CookieManager.getInstance().removeAllCookies(null);
                android.webkit.CookieManager.getInstance().flush();
            }
        } catch (Exception e) {
            android.util.Log.e("SpoonBrowser", "Error cleaning up storage systems", e);
        }

        super.onDestroy();
    }


        private boolean restoreSession() {
        try {
            String savedTabs = prefs.getString(KEY_OPEN_TABS, "");
            if (savedTabs == null || savedTabs.isEmpty()) {
                return false;
            }

            String[] urls = savedTabs.split("\n");
            int count = 0;

            for (String url : urls) {
                if (url == null || url.trim().isEmpty() || url.equals("about:blank")) {
                    continue;
                }
                if (!url.contains(".") && !url.startsWith("http")) {
                    continue;
                }
                
                // Cap at 3 simultaneous tabs to completely prevent startup OOM crashes
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
                        browserContainer.addView(webView, params);
                    }

                    webView.loadUrl(url.trim());
                    count++;
                } catch (Exception e) {
                    android.util.Log.e("SpoonBrowser", "Failed to restore single tab: " + url, e);
                }

            }

            if (tabs.isEmpty()) {
                return false;
            }

            int savedCurrentTab = prefs.getInt(KEY_CURRENT_TAB, 0);
            if (savedCurrentTab < 0 || savedCurrentTab >= tabs.size()) {
                savedCurrentTab = 0;
            }

            switchToTab(savedCurrentTab);
            return true;
        } catch (Exception e) {
            android.util.Log.e("SpoonBrowser", "Failed to restore session safely", e);
            tabs.clear();
            return false;
        }
    }

    private void setupRootLayout() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return windowInsets;
        });

        browserContainer = new LinearLayout(this);
        browserContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams browserParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1
        );
        browserContainer.setLayoutParams(browserParams);
    }

    private void loadSavedData() {
        String savedHistory = prefs.getString(KEY_HISTORY, "");
        if (!savedHistory.isEmpty()) {
            for (String item : savedHistory.split("\n")) {
                history.add(item);
                while (history.size() > MAX_HISTORY) {
                    history.remove(0);
                }
            }
        }

        String savedBookmarks = prefs.getString(KEY_BOOKMARKS, "");
        if (!savedBookmarks.isEmpty()) {
            for (String bookmark : savedBookmarks.split("\n")) {
                if (!bookmarks.contains(bookmark)) {
                    bookmarks.add(bookmark);
                }
            }
        }

        String savedFilterLists = prefs.getString(KEY_FILTER_LISTS, "");
        if (!savedFilterLists.isEmpty()) {
            for (String filter : savedFilterLists.split("\n")) {
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
            for (String item : savedPageTitles.split("\n")) {
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

    private void setupMenuButton() {
        menuButton.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, menuButton);
            popup.getMenu().add("New Tab");
            popup.getMenu().add("Reload");
            popup.getMenu().add("Bookmarks");
            popup.getMenu().add("Add Bookmark");
            popup.getMenu().add("History");
            popup.getMenu().add("Clear History");
            popup.getMenu().add("Clear Cache");
            popup.getMenu().add("Filter Lists");
            popup.getMenu().add("About");
            popup.getMenu().add("Export Passwords");
            popup.getMenu().add("Import Passwords");
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
                    case "Export Passwords":
                        if (secureCredentialManager != null) {
                            java.io.File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                            java.io.File csvFile = new java.io.File(downloadDir, "passwords.csv");
                            if (secureCredentialManager.exportToCSV(csvFile)) {
                                Toast.makeText(this, "Passwords exported to Downloads/passwords.csv", Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(this, "Failed to export passwords", Toast.LENGTH_SHORT).show();
                            }
                        }
                        return true;
                    case "Import Passwords":
                        if (secureCredentialManager != null) {
                            java.io.File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                            java.io.File csvFile = new java.io.File(downloadDir, "passwords.csv");
                            if (!csvFile.exists()) {
                                Toast.makeText(this, "Place passwords.csv in Downloads folder first", Toast.LENGTH_LONG).show();
                            } else if (secureCredentialManager.importFromCSV(csvFile)) {
                                Toast.makeText(this, "Passwords imported successfully!", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, "Failed to parse passwords.csv", Toast.LENGTH_SHORT).show();
                            }
                        }
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
        toolbar.setPadding(dp(8), dp(8), dp(8), dp(8));
        toolbar.setBackgroundColor(Color.parseColor("#111111"));

        forwardButton = makeButton("→");
        prevTabButton = makeButton("◀");
        nextTabButton = makeButton("▶");
        newTabButton = makeButton("+");
        menuButton = makeButton("⋮");

        int screenWidth = getScreenWidthDp();
        if (screenWidth < 400) {
            forwardButton.setVisibility(View.GONE);
            newTabButton.setVisibility(View.GONE);
        } else if (screenWidth < 600) {
            forwardButton.setVisibility(View.GONE);
        }

        tabIndicator = new TextView(this);
        tabIndicator.setTextColor(Color.WHITE);
        tabIndicator.setTextSize(15);
        tabIndicator.setPadding(dp(10), 0, dp(10), 0);

        addressBar = new AutoCompleteTextView(this);
        addressBar.setHint("Search or enter address");
        addressBar.setTextColor(Color.WHITE);
        addressBar.setHintTextColor(Color.GRAY);
        addressBar.setSingleLine(true);
        addressBar.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        addressBar.setThreshold(0);

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

        addressBar.setAdapter(addressBarAdapter);
        addressBar.setOnItemClickListener((parent, view, position, id) -> {
            String rawItem = addressBarAdapter.getItem(position);
            if (rawItem == null) return;
            
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
            addressBar.dismissDropDown();
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
            @Override public void afterTextChanged(Editable s) {}
        });

        GradientDrawable addressBg = new GradientDrawable();
        addressBg.setColor(Color.parseColor("#262626"));
        addressBg.setCornerRadius(dp(20));
        addressBar.setBackground(addressBg);
        addressBar.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        inputParams.setMargins(dp(8), 0, dp(8), 0);
        addressBar.setLayoutParams(inputParams);

        toolbar.addView(forwardButton);
        toolbar.addView(prevTabButton);
        toolbar.addView(tabIndicator);
        toolbar.addView(nextTabButton);
        toolbar.addView(newTabButton);
        toolbar.addView(addressBar);
        toolbar.addView(menuButton);
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
        settings.setJavaScriptEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        // Hardened File System & Resource Isolation
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);

        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        // High-Performance Engine Tuning Optimization
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Background Autoplay Optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }

        // Append speculative pre-rendering if utilizing a modern layout bridge
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true); // Lets Chromium parallelize security sweeps
        }

        // Securely handle modern mixed HTTPS/HTTP layout assets dynamically
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
    }


    private WebChromeClient createWebChromeClient() {
        return new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                if (view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
                customView = view;
                customViewCallback = callback;
                toolbar.setVisibility(View.GONE);
                browserContainer.setVisibility(View.GONE);
                root.addView(customView);
            }

            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                String[] resources = request.getResources();
                for (String resource : resources) {
                    if (resource.equals(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE) || 
                        resource.equals(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        request.deny();
                        return;
                    }
                }
                super.onPermissionRequest(request);
            }


            @Override
            public void onHideCustomView() {
                toolbar.setVisibility(View.VISIBLE);
                browserContainer.setVisibility(View.VISIBLE);
                if (customView != null) {
                    root.removeView(customView);
                }
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                }
                customView = null;
                customViewCallback = null;
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = filePathCallback;
                try {
                    filePickerLauncher.launch("*/*");
                } catch (Exception e) {
                    fileChooserCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback callback) {
                // Deny geolocation permissions automatically to keep container security maintenance-free
                callback.invoke(origin, false, false);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                createNewTab();
                WebView newWebView = getCurrentWebView();
                if (newWebView != null) {
                    WebViewTransport transport = (WebViewTransport) resultMsg.obj;
                    transport.setWebView(newWebView);
                    resultMsg.sendToTarget();
                    return true;
                }
                return false;
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                String url = view.getUrl();
                if (url != null && title != null && !title.isEmpty()) {
                    synchronized (pageTitles) {
                        pageTitles.put(url, title);
                    }
                    savePageTitles();
                }
            }
        };
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

    private WebViewClient createWebViewClient() {
        return new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url != null) {
                    String urlString = url.toString();
                    synchronized (filterEngine) {
                        if (filterEngine.shouldBlock(urlString)) {
                            return new WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url != null) {
                    String urlString = url.toString();

                    // Route magnet links and torrent files to external download managers
                    if (urlString.startsWith("magnet:") || urlString.endsWith(".torrent")) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW, url);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            view.getContext().startActivity(intent);
                            return true; // Tells WebView we handled the link externally
                        } catch (Exception e) {
                            android.widget.Toast.makeText(view.getContext(),
                                "No app found to handle torrent/magnet links",
                                android.widget.Toast.LENGTH_SHORT).show();
                            return true;
                        }
                    }

                    // Fail-Safe: Intercept App Intents & Custom Deep-Links (Point #4/Engine Safety Patch)
                    // This stops native rendering engines from crashing when web apps force a redirect
                    if (!urlString.startsWith("http://") && !urlString.startsWith("https://") && !urlString.startsWith("javascript:")) {
                        try {
                            Intent intent = Intent.parseUri(urlString, Intent.URI_INTENT_SCHEME);
                            if (intent != null) {
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                view.getContext().startActivity(intent);
                                return true; // Block the WebView from trying to compile a custom URI scheme
                            }
                        } catch (Exception e) {
                            // If no deep-link app handler exists on device, drop the unrenderable URL silently
                            return true;
                        }
                    }
                }
                return false; // Force internal loading of standard web addresses instead of dropping back to super
            }


            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (view == getCurrentWebView() && addressBar != null) {
                    addressBar.setText((url == null || url.isEmpty() || url.equals("about:blank")) ? "" : url);
                }

                if (url != null && !url.isEmpty() && !url.equals("about:blank") 
                        && !url.startsWith("chrome-error://") && !url.startsWith("data:") && !url.startsWith("file://")) {
                    if (history.isEmpty() || !history.get(history.size() - 1).equals(url)) {
                        history.add(url);
                        while (history.size() > MAX_HISTORY) {
                            history.remove(0);
                        }
                        saveHistory();
                    }
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith("http")) {
                    android.net.Uri uri = android.net.Uri.parse(url);
                    String host = uri.getHost();
                    if (host != null) {
                        String js = "javascript:(function() {" +
                            "var host = \"" + host + "\";" +
                            "var savedUser = SpoonVault.getSavedUser(host);" +
                            "var savedPass = SpoonVault.getSavedPass(host);" +
                            "var passFields = document.querySelectorAll(\"input[type='password']\");" +
                            "if (passFields.length > 0) {" +
                            "   var passField = passFields[0];" +
                            "   var form = passField.form;" +
                            "   var userField = null;" +
                            "   if (form) {" +
                            "       var inputs = form.querySelectorAll(\"input\");" +
                            "       for (var i = 0; i < inputs.length; i++) {" +
                            "           if (inputs[i] !== passField && (inputs[i].type === 'text' || inputs[i].type === 'email')) {" +
                            "               userField = inputs[i]; break;" +
                            "           }" +
                            "       }" +
                            "       form.addEventListener('submit', function() {" +
                            "           SpoonVault.saveLogin(host, userField ? userField.value : '', passField.value);" +
                            "       });" +
                            "   }" +
                            "   if (savedUser && savedPass) {" +
                            "       if (userField) userField.value = savedUser;" +
                            "       passField.value = savedPass;" +
                            "   }" +
                            "}" +
                            "})()";
                        view.evaluateJavascript(js, null);
                    }

                    // Cosmetic Filter Engine: Inject CSS rules to collapse blocked element structures natively
                    String cosmeticJs = "javascript:(function() {" +
                        "var selectors = [" +
                        "   '.ad-box', '.ad-banner', '.adsbygoogle', '[id^=\"google_ads_\"]', " +
                        "   '.ad-container', '.ad_wrapper', '#carbonads'" +
                        "];" +
                        "var style = document.createElement('style');" +
                        "style.innerHTML = selectors.join(', ') + ' { display: none !important; collapse: homework !important; height: 0px !important; margin: 0px !important; padding: 0px !important; }';" +
                        "document.head.appendChild(style);" +
                        "})()";
                    view.evaluateJavascript(cosmeticJs, null);
                }
                android.webkit.CookieManager.getInstance().flush();
                if (view == getCurrentWebView() && addressBar != null) {
                    addressBar.setText((url == null || url.isEmpty() || url.equals("about:blank")) ? "" : url);
                    addressBar.dismissDropDown();
                }
                updateTabIndicator();
                saveOpenTabs();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                String url = null;
                try {
                    url = view.getUrl();
                } catch (Exception ignored) {}

                int index = tabs.indexOf(view);
                if (index >= 0) {
                    view.stopLoading();
                    view.removeAllViews();
                    view.destroy();
                    
                    WebView replacement = createConfiguredWebView();
                    tabs.set(index, replacement);

                    if (index == currentTab) {
                        browserContainer.removeAllViews();
                        browserContainer.addView(replacement);
                    }
                    if (url != null && !url.isEmpty()) {
                        replacement.loadUrl(url);
                    }
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                if (!request.isForMainFrame()) return;
                Toast.makeText(MainActivity.this, "Page load failed", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onReceivedHttpError(WebView view, android.webkit.WebResourceRequest request, WebResourceResponse errorResponse) {
                if (!request.isForMainFrame()) return;
                android.util.Log.w("SpoonBrowser", "HTTP Error: " + errorResponse.getStatusCode());
            }

            @Override
            public void onReceivedSslError(WebView view, final android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
                // Build a native dialog to warn the user, giving them explicit choice to bypass
                final androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this);
                
                String message = "The security certificate for this website is invalid or untrusted.\n\n"
                               + "Error Code: " + error.getPrimaryError() + "\n"
                               + "URL: " + error.getUrl() + "\n\n"
                               + "Do you want to proceed anyway at your own risk?";
                
                builder.setTitle("Security Certificate Warning");
                builder.setMessage(message);
                
                // If they insist, allow the engine to proceed
                builder.setPositiveButton("Proceed Anyway", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        handler.proceed();
                    }
                });
                
                // If they back out, drop the network line instantly
                builder.setNegativeButton("Go Back", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        handler.cancel();
                    }
                });

                // Ensure that tapping outside the dialog cancels the request safely
                builder.setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(android.content.DialogInterface dialog) {
                        handler.cancel();
                    }
                });

                // Display the warning overlay contextually on the main thread
                androidx.appcompat.app.AlertDialog dialog = builder.create();
                dialog.show();
            }


            @Override
            public void onSafeBrowsingHit(WebView view, WebResourceRequest request, int threatType, SafeBrowsingResponse callback) {
                callback.backToSafety(true);
            }
        };
    }

    private WebView createConfiguredWebView() {
        WebView webView = new WebView(this);
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        webView.setLayoutParams(webParams);

        // Configure default native web settings
        configureWebSettings(webView.getSettings());

        // Native Anti-Tracking: Block third-party cross-site cookies natively in the engine
        android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, false);
        }

        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public String getSavedUser(String host) {
                return secureCredentialManager != null ? secureCredentialManager.getUsername(host) : "";
            }
            @android.webkit.JavascriptInterface
            public String getSavedPass(String host) {
                return secureCredentialManager != null ? secureCredentialManager.getPassword(host) : "";
            }
            @android.webkit.JavascriptInterface
            public void saveLogin(String host, String user, String pass) {
                if (secureCredentialManager != null && user != null && !user.isEmpty() && pass != null && !pass.isEmpty()) {
                    runOnUiThread(() -> {
                        new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle("Save Password?")
                            .setMessage("Would you like Spoon Browser to save your credentials for " + host + "?")
                            .setPositiveButton("Save", (dialog, which) -> secureCredentialManager.saveCredentials(host, user, pass))
                            .setNegativeButton("Never", null)
                            .show();
                    });
                }
            }
        }, "SpoonVault");

        webView.setWebChromeClient(createWebChromeClient());
        webView.setOnLongClickListener(createImageLongClickListener(webView));
        webView.setWebViewClient(createWebViewClient());

        // Configure system intent delegation for downloads (e.g., GitHub raw files)
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                try {
                    // Ask the OS to present all capable handlers (including external download managers like ADM/1DM)
                    android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                    
                    // Pass along the MIME type if available to help external apps identify the file stream type
                    if (mimeType != null && !mimeType.isEmpty() && !mimeType.contains("text/plain")) {
                        intent.setDataAndType(android.net.Uri.parse(url), mimeType);
                    }
                    
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    android.content.Intent chooser = android.content.Intent.createChooser(intent, "Download File via...");
                    chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    
                    startActivity(chooser);
                } catch (Exception e) {
                    try {
                        // Ultimate raw fallback if strict intent construction fails
                        android.content.Intent fallback = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                        fallback.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(fallback);
                    } catch (Exception fatal) {
                        Toast.makeText(MainActivity.this, "No download handler found on device", Toast.LENGTH_SHORT).show();
                    }
                }
                return;
            }
        });

        return webView;
    }

    private void createNewTab() {
        WebView webView = createConfiguredWebView();
        tabs.add(webView);
        currentTab = tabs.size() - 1;

        if (browserContainer != null) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            );
            // Natively attach to layout tree immediately, default to hidden
            webView.setVisibility(View.GONE);
            browserContainer.addView(webView, params);
        }
        
        switchToTab(currentTab);
    }

    private void switchToTab(int index) {
        if (tabs == null || index < 0 || index >= tabs.size()) return;

        currentTab = index;
        saveCurrentTab();
        updateTabIndicator();

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
        if (tabs.isEmpty()) return;
        ArrayList<BrowserItem> items = buildTabItems();
        BrowserItemAdapter adapter = new BrowserItemAdapter(this, items);
        ListView listView = new ListView(this);
        listView.setAdapter(adapter);

    // 2. Build a responsive instruction hint banner based on screen width profile
    TextView hintTextView = new TextView(this);
    boolean isTablet = getResources().getConfiguration().smallestScreenWidthDp >= 600;
    if (isTablet) {
        hintTextView.setText("💡 Tip: Long-press an item to close it / Tap to switch views");
    } else {
        hintTextView.setText("💡 Tip: Long-press a tab item to instantly close it");
    }
    hintTextView.setTextColor(Color.parseColor("#8A8A8A"));
    hintTextView.setTextSize(13);
    hintTextView.setGravity(Gravity.CENTER_HORIZONTAL);
    hintTextView.setPadding(0, 0, 0, dp(14));
    hintTextView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    dialogContainer.addView(hintTextView);

    // 3. Configure the main ListView container
    ListView listView = new ListView(this);
    listView.setBackgroundColor(Color.parseColor("#141414"));
    listView.setDivider(new ColorDrawable(Color.parseColor("#252525")));
    listView.setDividerHeight(dp(1));
    dialogContainer.addView(listView);

    // 4. Extract string titles or URLs from the active WebView tab list
    ArrayList<String> tabTitles = new ArrayList<>();
    if (tabs != null) {
        for (int i = 0; i < tabs.size(); i++) {
            WebView webView = tabs.get(i);
            String title = (webView != null) ? webView.getTitle() : null;
            if (title == null || title.isEmpty()) {
                title = (webView != null && webView.getUrl() != null) ? webView.getUrl() : "New Tab";
            }
            tabTitles.add(title);
        }
    }

    // Map your text list to the polished custom item layout adapter
    ArrayAdapter<String> tabAdapter = new ArrayAdapter<>(this, R.layout.modern_list_item, tabTitles);
    listView.setAdapter(tabAdapter);

    // 5. Build the dialog frame so click listeners can reference it
    final AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Active Tabs")
            .setView(dialogContainer)
            .create();

    // 6. Hook up navigation click actions using our mapped index parameters
    listView.setOnItemClickListener((parent, view, position, id) -> {
        currentTab = position;
        if (tabs != null && position < tabs.size()) {
            switchToTab(position); // Mapped backend view switcher
        }
        dialog.dismiss();
    });

    // Unified long-press action to close a tab cleanly (Works perfectly on mobile & tablet)
    listView.setOnItemLongClickListener((parent, view, position, id) -> {
        if (tabs != null && position < tabs.size()) {
            // 1. Let backend handle array removal, view removal, and memory cleanup safely
            closeTab(position); 

            // 2. Sync the local dialog layout tracking array strings
            tabTitles.remove(position);

            // 3. Bounds protection check
            if (currentTab >= tabs.size()) {
                currentTab = Math.max(0, tabs.size() - 1);
            }

            // 4. Update badge UI using the native method verified at line 1697
            updateTabBadgeCount();

            tabAdapter.notifyDataSetChanged();

            // 5. Safely switch to remaining tab if any are left open
            if (!tabs.isEmpty()) {
                switchToTab(currentTab);
            }
        }

        dialog.dismiss();
        if (tabs != null && !tabs.isEmpty()) {
            showTabSwitcher(); // Clean redraw loop
        }
        return true;
    });

    // 7. Style the dialog window frame on launch
    dialog.setOnShowListener(d -> {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new GradientDrawable() {{
                setColor(Color.parseColor("#141414"));
                setCornerRadius(dp(24));
            }});

            int titleId = getResources().getIdentifier("alertTitle", "id", "android");
            TextView titleView = dialog.findViewById(titleId);
            if (titleView != null) {
                titleView.setTextColor(Color.WHITE);
                titleView.setTextSize(18);
                titleView.setTypeface(Typeface.DEFAULT_BOLD);
                titleView.setPadding(dp(8), dp(8), 0, dp(6));
            }
        }
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

    private WebView getCurrentWebView() {
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            prefs.edit().putString(KEY_BOOKMARKS, String.join("\n", bookmarks)).apply();
        }
    }

    private void saveHistory() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            prefs.edit().putString(KEY_HISTORY, String.join("\n", history)).apply();
        }
    }

    // --- Advanced High-Speed Content Filter Engine ---
    private static class ContentFilterEngine {
        public final java.util.Set<String> blockPatterns = new java.util.HashSet<>();
        public final java.util.Set<String> whitelistPatterns = new java.util.HashSet<>();

        public void clear() {
            blockPatterns.clear();
            whitelistPatterns.clear();
        }

        public void addRule(String rule) {
            if (rule == null || rule.isEmpty()) return;
            if (rule.contains("##")) return; 
            
            boolean isWhitelist = rule.startsWith("@@");
            String pattern = isWhitelist ? rule.substring(2) : rule;

            int optionIdx = pattern.indexOf('$');
            if (optionIdx != -1) {
                pattern = pattern.substring(0, optionIdx);
            }

            pattern = pattern.replace("||", "").replace("^", "");

            if (isWhitelist) {
                whitelistPatterns.add(pattern.toLowerCase());
            } else {
                blockPatterns.add(pattern.toLowerCase());
            }
        }

        public boolean shouldBlock(String urlString) {
            if (urlString == null) return false;
            String lowerUrl = urlString.toLowerCase();

            for (String pattern : whitelistPatterns) {
                if (matchPattern(lowerUrl, pattern)) return false;
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

    private final ContentFilterEngine filterEngine = new ContentFilterEngine();

    private void refreshFilterLists() {
        new Thread(() -> {
            if (filterLists == null || filterLists.isEmpty()) {
                filterLists = new ArrayList<>();
                filterLists.add("https://easylist.to/easylist/easylist.txt");
                filterLists.add("https://easylist.to/easylist/easyprivacy.txt");
            }

            ContentFilterEngine newEngine = new ContentFilterEngine();
            for (String filterUrl : filterLists) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new java.net.URL(filterUrl).openStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("!")) continue;
                        newEngine.addRule(line);
                    }
                } catch (Exception e) {
                    android.util.Log.e("SpoonBlocker", "Filter download failed: " + filterUrl);
                }
            }

            synchronized (filterEngine) {
                filterEngine.clear();
                filterEngine.blockPatterns.addAll(newEngine.blockPatterns);
                filterEngine.whitelistPatterns.addAll(newEngine.whitelistPatterns);
            }
            prefs.edit().putLong(KEY_FILTER_REFRESH_TIME, System.currentTimeMillis()).apply();
        }).start();
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            prefs.edit().putString(KEY_FILTER_LISTS, String.join("\n", filterLists)).apply();
        }
    }

    private void saveOpenTabs() {
        ArrayList<String> urls = new ArrayList<>();
        for (WebView tab : tabs) {
            String url = tab.getUrl();
            if (url != null && !url.isEmpty() && !url.equals("about:blank")) {
                urls.add(url);
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            prefs.edit().putString(KEY_OPEN_TABS, String.join("\n", urls)).apply();
        }
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
                            new Thread(() -> {
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
                            }).start();
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
        synchronized (blockedDomains) {
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
                            + "\nHistory: " + history.size() + "\nBlocked Domains: " + blockedDomains.size()
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
        // High-Performance Lazy Initialization String Caching (Point #3)
        if (cachedHomeHtml == null) {
            cachedHomeHtml = "<html>" +
                    "<body style='margin:0;background:#000;color:white;font-family:sans-serif;text-align:center;'>" +
                    "<div style='padding-top:20%;'>" +
                    "<h1 style='font-size:48px;margin-bottom:40px;'>Spoon Browser</h1>" +
                    "<input id='q' type='text' placeholder='Search privately...' style='width:72%;padding:20px;border:none;border-radius:18px;background:#1f1f1f;color:white;font-size:18px;outline:none;'/>" +
                    "</div>" +
                    "<script>" +
                    "function goSearch(){" +
                    "var q=document.getElementById(\"q\").value;" +
                    "window.location.href='https://duckduckgo.com/?q='+encodeURIComponent(q);" +
                    "}" +
                    "document.getElementById('q').addEventListener('keydown',function(e){" +
                    "if(e.key==='Enter'){goSearch();}" +
                    "});" +
                    "</script>" +
                    "</body></html>";
        }

        WebView wv = getCurrentWebView();
        if (wv != null) {
            wv.loadDataWithBaseURL("about:blank", cachedHomeHtml, "text/html", "UTF-8", null);
        }
    }


    private void updateAddressBarSuggestions(String query) {
        addressBarAdapter.clear();
        if (query == null || query.trim().isEmpty()) return;

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
        if (addressBarAdapter.getCount() > 0) {
            addressBar.showDropDown();
        } else {
            addressBar.dismissDropDown();
        }
    }

    private void navigate() {
        String input = addressBar.getText().toString().trim();
        if (input.isEmpty()) return;

        String lowerInput = input.toLowerCase();
        if (lowerInput.startsWith("javascript:") ||
            lowerInput.startsWith("file:") ||
            lowerInput.startsWith("content:") ||
            lowerInput.startsWith("intent:")) {

            Toast.makeText(this, "Blocked unsafe URL", Toast.LENGTH_SHORT).show();
            return;
        }

        String url;
        if (input.contains(".") && !input.contains(" ")) {
            url = (input.startsWith("http://") || input.startsWith("https://")) ? input : "https://" + input;
        } else {
            url = "https://duckduckgo.com/?q=" + Uri.encode(input);
        }

        openUrl(url);
    }
}
