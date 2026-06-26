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
    private Button tabBadgeButton;
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(android.graphics.Color.BLACK);
        }

        setContentView(R.layout.activity_main);
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
            popup.getMenu().add("About");
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
        tabIndicator.setOnLongClickListener(v -> {
            showTabSwitcher();
            return true;
        });

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
        addressBar.setThreshold(0);
        
        // Modernized Address Bar Background styling - Isolated unique variable name
        android.graphics.drawable.GradientDrawable customAddressBg = new android.graphics.drawable.GradientDrawable();
        customAddressBg.setColor(Color.parseColor("#222222"));
        customAddressBg.setCornerRadius(dp(20)); // Perfectly rounded capsule shape
        addressBar.setBackground(customAddressBg);


        addressBar.setPadding(dp(16), dp(8), dp(16), dp(8));

        // CRITICAL FIX: Give address bar dynamic layout weight so it stretches elegantly
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        addressParams.setMargins(dp(6), 0, dp(6), 0);
        addressBar.setLayoutParams(addressParams);

        // Keep your existing adapter and text watcher setups underneath...


        addressBarAdapter = new ArrayAdapter<String>(this, R.layout.modern_list_item, new ArrayList<>()) {
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

        // STYLING ADDITION: Apply matching dark, rounded design to the dropdown panel itself
        addressBar.setAdapter(addressBarAdapter);
        addressBar.setDropDownBackgroundDrawable(new android.graphics.drawable.GradientDrawable() {{
            setColor(android.graphics.Color.parseColor("#1F1F1F")); // Subtle contrast dark accent
            setCornerRadius(dp(16));                               // Premium rounded corner profiles
        }});
        addressBar.setDropDownVerticalOffset(dp(4));               // Floating gap separation

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
                customView.setKeepScreenOn(true);
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
                    String host = url.getHost();
                    if (host != null && isBlockedDomain(host.toLowerCase())) {
                        return new WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
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

        webView.setWebChromeClient(createWebChromeClient());
        webView.setOnLongClickListener(createImageLongClickListener(webView));
        webView.setWebViewClient(createWebViewClient());

        // Configure system intent delegation for downloads (e.g., GitHub raw files)
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    // Use standard fallback if mimeType is broad, empty, or plain text
                    String resolvedMimeType = (mimeType == null || mimeType.isEmpty() || mimeType.contains("text/plain")) ? "*/*" : mimeType;
                    intent.setDataAndType(Uri.parse(url), resolvedMimeType);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(intent);
                } catch (Exception e) {
                    try {
                        // Fallback: Drop strict MIME enforcement completely and let OS handle via URL routing schema
                        Intent fallbackIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(fallbackIntent);
                    } catch (Exception fatal) {
                        Toast.makeText(MainActivity.this, "No external download application found", Toast.LENGTH_SHORT).show();
                    }
                }
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
        updateTabBadgeCount();
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
    // 1. Create a vertical container layout to hold both our hint and the list
    LinearLayout dialogContainer = new LinearLayout(this);
    dialogContainer.setOrientation(LinearLayout.VERTICAL);
    dialogContainer.setBackgroundColor(Color.parseColor("#141414"));
    dialogContainer.setPadding(dp(16), dp(12), dp(16), dp(16));

    // 2. Build a gorgeous, subtle instruction hint banner
    TextView hintTextView = new TextView(this);
    hintTextView.setText("💡 Tip: Long-press a tab item to instantly close it");
    hintTextView.setTextColor(Color.parseColor("#8A8A8A")); // Soft muted gray so it doesn't shout
    hintTextView.setTextSize(13);
    hintTextView.setGravity(Gravity.CENTER_HORIZONTAL);
    hintTextView.setPadding(0, 0, 0, dp(14)); // Generous separation gap before the list starts
    hintTextView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    
    // Add the hint to the top of our layout container
    dialogContainer.addView(hintTextView);

    // 3. Configure the main ListView container
    ListView listView = new ListView(this);
    listView.setBackgroundColor(Color.parseColor("#141414"));
    listView.setDivider(new ColorDrawable(Color.parseColor("#252525")));
    listView.setDividerHeight(dp(1));
    
    // Append the ListView right beneath our tip banner inside the layout container
    dialogContainer.addView(listView);

    // 4. Map your Tab adapter to the list view (Keep your existing adapter assignment line here)
    // For example: listView.setAdapter(new ArrayAdapter<>(this, R.layout.modern_list_item, tabTitles));
    
    // 5. Build and launch the premium Dark Dialog modal frame
    AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Active Tabs")
            .setView(dialogContainer) // Swap raw listView for our newly built compound dialogContainer!
            .create();

    // 6. Style the dialog window frame on launch
    dialog.setOnShowListener(d -> {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new GradientDrawable() {{
                setColor(Color.parseColor("#141414"));
                setCornerRadius(dp(24)); // Smooth rounded overlay card
            }});
            
            // Enforce sharp crisp white header styling
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

    private void rebuildBlockedDomains() {
        synchronized (blockedDomains) {
            blockedDomains.clear();
            for (String rule : rawFilterRules) {
                blockedDomains.add(rule.toLowerCase());
            }
        }
    }

    private void refreshFilterLists() {
        new Thread(() -> {
            HashSet<String> newRules = new HashSet<>();
            for (String filterUrl : filterLists) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new URL(filterUrl).openStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("!")) continue;
                        if (line.startsWith("||")) {
                            int end = line.indexOf('^');
                            if (end > 2) {
                                newRules.add(line.substring(2, end));
                            }
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.e("SpoonBlocker", "Filter download failed: " + filterUrl);
                }
            }
            synchronized (blockedDomains) {
                rawFilterRules.clear();
                rawFilterRules.addAll(newRules);
                rebuildBlockedDomains();
            }
            prefs.edit().putLong(KEY_FILTER_REFRESH_TIME, System.currentTimeMillis()).apply();
        }).start();
    }

    private boolean isBlockedDomain(String host) {
        if (host == null) return false;
        synchronized (blockedDomains) {
            if (blockedDomains.contains(host)) return true;
            int idx = host.indexOf('.');
            while (idx != -1) {
                String sub = host.substring(idx + 1);
                if (blockedDomains.contains(sub)) return true;
                idx = host.indexOf('.', idx + 1);
            }
        }
        return false;
    }

    private void saveFilterLists() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            prefs.edit().putString(KEY_FILTER_LISTS, String.join("\n", filterLists)).apply();
        }
        rebuildBlockedDomains();
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
        String[] options = {"View Subscriptions", "Add EasyList", "Add EasyPrivacy"};
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
