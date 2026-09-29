package com.spoondon.browser;

import android.app.AlertDialog;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * All history and bookmark UI: the global history dialog, the bookmarks list,
 * and the one-shot legacy bookmark migration.
 *
 * Extracted from MainActivity (god-object split, slice 3).
 */
public class HistoryController {

    public interface Callbacks {
        /** Load a URL in the currently active tab. */
        void onNavigate(@NonNull String url);

        /** Open a URL in a new tab. */
        void openInNewTab(@NonNull String url);
    }

    private static final String LEGACY_BOOKMARKS_PREFS = "spoon_bookmarks";
    private static final String LEGACY_BOOKMARKS_KEY = "bookmarks";

    private final MainActivity activity;
    private final BrowserDatabaseHelper dbHelper;
    private final Executor backgroundExecutor;
    private final Callbacks callbacks;

    public HistoryController(@NonNull MainActivity activity,
                             @NonNull BrowserDatabaseHelper dbHelper,
                             @NonNull Executor backgroundExecutor,
                             @NonNull Callbacks callbacks) {
        this.activity = activity;
        this.dbHelper = dbHelper;
        this.backgroundExecutor = backgroundExecutor;
        this.callbacks = callbacks;
    }

    // ------------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------------
    public void showHistoryDialog() {
        if (dbHelper == null) return;

        List<String[]> historyData = dbHelper.getAllHistory();
        if (historyData.isEmpty()) {
            Toast.makeText(activity, "History is empty", Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayList<BrowserItem> items = new ArrayList<>();
        for (String[] entry : historyData) {
            String url = entry[0];
            String title = entry[1];
            items.add(new BrowserItem(
                    title != null && !title.isEmpty() ? title : url, url));
        }

        BrowserItemAdapter adapter = new BrowserItemAdapter(activity, items);
        ListView listView = new ListView(activity);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, which, id) ->
                callbacks.onNavigate(items.get(which).url));

        listView.setOnItemLongClickListener((parent, view, which, id) -> {
            String[] options = {"Open in New Tab", "Add Bookmark"};
            new AlertDialog.Builder(activity).setItems(options, (dialog, item) -> {
                if (item == 0) {
                    callbacks.openInNewTab(items.get(which).url);
                } else if (item == 1) {
                    dbHelper.addBookmark(items.get(which).url, items.get(which).title);
                    Toast.makeText(activity, "Bookmark added", Toast.LENGTH_SHORT).show();
                }
            }).show();
            return true;
        });

        new AlertDialog.Builder(activity)
                .setTitle("Global History")
                .setView(listView)
                .show();
    }

    public void clearHistory() {
        if (dbHelper != null) dbHelper.clearHistory();
        Toast.makeText(activity, "History cleared", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------------
    // Bookmarks
    // ------------------------------------------------------------------------
    public void showBookmarks() {
        if (dbHelper == null) return;

        List<String> bookmarkUrls = dbHelper.getAllBookmarksUrls();
        if (bookmarkUrls.isEmpty()) {
            Toast.makeText(activity, "No bookmarks saved", Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayList<BrowserItem> items = new ArrayList<>();
        for (String url : bookmarkUrls) {
            items.add(new BrowserItem(url, url));
        }

        BrowserItemAdapter adapter = new BrowserItemAdapter(activity, items);
        ListView listView = new ListView(activity);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, which, id) ->
                callbacks.onNavigate(items.get(which).url));

        listView.setOnItemLongClickListener((parent, view, which, id) -> {
            String[] options = {"Open", "Open in New Tab", "Remove Bookmark"};
            new AlertDialog.Builder(activity).setItems(options, (dialog, item) -> {
                if (item == 0) {
                    callbacks.onNavigate(items.get(which).url);
                } else if (item == 1) {
                    callbacks.openInNewTab(items.get(which).url);
                } else if (item == 2) {
                    dbHelper.removeBookmark(items.get(which).url);
                    Toast.makeText(activity, "Bookmark removed",
                            Toast.LENGTH_SHORT).show();
                }
            }).show();
            return true;
        });

        new AlertDialog.Builder(activity)
                .setTitle("Bookmarks")
                .setView(listView)
                .show();
    }

    public void addBookmark(@Nullable String url, @Nullable String title) {
        if (url == null || url.isEmpty() || url.equals("about:blank") || dbHelper == null) return;
        dbHelper.addBookmark(url, title);
        Toast.makeText(activity, "Bookmark saved", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------------
    // Legacy migration (one-shot at startup)
    // ------------------------------------------------------------------------
    /**
     * Migrates bookmarks from the pre-3.x SharedPreferences-based store into
     * the SQLite database, then clears the old preference. Safe to call on
     * every cold start — it no-ops if the legacy key is absent or empty.
     */
    public void migrateLegacyBookmarksToDatabase() {
        android.content.SharedPreferences legacyPrefs = activity.getSharedPreferences(
                LEGACY_BOOKMARKS_PREFS, android.content.Context.MODE_PRIVATE);
        String legacyData = legacyPrefs.getString(LEGACY_BOOKMARKS_KEY, null);

        if (legacyData == null || legacyData.equals("[]")) return;
        if (backgroundExecutor == null || dbHelper == null) return;

        backgroundExecutor.execute(() -> {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(legacyData);
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject bm = arr.optJSONObject(i);
                    if (bm == null) continue;
                    String title = bm.optString("title", "Untitled");
                    String url = bm.optString("url", "");
                    if (!url.isEmpty()) dbHelper.addBookmark(url, title);
                }
                legacyPrefs.edit().clear().apply();
            } catch (Exception ignored) {}
        });
    }
}
