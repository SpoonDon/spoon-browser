package com.spoondon.browser;

import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * History UI + persistence bridge for the unified History manager.
 *
 * The list/edit/search/sort/delete-all UI lives in {@link ItemManagerDialog};
 * this class supplies the data by implementing {@link ItemManagerDialog.DataSource}
 * and routes persistence through {@link BrowserDatabaseHelper}.
 *
 * Also owns the one-shot legacy SharedPreferences bookmark migration that
 * runs at cold start.
 *
 * 2026-10-01 cleanup: the legacy {@code addBookmark(String, String)} passthrough
 * was removed. Bookmark write paths now live exclusively in
 * {@link BookmarkManager}; AppWiring routes the menu action there.
 */
public class HistoryController implements ItemManagerDialog.DataSource {

    /**
     * Extends the manager dialog's callbacks so callers can keep their
     * existing {@code HistoryController.Callbacks} implementation.
     */
    public interface Callbacks extends ItemManagerDialog.Callbacks {
        // Inherits onNavigate(String) and openInNewTab(String)
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
    // Entry point
    // ------------------------------------------------------------------------

    public void showHistoryDialog() {
        if (dbHelper == null) return;
        if (dbHelper.getHistoryCount() == 0) {
            Toast.makeText(activity, "History is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        ItemManagerDialog.show(activity, "History", this, callbacks);
    }

    public void clearHistory() {
        if (dbHelper != null) dbHelper.clearHistory();
        Toast.makeText(activity, "History cleared", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------------
    // ItemManagerDialog.DataSource
    // ------------------------------------------------------------------------

    @NonNull
    @Override
    public List<ManagedItem> load() {
        List<ManagedItem> items = new ArrayList<>();
        if (dbHelper == null) return items;
        for (String[] row : dbHelper.getAllHistoryWithIds()) {
            try {
                long id = Long.parseLong(row[0]);
                String url = row[1];
                String title = row[2];
                long ts = Long.parseLong(row[3]);
                items.add(new ManagedItem(id, ManagedItem.TYPE_HISTORY, title, url, ts));
            } catch (Exception ignored) { /* skip malformed row */ }
        }
        return items;
    }

    @Override
    public void update(@NonNull ManagedItem item, @Nullable String newTitle, @NonNull String newUrl) {
        if (dbHelper == null) return;
        dbHelper.updateHistoryEntry(item.id, newTitle, newUrl);
    }

    @Override
    public void delete(@NonNull List<Long> ids) {
        if (dbHelper == null || ids.isEmpty()) return;
        dbHelper.deleteHistoryByIds(ids);
    }

    @Override
    public void clearAll() {
        if (dbHelper == null) return;
        dbHelper.clearHistory();
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
