package com.spoondon.browser;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Color;
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

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;

import java.util.ArrayList;

public class MainActivity extends BridgeActivity {

    private EditText addressBar;
    private LinearLayout root;
    private TextView tabIndicator;

    private final ArrayList<WebView> tabs = new ArrayList<>();
    private int currentTab = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);

        int topPadding = dp(18);

        toolbar.setPadding(
                dp(8),
                topPadding,
                dp(8),
                dp(8)
        );

        toolbar.setBackgroundColor(Color.parseColor("#1a1a1a"));
        toolbar.setGravity(Gravity.CENTER_VERTICAL);

        Button back = makeButton("←");
        Button forward = makeButton("→");
        Button home = makeButton("⌂");
        Button prevTab = makeButton("◀");
        Button nextTab = makeButton("▶");
        Button newTab = makeButton("+");

        tabIndicator = new TextView(this);
        tabIndicator.setTextColor(Color.WHITE);
        tabIndicator.setTextSize(16);
        tabIndicator.setPadding(dp(8), 0, dp(8), 0);

        Button go = makeButton("Go");

        addressBar = new EditText(this);

        addressBar.setHint("Search or enter URL");
        addressBar.setTextColor(Color.WHITE);
        addressBar.setHintTextColor(Color.GRAY);

        addressBar.setBackgroundColor(
                Color.parseColor("#2a2a2a")
        );

        addressBar.setPadding(
                dp(14),
                dp(10),
                dp(14),
                dp(10)
        );

        addressBar.setSingleLine(true);

        LinearLayout.LayoutParams inputParams =
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1
                );

        inputParams.setMargins(dp(6), 0, dp(6), 0);

        addressBar.setLayoutParams(inputParams);

        toolbar.addView(back);
        toolbar.addView(forward);
        toolbar.addView(home);
        toolbar.addView(prevTab);
        toolbar.addView(tabIndicator);
        toolbar.addView(nextTab);
        toolbar.addView(newTab);
        toolbar.addView(addressBar);
        toolbar.addView(go);

        root.addView(toolbar);

        setContentView(root);

        createNewTab();
        showHome();

        go.setOnClickListener(v -> navigate());

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

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        dp(48),
                        dp(48)
                );

        params.setMargins(dp(2), 0, dp(2), 0);

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
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);

        webView.setWebChromeClient(new WebChromeClient());

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageStarted(WebView view,
                                      String url,
                                      Bitmap favicon) {

                addressBar.setText(url);
            }
        });

        return webView;
    }

    private void createNewTab() {

        WebView webView = createConfiguredWebView();

        tabs.add(webView);

        switchToTab(tabs.size() - 1);
    }

    private void switchToTab(int index) {

        if (index < 0 || index >= tabs.size()) return;

        currentTab = index;

        updateTabIndicator();

        if (root.getChildCount() > 1) {
            root.removeViewAt(1);
        }

        root.addView(getCurrentWebView());
    }

    private void updateTabIndicator() {

        tabIndicator.setText(
                (currentTab + 1) + "/" + tabs.size()
        );
    }

    private WebView getCurrentWebView() {
        return tabs.get(currentTab);
    }

    private void showHome() {

        String homePage =
                "<html>" +
                "<body style='margin:0;background:#000;color:white;" +
                "font-family:sans-serif;text-align:center;'>" +

                "<div style='padding-top:25%;'>" +

                "<h1 style='font-size:42px;margin-bottom:40px;'>" +
                "Spoon Browser</h1>" +

                "<input id='q' type='text' " +
                "placeholder='Search privately...' " +

                "style='width:70%;padding:18px;border:none;" +
                "border-radius:14px;background:#1f1f1f;" +
                "color:white;font-size:18px;outline:none;'/>" +

                "<br><br>" +

                "<button onclick='goSearch()' " +
                "style='padding:14px 28px;border:none;" +
                "border-radius:12px;background:#333;" +
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

        getCurrentWebView().loadUrl(url);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        menu.add("Close Current Tab");
        menu.add("Refresh");
        menu.add("Home");
        menu.add("About");

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        String title = item.getTitle().toString();

        switch (title) {

            case "Close Current Tab":

                if (tabs.size() > 1) {

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
                }

                return true;

            case "Refresh":

                getCurrentWebView().reload();

                return true;

            case "Home":

                showHome();

                return true;

            case "About":

                Toast.makeText(
                        this,
                        "Spoon Browser",
                        Toast.LENGTH_SHORT
                ).show();

                return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
