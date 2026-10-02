package com.spoondon.browser;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

/**
 * All ad-block UI.
 *
 * 2026-10-03 rewrite: every sub-dialog now routes through SpoonDialog so the
 * whole ad-block surface matches the polished menu visual language
 * (MainMenuDialog / FilterListsDialog / SettingsDialog). The raw
 * AlertDialog.Builder chrome is gone.
 *
 * Backing store is AdBlockPreferences (rich JSON model with per-list title,
 * source, enabled flag). The legacy CopyOnWriteArrayList<String> reference
 * held by AppWiring is kept and mutated in place so the engine still consumes
 * a List<String> produced by AdBlockPreferences.enabledListUrls().
 *
 * Preserved API (used by MenuController + AppWiring):
 *   isEngineEnabled()
 *   toggleEngine(WebView)
 *   showFilterListsDialog()
 *   syncEngineWithPrefs()
 *   refreshAll()
 *   cacheEngineStats()
 *   KEY_FILTER_LISTS / KEY_FILTER_REFRESH_TIME
 *
 * Threading: UI methods must be called from the UI thread. Engine refresh
 * work runs on backgroundExecutor.
 */
public class AdBlockController {

    // Legacy constants — kept for MainActivity migration.
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
    // Engine sync — AdBlockPreferences -> engine
    // ========================================================================

    public void syncEngineWithPrefs() {
        List<String> enabled = adPrefs.enabledListUrls();

        filterLists.removeIf(url -> !enabled.contains(url));
        for (String url : enabled) {
            if (!filterLists.contains(url)) filterLists.add(url);
        }

        if (filterLists.isEmpty()) {
            AdBlockEngine.clearAllFilterLists(activity, backgroundExecutor);
            return;
        }
        AdBlockEngine.checkAndRefreshFilters(
                activity, backgroundExecutor, filterLists, true);
    }

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
    // Main filter-lists menu — entry point from MenuController
    // ========================================================================

    public void showFilterListsDialog() {
        FilterListsDialog.show(activity, buildSummaryText(), actionId -> {
            switch (actionId) {
                case FilterListsDialog.ACTION_PRESET_CATALOG: showPresetCatalog(); break;
                case FilterListsDialog.ACTION_MANAGE_SUBS:    showManageSubscriptions(); break;
                case FilterListsDialog.ACTION_ADD_CUSTOM:     showAddCustomListDialog(); break;
                case FilterListsDialog.ACTION_IMPORT_CLIP:    showImportFromClipboardDialog(); break;
                case FilterListsDialog.ACTION_UPDATE_ALL:     refreshAll(); break;
                case FilterListsDialog.ACTION_AUTO_UPDATE:    showAutoUpdateDialog(); break;
                case FilterListsDialog.ACTION_SITE_ALLOWLIST: showSiteAllowlistDialog(); break;
                case FilterListsDialog.ACTION_CLEAR_ALL:      confirmClearAll(); break;
            }
        });
    }

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

    public void cacheEngineStats() {
        adPrefs.setLastParseStats(
                AdBlockEngine.getLastParsedRuleCount(),
                AdBlockEngine.getLastSkippedCount());
    }

    // ========================================================================
    // Preset catalog — polished list
    // ========================================================================

    private void showPresetCatalog() {
        final List<AdBlockPreferences.Preset> catalog = AdBlockPreferences.presetCatalog();
        final List<SpoonDialog.Item> items = new ArrayList<>(catalog.size());

        for (AdBlockPreferences.Preset p : catalog) {
            boolean subbed = adPrefs.isSubscribed(p.url);
            items.add(new SpoonDialog.Item(
                    p.title,
                    p.description,
                    0,
                    subbed,
                    false));
        }

        SpoonDialog.list(activity, "Preset Catalog", items, which -> {
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
            showPresetCatalog();   // re-show so checkmarks reflect new state
        });
    }

    // ========================================================================
    // Manage subscriptions — tap toggles, long-press opens context menu
    // ========================================================================

    private void showManageSubscriptions() {
        final List<AdBlockPreferences.SubscribedList> lists = adPrefs.allLists();
        if (lists.isEmpty()) {
            Toast.makeText(activity, "No subscriptions yet",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        final List<SpoonDialog.Item> items = new ArrayList<>(lists.size());
        for (AdBlockPreferences.SubscribedList l : lists) {
            items.add(new SpoonDialog.Item(
                    l.title,
                    l.url,
                    0,
                    l.enabled,
                    false));
        }

        SpoonDialog.list(activity, "Manage Subscriptions", items,
                // tap = toggle enabled
                which -> {
                    AdBlockPreferences.SubscribedList l = lists.get(which);
                    boolean nowEnabled = !l.enabled;
                    adPrefs.setEnabled(l.url, nowEnabled);
                    Toast.makeText(activity,
                            (nowEnabled ? "Enabled: " : "Disabled: ") + l.title,
                            Toast.LENGTH_SHORT).show();
                    syncEngineWithPrefs();
                    showManageSubscriptions();
                },
                // long-press = context menu
                (which, anchor) -> {
                    AdBlockPreferences.SubscribedList l = lists.get(which);
                    showListContextMenu(l);
                },
                "Update All",
                this::refreshAll);
    }

    private void showListContextMenu(@NonNull AdBlockPreferences.SubscribedList list) {
        final List<SpoonDialog.Item> items = new ArrayList<>(2);
        items.add(new SpoonDialog.Item("Rename", null, 0, false, false));
        items.add(new SpoonDialog.Item("Remove", null, 0, false, true));

        SpoonDialog.list(activity, list.title, items, which -> {
            if (which == 0) {
                showRenameDialog(list);
            } else if (which == 1) {
                confirmRemove(list);
            }
        });
    }

    private void showRenameDialog(@NonNull AdBlockPreferences.SubscribedList list) {
        SpoonDialog.input(activity,
                "Rename List",
                null,
                "List name",
                list.title,
                "Save",
                name -> {
                    if (adPrefs.setTitle(list.url, name)) {
                        Toast.makeText(activity, "Renamed",
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void confirmRemove(@NonNull AdBlockPreferences.SubscribedList list) {
        SpoonDialog.confirm(activity,
                "Remove subscription?",
                list.title + "\n\n" + list.url,
                "Remove",
                "Cancel",
                () -> {
                    adPrefs.unsubscribe(list.url);
                    syncEngineWithPrefs();
                    Toast.makeText(activity, "Removed", Toast.LENGTH_SHORT).show();
                });
    }

    // ========================================================================
    // Add custom filter list
    // ========================================================================

    private void showAddCustomListDialog() {
        SpoonDialog.input(activity,
                "Add Custom Filter List",
                "Enter the full URL of a filter list (must start with https://)",
                "https://example.com/filter.txt",
                null,
                "Add",
                url -> {
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
                });
    }

    // ========================================================================
    // Import filter list URLs from clipboard
    // ========================================================================

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

        SpoonDialog.confirm(activity,
                "Import from Clipboard",
                preview.toString(),
                "Import",
                "Cancel",
                () -> {
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
                });
    }

    // ========================================================================
    // Auto-update interval — polished single-choice
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
        final String[] labels = new String[] {
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

        SpoonDialog.singleChoice(activity,
                "Auto-update Filter Lists",
                labels,
                checked,
                which -> {
                    adPrefs.setAutoUpdateHours(hours[which]);
                    Toast.makeText(activity,
                            "Auto-update: " + labels[which],
                            Toast.LENGTH_SHORT).show();
                });
    }

    // ========================================================================
    // Site allowlist — tap a row to remove, footer to add
    // ========================================================================

    private void showSiteAllowlistDialog() {
        final List<String> hosts = AdBlockEngine.getWhitelistedDomainsSorted();

        if (hosts.isEmpty()) {
            SpoonDialog.message(activity,
                    "Site allowlist",
                    "Ads are blocked everywhere. Add a host here to allow ads on that site.\n\n"
                            + "Subdomains are covered: adding google.com also allows "
                            + "mail.google.com, but NOT evil-google.com.",
                    "Add host",
                    "Close",
                    this::showAddAllowlistHostDialog);
            return;
        }

        final List<SpoonDialog.Item> items = new ArrayList<>(hosts.size());
        for (String host : hosts) {
            items.add(new SpoonDialog.Item(host, null, 0, false, false));
        }

        SpoonDialog.list(activity, "Site allowlist (" + hosts.size() + ")", items,
                which -> confirmRemoveAllowlist(hosts.get(which)),
                null,
                "Add host",
                this::showAddAllowlistHostDialog);
    }

    private void confirmRemoveAllowlist(@NonNull String host) {
        SpoonDialog.confirm(activity,
                "Remove from allowlist?",
                "Ads will be blocked again on " + host + " and its subdomains.",
                "Remove",
                "Cancel",
                () -> {
                    AdBlockEngine.removeWhitelistedDomain(activity, host);
                    Toast.makeText(activity,
                            "Ads will be blocked on " + host,
                            Toast.LENGTH_SHORT).show();
                });
    }

    private void showAddAllowlistHostDialog() {
        SpoonDialog.input(activity,
                "Add host to allowlist",
                "Ads will be allowed on this host and its subdomains.",
                "example.com",
                null,
                "Add",
                raw -> {
                    if (raw.isEmpty()) return;
                    String before = AdBlockEngine.getWhitelistSize() > 0
                            ? AdBlockEngine.getWhitelistedDomainsSorted().toString()
                            : "";
                    AdBlockEngine.addWhitelistedDomain(activity, raw);
                    String after = AdBlockEngine.getWhitelistedDomainsSorted().toString();
                    if (before.equals(after)) {
                        Toast.makeText(activity,
                                "Already in allowlist (or invalid host)",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(activity,
                                "Ads allowed on " + raw,
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // ========================================================================
    // Clear-all confirmations
    // ========================================================================

    private void confirmClearAll() {
        if (adPrefs.size() == 0) {
            Toast.makeText(activity, "Nothing to clear", Toast.LENGTH_SHORT).show();
            return;
        }
        SpoonDialog.confirm(activity,
                "Clear all lists?",
                "This removes every subscription and wipes the downloaded filter files. "
                        + "Ad blocking will stop until you subscribe to a list again.",
                "Clear",
                "Cancel",
                () -> {
                    adPrefs.allLists().forEach(l -> adPrefs.unsubscribe(l.url));
                    filterLists.clear();
                    AdBlockEngine.clearAllFilterLists(activity, backgroundExecutor);
                    adPrefs.setLastParseStats(0, 0);
                    Toast.makeText(activity, "All filter lists cleared",
                            Toast.LENGTH_SHORT).show();
                });
    }
}
