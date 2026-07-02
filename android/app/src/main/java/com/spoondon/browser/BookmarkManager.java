package com.spoondon.browser;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class BookmarkManager {

    private static final String PREFS = "spoon_bookmarks";
    private static final String KEY = "bookmarks";
    private final SharedPreferences prefs;

    public BookmarkManager(Context context) {
        // Enforce application context to prevent memory leaks if initialized within short-lived activities
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // OPTIMIZATION: Synchronized execution blocks prevent concurrent threads from wiping out database commits
    public synchronized void addBookmark(String title, String url) {
        if (url == null || url.isEmpty()) return;
        
        try {
            JSONArray bookmarks = getBookmarksJson();

            JSONObject bookmark = new JSONObject();
            bookmark.put("title", title != null ? title.trim() : "Untitled");
            bookmark.put("url", url.trim());
            bookmark.put("timestamp", System.currentTimeMillis());

            bookmarks.put(bookmark);

            prefs.edit()
                    .putString(KEY, bookmarks.toString())
                    .apply();

        } catch (Exception ignored) {
            // Guard pipeline against runtime json mutation errors
        }
    }

    public synchronized JSONArray getBookmarksJson() {
        try {
            String data = prefs.getString(KEY, "[]");
            return new JSONArray(data != null ? data : "[]");
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public synchronized List<String> getTitles() {
        List<String> list = new ArrayList<>();
        try {
            JSONArray bookmarks = getBookmarksJson();
            for (int i = 0; i < bookmarks.length(); i++) {
                JSONObject item = bookmarks.getJSONObject(i);
                if (item != null) {
                    list.add(item.optString("title", "Untitled"));
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public synchronized String getUrl(int index) {
        try {
            JSONArray bookmarks = getBookmarksJson();
            // OPTIMIZATION: Added strict range verification to prevent IndexOutOfBounds exceptions
            if (index >= 0 && index < bookmarks.length()) {
                JSONObject item = bookmarks.getJSONObject(index);
                if (item != null) {
                    return item.optString("url", null);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
