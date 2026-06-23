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
import com.getcapacitor.BridgeActivity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;

public class MainActivity extends BridgeActivity {

    private static final String PREFS_NAME = "SpoonBrowserPrefs";
    private static final String KEY_OPEN_TABS = "open_tabs_v2";
    private static final String KEY_CURRENT_TAB = "current_tab_index_v2";
    private static final String KEY_HISTORY = "browser_history";
    private static final String KEY_PAGE_TITLES = "page_titles";
    private static final String KEY_FILTER_LISTS = "filter_lists";
    private static final int MAX_HISTORY = 200;

    private LinearLayout root;
    private LinearLayout toolbar;
    private LinearLayout browserContainer;
    private AutoCompleteTextView addressBar;
    private ArrayAdapter<String> addressBarAdapter;
    private TextView tabIndicator;
    private Button backButton;
    private Button forwardButton;
    private Button newTabButton;
    private Button closeTabButton;
    private Button menuButton;

    private final ArrayList<WebView> tabs = new ArrayList<>();
    private int currentTab = -1;
    private SharedPreferences prefs;
    private final ArrayList<String> history = new ArrayList<>();
    private final HashMap<String, String> pageTitles = new HashMap<>();
    private final HashSet<String> rawFilterRules = new HashSet<>();

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private WebView.HitTestResult pendingLongClickResult;
    private androidx.activity.result.ActivityResultLauncher<String> filePickerLauncher;
    private android.webkit.ValueCallback<Uri[]> fileChooserCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        filePickerLauncher = registerForActivityResult(
           new androidx.activity.result.ActivityResultContracts.GetContent(),
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
        handleIncomingIntent(intent);
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
            if (!restoreSession() || tabs.isEmpty()) {
                tabs.clear();
                createNewTab();
                showHome();
            }
            setIntent(new Intent());
        }
    }
    private void setupRootLayout() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));

        browserContainer = new LinearLayout(this);
        browserContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
        );
        browserContainer.setLayoutParams(containerParams);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return windowInsets;
        });
    }

    private void createToolbarViews() {
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setBackgroundColor(Color.parseColor("#121212"));
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(6), dp(8), dp(6));
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        backButton = createNavButton("◀");
        forwardButton = createNavButton("▶");
        newTabButton = createNavButton("＋");
        closeTabButton = createNavButton("✕");
        menuButton = createNavButton("⋮");

        tabIndicator = new TextView(this);
        tabIndicator.setTextColor(Color.WHITE);
        tabIndicator.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tabIndicator.setGravity(Gravity.CENTER);
        tabIndicator.setPadding(dp(8), 0, dp(8), 0);

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
                        results.values = addressBarAdapter;
                        results.count = addressBarAdapter.getCount();
                        return results;
                    }
                    @Override
                    protected void publishResults(CharSequence constraint, FilterResults results) {}
                };
            }
        };
        addressBar.setAdapter(addressBarAdapter);

        addressBar.setOnItemClickListener((parent, view, position, id) -> {
            String url = addressBarAdapter.getItem(position);
            if (url != null) {
                addressBar.setText(url);
                addressBar.setSelection(url.length());
                addressBar.dismissDropDown();
                addressBar.post(this::navigate);
            }
        });

        addressBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (addressBar.hasFocus()) {
                    updateSuggestions(s.toString());
                }
            }
        });

        GradientDrawable addressBg = new GradientDrawable();
        addressBg.setColor(Color.parseColor("#222222"));
        addressBg.setCornerRadius(dp(20));
        addressBar.setBackground(addressBg);
        addressBar.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        inputParams.setMargins(dp(6), 0, dp(6), 0);
        addressBar.setLayoutParams(inputParams);

        toolbar.addView(backButton);
        toolbar.addView(forwardButton);
        toolbar.addView(addressBar);
        toolbar.addView(tabIndicator);
        toolbar.addView(newTabButton);
        toolbar.addView(closeTabButton);
        toolbar.addView(menuButton);
    }

    private Button createNavButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(Color.TRANSPARENT);
        btn.setMinWidth(dp(36));
        btn.setMinimumWidth(dp(36));
        btn.setPadding(0, 0, 0, 0);
        btn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));
        return btn;
    }
    private void setupToolbarListeners() {
        backButton.setOnClickListener(v -> {
            WebView webView = getCurrentWebView();
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
            }
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

        closeTabButton.setOnClickListener(v -> closeTab(currentTab));

        addressBar.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                navigate();
                return true;
            }
            return false;
        });

        tabIndicator.setOnClickListener(v -> showTabSwitcherDialog());
    }

    private void setupMenuButton() {
        menuButton.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(MainActivity.this, menuButton);
            popup.getMenu().add("History");
            popup.getMenu().add("Clear History");
            popup.getMenu().add("Filter Lists");

            popup.setOnMenuItemClickListener(item -> {
                switch (item.getTitle().toString()) {
                    case "History":
                        showHistoryDialog();
                        return true;
                    case "Clear History":
                        history.clear();
                        saveHistory();
                        Toast.makeText(MainActivity.this, "History cleared", Toast.LENGTH_SHORT).show();
                        return true;
                    case "Filter Lists":
                        showFilterListsDialog();
                        return true;
                    default:
                        return false;
                }
            });
            popup.show();
        });
    }

    private void setupBackButtonHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (customView != null) {
                    WebChromeClient chromeClient = getCurrentWebView() != null ? getCurrentWebView().getWebChromeClient() : null;
                    if (chromeClient != null) {
                        chromeClient.onHideCustomView();
                    }
                    return;
                }
                WebView webView = getCurrentWebView();
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else if (tabs.size() > 1) {
                    closeTab(currentTab);
                } else {
                    finish();
                }
            }
        });
    }

    private void updateNavButtonVisibility() {
        WebView webView = getCurrentWebView();
        if (webView == null) return;

        if (webView.canGoBack()) {
            backButton.setVisibility(View.VISIBLE);
        } else {
            backButton.setVisibility(View.GONE);
        }

        if (webView.canGoForward()) {
            forwardButton.setVisibility(View.VISIBLE);
        } else {
            forwardButton.setVisibility(View.GONE);
        }
    }

    private void createNewTab() {
        WebView webView = new WebView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        );
        webView.setLayoutParams(params);

        configureWebSettings(webView.getSettings());
        webView.setWebChromeClient(createWebChromeClient());
        webView.setOnLongClickListener(createImageLongClickListener(webView));
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

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

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("magnet:") || url.endsWith(".torrent")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        view.getContext().startActivity(intent);
                        return true;
                    } catch (Exception e) {
                        Toast.makeText(view.getContext(), "No app found to handle magnet links", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                }
                return false;
            }

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
                updateNavButtonVisibility();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                android.webkit.CookieManager.getInstance().flush();
                if (view == getCurrentWebView() && addressBar != null) {
                    addressBar.setText((url == null || url.isEmpty() || url.equals("about:blank")) ? "" : url);
                    addressBar.dismissDropDown();
                }
                updateNavButtonVisibility();
                saveTabsState();
            }
        });

        tabs.add(webView);
        currentTab = tabs.size() - 1;
        updateTabIndicator();

        if (browserContainer != null) {
            browserContainer.removeAllViews();
            browserContainer.addView(webView);
        }
    }
    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebSettings(WebSettings settings) {
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
    }

    private WebChromeClient createWebChromeClient() {
        return new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;

                if (toolbar != null) toolbar.setVisibility(View.GONE);
                if (browserContainer != null) browserContainer.setVisibility(View.GONE);

                ViewGroup decor = (ViewGroup) getWindow().getDecorView();
                decor.addView(customView, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;

                ViewGroup decor = (ViewGroup) getWindow().getDecorView();
                decor.removeView(customView);
                customView = null;

                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                }

                if (toolbar != null) toolbar.setVisibility(View.VISIBLE);
                if (browserContainer != null) browserContainer.setVisibility(View.VISIBLE);
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, android.webkit.ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
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
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(newWebView);
                resultMsg.sendToTarget();
                return true;
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
            int type = result.getType();
            if (type == WebView.HitTestResult.IMAGE_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                pendingLongClickResult = result;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Image Options")
                        .setItems(new CharSequence[]{"Open Image in New Tab", "Download Image"}, (dialog, which) -> {
                            if (pendingLongClickResult == null) return;
                            String imageUrl = pendingLongClickResult.getExtra();
                            if (imageUrl != null) {
                                if (which == 0) {
                                    createNewTab();
                                    openUrl(imageUrl);
                                } else if (which == 1) {
                                    if (webView.getDownloadListener() != null) {
                                        webView.getDownloadListener().onDownloadStart(imageUrl, webView.getSettings().getUserAgentString(), null, null, 0);
                                    }
                                }
                            }
                        })
                        .show();
                return true;
            }
            return false;
        };
    }

    private void switchToTab(int index) {
        if (tabs == null || index < 0 || index >= tabs.size()) return;

        currentTab = index;
        saveCurrentTab();
        updateTabIndicator();

        if (browserContainer != null) {
            browserContainer.removeAllViews();
            WebView currentWebView = getCurrentWebView();
            if (currentWebView != null) {
                browserContainer.addView(currentWebView);
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
        updateNavButtonVisibility();
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;

        WebView webViewToRemove = tabs.get(index);
        if (browserContainer != null) {
            browserContainer.removeView(webViewToRemove);
        }
        webViewToRemove.loadUrl("about:blank");
        webViewToRemove.clearHistory();
        webViewToRemove.removeAllViews();
        webViewToRemove.destroy();
        tabs.remove(index);

        if (tabs.isEmpty()) {
            createNewTab();
            showHome();
        } else {
            if (currentTab >= tabs.size()) {
                currentTab = tabs.size() - 1;
            }
            switchToTab(currentTab);
        }
        saveTabsState();
    }
    private WebView getCurrentWebView() {
        if (currentTab >= 0 && currentTab < tabs.size()) {
            return tabs.get(currentTab);
        }
        return null;
    }

    private void openUrl(String url) {
        WebView webView = getCurrentWebView();
        if (webView != null) {
            webView.loadUrl(url);
        }
    }

    private void showHome() {
        WebView webView = getCurrentWebView();
        if (webView != null) {
            String html = "<html><head><meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                    + "<style>body { background-color: #000000; color: #ffffff; font-family: sans-serif; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; margin: 0; } "
                    + "h1 { font-size: 2.5rem; margin-bottom: 0.5rem; letter-spacing: 1px; } "
                    + "p { color: #888888; font-size: 1rem; }</style></head>"
                    + "<body><h1>Spoon</h1><p>Fast. Private. Clean.</p></body></html>";
            webView.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null);
            if (addressBar != null) addressBar.setText("");
        }
    }

    private void updateTabIndicator() {
        if (tabIndicator != null) {
            tabIndicator.setText(String.valueOf(tabs.size()));
        }
    }

    private void showTabSwitcherDialog() {
        ArrayList<String> tabTitles = new ArrayList<>();
        synchronized (pageTitles) {
            for (WebView w : tabs) {
                String url = w.getUrl();
                String title = pageTitles.get(url);
                if (title == null || title.isEmpty()) {
                    title = (url == null || url.isEmpty() || url.equals("about:blank")) ? "New Tab" : url;
                }
                tabTitles.add(title);
            }
        }

        CharSequence[] items = tabTitles.toArray(new CharSequence[0]);
        new AlertDialog.Builder(this)
                .setTitle("Switch Tab")
                .setItems(items, (dialog, which) -> switchToTab(which))
                .show();
    }

    private void loadSavedData() {
        String savedHistory = prefs.getString(KEY_HISTORY, "");
        if (!savedHistory.isEmpty()) {
            for (String url : savedHistory.split("\n")) {
                if (!url.trim().isEmpty()) history.add(url.trim());
            }
        }

        String savedTitles = prefs.getString(KEY_PAGE_TITLES, "");
        if (!savedTitles.isEmpty()) {
            synchronized (pageTitles) {
                for (String line : savedTitles.split("\n")) {
                    int idx = line.indexOf("|||");
                    if (idx > 0) {
                        pageTitles.put(line.substring(0, idx), line.substring(idx + 3));
                    }
                }
            }
        }

        String savedFilterLists = prefs.getString(KEY_FILTER_LISTS, "");
        if (!savedFilterLists.isEmpty()) {
            for (String filter : savedFilterLists.split("\n")) {
                if (!filter.trim().isEmpty()) {
                    rawFilterRules.add(filter.trim());
                }
            }
            refreshFilterLists();
        }

        handleIncomingIntent(getIntent());
    }

    private void saveTabsState() {
        StringBuilder sb = new StringBuilder();
        for (WebView w : tabs) {
            String url = w.getUrl();
            if (url != null && !url.isEmpty() && !url.equals("about:blank")) {
                sb.append(url).append("\n");
            }
        }
        prefs.edit().putString(KEY_OPEN_TABS, sb.toString()).apply();
    }

    private void saveCurrentTab() {
        prefs.edit().putInt(KEY_CURRENT_TAB, currentTab).apply();
    }

    private boolean restoreSession() {
        try {
            String savedTabs = prefs.getString(KEY_OPEN_TABS, "");
            if (savedTabs == null || savedTabs.isEmpty()) {
                return false;
            }

            tabs.clear();

            for (String url : savedTabs.split("\n")) {
                if (url == null || url.trim().isEmpty() || url.equals("about:blank")) {
                    continue;
                }
                createNewTab();
                WebView webView = tabs.get(tabs.size() - 1);
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
            tabs.clear();
            if (browserContainer != null) {
                browserContainer.removeAllViews();
            }
            return false;
        }
    }

    private void saveHistory() {
        StringBuilder sb = new StringBuilder();
        for (String url : history) {
            sb.append(url).append("\n");
        }
        prefs.edit().putString(KEY_HISTORY, sb.toString()).apply();
    }

    private void savePageTitles() {
        StringBuilder sb = new StringBuilder();
        synchronized (pageTitles) {
            for (String url : pageTitles.keySet()) {
                sb.append(url).append("|||").append(pageTitles.get(url)).append("\n");
            }
        }
        prefs.edit().putString(KEY_PAGE_TITLES, sb.toString()).apply();
    }

    private void showHistoryDialog() {
        if (history.isEmpty()) {
            Toast.makeText(this, "No history found", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] items = history.toArray(new CharSequence[0]);
        new AlertDialog.Builder(this)
                .setTitle("History")
                .setItems(items, (dialog, which) -> openUrl(history.get(which)))
                .show();
    }

    private boolean isBlockedDomain(String host) {
        if (host == null || rawFilterRules.isEmpty()) return false;
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        return rawFilterRules.contains(host);
    }

    private void refreshFilterLists() {
        // Core Blocker execution hooks remain structured under rule tree tracking
    }

    private void saveFilterLists() {
        StringBuilder sb = new StringBuilder();
        for (String rule : rawFilterRules) {
            sb.append(rule).append("\n");
        }
        prefs.edit().putString(KEY_FILTER_LISTS, sb.toString()).apply();
    }

    private void showFilterListsDialog() {
        ListView listView = new ListView(this);
        ArrayList<String> items = new ArrayList<>(rawFilterRules);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items);
        listView.setAdapter(adapter);

        new AlertDialog.Builder(this)
                .setTitle("Subscribed Filter Lists")
                .setView(listView)
                .setPositiveButton("Add", (dialog, which) -> {
                    EditText input = new EditText(MainActivity.this);
                    input.setHint("example.com");
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Block Domain")
                            .setView(input)
                            .setPositiveButton("Block", (d, w) -> {
                                String domain = input.getText().toString().trim().toLowerCase();
                                if (!domain.isEmpty()) {
                                    if (domain.startsWith("www.")) domain = domain.substring(4);
                                    rawFilterRules.add(domain);
                                    saveFilterLists();
                                    refreshFilterLists();
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void updateSuggestions(String query) {
        addressBarAdapter.clear();
        String lower = query.trim().toLowerCase();
        if (lower.isEmpty()) {
            addressBarAdapter.notifyDataSetChanged();
            addressBar.dismissDropDown();
            return;
        }

        HashSet<String> seen = new HashSet<>();
        int count = 0;

        for (int i = history.size() - 1; i >= 0 && count < 5; i--) {
            String url = history.get(i);
            String host = null;
            try {
                Uri uri = Uri.parse(url);
                host = uri.getHost();
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

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
