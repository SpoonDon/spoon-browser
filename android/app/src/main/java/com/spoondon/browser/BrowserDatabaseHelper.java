package com.spoondon.browser;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class BrowserDatabaseHelper extends SQLiteOpenHelper {

    private static BrowserDatabaseHelper instance;

    private static final String DATABASE_NAME = "SpoonBrowser.db";
    private static final int DATABASE_VERSION = 2;

    private static final String TABLE_HISTORY = "history";
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_URL = "url";
    private static final String COLUMN_TITLE = "title";
    private static final String COLUMN_TIMESTAMP = "timestamp";

    private static final String TABLE_BOOKMARKS = "bookmarks";
    
    private static final String TABLE_TABS = "tabs";
    private static final String COLUMN_TAB_ORDER = "tab_order";

    private BrowserDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
            setWriteAheadLoggingEnabled(true);
        }
    }

    public static synchronized BrowserDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new BrowserDatabaseHelper(context.getApplicationContext());
        }
        return instance;
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
            String createTabsTable = "CREATE TABLE IF NOT EXISTS " + TABLE_TABS + " (" +
                    COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_TAB_ORDER + " INTEGER NOT NULL, " +
                    COLUMN_URL + " TEXT NOT NULL, " +
                    COLUMN_TITLE + " TEXT)";
            db.execSQL(createTabsTable);
        }
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
            db.enableWriteAheadLogging();
        }
        
        try {
            db.execSQL("PRAGMA synchronous = NORMAL;");
            db.execSQL("PRAGMA journal_size_limit = 524288;");
        } catch (Exception ignored) {}
    }

    public void addHistory(String url, String title) {
        if (url == null || url.isEmpty() || url.equals("about:blank")) return;
        SQLiteDatabase db = this.getWritableDatabase();
        if (db == null || !db.isOpen()) return;
        
        try {
            ContentValues values = new ContentValues();
            values.put(COLUMN_URL, url);
            values.put(COLUMN_TITLE, title != null ? title : url);
            db.insert(TABLE_HISTORY, null, values);
        } catch (Exception ignored) {}
    }

    public void clearHistory() {
        SQLiteDatabase db = this.getWritableDatabase();
        if (db == null || !db.isOpen()) return;
        
        try {
            db.delete(TABLE_HISTORY, null, null);
        } catch (Exception ignored) {}
    }

    public void addBookmark(String url, String title) {
        if (url == null || url.isEmpty() || url.equals("about:blank")) return;
        SQLiteDatabase db = this.getWritableDatabase();
        if (db == null || !db.isOpen()) return;
        
        try {
            ContentValues values = new ContentValues();
            values.put(COLUMN_URL, url);
            values.put(COLUMN_TITLE, title != null ? title : url);
            db.insertWithOnConflict(TABLE_BOOKMARKS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        } catch (Exception ignored) {}
    }

    public void removeBookmark(String url) {
        if (url == null || url.isEmpty()) return;
        SQLiteDatabase db = this.getWritableDatabase();
        if (db == null || !db.isOpen()) return;
        
        try {
            db.delete(TABLE_BOOKMARKS, COLUMN_URL + " = ?", new String[]{url});
        } catch (Exception ignored) {}
    }

    public List<String[]> getAllHistory() {
        List<String[]> historyList = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        if (db == null || !db.isOpen()) return historyList;
        
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT " + COLUMN_URL + ", " + COLUMN_TITLE + " FROM " + TABLE_HISTORY + " ORDER BY id DESC LIMIT 500", null);
            if (cursor.moveToFirst()) {
                do {
                    historyList.add(new String[]{cursor.getString(0), cursor.getString(1)});
                } while (cursor.moveToNext());
            }
        } catch (IllegalStateException e) {
            return historyList;
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
        return historyList;
    }

    public List<String[]> getMatchingHistory(String query) {
        List<String[]> historyList = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        if (db == null || !db.isOpen()) return historyList;
        
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT " + COLUMN_URL + ", " + COLUMN_TITLE + " FROM " + TABLE_HISTORY + 
                " WHERE " + COLUMN_URL + " LIKE ? OR " + COLUMN_TITLE + " LIKE ? ORDER BY id DESC LIMIT 30", 
                new String[]{"%" + query + "%", "%" + query + "%"});

            if (cursor.moveToFirst()) {
                do {
                    historyList.add(new String[]{cursor.getString(0), cursor.getString(1)});
                } while (cursor.moveToNext());
            }
        } catch (IllegalStateException e) {
            return historyList;
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
        return historyList;
    }

    public List<String> getAllBookmarksUrls() {
        List<String> bookmarkUrls = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        if (db == null || !db.isOpen()) return bookmarkUrls;
        
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT " + COLUMN_URL + " FROM " + TABLE_BOOKMARKS + " ORDER BY " + COLUMN_TIMESTAMP + " DESC", null);
            if (cursor.moveToFirst()) {
                do {
                    bookmarkUrls.add(cursor.getString(0));
                } while (cursor.moveToNext());
            }
        } catch (IllegalStateException e) {
            return bookmarkUrls;
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
        return bookmarkUrls;
    }
    
    public int getHistoryCount() {
        SQLiteDatabase db = this.getReadableDatabase();
        if (db == null || !db.isOpen()) return 0;
        
        Cursor cursor = null;
        int count = 0;
        try {
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_HISTORY, null);
            if (cursor.moveToFirst()) count = cursor.getInt(0);
        } catch (IllegalStateException e) {
            return 0;
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
        return count;
    }

    public int getBookmarkCount() {
        SQLiteDatabase db = this.getReadableDatabase();
        if (db == null || !db.isOpen()) return 0;
        
        Cursor cursor = null;
        int count = 0;
        try {
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_BOOKMARKS, null);
            if (cursor.moveToFirst()) count = cursor.getInt(0);
        } catch (IllegalStateException e) {
            return 0;
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
        return count;
    }

    public void saveAllTabs(List<String[]> tabsData) {
        SQLiteDatabase db = this.getWritableDatabase();
        if (db == null || !db.isOpen()) return;
        
        try {
            db.beginTransaction();
            db.delete(TABLE_TABS, null, null);
            
            for (int i = 0; i < tabsData.size(); i++) {
                String[] tab = tabsData.get(i);
                ContentValues values = new ContentValues();
                values.put(COLUMN_TAB_ORDER, i);
                values.put(COLUMN_URL, tab[0]);
                values.put(COLUMN_TITLE, tab[1]);
                db.insert(TABLE_TABS, null, values);
            }
            db.setTransactionSuccessful();
        } catch (Exception ignored) {
        } finally {
            if (db.isOpen() && db.inTransaction()) {
                db.endTransaction();
            }
        }
    }

    public List<String[]> getAllTabs() {
        List<String[]> tabsList = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        if (db == null || !db.isOpen()) return tabsList;
        
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT " + COLUMN_URL + ", " + COLUMN_TITLE + " FROM " + TABLE_TABS + " ORDER BY " + COLUMN_TAB_ORDER + " ASC", null);
            if (cursor.moveToFirst()) {
                do {
                    tabsList.add(new String[]{cursor.getString(0), cursor.getString(1)});
                } while (cursor.moveToNext());
            }
        } catch (IllegalStateException e) {
            return tabsList;
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
        return tabsList;
    }

    public void cleanupOldHistory(int daysToKeep) {
        SQLiteDatabase db = this.getWritableDatabase();
        if (db == null || !db.isOpen()) return;
        
        try {
            db.execSQL("DELETE FROM " + TABLE_HISTORY + 
                       " WHERE " + COLUMN_TIMESTAMP + " <= datetime('now', '-" + daysToKeep + " days')");
        } catch (Exception ignored) {}
    }
}
