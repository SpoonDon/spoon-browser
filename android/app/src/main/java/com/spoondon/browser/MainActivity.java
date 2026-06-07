package com.spoondon.browser;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private WebView webView;
    private EditText addressBar;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(10, 20, 10, 10);
        toolbar.setBackgroundColor(Color.parseColor("#1a1a1a"));
        toolbar.setGravity(Gravity.CENTER_VERTICAL);

        Button back = new Button(this);
        back.setText("←");

        Button forward = new Button(this);
        forward.setText("→");

        Button home = new Button(this);
        home.setText("⌂");

        Button go = new Button(this);
        go.setText("Go");

        addressBar = new EditText(this);
        addressBar.setHint("Search or enter URL");
        addressBar.setTextColor(Color.WHITE);
        addressBar.setHintTextColor(Color.GRAY);
        addressBar.setBackgroundColor(Color.parseColor("#2a2a2a"));
        addressBar.setPadding(20, 10, 20, 10);

        LinearLayout.LayoutParams inputParams =
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1
                );

        addressBar.setLayoutParams(inputParams);

        toolbar.addView(back);
        toolbar.addView(forward);
        toolbar.addView(home);
        toolbar.addView(addressBar);
        toolbar.addView(go);

        webView = new WebView(this);

        LinearLayout.LayoutParams webParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1
                );

        webView.setLayoutParams(webParams);

        root.addView(toolbar);
        root.addView(webView);

        setContentView(root);

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
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                addressBar.setText(url);
            }
        });

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

            if (webView.canGoBack()) {
                webView.goBack();
            }
        });

        forward.setOnClickListener(v -> {

            if (webView.canGoForward()) {
                webView.goForward();
            }
        });

        home.setOnClickListener(v -> showHome());

        getOnBackPressedDispatcher().addCallback(this,
                new OnBackPressedCallback(true) {

                    @Override
                    public void handleOnBackPressed() {

                        if (webView.canGoBack()) {
                            webView.goBack();
                        } else {
                            finish();
                        }
                    }
                });

        showHome();
    }

    private void showHome() {

        String homePage =
                "<html>" +
                "<body style='margin:0;background:#000;color:white;font-family:sans-serif;text-align:center;'>" +

                "<div style='padding-top:22%;'>" +

                "<h1 style='font-size:42px;margin-bottom:10px;'>Spoon Browser</h1>" +

                "<p style='opacity:0.7;font-size:18px;'>Private • Simple • Fast</p>" +

                "<div style='margin-top:50px;'>" +

                "<input id='q' type='text' placeholder='Search privately...' " +
                "style='width:70%;padding:18px;border:none;border-radius:14px;" +
                "background:#1f1f1f;color:white;font-size:18px;outline:none;'/>" +

                "<br><br>" +

                "<button onclick='goSearch()' " +
                "style='padding:14px 28px;border:none;border-radius:12px;" +
                "background:#333;color:white;font-size:16px;'>Search</button>" +

                "</div>" +

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

        webView.loadDataWithBaseURL(
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

        webView.loadUrl(url);
    }
}
