package com.spoondon.browser;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Owns the main menu, Settings submenu, Ad Blocking dialog, "About" dialog,
 * search engine picker, "Find in Page" overlay, and "Allow HTTP sites" manager.
 *
 * 2026-10-03 (v7): About re-skinned from BottomSheetDialog to shared Dialog
 * chrome (matches Downloads / Settings / Main Menu).
 */
public class MenuController {

    public static final String KEY_SEARCH_ENGINE = "search_engine";

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
    // Main menu
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
    // Allow HTTP sites
    // ------------------------------------------------------------------------
    private void showCleartextHostsDialog() {
        HttpSitesDialog.show(activity);
    }

    // ------------------------------------------------------------------------
    // Search engine
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
        prevBtn.setText(String.valueOf((char) 0x2227));
        prevBtn.setTextColor(Color.WHITE);
        prevBtn.setBackground(null);

        android.widget.Button nextBtn = new android.widget.Button(activity);
        nextBtn.setText(String.valueOf((char) 0x2228));
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
    // About dialog (modernized — zero backslash escapes)
    // ------------------------------------------------------------------------
    public void showAbout() {
        final Context ctx = activity;
        final char NL = (char) 10;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(0xFF1B1B1D);
        rootBg.setCornerRadius(dp(14));
        root.setBackground(rootBg);

        // ---- header ----
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(14), dp(12), dp(12));

        TextView title = new TextView(ctx);
        title.setText("About");
        title.setTextColor(0xFFEDEDED);
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(ctx);
        close.setText(String.valueOf((char) 0x2715));
        close.setTextColor(0xFF9A9A9A);
        close.setTextSize(16);
        close.setPadding(dp(14), dp(6), dp(4), dp(6));
        header.addView(close);

        root.addView(header);

        View headerDivider = new View(ctx);
        headerDivider.setBackgroundColor(0xFF2C2C2E);
        root.addView(headerDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));

        // ---- scrollable body ----
        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(dp(24), dp(24), dp(24), dp(20));
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- launcher icon ----
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.mipmap.ic_launcher);
        body.addView(icon, new LinearLayout.LayoutParams(dp(84), dp(84)));

        // ---- app name ----
        TextView name = new TextView(ctx);
        name.setText("Spoon Browser");
        name.setTextColor(0xFFEDEDED);
        name.setTextSize(19);
        name.setTypeface(null, Typeface.BOLD);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameP.topMargin = dp(14);
        body.addView(name, nameP);

        // ---- version pill ----
        TextView versionPill = new TextView(ctx);
        versionPill.setText("v" + getAppVersion());
        versionPill.setTextColor(0xFF4D6BFE);
        versionPill.setTextSize(12);
        versionPill.setGravity(Gravity.CENTER);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0x1A4D6BFE);
        pillBg.setCornerRadius(dp(10));
        versionPill.setBackground(pillBg);
        versionPill.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.topMargin = dp(8);
        body.addView(versionPill, vp);

        // ---- stats card ----
        LinearLayout stats = new LinearLayout(ctx);
        stats.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF2C2C2E);
        cardBg.setCornerRadius(dp(12));
        stats.setBackground(cardBg);

        String webViewVer = "Unknown";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.content.pm.PackageInfo pi = WebView.getCurrentWebViewPackage();
            if (pi != null && pi.versionName != null) webViewVer = pi.versionName;
        }

        stats.addView(createStatRow("WebView",   webViewVer, true));
        stats.addView(createStatRow("Tabs",      String.valueOf(activity.getTabCount()), true));
        stats.addView(createStatRow("Bookmarks", String.valueOf(activity.getBookmarkCount()), true));
        stats.addView(createStatRow("History",   String.valueOf(activity.getHistoryCount()), true));

        View adBlockRow = createStatRow("Ad Block rules", "...", false);
        stats.addView(adBlockRow);

        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(22);
        body.addView(stats, sp);

        // ---- signature ----
        String sigText = "Built one green commit at a time." + NL
                + "Designed to evolve dynamically with Android WebView." + NL + NL
                + "- with love, Plaban.";
        TextView sig = new TextView(ctx);
        sig.setText(sigText);
        sig.setTextSize(12);
        sig.setGravity(Gravity.CENTER);
        sig.setTextColor(0xFF636366);
        sig.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams sigP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sigP.topMargin = dp(24);
        body.addView(sig, sigP);

        // ---- dialog ----
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.55f;
            lp.gravity = Gravity.CENTER;
            w.setAttributes(lp);
            w.setLayout(dp(400), dp(620));
        }

        final Handler handler = new Handler(Looper.getMainLooper());
        Runnable updater = new Runnable() {
            @Override
            public void run() {
                if (dialog.isShowing()) {
                    TextView tv = adBlockRow.findViewWithTag("stat_value");
                    if (tv != null) {
                        tv.setText(String.valueOf(AdBlockEngine.getBlocklistSize()));
                    }
                    handler.postDelayed(this, 1500);
                }
            }
        };

        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> handler.removeCallbacksAndMessages(null));

        dialog.show();
        handler.postDelayed(updater, 300);
    }

    private View createStatRow(String labelText, String valueText, boolean drawDivider) {
        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));

        TextView label = new TextView(activity);
        label.setText(labelText);
        label.setTextColor(0xFFEDEDED);
        label.setTextSize(14);
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(activity);
        value.setText(valueText);
        value.setTextColor(0xFF9A9A9A);
        value.setTextSize(14);
        value.setGravity(Gravity.END);
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        value.setTag("stat_value");
        row.addView(value);

        wrap.addView(row);

        if (drawDivider) {
            View divider = new View(activity);
            divider.setBackgroundColor(0xFF3A3A3C);
            LinearLayout.LayoutParams divP = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
            divP.leftMargin = dp(18);
            wrap.addView(divider, divP);
        }
        return wrap;
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
