package com.spoondon.browser;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputConnection;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AutoCompleteTextView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private Toolbar toolbar;
    private EditText addressBar;
    private ImageButton refreshBtn;
    private ImageButton homeBtn;
    private ImageButton menuBtn;
    private FrameLayout browserContainer;
    private ProgressBar progressBar;
    private LinearLayout findInPageBar;
    private EditText findInput;
    private TextView findMatchesCount;
    private ImageButton findPrevBtn;
    private ImageButton findNextBtn;
    private ImageButton findCloseBtn;

    private TabManager tabManager;
    private ContentFilterEngine filterEngine;
    
    // Cached MasterKey for security
    private static volatile android.security.keystore.KeyGenParameterSpec masterKeySpec;
    private static volatile androidx.security.crypto.MasterKey cachedMasterKey;
    
    // Background executor for WebView startup
    private static final Executor backgroundExecutor = Executors.newSingleThreadExecutor();

    // Optimized regex patterns
    private static final Pattern DOMAIN_PATTERN = Pattern.compile("^\\|\\|([a-zA-Z0-9.-]+)\\^");
    private static final Pattern COSMETIC_PATTERN = Pattern.compile("^(.+?)##(.+)");

    private static final int REQUEST_FILE_PICKER = 1;
    private static final int REQUEST_PERMISSIONS = 2;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeViews();
        
        // Initialize Tab Manager (after views are initialized)
        tabManager = new TabManager(this, browserContainer);
        
        setupToolbar();
        setupFindInPage();
        
        // Initialize Filter Engine (Singleton usage)
        filterEngine = ContentFilterEngine.getInstance();
        filterEngine.loadFilters(this);

        // WebView Pre-initialization (Startup optimization)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.START_UP_WEB_VIEW)) {
            WebViewStartUpConfig config = new WebViewStartUpConfig.Builder()
                .setBackgroundThreadExecutor(backgroundExecutor)
                .build();
            // Note: WebViewCompat.startUpWebView requires specific setup, 
            // usually done in Application class, but included here for completeness
            // if strictly required in Activity context for this snippet.
        }

        // Restore or Create Tab
        if (savedInstanceState != null) {
            tabManager.restoreState(savedInstanceState);
        } else {
            tabManager.createNewTab("https://www.google.com");
        }

        updateAddressBarSuggestions();
    }

    private void initializeViews() {
        toolbar = findViewById(R.id.toolbar);
        addressBar = findViewById(R.id.addressBar);
        refreshBtn = findViewById(R.id.refreshBtn);
        homeBtn = findViewById(R.id.homeBtn);
        menuBtn = findViewById(R.id.menuBtn);
        browserContainer = findViewById(R.id.browserContainer);
        progressBar = findViewById(R.id.progressBar);
        findInPageBar = findViewById(R.id.findInPageBar);
        findInput = findViewById(R.id.findInput);
        findMatchesCount = findViewById(R.id.findMatchesCount);
        findPrevBtn = findViewById(R.id.findPrevBtn);
        findNextBtn = findViewById(R.id.findNextBtn);
        findCloseBtn = findViewById(R.id.findCloseBtn);
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        
        addressBar.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                String url = addressBar.getText().toString().trim();
                if (!url.isEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://" + url;
                    }
                    WebView currentWebView = tabManager.getCurrentWebView();
                    if (currentWebView != null) {
                        currentWebView.loadUrl(url);
                    }
                    addressBar.clearFocus();
                }
                return true;
            }
            return false;
        });

        refreshBtn.setOnClickListener(v -> {
            WebView currentWebView = tabManager.getCurrentWebView();
            if (currentWebView != null) {
                currentWebView.reload();
            }
        });

        homeBtn.setOnClickListener(v -> {
            WebView currentWebView = tabManager.getCurrentWebView();
            if (currentWebView != null) {
                currentWebView.loadUrl("https://www.google.com");
            }
        });

        menuBtn.setOnClickListener(v -> showMenu());
    }

    private void setupFindInPage() {
        findInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                performFindInPage();
                return true;
            }
            return false;
        });

        findNextBtn.setOnClickListener(v -> {
            WebView currentWebView = tabManager.getCurrentWebView();
            if (currentWebView != null) {
                currentWebView.findNext(true);
            }
        });

        findPrevBtn.setOnClickListener(v -> {
            WebView currentWebView = tabManager.getCurrentWebView();
            if (currentWebView != null) {
                currentWebView.findNext(false);
            }
        });

        findCloseBtn.setOnClickListener(v -> closeFindInPage());
    }

    private void showMenu() {
        // Simplified menu for brevity
        Toast.makeText(this, "Menu clicked", Toast.LENGTH_SHORT).show();
    }

    private void updateAddressBarSuggestions() {
        // Thread-safe adapter initialization check
        new Handler(Looper.getMainLooper()).post(() -> {
            if (addressBar != null) {
                List<String> suggestions = new ArrayList<>();
                suggestions.add("https://www.google.com");
                suggestions.add("https://www.wikipedia.org");
                
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this, 
                    android.R.layout.simple_dropdown_item_1line, suggestions);
                addressBar.setAdapter(adapter);
            }
        });
    }

    private void performFindInPage() {
        WebView currentWebView = tabManager.getCurrentWebView();
        if (currentWebView != null && findInput != null) {
            String query = findInput.getText().toString();
            if (!query.isEmpty()) {
                currentWebView.findAllAsync(query);
                // Listener for match count will be set in WebViewClient
            }
        }
    }

    private void closeFindInPage() {
        if (findInPageBar != null) {
            findInPageBar.setVisibility(View.GONE);
        }
        WebView currentWebView = tabManager.getCurrentWebView();
        if (currentWebView != null) {
            currentWebView.clearMatches();
        }
        if (addressBar != null) {
            addressBar.requestFocus();
        }
    }

    public /* package */ android.webkit.WebView createConfiguredWebView() {
        android.webkit.WebView webView = new android.webkit.WebView(this);
        WebSettings settings = webView.getSettings();
        
        // Standard Settings
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setGeolocationEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        
        // Layer Type Optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        } else {
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        // Multi-Profile Support
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            String profileName = "tab_" + System.currentTimeMillis();
            WebViewCompat.setProfile(webView, profileName);
        }

        // Navigation Listener
        if (WebViewFeature.isFeatureSupported(WebViewFeature.NAVIGATION_LISTENER)) {
            WebViewCompat.addNavigationListener(webView, new androidx.webkit.NavigationListener() {
                @Override
                public void onNavigationStarted(@NonNull androidx.webkit.Navigation navigation) {
                    if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
                }

                @Override
                public void onNavigationCompleted(@NonNull androidx.webkit.Navigation navigation) {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    // Auto-dismiss find-in-page on navigation
                    closeFindInPage();
                }
            });
        }

        // Document Start Script (Ad Blocking Hook)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            String script = "(function() { console.log('Content Script Loaded'); })();";
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                script,
                Collections.singleton("*")
            );
        }

        webView.setWebViewClient(new SpoonWebViewClient(progressBar, findMatchesCount));
        webView.setWebChromeClient(new SpoonWebChromeClient());
        webView.setDownloadListener(new SpoonDownloadListener());

        return webView;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // Proper Cookie Cleanup
        CookieManager cm = CookieManager.getInstance();
        if (cm != null) {
            cm.removeAllCookies(null);
            cm.flush();
        }
        
        tabManager.destroyAllTabs();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        tabManager.saveState(outState);
    }

    // Inner Classes for Clients
    
    private class SpoonWebViewClient extends WebViewClient {
        private ProgressBar localProgressBar;
        private TextView localMatchCount;

        public SpoonWebViewClient(ProgressBar pb, TextView mc) {
            this.localProgressBar = pb;
            this.localMatchCount = mc;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            if (localProgressBar != null) localProgressBar.setVisibility(View.VISIBLE);
            if (addressBar != null) addressBar.setText(url);
            
            // Auto-dismiss find bar on new page load
            closeFindInPage();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (localProgressBar != null) localProgressBar.setVisibility(View.GONE);
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            super.onReceivedHttpError(view, request, errorResponse);
            // Handle errors if needed
        }
        
        @Override
        public void onFindResultReceived(int activeOrdinal, int matchesCount, boolean isDoneCount) {
            if (localMatchCount != null && findInPageBar.getVisibility() == View.VISIBLE) {
                localMatchCount.setText(matchesCount + "/" + matchesCount);
                // Dynamic coloring for prominence
                if (matchesCount > 0) {
                    localMatchCount.setTextColor(ContextCompat.getColor(MainActivity.this, android.R.color.white));
                } else {
                    localMatchCount.setTextColor(ContextCompat.getColor(MainActivity.this, android.R.color.darker_gray));
                }
            }
        }
    }

    private class SpoonWebChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (progressBar != null) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                }
            }
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            callback.invoke(origin, true, false);
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.grant(request.getResources());
        }
        
        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
            MainActivity.this.filePathCallback = filePathCallback;
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(Intent.createChooser(intent, "File Chooser"), REQUEST_FILE_PICKER);
            return true;
        }
    }

    private class SpoonDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(fileName);
            request.setDescription("Downloading...");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                Toast.makeText(MainActivity.this, "Download started", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE_PICKER && filePathCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }
}
