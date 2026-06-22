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

public class MainActivity extends BridgeActivity {

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

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
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

        loadSavedData();
        setupRootLayout();
        createToolbarViews();
        setupToolbarListeners();
        setupMenuButton();
        setupBackButtonHandler();

        root.addView(toolbar);
        root.addView(browserContainer);
        setContentView(root);

        setupInitialTab();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            createNewTab();
            handleIncomingIntent(intent);
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

    private void setupInitialTab() {
        Intent intent = getIntent();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            createNewTab();
            handleIncomingIntent(intent);
            return;
        }
        if (restoreSession()) {
            return;
        }
        createNewTab();
        showHome();
    }

    private boolean restoreSession() {
        String savedTabs = prefs.getString(KEY_OPEN_TABS, "");
        if (savedTabs.isEmpty()) {
            return false;
        }
        for (String url : savedTabs.split("\n")) {
            if (url.isEmpty()) continue;
            WebView webView = createConfiguredWebView();
            tabs.add(webView);
            webView.loadUrl(url);
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
    }

    private void handleIncomingIntent(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            getCurrentWebView().loadUrl(intent.getData().toString());
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
                if (webView.canGoBack()) {
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
                        getCurrentWebView().reload();
                        return true;
                    case "Bookmarks":
                        showBookmarks();
                        return true;
                    case "Add Bookmark":
                        String url = getCurrentWebView().getUrl();
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
                        getCurrentWebView().clearCache(true);
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
            if (webView.canGoForward()) {
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
                        results.values = addressBarAdapter;
                        results.count = addressBarAdapter.getCount();
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
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (suppressSuggestions) return;
                updateAddressBarSuggestions(s.toString());
            }
            @Override
            public void afterTextChanged(Editable s) {}
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
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        
        // Future Proofing: Let modern Chromium handle file access natively and safely
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
    }

    private WebChromeClient createWebChromeClient() {
        return new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
[D[D[D[D[A[A[C[C