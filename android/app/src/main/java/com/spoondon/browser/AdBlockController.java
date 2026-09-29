package com.spoondon.browser;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

/**
 * All ad-block UI: filter-list subscription dialog, subscribed-list viewer,
 * and the engine enable/disable toggle.
 *
 * Extracted from MainActivity (god-object split, slice 3).
 *
 * The {@code filterLists} reference is shared with MainActivity; mutations
 * here are visible there and vice versa. It is a CopyOnWriteArrayList so
 * iteration is safe from any thread.
 *
 * NOTE: the executor must be an ExecutorService (not just Executor) because
 * AdBlockEngine.checkAndRefreshFilters / removeFilterList / clearAllFilterLists
 * all require ExecutorService.
 */
public class AdBlockController {

    public static final String KEY_FILTER_LISTS = "filter_lists";
    public static final String KEY_FILTER_REFRESH_TIME = "filter_refresh_time";

    private final MainActivity activity;
    private final CopyOnWriteArrayList<String> filterLists;
    private final ExecutorService backgroundExecutor;
    private final SharedPreferences prefs;

    public AdBlockController(@NonNull MainActivity activity,
                             @NonNull CopyOnWriteArrayList<String> filterLists,
                             @NonNull ExecutorService backgroundExecutor,
                             @NonNull SharedPreferences prefs) {
        this.activity = activity;
        this.filterLists = filterLists;
        this.backgroundExecutor = backgroundExecutor;
        this.prefs = prefs;
    }

    // ------------------------------------------------------------------------
    // Engine toggle
    // ------------------------------------------------------------------------
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

    // ------------------------------------------------------------------------
    // Filter-list management
    // ------------------------------------------------------------------------
    public void showFilterListsDialog() {
        EditText input = new EditText(activity);
        new AlertDialog.Builder(activity)
                .setTitle("Subscribe Filter List")
                .setMessage("Subscribed: " + filterLists.size()
                        + "\n\nEnter filter list URL")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty() && !filterLists.contains(url)) {
                        filterLists.add(url);
                        AdBlockEngine.checkAndRefreshFilters(
                                activity, backgroundExecutor, filterLists, true);
                        saveFilterLists();
                    }
                })
                .setNeutralButton("More", (d, w) -> showFilterListOptions())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showFilterListOptions() {
        String[] options = {"View Subscriptions", "Add Custom Filter List", "Update All Subscriptions"};

        new AlertDialog.Builder(activity)
                .setTitle("Filter Lists")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showSubscribedFilterLists();
                    } else if (which == 1) {
                        showAddCustomListDialog();
                    } else if (which == 2) {
                        if (filterLists.isEmpty()) {
                            Toast.makeText(activity, "No lists to update",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(activity,
                                    "Updating filter lists in background...",
                                    Toast.LENGTH_SHORT).show();
                            AdBlockEngine.checkAndRefreshFilters(
                                    activity, backgroundExecutor, filterLists, true);
                        }
                    }
                })
                .show();
    }

    private void showAddCustomListDialog() {
        EditText input = new EditText(activity);
        input.setHint("https://...");

        new AlertDialog.Builder(activity)
                .setTitle("Add Filter List")
                .setView(input)
                .setPositiveButton("Add", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty() && !filterLists.contains(url)) {
                        filterLists.add(url);
                        saveFilterLists();
                        Toast.makeText(activity, "Downloading list...",
                                Toast.LENGTH_SHORT).show();
                        AdBlockEngine.checkAndRefreshFilters(
                                activity, backgroundExecutor, filterLists, true);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSubscribedFilterLists() {
        if (filterLists.isEmpty()) {
            Toast.makeText(activity, "No filter lists subscribed",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        ListView listView = new ListView(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_list_item_1, filterLists);
        listView.setAdapter(adapter);

        listView.setOnItemLongClickListener((parent, view, which, id) -> {
            String url = filterLists.get(which);
            new AlertDialog.Builder(activity)
                    .setTitle("Remove Filter List")
                    .setMessage(url)
                    .setPositiveButton("Remove", (d, w) -> {
                        filterLists.remove(url);
                        adapter.notifyDataSetChanged();
                        saveFilterLists();
                        AdBlockEngine.removeFilterList(
                                activity, url, filterLists, backgroundExecutor);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });

        new AlertDialog.Builder(activity)
                .setTitle("Subscribed Filter Lists")
                .setView(listView)
                .setPositiveButton("OK", null)
                .show();
    }

    private void saveFilterLists() {
        prefs.edit()
                .putString(KEY_FILTER_LISTS, TextUtils.join("\n", filterLists))
                .apply();
        AdBlockEngine.checkAndRefreshFilters(
                activity, backgroundExecutor, filterLists, true);
    }
}
