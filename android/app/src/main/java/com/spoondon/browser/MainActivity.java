package com.spoondon.browser;

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
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_NoActionBar);
        super.onCreate(savedInstanceState);

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
            if (restoreSession()) {
                return;
            }
            createNewTab();
            showHome();
            setIntent(new Intent());
        }
    }

    @Override
    public void onStop() {
        if (!clearSessionOnExit) {
            saveOpenTabs();
            saveCurrentTab();
        }
        super.onStop();
    }

    private boolean restoreSession() {
        try {
            String savedTabs = prefs.getString(KEY_OPEN_TABS, "");
            if (savedTabs == null || savedTabs.isEmpty()) {
                return false;
            }

            for (String url : savedTabs.split("\n")) {
                if (url == null || url.trim().isEmpty() || url.equals("about:blank")) {
                    continue;
                }
                if (!url.contains(".") && !url.startsWith("http")) {
                    continue;
                }

                WebView webView = createConfiguredWebView();
                tabs.add(webView);
                webView.loadUrl(url.trim());
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
            String url = addressBarAdapter.getItem(position);
            if (url == null) return;
            suppressSuggestions = true;
            addressBar.setText(url);
            addressBar.setSelection(url.length());
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
        settings.setSafeBrowsingEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);

        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
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
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
                handler.cancel();
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

        configureWebSettings(webView.getSettings());
        webView.setWebChromeClient(createWebChromeClient());
        webView.setOnLongClickListener(createImageLongClickListener(webView));
        webView.setWebViewClient(createWebViewClient());

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    String resolvedMimeType = (mimeType == null || mimeType.isEmpty()) ? "application/octet-stream" : mimeType;
                    intent.setDataAndType(Uri.parse(url), resolvedMimeType);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(intent);
                } catch (Exception e) {
                    try {
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
            browserContainer.removeAllViews(); 
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.MATCH_PARENT
            );
            browserContainer.addView(webView, params);
        }
        updateTabIndicator();
    }

    private void switchToTab(int index) {
        if (tabs == null || index < 0 || index >= tabs.size()) return;

        currentTab = index;
        saveCurrentTab();
        updateTabIndicator();

        if (browserContainer != null) {
            browserContainer.removeAllViews();
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.MATCH_PARENT
            );
            
            WebView currentWebView = getCurrentWebView();
            if (currentWebView != null) {
                browserContainer.addView(currentWebView, params);
                
                String url = currentWebView.getUrl();
                if (addressBar != null) {
                    addressBar.setText((url == null || url.isEmpty() || "about:blank".equals(url)) ? "" : url);
                }
            }
        }

        if (addressBar != null) {
            addressBar.dismissDropDown();
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

        webView.stopLoading();
        webView.clearHistory();
        webView.loadUrl("about:blank");
        webView.removeAllViews();
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

        listView.setOnItemClickListener((parent, view, which, id) -> switchToTab(which));
        listView.setOnItemLongClickListener((parent, view, which, id) -> {
            closeTab(which);
            items.remove(which);
            adapter.notifyDataSetChanged();
            return true;
        });

        new AlertDialog.Builder(this).setTitle("Tabs").setView(listView).create().show();
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
            new AlertDialog.Builder(this)
                    .setTitle("Spoon Browser")
                    .setMessage("Version: " + getAppVersion() + "\n\n"
                            + "Tabs: " + tabs.size() + "\nBookmarks: " + bookmarks.size()
                            + "\nHistory: " + history.size() + "\nBlocked Domains: " + blockedDomains.size()
                            + "\n\nBuilt one green commit at a time.\nDesigned to evolve dynamically with Android WebView.\n\n-with love, Plaban.")
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
        String homePage = "<html>" +
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

        WebView wv = getCurrentWebView();
        if (wv != null) {
            wv.loadDataWithBaseURL(null, homePage, "text/html", "UTF-8", null);
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
