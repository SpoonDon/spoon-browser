package com.spoondon.browser;

import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Bookmarks UI + persistence bridge. Symmetric with {@link HistoryController}
 * but takes a narrower constructor: bookmarks don't need the background
 * executor because there is no legacy migration to run here.
 *
 * All list/edit/search/sort/delete UI is delegated to {@link ItemManagerDialog}.
 * This class only knows how to load rows, persist edits, and delete.
 *
 * 2026-10-01 (live-refresh): added {@link #setOnChangedListener(Runnable)}.
 * AppWiring subscribes and re-renders the home page when the current tab
 * is showing it, so bookmark mutations show up immediately.
 */
public class BookmarkManager implements ItemManagerDialog.DataSource {

    private final MainActivity activity;
    private final BrowserDatabaseHelper dbHelper;
    private final ItemManagerDialog.Callbacks callbacks;

    @Nullable private Runnable onChangedListener;

    public BookmarkManager(@NonNull MainActivity activity,
                           @NonNull BrowserDatabaseHelper dbHelper,
                           @NonNull ItemManagerDialog.Callbacks callbacks) {
        this.activity = activity;
        this.dbHelper = dbHelper;
        this.callbacks = callbacks;
    }

    /**
     * Fired after every successful mutation (add, delete, clear, update).
     * AppWiring uses this to re-render the home page if it's currently
     * visible. Runs on the caller's thread — the listener is responsible
     * for hopping to UI if needed.
     */
    public void setOnChangedListener(@Nullable Runnable listener) {
        this.onChangedListener = listener;
    }

    private void fireChanged() {
        Runnable r = onChangedListener;
        if (r != null) r.run();
    }

    // ------------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------------

    public void showBookmarks() {
        if (dbHelper == null) return;
        if (dbHelper.getBookmarkCount() == 0) {
            Toast.makeText(activity, "No bookmarks saved", Toast.LENGTH_SHORT).show();
            return;
        }
        ItemManagerDialog.show(activity, "Bookmarks", this, callbacks);
    }

    public void addBookmark(@Nullable String url, @Nullable String title) {
        if (url == null || url.isEmpty() || url.equals("about:blank") || dbHelper == null) return;
        dbHelper.addBookmark(url, title);
        Toast.makeText(activity, "Bookmark saved", Toast.LENGTH_SHORT).show();
        fireChanged();
    }

    public void removeBookmark(@Nullable String url) {
        if (url == null || url.isEmpty() || dbHelper == null) return;
        dbHelper.removeBookmark(url);
        fireChanged();
    }

    // ------------------------------------------------------------------------
    // ItemManagerDialog.DataSource
    // ------------------------------------------------------------------------

    @NonNull
    @Override
    public List<ManagedItem> load() {
        List<ManagedItem> items = new ArrayList<>();
        if (dbHelper == null) return items;
        for (String[] row : dbHelper.getAllBookmarksWithIds()) {
            try {
                long id = Long.parseLong(row[0]);
                String url = row[1];
                String title = row[2];
                long ts = Long.parseLong(row[3]);
                items.add(new ManagedItem(id, ManagedItem.TYPE_BOOKMARK, title, url, ts));
            } catch (Exception ignored) { /* skip malformed row */ }
        }
        return items;
    }

    @Override
    public void update(@NonNull ManagedItem item, @Nullable String newTitle, @NonNull String newUrl) {
        if (dbHelper == null) return;
        dbHelper.updateBookmarkEntry(item.id, newTitle, newUrl);
        fireChanged();
    }

    @Override
    public void delete(@NonNull List<Long> ids) {
        if (dbHelper == null || ids.isEmpty()) return;
        dbHelper.deleteBookmarksByIds(ids);
        fireChanged();
    }

    @Override
    public void clearAll() {
        if (dbHelper == null) return;
        List<String[]> rows = dbHelper.getAllBookmarksWithIds();
        List<Long> ids = new ArrayList<>(rows.size());
        for (String[] row : rows) {
            try { ids.add(Long.parseLong(row[0])); }
            catch (Exception ignored) {}
        }
        dbHelper.deleteBookmarksByIds(ids);
        fireChanged();
    }
}
