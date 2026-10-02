package com.spoondon.browser;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.view.View;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

/**
 * All ad-block UI: filter-list management, engine toggle, preset catalog,
 * subscription editor, auto-update interval.
 *
 * Phase-2 rewrite (2026-10-02):
 *   - Backing store is now AdBlockPreferences (rich JSON model with per-list
 *     title, source, enabled flag) instead of a newline-separated string.
 *   - The legacy CopyOnWriteArrayList<String> filterLists reference is kept
 *     and mutated in place so AppWiring's reference stays valid. AdBlockEngine
 *     still consumes a List<String> — which is now produced by
 *     AdBlockPreferences.enabledListUrls() and mirrored into filterLists.
 *   - Main menu replaced by a summary dialog that shows active/total lists
 *     and offers: Preset catalog, Manage subscriptions, Add custom URL,
 *     Import from clipboard, Update all, Auto-update interval.
 *   - Engine still uses AdBlockEngine.checkAndRefreshFilters as the refresh
 *     trigger. The parse stats (parsed/skipped) are cached into
 *     AdBlockPreferences after each refresh so the UI can display them
 *     without touching the engine.
 *
 * Preserved API (used by MenuController + AppWiring):
 *   isEngineEnabled()
 *   toggleEngine(WebView)
 *   showFilterListsDialog()
 *   KEY_FILTER_LISTS / KEY_FILTER_REFRESH_TIME constants
 *
 * Threading: all UI methods must be called from the UI thread. Engine
 * refresh work runs on backgroundExecutor.
 */
public class AdBlockController {

    // Legacy constants — kept because MainActivity may still reference them
    // during migration. Not written by this class anymore.
    public static final String KEY_FILTER_LISTS = "filter_lists";
    public static final String KEY_FILTER_REFRESH_TIME = "filter_refresh_time";

    private final MainActivity activity;
    private final CopyOnWriteArrayList<String> filterLists;
    private final ExecutorService backgroundExecutor;
    private final SharedPreferences prefs;
    private final AdBlockPreferences adPrefs;

    public AdBlockController(@NonNull MainActivity activity,
                             @NonNull CopyOnWriteArrayList<String> filterLists,
                             @NonNull ExecutorService backgroundExecutor,
                             @NonNull SharedPreferences prefs) {
        this.activity = activity;
        this.filterLists = filterLists;
        this.backgroundExecutor = backgroundExecutor;
        this.prefs = prefs;
        this.adPrefs = AdBlockPreferences.get(activity);
    }

    // ========================================================================
    // Engine toggle
    // ========================================================================

    public boolean isEngineEnabled() {
        return AdBlockEngine.checkIsEngineEnabled(activity);
    }

    /**
     * Flips the enabled state. If a WebView is provided and the state changed,
     * the page is reloaded so the new rule set takes effect immediately.
     */
    public void toggleEngine(@Nullable WebView webViewToReload) {
        boolean currentState = AdBlockEngine.checkIsEngineEnabled(activity);
        boolean newState = !currentState;
        AdBlockEngine.setEngineEnabled(activity, newState);

        Toast.makeText(activity,
                newState ? "Filterlists Enabled" : "Filterlists Disabled",
                Toast.LENGTH_SHORT).show();

        if (webViewToReload != null) webViewToReload.reload();
    }

    // ========================================================================
    // Engine sync — the bridge between AdBlockPreferences and the engine
    // ========================================================================

    /**
     * Pulls enabled URLs from AdBlockPreferences and mutates the shared
     * filterLists list in place so AppWiring's reference sees the same set.
     * Then triggers an engine refresh on the background executor.
     *
     * Call this after any mutation (subscribe, unsubscribe, setEnabled) or
     * at cold start.
     */
    public void syncEngineWithPrefs() {
        List<String> enabled = adPrefs.enabledListUrls();

        // Mutate in place — preserve the list reference held by AppWiring.
        // CopyOnWriteArrayList supports removeIf / addAll atomically enough
        // for this single-writer pattern.
        filterLists.removeIf(url -> !enabled.contains(url));
        for (String url : enabled) {
            if (!filterLists.contains(url)) filterLists.add(url);
        }

        if (filterLists.isEmpty()) {
            // Nothing to refresh — clear the engine's current rule set.
            AdBlockEngine.clearAllFilterLists(activity, backgroundExecutor);
            return;
        }
        AdBlockEngine.checkAndRefreshFilters(
                activity, backgroundExecutor, filterLists, true);
    }

    /**
     * Forces a full refresh of every enabled list without changing the set.
     * Used by "Update all subscriptions".
     */
    public void refreshAll() {
        if (filterLists.isEmpty()) {
            Toast.makeText(activity, "No lists subscribed", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(activity, "Updating filter lists in background...",
                Toast.LENGTH_SHORT).show();
        AdBlockEngine.checkAndRefreshFilters(
                activity, backgroundExecutor, filterLists, true);
        adPrefs.setLastAutoRefreshMillis(System.currentTimeMillis());
    }

    // ========================================================================
    // Main Filter Lists menu
    // ========================================================================

    /**
     * Entry point called by MenuController → "Filter Lists".
     *
     * Summary-first dialog: shows active count, engine state, and everything
     * the user can do. No deep navigation forced on them.
     */        
    public void showFilterListsDialog() {
        final CharSequence[] items = new CharSequence[] {
                "Preset catalog",
                "Manage subscriptions",
                "Add custom URL",
                "Import from clipboard",
                "Update all subscriptions",
                "Auto-update interval",
                "Clear all lists"
        };

        // Custom title area: heading + summary. This is the only reliable
        // way to combine an info block with a setItems() list — setMessage()
        // and setItems() collide in AlertController, and setCustomTitle()
        // sidesteps the conflict entirely.
        LinearLayout titleBox = new LinearLayout(activity);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(24);
        int padV = dp(16);
        titleBox.setPadding(padH, padV, padH, dp(8));

        TextView heading = new TextView(activity);
        heading.setText("Filter Lists");
        heading.setTextSize(20);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBox.addView(heading);

        TextView summary = new TextView(activity);
        summary.setText(buildSummaryText());
        summary.setTextSize(13);
        summary.setPadding(0, dp(8), 0, 0);
        titleBox.addView(summary);

        new AlertDialog.Builder(activity)
                .setCustomTitle(titleBox)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: showPresetCatalog(); break;
                        case 1: showManageSubscriptions(); break;
                        case 2: showAddCustomListDialog(); break;
                        case 3: showImportFromClipboardDialog(); break;
                        case 4: refreshAll(); break;
                        case 5: showAutoUpdateDialog(); break;
                        case 6: confirmClearAll(); break;
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private int dp(int value) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics());
    }

    /** Row in the main dialog list — header rows are non-clickable. */
    private static final class Row {
        final CharSequence label;
        final int actionId;   // -1 = header/spacer
        Row(CharSequence label, int actionId) {
            this.label = label;
            this.actionId = actionId;
        }
        static Row header(CharSequence s) { return new Row(s, -1); }
        static Row spacer()               { return new Row(" ", -1); }
        static Row action(CharSequence s, int id) { return new Row(s, id); }
    }

    /**
     * Summary text shown at the top of the main dialog.
     *
     * Format:
     *   Filterlists: Enabled / Disabled
     *   3 of 5 lists active
     *   Last parse: 45210 rules loaded, 87 skipped
     */
    @NonNull
    private String buildSummaryText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Filterlists: ")
          .append(isEngineEnabled() ? "Enabled" : "Disabled")
          .append('\n');
        sb.append(adPrefs.summaryLine());

        int parsed = adPrefs.getLastParsedCount();
        int skipped = adPrefs.getLastSkippedCount();
        if (parsed > 0 || skipped > 0) {
            sb.append('\n')
              .append("Last parse: ")
              .append(parsed)
              .append(" rules loaded");
            if (skipped > 0) {
                sb.append(", ")
                  .append(skipped)
                  .append(" skipped (unsupported syntax)");
            }
        }

        int autoHours = adPrefs.getAutoUpdateHours();
        if (autoHours > 0) {
            sb.append('\n')
              .append("Auto-update: every ")
              .append(formatHours(autoHours));
        } else {
            sb.append('\n').append("Auto-update: off");
        }

        return sb.toString();
    }

    @NonNull
    private static String formatHours(int h) {
        if (h == 6) return "6 hours";
        if (h == 12) return "12 hours";
        if (h == 24) return "day";
        if (h == 72) return "3 days";
        if (h == 168) return "week";
        return h + "h";
    }

    private void confirmClearAll() {
        if (adPrefs.size() == 0) {
            Toast.makeText(activity, "Nothing to clear", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Clear all lists?")
                .setMessage("This removes every subscription and wipes the "
                        + "downloaded filter files. Ad blocking will stop until "
                        + "you subscribe to a list again.")
                .setPositiveButton("Clear", (d, w) -> {
                    adPrefs.allLists().forEach(l -> adPrefs.unsubscribe(l.url));
                    filterLists.clear();
                    AdBlockEngine.clearAllFilterLists(activity, backgroundExecutor);
                    adPrefs.setLastParseStats(0, 0);
                    Toast.makeText(activity, "All filter lists cleared",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ========================================================================
    // Cache engine stats after a refresh (called externally, from AppWiring)
    // ========================================================================

    /**
     * Called by AppWiring (or the user) once the engine has finished a
     * refresh. Caches the numbers so the summary dialog can display them
     * without poking the engine directly.
     */
    public void cacheEngineStats() {
        adPrefs.setLastParseStats(
                AdBlockEngine.getLastParsedRuleCount(),
                AdBlockEngine.getLastSkippedCount());
    }

    // ========================================================================
    // Preset catalog
    // ========================================================================

    /**
     * Shows the built-in catalog of known filter lists. Tapping a row
     * subscribes (if not already). Subscribed entries show a checkmark
     * prefix and tapping them unsubscribes.
     */
    private void showPresetCatalog() {
        final List<AdBlockPreferences.Preset> catalog = AdBlockPreferences.presetCatalog();
        final List<String> labels = new ArrayList<>(catalog.size());

        for (AdBlockPreferences.Preset p : catalog) {
            boolean subbed = adPrefs.isSubscribed(p.url);
            String prefix = subbed ? "\u2713 " : "";
            labels.add(prefix + p.title + "\n" + p.description);
        }

        new AlertDialog.Builder(activity)
                .setTitle("Preset Catalog")
                .setAdapter(new ArrayAdapter<>(
                                activity,
                                android.R.layout.simple_list_item_1,
                                labels),
                        (dialog, which) -> {
                            AdBlockPreferences.Preset p = catalog.get(which);
                            if (adPrefs.isSubscribed(p.url)) {
                                adPrefs.unsubscribe(p.url);
                                Toast.makeText(activity, "Unsubscribed: " + p.title,
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                adPrefs.subscribe(p.url, p.title,
                                        AdBlockPreferences.SOURCE_PRESET);
                                Toast.makeText(activity, "Subscribed: " + p.title,
                                        Toast.LENGTH_SHORT).show();
                            }
                            syncEngineWithPrefs();
                            showPresetCatalog();
                        })
                .setNegativeButton("Close", null)
                .show();
    }

    // ========================================================================
    // Manage subscriptions
    // ========================================================================

    /**
     * Shows every subscribed list.
     *
     * Tap    -> toggle enabled/disabled
     * Long   -> remove / rename
     */
    private void showManageSubscriptions() {
        final List<AdBlockPreferences.SubscribedList> lists = adPrefs.allLists();
        if (lists.isEmpty()) {
            Toast.makeText(activity, "No subscriptions yet",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        final List<String> labels = new ArrayList<>(lists.size());
        for (AdBlockPreferences.SubscribedList l : lists) {
            String box = l.enabled ? "[\u2713] " : "[ ] ";
            String subtitle = l.url;
            labels.add(box + l.title + "\n" + subtitle);
        }

        ListView lv = new ListView(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_list_item_1, labels);
        lv.setAdapter(adapter);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Manage Subscriptions")
                .setView(lv)
                .setPositiveButton("Update All", (d, w) -> refreshAll())
                .setNegativeButton("Close", null)
                .create();

        lv.setOnItemClickListener((parent, view, position, id) -> {
            AdBlockPreferences.SubscribedList l = lists.get(position);
            boolean nowEnabled = !l.enabled;
            adPrefs.setEnabled(l.url, nowEnabled);
            Toast.makeText(activity,
                    (nowEnabled ? "Enabled: " : "Disabled: ") + l.title,
                    Toast.LENGTH_SHORT).show();
            syncEngineWithPrefs();
            dialog.dismiss();
            showManageSubscriptions();
        });

        lv.setOnItemLongClickListener((parent, view, position, id) -> {
            AdBlockPreferences.SubscribedList l = lists.get(position);
            showListContextMenu(l);
            return true;
        });

        dialog.show();
    }

    /**
     * Long-press context menu for a single subscription:
     * rename or remove.
     */
    private void showListContextMenu(@NonNull AdBlockPreferences.SubscribedList list) {
        final CharSequence[] items = new CharSequence[] {
                "Rename",
                "Remove"
        };
        new AlertDialog.Builder(activity)
                .setTitle(list.title)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        showRenameDialog(list);
                    } else if (which == 1) {
                        confirmRemove(list);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRenameDialog(@NonNull AdBlockPreferences.SubscribedList list) {
        final EditText input = new EditText(activity);
        input.setText(list.title);
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(activity)
                .setTitle("Rename List")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (adPrefs.setTitle(list.url, name)) {
                        Toast.makeText(activity, "Renamed", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmRemove(@NonNull AdBlockPreferences.SubscribedList list) {
        new AlertDialog.Builder(activity)
                .setTitle("Remove subscription?")
                .setMessage(list.title + "\n\n" + list.url)
                .setPositiveButton("Remove", (d, w) -> {
                    adPrefs.unsubscribe(list.url);
                    syncEngineWithPrefs();
                    Toast.makeText(activity, "Removed", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ========================================================================
    // Add custom list
    // ========================================================================

    private void showAddCustomListDialog() {
        final EditText urlInput = new EditText(activity);
        urlInput.setHint("https://example.com/filter.txt");

        new AlertDialog.Builder(activity)
                .setTitle("Add Custom Filter List")
                .setMessage("Enter the full URL of a filter list (must start with https://)")
                .setView(urlInput)
                .setPositiveButton("Add", (d, w) -> {
                    String url = urlInput.getText().toString().trim();
                    if (url.isEmpty()) return;
                    if (!url.toLowerCase(Locale.ROOT).startsWith("https://")) {
                        Toast.makeText(activity,
                                "Only https:// URLs are supported",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (adPrefs.subscribe(url, null, AdBlockPreferences.SOURCE_CUSTOM)) {
                        Toast.makeText(activity, "Added — downloading…",
                                Toast.LENGTH_SHORT).show();
                        syncEngineWithPrefs();
                    } else {
                        Toast.makeText(activity, "Already subscribed",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ========================================================================
    // Import from clipboard
    // ========================================================================

    /**
     * Reads the system clipboard. Any https:// lines in it are treated as
     * candidate filter list URLs. Shows a confirmation with the count
     * before committing, so an accidental clipboard read never silently
     * subscribes the user to something.
     */
    private void showImportFromClipboardDialog() {
        ClipboardManager cm = (ClipboardManager)
                activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            Toast.makeText(activity, "Clipboard is empty",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Toast.makeText(activity, "Clipboard is empty",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence textCs = clip.getItemAt(0).coerceToText(activity);
        String text = textCs != null ? textCs.toString() : "";

        final List<String> candidates = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (!line.toLowerCase(Locale.ROOT).startsWith("https://")) continue;
            if (adPrefs.isSubscribed(line)) continue;
            if (!candidates.contains(line)) candidates.add(line);
        }

        if (candidates.isEmpty()) {
            Toast.makeText(activity,
                    "No new list URLs found in clipboard",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder preview = new StringBuilder();
        preview.append("Found ")
               .append(candidates.size())
               .append(candidates.size() == 1 ? " URL:" : " URLs:")
               .append("\n\n");
        int shown = Math.min(candidates.size(), 6);
        for (int i = 0; i < shown; i++) {
            preview.append("• ").append(candidates.get(i)).append('\n');
        }
        if (candidates.size() > shown) {
            preview.append("… and ")
                   .append(candidates.size() - shown)
                   .append(" more");
        }

        new AlertDialog.Builder(activity)
                .setTitle("Import from Clipboard")
                .setMessage(preview.toString())
                .setPositiveButton("Import", (d, w) -> {
                    int added = 0;
                    for (String url : candidates) {
                        if (adPrefs.subscribe(url, null,
                                AdBlockPreferences.SOURCE_CUSTOM)) {
                            added++;
                        }
                    }
                    if (added > 0) {
                        Toast.makeText(activity,
                                "Imported " + added + " list" + (added == 1 ? "" : "s"),
                                Toast.LENGTH_SHORT).show();
                        syncEngineWithPrefs();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ========================================================================
    // Auto-update interval
    // ========================================================================

    private void showAutoUpdateDialog() {
        final int[] hours = new int[] {
                AdBlockPreferences.UPDATE_OFF,
                AdBlockPreferences.UPDATE_6H,
                AdBlockPreferences.UPDATE_12H,
                AdBlockPreferences.UPDATE_24H,
                AdBlockPreferences.UPDATE_72H,
                AdBlockPreferences.UPDATE_168H
        };
        final CharSequence[] labels = new CharSequence[] {
                "Off (manual only)",
                "Every 6 hours",
                "Every 12 hours",
                "Every day",
                "Every 3 days",
                "Every week"
        };
        int current = adPrefs.getAutoUpdateHours();
        int checked = 0;
        for (int i = 0; i < hours.length; i++) {
            if (hours[i] == current) { checked = i; break; }
        }

        new AlertDialog.Builder(activity)
                .setTitle("Auto-update Filter Lists")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    adPrefs.setAutoUpdateHours(hours[which]);
                    Toast.makeText(activity,
                            "Auto-update: " + labels[which],
                            Toast.LENGTH_SHORT).show();
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
