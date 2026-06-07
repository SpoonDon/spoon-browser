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
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void addBookmark(String title, String url) {

        try {

            JSONArray bookmarks = getBookmarksJson();

            JSONObject bookmark = new JSONObject();
            bookmark.put("title", title);
            bookmark.put("url", url);
            bookmark.put("timestamp",
                    System.currentTimeMillis());

            bookmarks.put(bookmark);

            prefs.edit()
                    .putString(KEY, bookmarks.toString())
                    .apply();

        } catch (Exception ignored) {
        }
    }

    public JSONArray getBookmarksJson() {

        try {

            String data =
                    prefs.getString(KEY, "[]");

            return new JSONArray(data);

        } catch (Exception e) {

            return new JSONArray();
        }
    }

    public List<String> getTitles() {

        List<String> list =
                new ArrayList<>();

        try {

            JSONArray bookmarks =
                    getBookmarksJson();

            for (int i = 0;
                 i < bookmarks.length();
                 i++) {

                JSONObject item =
                        bookmarks.getJSONObject(i);

                list.add(
                        item.optString(
                                "title",
                                "Untitled"
                        )
                );
            }

        } catch (Exception ignored) {
        }

        return list;
    }

    public String getUrl(int index) {

        try {

            JSONArray bookmarks =
                    getBookmarksJson();

            if (index >= 0 &&
                    index < bookmarks.length()) {

                return bookmarks
                        .getJSONObject(index)
                        .optString("url");
            }

        } catch (Exception ignored) {
        }

        return null;
    }
}
