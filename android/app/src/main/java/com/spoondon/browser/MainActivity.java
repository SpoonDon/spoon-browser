package com.spoondon.browser;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.PopupMenu;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.widget.HorizontalScrollView;
import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;

import java.util.ArrayList;

import java.util.HashMap;

   public class MainActivity extends BridgeActivity {

   private static final String PREFS_NAME =
        "spoon_browser";

   private static final String KEY_BOOKMARKS =
        "bookmarks";

   private static final String KEY_HISTORY =
        "history";

   private static final String KEY_PAGE_TITLES =
        "page_titles";

   private static final String KEY_FILTER_LISTS =
        "filter_lists";

    private static final int MAX_HISTORY =
            500;

    private static final int MAX_PAGE_TITLES =
            500;

    private static final int MAX_PAGE_ICONS =
            200;

    private EditText addressBar;
    private LinearLayout root;
    // Reserved for future WebView container features
    private LinearLayout browserContainer;
    private TextView tabIndicator;

    private final ArrayList<WebView> tabs = new ArrayList<>();
    private WebView lastClosedTab = null;
    private int lastClosedTabIndex = -1;
    private final ArrayList<String> bookmarks = new ArrayList<>();
    private final ArrayList<String> history = new ArrayList<>();
    private final HashMap<String, String> pageTitles =
            new HashMap<>();
    private SharedPreferences prefs;
    private int currentTab = 0;
    private final ArrayList<String>
    filterLists =
            new ArrayList<>();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(
                PREFS_NAME,
                MODE_PRIVATE
        );

        String savedHistory =
                        prefs.getString(KEY_HISTORY, "");

                if (!savedHistory.isEmpty()) {

                        for (String item :
            savedHistory.split("\n")) {

            history.add(item);

            while (
                    history.size()
                            > MAX_HISTORY
            ) {
                    history.remove(0);
            }
}
                }

                String savedBookmarks =
                        prefs.getString(KEY_BOOKMARKS, "");

                if (!savedBookmarks.isEmpty()) {

                        for (String bookmark :
                                savedBookmarks.split("\n")) {

                                if (!bookmarks.contains(
        bookmark
)) {
        bookmarks.add(
                bookmark
        );
}
                        }
                }

String savedFilterLists =
        prefs.getString(
                KEY_FILTER_LISTS,
                ""
        );

if (!savedFilterLists.isEmpty()) {

    for (String filter :
            savedFilterLists.split("\n")) {

        if (!filterLists.contains(
                filter
        )) {

            filterLists.add(
                    filter
            );
        }
    }
}

                String savedPageTitles =
        prefs.getString(
                KEY_PAGE_TITLES,
                ""
        );

if (!savedPageTitles.isEmpty()) {

    for (String item :
            savedPageTitles.split(
                    "\n"
            )) {

        String[] parts =
                item.split(
                        "\\|",
                        2
                );

        if (parts.length == 2) {

            pageTitles.put(
                    parts[0],
                    parts[1]
            );
        }
    }
}

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, windowInsets) -> {

            Insets systemBars =
                    windowInsets.getInsets(
                            WindowInsetsCompat.Type.systemBars()
                    );

            v.setPadding(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    systemBars.bottom
            );

            return windowInsets;
        }
);

        browserContainer = new LinearLayout(this);
        browserContainer.setOrientation(
                LinearLayout.VERTICAL
        );

        LinearLayout.LayoutParams browserParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1
                );

        browserContainer.setLayoutParams(
                browserParams
        );

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);

        toolbar.setPadding(
                dp(8),
                dp(8),
                dp(8),
                dp(8)
        );

        toolbar.setBackgroundColor(Color.parseColor("#111111"));

        Button back = makeButton("←");
        Button forward = makeButton("→");
        Button home = makeButton("⌂");

        Button prevTab = makeButton("◀");

        tabIndicator = new TextView(this);
        tabIndicator.setTextColor(Color.WHITE);
        tabIndicator.setTextSize(15);
        tabIndicator.setPadding(dp(10), 0, dp(10), 0);
        tabIndicator.setOnLongClickListener(v -> {

            showTabSwitcher();

            return true;
        });

        Button nextTab = makeButton("▶");
        Button bookmark = makeButton("★");
        Button newTab = makeButton("+");
        Button closeTab = makeButton("×");
        Button menuButton = makeButton("⋮");

        addressBar = new EditText(this);

        addressBar.setHint("Enter URL");
        addressBar.setTextColor(Color.WHITE);
        addressBar.setHintTextColor(Color.GRAY);
        addressBar.setSingleLine(true);

        GradientDrawable addressBg = new GradientDrawable();
        addressBg.setColor(Color.parseColor("#1e1e1e"));
        addressBg.setCornerRadius(dp(14));

        addressBar.setBackground(addressBg);

        addressBar.setPadding(
                dp(16),
                dp(12),
                dp(16),
                dp(12)
        );

        LinearLayout.LayoutParams inputParams =
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1
                );

        inputParams.setMargins(dp(8), 0, dp(8), 0);

        addressBar.setLayoutParams(inputParams);

        Button go = makeButton("Go");

        toolbar.addView(back);
        toolbar.addView(forward);
        toolbar.addView(home);
        toolbar.addView(prevTab);
        toolbar.addView(tabIndicator);
        toolbar.addView(nextTab);
        toolbar.addView(newTab);
        toolbar.addView(bookmark);
        toolbar.addView(closeTab);
        toolbar.addView(addressBar);
        toolbar.addView(go);
        toolbar.addView(menuButton);


HorizontalScrollView toolbarScroll =
        new HorizontalScrollView(
                this
        );

toolbarScroll.addView(
        toolbar
);

root.addView(
        toolbarScroll
);

        root.addView(browserContainer);

        setContentView(root);

        createNewTab();

        Intent intent = getIntent();

        if (Intent.ACTION_VIEW.equals(intent.getAction())
                && intent.getData() != null) {

            getCurrentWebView().loadUrl(
                    intent.getData().toString()
            );
        }

        showHome();

go.setOnClickListener(v -> navigate());

bookmark.setOnClickListener(v -> {

        String url = getCurrentWebView().getUrl();

       if (url != null &&
               !url.isEmpty() &&
               !url.equals("about:blank") &&
               !bookmarks.contains(url)) {

            bookmarks.add(url);
            saveBookmarks();

            Toast.makeText(
                    this,
                    "Saved: " + url,
                    Toast.LENGTH_SHORT
            ).show();
        }
});

bookmark.setOnLongClickListener(
        v -> {

    showBookmarks();

    return true;
});

menuButton.setOnClickListener(v -> {

    PopupMenu popup = new PopupMenu(this, menuButton);

    popup.getMenu().add("Bookmarks");
    popup.getMenu().add("History");
    popup.getMenu().add("Clear History");
    popup.getMenu().add("Clear Cache");
    popup.getMenu().add("About");

    popup.setOnMenuItemClickListener(item -> {

        String title = item.getTitle().toString();

        switch (title) {

            case "Bookmarks":
                showBookmarks();
                return true;

            case "History":
                showHistoryDialog();
                return true;

            case "Clear History":
                history.clear();
                saveHistory();
                return true;

            case "Clear Cache":
                getCurrentWebView().clearCache(
                        true
                );

                Toast.makeText(
                        this,
                        "Cache cleared",
                        Toast.LENGTH_SHORT
                ).show();

                return true;

            case "About":
                showAbout();
                return true;
        }

        return false;
    });

    popup.show();
});

addressBar.setOnKeyListener((v, keyCode, event) -> {

            if (keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.getAction() == KeyEvent.ACTION_DOWN) {

                navigate();
                return true;
            }

            return false;
        });

        back.setOnClickListener(v -> {

            WebView webView = getCurrentWebView();

            if (webView.canGoBack()) {
                webView.goBack();
            }
        });

        back.setOnLongClickListener(v -> {

            showHistoryDialog();

            return true;
        });

        forward.setOnClickListener(v -> {

            WebView webView = getCurrentWebView();

            if (webView.canGoForward()) {
                webView.goForward();
            }
        });

        home.setOnClickListener(v -> showHome());

        newTab.setOnClickListener(v -> {

            createNewTab();
            showHome();
        });

        newTab.setOnLongClickListener(v -> {

            restoreLastClosedTab();

            return true;
        });

        closeTab.setOnClickListener(v -> {

            if (tabs.size() == 1) {

                finishAndRemoveTask();

                return;
            }

            lastClosedTab = tabs.get(currentTab);
            lastClosedTabIndex = currentTab;

            tabs.remove(currentTab);

            if (currentTab >= tabs.size()) {
                currentTab = tabs.size() - 1;
            }

            switchToTab(currentTab);

            Toast.makeText(
                    this,
                    "Tab Closed",
                    Toast.LENGTH_SHORT
            ).show();
        });

        prevTab.setOnClickListener(v -> {

            if (tabs.size() > 1) {

                int previous = currentTab - 1;

                if (previous < 0) {
                    previous = tabs.size() - 1;
                }

                switchToTab(previous);
            }
        });

        nextTab.setOnClickListener(v -> {

            if (tabs.size() > 1) {

                int next = (currentTab + 1) % tabs.size();

                switchToTab(next);
            }
        });

        getOnBackPressedDispatcher().addCallback(this,
                new OnBackPressedCallback(true) {

                    @Override
                    public void handleOnBackPressed() {

                        WebView webView = getCurrentWebView();

                        if (webView.canGoBack()) {
                            webView.goBack();
                        } else {
                            finish();
                        }
                    }
                });
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

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        dp(46),
                        dp(46)
                );

        params.setMargins(dp(3), 0, dp(3), 0);

        button.setLayoutParams(params);

        return button;
    }

    private int dp(int value) {

        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
    }

    private WebView createConfiguredWebView() {

        WebView webView = new WebView(this);

        LinearLayout.LayoutParams webParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1
                );

        webView.setLayoutParams(webParams);

        WebSettings s = webView.getSettings();

s.setJavaScriptEnabled(true);
s.setSafeBrowsingEnabled(
        true
);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);

        webView.setWebChromeClient(
                new WebChromeClient() {

                    @Override
                    public void onReceivedTitle(
                            WebView view,
                            String title) {

                        String url = view.getUrl();

                        if (url != null &&
                                title != null &&
                                !title.isEmpty()) {

                            pageTitles.put(
                                    url,
                                    title
                            );

                            savePageTitles();

                        }
                    }
                }
        );

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageStarted(WebView view,
                                      String url,
                                      Bitmap favicon) {

                if (url == null ||
        url.isEmpty() ||
        url.equals("about:blank")) {

    addressBar.setText(
            ""
    );

} else {

    addressBar.setText(
            url
    );
}

                if (url != null &&
                        !url.isEmpty() &&
                        !url.equals("about:blank") &&
                        !url.startsWith(
                                "chrome-error://"
                        ) &&
                        !url.startsWith(
                                "data:"
                        ) &&
                        !url.startsWith(
                                "file://"
                        )) {

                        if (history.isEmpty() ||
                                !history.get(history.size() - 1)
                                        .equals(url)) {

                                history.add(url);

                                while (
                                        history.size()
                                                > MAX_HISTORY
                                ) {
                                        history.remove(0);
                                }

                                saveHistory();
                        }
                }
           }
       });

       return webView;
    }

    private void createNewTab() {

        WebView webView = createConfiguredWebView();

        tabs.add(webView);

        switchToTab(tabs.size() - 1);
    }

    // Long press "+" to restore the most recently closed tab.
    private void restoreLastClosedTab() {

        if (lastClosedTab == null) {
            return;
        }

        tabs.add(
                lastClosedTabIndex,
                lastClosedTab
        );

        switchToTab(lastClosedTabIndex);

        lastClosedTab = null;
        lastClosedTabIndex = -1;
    }

    private void switchToTab(int index) {

        if (index < 0 || index >= tabs.size()) return;

        currentTab = index;

        updateTabIndicator();

        browserContainer.removeAllViews();
        browserContainer.addView(getCurrentWebView());

        String url =
        getCurrentWebView()
                .getUrl();

if (url == null ||
        url.isEmpty() ||
        url.equals("about:blank")) {

    addressBar.setText(
            ""
    );

} else {

    addressBar.setText(
            url
    );
}

    }

    private void updateTabIndicator() {

        tabIndicator.setText(
                (currentTab + 1) + "/" + tabs.size()
        );
    }

    private ArrayList<BrowserItem>
    buildTabItems() {

             ArrayList<BrowserItem> items =
                             new ArrayList<>();

        for (int i = 0;
             i < tabs.size();
             i++) {

            WebView webView =
                    tabs.get(i);

            String url =
                    webView.getUrl();

            String title = null;

            if (url != null) {
                title =
                        pageTitles.get(url);
            }

            if (title == null ||
                    title.isEmpty()) {

                if (url == null ||
                        url.isEmpty()) {

                    title = "New Tab";

                } else {

                    title = url;
                }
            }

            items.add(
                    new BrowserItem(
                            title,
                            url
                    )
            );
        }

        return items;
    }

    private void showTabSwitcher() {

        if (tabs.isEmpty()) {
            return;
        }

        ArrayList<BrowserItem> items =
                        buildTabItems();

        BrowserItemAdapter adapter =
                new BrowserItemAdapter(
                        this,
                        items
                );

        ListView listView =
                new ListView(
                        this
                );

                listView.setAdapter(
                        adapter
                );

                listView.setOnItemClickListener(
                        (parent, view, which, id) -> {

            switchToTab(
                    which
            );
        }
);

new AlertDialog.Builder(this)
        .setTitle("Tabs")
        .setView(
                listView
        )
        .show();
    }

    private ArrayList<BrowserItem>
    buildHistoryItems() {

        ArrayList<BrowserItem> items =
                new ArrayList<>();

        for (int i = 0;
             i < history.size();
             i++) {

            String url = history.get(
                    history.size() - 1 - i
            );

            String title =
                    pageTitles.get(url);

            if (title == null ||
                    title.isEmpty()) {

                title = url;
            }

            items.add(
                    new BrowserItem(
                            title,
                            url
                    )
            );

        }

        return items;
    }

    private ArrayList<BrowserItem>
    buildBookmarkItems() {

        ArrayList<BrowserItem> items =
                new ArrayList<>();

        for (int i = 0;
             i < bookmarks.size();
             i++) {

            String url =
                    bookmarks.get(i);

            String title =
                    pageTitles.get(url);

            if (title == null ||
                    title.isEmpty()) {

                title = url;
            }


            items.add(
                            new BrowserItem(
                            title,
                            url
                    )
            );
        }

        return items;
    }

    private void showHistoryDialog() {

        if (history.isEmpty()) {
            return;
        }

        ArrayList<BrowserItem> items =
                buildHistoryItems();

        BrowserItemAdapter adapter =
                new BrowserItemAdapter(
                        this,
                        items
                );

        ListView listView =
                        new ListView(
                                this
                        );

                listView.setAdapter(
                        adapter
                );

        listView.setOnItemClickListener(
                        (parent, view, which, id) -> {

                            openUrl(
        items.get(which).url
);
                        }
                );

        listView.setOnItemLongClickListener(
                (parent, view, which, id) -> {

                    String[] options = {
                            "Open in New Tab",
                            "Add Bookmark"
                    };

                    new AlertDialog.Builder(
                            this
                    )
                            .setItems(
                                    options,
                                    (dialog, item) -> {

    if (item == 0) {

        createNewTab();

        openUrl(
        items.get(which).url
);

    } else if (item == 1) {

        String url =
                items.get(which).url;

        if (!bookmarks.contains(
                url
        )) {

            bookmarks.add(
                    url
            );

            saveBookmarks();

            Toast.makeText(
                    this,
                    "Bookmark added",
                    Toast.LENGTH_SHORT
            ).show();

        } else {

            Toast.makeText(
                    this,
                    "Already bookmarked",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }
}

                            )
                            .show();

                    return true;
                }
        );

                new AlertDialog.Builder(this)
                        .setTitle("History")
                        .setView(
                                listView
                        )
                        .show();

    }

    // Utility helpers

    private WebView getCurrentWebView() {
        return tabs.get(currentTab);
    }

private void openUrl(
        String url
) {

    getCurrentWebView()
            .loadUrl(
                    url
            );
}

    private String getAppVersion() {

        try {

            return getPackageManager()
                    .getPackageInfo(
                            getPackageName(),
                            0
                    ).versionName;

        } catch (Exception e) {

            return "?";
        }
    }

    // Persistence helpers

    private void saveBookmarks() {

        prefs.edit().putString(
                KEY_BOOKMARKS,
                String.join("\n", bookmarks)
        ).apply();
    }

    private void saveHistory() {

        prefs.edit().putString(
                KEY_HISTORY,
                String.join("\n", history)
        ).apply();
    }

    private void saveFilterLists() {

    prefs.edit().putString(
            KEY_FILTER_LISTS,
            String.join(
                    "\n",
                    filterLists
            )
    ).apply();
}

    // Metadata persistence

    private void savePageTitles() {

    StringBuilder builder =
            new StringBuilder();

    for (String url :
            pageTitles.keySet()) {

        builder.append(url)
                .append("|")
                .append(
                        pageTitles.get(url)
                )
                .append("\n");
    }

    prefs.edit().putString(
            KEY_PAGE_TITLES,
            builder.toString()
    ).apply();
}

private void showAbout() {

        AlertDialog dialog =
                new AlertDialog.Builder(
                        this
                )
                        .setTitle(
                                "Spoon Browser"
                        )
                        .setMessage(
                                "Version: "
                                        + getAppVersion()
                                        + "\n\n"
                                        + "Tabs: "
                                        + tabs.size()
                                        + "\nBookmarks: "
                                        + bookmarks.size()
                                        + "\nHistory: "
                                        + history.size()
                                        + "\n\nBuilt one green commit at a time."
                                        + "\n\nDesigned to evolve with Android WebView."
                                        + "\n\n-with love, Plaban."
                        )
                        .setPositiveButton(
                                "OK",
                                null
                        )
                        .create();

        dialog.show();

        dialog.setCanceledOnTouchOutside(
                false
        );

    }

    private void showBookmarks() {

    if (bookmarks.isEmpty()) {

        Toast.makeText(
                this,
                "No bookmarks saved",
                Toast.LENGTH_SHORT
        ).show();

        return;
    }

ArrayList<BrowserItem> items =
                buildBookmarkItems();

        BrowserItemAdapter adapter =
                new BrowserItemAdapter(
                        this,
                        items
                );

        ListView listView =
                new ListView(
                        this
                );

        listView.setAdapter(
                adapter
        );

                    listView.setOnItemClickListener(
        (parent, view, which, id) -> {

            openUrl(
        items.get(which).url
);
        }
);

listView.setOnItemLongClickListener(
        (parent, view, which, id) -> {

            String[] options = {
                    "Open",
                    "Open in New Tab",
                    "Remove Bookmark"
            };

            new AlertDialog.Builder(
                    this
            )
                    .setItems(
                            options,
                            (dialog, item) -> {

if (item == 0) {

    getCurrentWebView()
            .loadUrl(
                    items.get(which).url
            );
}

else if (item == 1) {

    createNewTab();

    getCurrentWebView()
            .loadUrl(
                    items.get(which).url
            );
}
                                else if (item == 2) {

    bookmarks.remove(
            items.get(which).url
    );

    saveBookmarks();

    Toast.makeText(
            this,
            "Bookmark removed",
            Toast.LENGTH_SHORT
    ).show();
}

                            }
                    )
                    .show();

            return true;
        }
);

new AlertDialog.Builder(this)
        .setTitle("Bookmarks")
        .setView(
                listView
        )
        .show();

}

    private void showHome() {

        String homePage =
                "<html>" +
                "<body style='margin:0;background:#000;color:white;" +
                "font-family:sans-serif;text-align:center;'>" +

                "<div style='padding-top:20%;'>" +

                "<h1 style='font-size:48px;margin-bottom:40px;'>" +
                "Spoon Browser</h1>" +

                "<input id='q' type='text' " +
                "placeholder='Search privately...' " +

                "style='width:72%;padding:20px;border:none;" +
                "border-radius:18px;background:#1f1f1f;" +
                "color:white;font-size:18px;outline:none;'/>" +

                "<br><br>" +

                "<button onclick='goSearch()' " +
                "style='padding:14px 32px;border:none;" +
                "border-radius:14px;background:#333;" +
                "color:white;font-size:16px;'>" +

                "Search</button>" +

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

        getCurrentWebView().loadDataWithBaseURL(
                null,
                homePage,
                "text/html",
                "UTF-8",
                null
        );
    }

    private void navigate() {

        String input = addressBar.getText().toString().trim();

        if (input.isEmpty()) return;

        String url;

        if (input.contains(".") && !input.contains(" ")) {

            if (!input.startsWith("http://") &&
                    !input.startsWith("https://")) {

                url = "https://" + input;

            } else {

                url = input;
            }

        } else {

            url = "https://duckduckgo.com/?q=" +
                    input.replace(" ", "+");
        }

if (url.startsWith(
        "javascript:"
)
        || url.startsWith(
                "file:"
        )
        || url.startsWith(
                "content:"
        )
        || url.startsWith(
                "intent:"
        )) {

    Toast.makeText(
            this,
            "Blocked unsafe URL",
            Toast.LENGTH_SHORT
    ).show();

    return;
}

openUrl(
        url
);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        menu.add("Refresh");
        menu.add("Home");
        menu.add("Bookmark Page");
        menu.add("About");

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        String title = item.getTitle().toString();

        switch (title) {

            case "Refresh":

                getCurrentWebView().reload();

                return true;

            case "Home":

                showHome();

                return true;

            case "Bookmark Page":

                Toast.makeText(
                        this,
                        bookmarks.toString(),
                        Toast.LENGTH_LONG
                ).show();

                return true;

            case "About":

                showAbout();

                return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
