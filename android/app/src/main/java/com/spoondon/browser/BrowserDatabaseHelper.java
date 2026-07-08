package com.spoondon.browser; // Make sure this matches your package name!

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class BrowserDatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "SpoonBrowser.db";
    // Incremented version to 2 to trigger the tabs table creation
    private static final int DATABASE_VERSION = 2;

    // History Table
    private static final String TABLE_HISTORY = "history";
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_URL = "url";
    private static final String COLUMN_TITLE = "title";
    private static final String COLUMN_TIMESTAMP = "timestamp";

    // Bookmarks Table
    private static final String TABLE_BOOKMARKS = "bookmarks";
    
    // Tabs Table
    private static final String TABLE_TABS = "tabs";
    private static final String COLUMN_TAB_ORDER = "tab_order";

    public BrowserDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createHistoryTable = "CREATE TABLE " + TABLE_HISTORY + " (" +
                COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COLUMN_URL + " TEXT NOT NULL, " +
                COLUMN_TITLE + " TEXT, " +
                COLUMN_TIMESTAMP + " DATETIME DEFAULT CURRENT_TIMESTAMP)";
        
        String createBookmarksTable = "CREATE TABLE " + TABLE_BOOKMARKS + " (" +
                COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COLUMN_URL + " TEXT NOT NULL UNIQUE, " +
                COLUMN_TITLE + " TEXT, " +
                COLUMN_TIMESTAMP + " DATETIME DEFAULT CURRENT_TIMESTAMP)";
                
        String createTabsTable = "CREATE TABLE " + TABLE_TABS + " (" +
                COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COLUMN_TAB_ORDER + " INTEGER NOT NULL, " +
                COLUMN_URL + " TEXT NOT NULL, " +
                COLUMN_TITLE + " TEXT)";

        db.execSQL(createHistoryTable);
        db.execSQL(createBookmarksTable);
        db.execSQL(createTabsTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // Upgrade from V1 to V2: Just add the new Tabs table. 
            // DO NOT drop history or bookmarks!
            String createTabsTable = "CREATE TABLE IF NOT EXISTS " + TABLE_TABS + " (" +
                    COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_TAB_ORDER + " INTEGER NOT NULL, " +
                    COLUMN_URL + " TEXT NOT NULL, " +
                    COLUMN_TITLE + " TEXT)";
            db.execSQL(createTabsTable);
        }
    }

    // --- HISTORY METHODS ---
    public void addHistory(String url, String title) {
        if (url == null || url.isEmpty() || url.equals("about:blank")) return;
        
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_URL, url);
        values.put(COLUMN_TITLE, title != null ? title : url);
        
        db.insert(TABLE_HISTORY, null, values);
        db.close();
    }

    public void clearHistory() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_HISTORY, null, null);
        db.close();
    }

    // --- BOOKMARK METHODS ---
    public void addBookmark(String url, String title) {
        if (url == null || url.isEmpty() || url.equals("about:blank")) return;

        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_URL, url);
        values.put(COLUMN_TITLE, title != null ? title : url);
        
        // Uses insertWithOnConflict to ignore if the user adds the exact same bookmark twice
        db.insertWithOnConflict(TABLE_BOOKMARKS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        db.close();
    }

    public void removeBookmark(String url) {
        if (url == null || url.isEmpty()) return;
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_BOOKMARKS, COLUMN_URL + " = ?", new String[]{url});
        db.close();
    }

    public List<String[]> getAllHistory() {
        List<String[]> historyList = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT " + COLUMN_URL + ", " + COLUMN_TITLE + " FROM " + TABLE_HISTORY + " ORDER BY id DESC LIMIT 500", null);

        if (cursor.moveToFirst()) {
            do {
                historyList.add(new String[]{cursor.getString(0), cursor.getString(1)});
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
        return historyList;
    }

    public List<String> getAllBookmarksUrls() {
        List<String> bookmarkUrls = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT " + COLUMN_URL + " FROM " + TABLE_BOOKMARKS + " ORDER BY " + COLUMN_TIMESTAMP + " DESC", null);

        if (cursor.moveToFirst()) {
            do {
                bookmarkUrls.add(cursor.getString(0));
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
        return bookmarkUrls;
    }
    
    public int getHistoryCount() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_HISTORY, null);
        int count = 0;
        if (cursor.moveToFirst()) count = cursor.getInt(0);
        cursor.close();
        db.close();
        return count;
    }

    public int getBookmarkCount() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_BOOKMARKS, null);
        int count = 0;
        if (cursor.moveToFirst()) count = cursor.getInt(0);
        cursor.close();
        db.close();
        return count;
    }

    // --- TABS METHODS ---
    public void saveAllTabs(List<String[]> tabsData) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            // Wipe the old state completely
            db.delete(TABLE_TABS, null, null);
            
            // Insert the new exact order
            for (int i = 0; i < tabsData.size(); i++) {
                String[] tab = tabsData.get(i);
                ContentValues values = new ContentValues();
                values.put(COLUMN_TAB_ORDER, i);
                values.put(COLUMN_URL, tab[0]);
                values.put(COLUMN_TITLE, tab[1]);
                db.insert(TABLE_TABS, null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            db.close();
        }
    }

    public List<String[]> getAllTabs() {
        List<String[]> tabsList = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT " + COLUMN_URL + ", " + COLUMN_TITLE + " FROM " + TABLE_TABS + " ORDER BY " + COLUMN_TAB_ORDER + " ASC", null);

        if (cursor.moveToFirst()) {
            do {
                tabsList.add(new String[]{cursor.getString(0), cursor.getString(1)});
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
        return tabsList;
    }
}
