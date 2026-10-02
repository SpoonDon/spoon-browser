package com.spoondon.browser;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * Owns the main menu, Settings submenu, Ad Blocking dialog, "About" bottom
 * sheet, search engine picker, "Find in Page" overlay, and "Allow HTTP sites"
 * manager.
 *
 * 2026-10-03 (v6):
 *   - Settings submenu routed through {@link SettingsDialog} with startup
 *     animation toggle indicator.
 *   - Search engine picker routed through {@link SearchEngineDialog}.
 *   - Allow HTTP sites manager routed through {@link HttpSitesDialog}.
 *   - All three share the polished menu visual language.
 */
public class MenuController {

    public static final String KEY_SEARCH_ENGINE = "search_engine";

    // ------------------------------------------------------------------------
    // Callbacks (unchanged)
    // ------------------------------------------------------------------------
    public interface Callbacks {
        void newTab(boolean incognito);
        void reload();
        void showDownloads();
        void findInPage();

        void showBookmarks();
        void addBookmark();
        void showHistory();
        void clearHistory();
        void clearCache();

        void showFilterLists();
        void toggleFilterEngine();

        void toggleDesktopMode();
        boolean isDesktopEnabledForCurrentSite();

        void showVault();

        void showSavedPasswords();
        void importPasswords();
        void exportPasswords();

        void showVaultForCurrentSite();
        void toggleStartupAnimation();
        void exit();

        @Nullable WebView getCurrentWebView();
        @NonNull Context getContext();
        @NonNull SharedPreferences getPreferences();
    }

    // ------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------
    private final MainActivity activity;
    private final Callbacks callbacks;
    private final SharedPreferences prefs;

    public MenuController(@NonNull MainActivity activity,
                          @NonNull SharedPreferences prefs,
                          @NonNull Callbacks callbacks) {
        this.activity = activity;
        this.prefs = prefs;
        this.callbacks = callbacks;
    }

    // ------------------------------------------------------------------------
    // Main menu (routes through MainMenuDialog)
    // ------------------------------------------------------------------------
    public void showMainMenu(@NonNull View anchor) {
        boolean desktopOn = callbacks.isDesktopEnabledForCurrentSite();
        boolean adBlockOn = AdBlockEngine.checkIsEngineEnabled(activity);
        MainMenuDialog.show(activity, desktopOn, adBlockOn, this::handleAction);
    }

    private void handleAction(@NonNull String actionId) {
        switch (actionId) {
            case MainMenuDialog.ACTION_NEW_TAB:         callbacks.newTab(false);          return;
            case MainMenuDialog.ACTION_NEW_INCOGNITO:   callbacks.newTab(true);           return;
            case MainMenuDialog.ACTION_RELOAD:          callbacks.reload();               return;
            case MainMenuDialog.ACTION_FIND_IN_PAGE:    callbacks.findInPage();           return;
            case MainMenuDialog.ACTION_TOGGLE_DESKTOP:  callbacks.toggleDesktopMode();    return;
            case MainMenuDialog.ACTION_BOOKMARKS:       callbacks.showBookmarks();        return;
            case MainMenuDialog.ACTION_ADD_BOOKMARK:    callbacks.addBookmark();          return;
            case MainMenuDialog.ACTION_HISTORY:         callbacks.showHistory();          return;
            case MainMenuDialog.ACTION_DOWNLOADS:       callbacks.showDownloads();        return;
            case MainMenuDialog.ACTION_PASSWORDS:       callbacks.showVault();            return;
            case MainMenuDialog.ACTION_AD_BLOCKING:     showAdBlockingDialog();           return;
            case MainMenuDialog.ACTION_SEARCH_ENGINE:   showSearchEngineDialog();         return;
            case MainMenuDialog.ACTION_CLEARTEXT_HOSTS: showCleartextHostsDialog();       return;
            case MainMenuDialog.ACTION_SETTINGS:        showSettingsDialog();             return;
            case MainMenuDialog.ACTION_ABOUT:           showAbout();                      return;
            case MainMenuDialog.ACTION_EXIT:            callbacks.exit();                 return;
        }
    }

    // ------------------------------------------------------------------------
    // Settings submenu
    // ------------------------------------------------------------------------

    private void showSettingsDialog() {
        boolean startupOn = isStartupAnimationOn();
        SettingsDialog.show(activity, startupOn, this::handleSettingsAction);
    }

    private void handleSettingsAction(@NonNull String actionId) {
        switch (actionId) {
            case SettingsDialog.ACTION_SEARCH_ENGINE:
                showSearchEngineDialog();
                return;
            case SettingsDialog.ACTION_ALLOW_HTTP:
                showCleartextHostsDialog();
                return;
            case SettingsDialog.ACTION_CLEAR_CACHE:
                callbacks.clearCache();
                return;
            case SettingsDialog.ACTION_CLEAR_HISTORY:
                callbacks.clearHistory();
                return;
            case SettingsDialog.ACTION_STARTUP_ANIMATION:
                callbacks.toggleStartupAnimation();
                return;
            case SettingsDialog.ACTION_FILTER_LISTS:
                callbacks.showFilterLists();
                return;
            case SettingsDialog.ACTION_IMPORT_PASSWORDS:
                callbacks.importPasswords();
                return;
            case SettingsDialog.ACTION_EXPORT_PASSWORDS:
                callbacks.exportPasswords();
                return;
        }
    }

    /** Reads the same pref MainActivity.toggleStartupAnimation() toggles. */
    private boolean isStartupAnimationOn() {
        SharedPreferences sp = activity.getSharedPreferences(
                "browser_prefs", Context.MODE_PRIVATE);
        return sp.getBoolean("show_splash_screen", true);
    }

    // ------------------------------------------------------------------------
    // Ad Blocking submenu
    // ------------------------------------------------------------------------
    private void showAdBlockingDialog() {
        boolean enabled = AdBlockEngine.checkIsEngineEnabled(activity);
        String[] options = {
                enabled ? "Disable ad blocking" : "Enable ad blocking",
                "Manage filter lists",
                "Site allowlist"
        };
        new AlertDialog.Builder(activity)
                .setTitle("Ad Blocking")
                .setItems(options, (d, which) -> {
                    if (which == 0)      callbacks.toggleFilterEngine();
                    else if (which == 1) callbacks.showFilterLists();
                    else if (which == 2) showSiteAllowlistStub();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showSiteAllowlistStub() {
        callbacks.showFilterLists();
    }

    // ------------------------------------------------------------------------
    // Allow HTTP sites (routes through HttpSitesDialog)
    // ------------------------------------------------------------------------
    private void showCleartextHostsDialog() {
        HttpSitesDialog.show(activity);
    }

    // ------------------------------------------------------------------------
    // Search engine (routes through SearchEngineDialog)
    // ------------------------------------------------------------------------
    public void showSearchEngineDialog() {
        String current = prefs != null
                ? prefs.getString(KEY_SEARCH_ENGINE, "brave")
                : "brave";
        SearchEngineDialog.show(activity, current, engine -> {
            if (prefs != null) {
                prefs.edit().putString(KEY_SEARCH_ENGINE, engine).apply();
            }
        });
    }

    public String getSearchUrlFor(@Nullable String query) {
        String engine = prefs != null
                ? prefs.getString(KEY_SEARCH_ENGINE, "brave") : "brave";
        String encoded = Uri.encode(query == null ? "" : query);
        switch (engine) {
            case "google":     return "https://www.google.com/search?q=" + encoded;
            case "duckduckgo": return "https://duckduckgo.com/?q=" + encoded;
            case "brave":
            default:           return "https://search.brave.com/search?q=" + encoded;
        }
    }

    // ------------------------------------------------------------------------
    // Find in page
    // ------------------------------------------------------------------------
    public void showFindInPageDialog(@Nullable WebView webView) {
        if (webView == null) return;

        ViewGroup rootLayout = activity.findViewById(android.R.id.content);
        View existing = rootLayout.findViewWithTag("spoon_find_bar");
        if (existing != null) rootLayout.removeView(existing);

        LinearLayout barLayout = new LinearLayout(activity);
        barLayout.setTag("spoon_find_bar");
        barLayout.setOrientation(LinearLayout.HORIZONTAL);
        barLayout.setGravity(Gravity.CENTER_VERTICAL);
        barLayout.setPadding(dp(20), dp(10), dp(20), dp(10));
        barLayout.setElevation(10f);

        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(24));
        shape.setColor(Color.parseColor("#B3121212"));
        shape.setStroke(2, Color.parseColor("#333333"));
        barLayout.setBackground(shape);

        EditText input = new EditText(activity);
        input.setHint("Find...");
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        input.setBackground(null);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        barLayout.addView(input, inputParams);

        TextView countText = new TextView(activity);
        countText.setText("0/0");
        countText.setTextColor(Color.LTGRAY);
        countText.setPadding(dp(15), 0, dp(15), 0);
        barLayout.addView(countText);

        android.widget.Button prevBtn = new android.widget.Button(activity);
        prevBtn.setText("\u2227"); // ∧
        prevBtn.setTextColor(Color.WHITE);
        prevBtn.setBackground(null);

        android.widget.Button nextBtn = new android.widget.Button(activity);
        nextBtn.setText("\u2228"); // ∨
        nextBtn.setTextColor(Color.WHITE);
        nextBtn.setBackground(null);

        android.widget.Button closeBtn = new android.widget.Button(activity);
        closeBtn.setText("X");
        closeBtn.setTextColor(Color.parseColor("#FF5555"));
        closeBtn.setBackground(null);

        barLayout.addView(prevBtn);
        barLayout.addView(nextBtn);
        barLayout.addView(closeBtn);

        webView.setFindListener((activeMatchOrdinal, numberOfMatches, isDoneCounting) ->
                countText.setText(numberOfMatches == 0
                        ? "0/0" : (activeMatchOrdinal + 1) + "/" + numberOfMatches));

        final Handler searchHandler = new Handler(Looper.getMainLooper());
        final Runnable[] searchRunnable = new Runnable[1];

        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (searchRunnable[0] != null) searchHandler.removeCallbacks(searchRunnable[0]);
                searchRunnable[0] = () -> webView.findAllAsync(s.toString());
                searchHandler.postDelayed(searchRunnable[0], 300);
            }
        });

        prevBtn.setOnClickListener(v -> webView.findNext(false));
        nextBtn.setOnClickListener(v -> webView.findNext(true));
        closeBtn.setOnClickListener(v -> {
            webView.clearMatches();
            rootLayout.removeView(barLayout);
        });

        android.widget.FrameLayout.LayoutParams params =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP;
        params.setMargins(dp(20), dp(40), dp(20), 0);

        rootLayout.addView(barLayout, params);
        input.requestFocus();
    }

    // ------------------------------------------------------------------------
    // About bottom sheet (unchanged)
    // ------------------------------------------------------------------------
    public void showAbout() {
        BottomSheetDialog bottomSheet = new BottomSheetDialog(activity);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(64), dp(64), dp(64), dp(80));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.parseColor("#1C1C1E"));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                dp(180), dp(180));
        iconParams.setMargins(0, dp(32), 0, dp(16));
        root.addView(icon, iconParams);

        TextView version = new TextView(activity);
        version.setText("Version " + getAppVersion());
        version.setTextSize(14);
        version.setTextColor(Color.parseColor("#8E8E93"));
        version.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        versionParams.setMargins(0, 0, 0, dp(48));
        root.addView(version, versionParams);

        LinearLayout statsContainer = new LinearLayout(activity);
        statsContainer.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadii(new float[]{32, 32, 32, 32, 32, 32, 32, 32});
        shape.setColor(Color.parseColor("#2C2C2E"));
        statsContainer.setBackground(shape);

        LinearLayout.LayoutParams statsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statsParams.setMargins(dp(32), 0, dp(32), dp(48));

        String webViewVer = "Unknown";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.content.pm.PackageInfo pi = WebView.getCurrentWebViewPackage();
            if (pi != null) webViewVer = pi.versionName;
        }

        statsContainer.addView(createStatRow("WebView Engine", webViewVer, true));
        statsContainer.addView(createStatRow("Tabs Open",
                String.valueOf(activity.getTabCount()), true));
        statsContainer.addView(createStatRow("Bookmarks Saved",
                String.valueOf(activity.getBookmarkCount()), true));
        statsContainer.addView(createStatRow("History Items",
                String.valueOf(activity.getHistoryCount()), true));

        View adblockRow = createStatRow("AdBlock Rules", "Loading...", false);
        statsContainer.addView(adblockRow);

        final Handler handler = new Handler(Looper.getMainLooper());
        Runnable updater = new Runnable() {
            @Override
            public void run() {
                if (bottomSheet.isShowing()) {
                    TextView txt = adblockRow.findViewWithTag("AdBlock Rules");
                    if (txt != null) {
                        txt.setText(String.valueOf(AdBlockEngine.getBlocklistSize()));
                    }
                    handler.postDelayed(this, 1000);
                }
            }
        };
        handler.postDelayed(updater, 1000);

        root.addView(statsContainer, statsParams);

        TextView signature = new TextView(activity);
        signature.setText("Built one green commit at a time.\n"
                + "Designed to evolve dynamically with Android WebView.\n\n"
                + "- with love, Plaban.");
        signature.setTextSize(13);
        signature.setGravity(Gravity.CENTER);
        signature.setTextColor(Color.parseColor("#636366"));
        signature.setLineSpacing(0, 1.2f);
        root.addView(signature);

        bottomSheet.setContentView(root);

        View internal = bottomSheet.findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (internal != null) internal.setBackgroundColor(Color.TRANSPARENT);

        bottomSheet.show();
    }

    private View createStatRow(String labelText, String valueText, boolean drawDivider) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(40), dp(32), dp(40), dp(32));

        TextView label = new TextView(activity);
        label.setText(labelText);
        label.setTextColor(Color.WHITE);
        label.setTextSize(15);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        row.addView(label, labelParams);

        TextView value = new TextView(activity);
        value.setText(valueText);
        value.setTextColor(Color.parseColor("#8E8E93"));
        value.setTextSize(15);
        value.setTag(labelText);
        value.setGravity(Gravity.END);
        row.addView(value);

        if (!drawDivider) return row;

        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(row);

        View divider = new View(activity);
        divider.setBackgroundColor(Color.parseColor("#3A3A3C"));
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 2);
        divParams.setMargins(dp(40), 0, 0, 0);
        wrapper.addView(divider, divParams);
        return wrapper;
    }

    private String getAppVersion() {
        try {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private int dp(int value) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics());
    }
}
