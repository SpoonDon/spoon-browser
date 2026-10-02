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
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * Owns the main menu (routed through {@link MainMenuDialog}), the Settings
 * submenu, the Ad Blocking dialog, the "About" bottom sheet, the search
 * engine picker, the "Find in Page" overlay, and the "Allow HTTP sites"
 * (cleartext hosts) manager.
 *
 * 2026-10-02: The main menu is no longer a PopupMenu. It's a custom
 * AlertDialog+LinearLayout grouped into sections, built by
 * {@link MainMenuDialog}. The {@link Callbacks} interface is unchanged —
 * only the surface presentation moved.
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

    /**
     * Shows the main menu. The {@code anchor} argument is retained for
     * source compatibility with the old PopupMenu implementation but is
     * no longer used — the new dialog is centered, not anchored.
     */
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
        String[] items = {
                "Search engine",
                "Allow HTTP sites",
                "Clear cache",
                "Clear history",
                "Startup animation",
                "Manage filter lists",
                "Import passwords (CSV)",
                "Export passwords (CSV)"
        };
        new AlertDialog.Builder(activity)
                .setTitle("Settings")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: showSearchEngineDialog();            break;
                        case 1: showCleartextHostsDialog();          break;
                        case 2: callbacks.clearCache();              break;
                        case 3: callbacks.clearHistory();            break;
                        case 4: callbacks.toggleStartupAnimation();  break;
                        case 5: callbacks.showFilterLists();         break;
                        case 6: callbacks.importPasswords();         break;
                        case 7: callbacks.exportPasswords();         break;
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    // ------------------------------------------------------------------------
    // Ad Blocking submenu
    // ------------------------------------------------------------------------

    /**
     * Small dialog offering the enable/disable toggle plus a path into the
     * filter-list manager. The top-level menu uses a toggle row directly,
     * but the user can also reach the manager from here.
     */
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

    /**
     * Placeholder for the allowlist — the real dialog lives inside
     * {@code AdBlockController}. We just hand off to the same code path
     * the "Manage filter lists" dialog uses.
     */
    private void showSiteAllowlistStub() {
        // AdBlockController already surfaces the allowlist from inside
        // the filter-lists dialog; reuse that entry point rather than
        // duplicating the UI here.
        callbacks.showFilterLists();
    }

    // ------------------------------------------------------------------------
    // Allow HTTP sites (formerly: Trusted cleartext hosts)
    // ------------------------------------------------------------------------
    private void showCleartextHostsDialog() {
        Set<String> hosts = CleartextPreferences.getUserHosts(activity);
        ArrayList<String> list = new ArrayList<>(hosts);
        Collections.sort(list);

        if (list.isEmpty()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Allow HTTP sites")
                    .setMessage("No sites added yet.\n\n"
                            + "Sites added here are allowed to load over http:// "
                            + "instead of being auto-upgraded to https://.\n\n"
                            + "Use this for routers, local devices, and development "
                            + "hosts that don't support HTTPS. The built-in list "
                            + "(router brands, gateway IPs, localhost, emulator host, "
                            + "mDNS) is always trusted.")
                    .setPositiveButton("Add site", (d, w) -> showAddCleartextHostDialog())
                    .setNegativeButton("Close", null)
                    .show();
            return;
        }

        ListView listView = new ListView(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_list_item_1, list);
        listView.setAdapter(adapter);

        listView.setOnItemLongClickListener((parent, view, pos, id) -> {
            String host = list.get(pos);
            new AlertDialog.Builder(activity)
                    .setTitle("Remove trusted site?")
                    .setMessage(host)
                    .setPositiveButton("Remove", (d, w) -> {
                        CleartextPreferences.removeUserHost(activity, host);
                        Toast.makeText(activity, "Removed " + host,
                                Toast.LENGTH_SHORT).show();
                        showCleartextHostsDialog();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });

        new AlertDialog.Builder(activity)
                .setTitle("Allow HTTP sites")
                .setMessage("Long-press a site to remove it.")
                .setView(listView)
                .setPositiveButton("Add site", (d, w) -> showAddCleartextHostDialog())
                .setNegativeButton("Close", null)
                .show();
    }

    private void showAddCleartextHostDialog() {
        EditText input = new EditText(activity);
        input.setHint("192.168.1.100  or  router.example.com");

        new AlertDialog.Builder(activity)
                .setTitle("Add site")
                .setMessage("HTTP loads for this site will not be upgraded to HTTPS.")
                .setView(input)
                .setPositiveButton("Add", (d, w) -> {
                    String raw = input.getText().toString().trim();
                    if (raw.isEmpty()) return;

                    String normalized = raw.toLowerCase(Locale.ROOT);
                    if (normalized.contains("://")) {
                        normalized = normalized.substring(normalized.indexOf("://") + 3);
                    }
                    if (normalized.contains("/")) {
                        normalized = normalized.substring(0, normalized.indexOf('/'));
                    }
                    if (normalized.contains(":")) {
                        normalized = normalized.split(":")[0];
                    }
                    normalized = normalized.trim();
                    if (normalized.isEmpty()) return;

                    CleartextPreferences.addUserHost(activity, normalized);
                    Toast.makeText(activity, "Added " + normalized,
                            Toast.LENGTH_SHORT).show();
                    showCleartextHostsDialog();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------------
    // Search engine
    // ------------------------------------------------------------------------
    public void showSearchEngineDialog() {
        String current = prefs != null ? prefs.getString(KEY_SEARCH_ENGINE, "brave") : "brave";
        final String[] engines = {"Brave", "Google", "DuckDuckGo"};
        final String[] values = {"brave", "google", "duckduckgo"};

        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) { checked = i; break; }
        }

        new AlertDialog.Builder(activity)
                .setTitle("Search Engine")
                .setSingleChoiceItems(engines, checked, (dialog, which) -> {
                    prefs.edit().putString(KEY_SEARCH_ENGINE, values[which]).apply();
                    dialog.dismiss();
                    Toast.makeText(activity,
                            engines[which] + " set as default",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
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
        prevBtn.setText("∧");
        prevBtn.setTextColor(Color.WHITE);
        prevBtn.setBackground(null);

        android.widget.Button nextBtn = new android.widget.Button(activity);
        nextBtn.setText("∨");
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
    // About bottom sheet
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
