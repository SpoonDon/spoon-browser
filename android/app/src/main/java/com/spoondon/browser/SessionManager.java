package com.spoondon.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.WebStorage;

import androidx.annotation.NonNull;

/**
 * Owns the browser session lifecycle.
 *
 * Previously this logic was scattered across MainActivity.onPause(),
 * onStop(), onDestroy(), showExitConfirmationDialog(), and the "Exit"
 * menu item. Consolidating it here means the {@code clearSessionOnExit}
 * flag has exactly one writer and one reader.
 *
 * Extracted from MainActivity (god-object split, slice 5).
 *
 * Threading: all public methods must be called on the main thread.
 */
public class SessionManager {

    private static final String TAB_PREFS = "browser_prefs";
    private static final String KEY_OPEN_TABS = "open_tabs";
    private static final String KEY_CURRENT_TAB = "current_tab";

    private final MainActivity activity;
    private final SharedPreferences tabPrefs;

    /**
     * When true, {@link #onStop()} wipes cookies + WebStorage instead of
     * flushing them. Set by features that want a clean slate on exit.
     */
    private boolean clearSessionOnExit = false;

    public SessionManager(@NonNull MainActivity activity) {
        this.activity = activity;
        this.tabPrefs = activity.getSharedPreferences(TAB_PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------------
    // Flag
    // ------------------------------------------------------------------------
    public void setClearSessionOnExit(boolean value) {
        this.clearSessionOnExit = value;
    }

    public boolean isClearSessionOnExit() {
        return clearSessionOnExit;
    }

    // ------------------------------------------------------------------------
    // Activity lifecycle hooks
    // ------------------------------------------------------------------------
    public void onPause() {
        if (!clearSessionOnExit) flushCookies();
    }

    public void onStop() {
        if (!clearSessionOnExit) return;
        try {
            WebStorage.getInstance().deleteAllData();
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
        } catch (Exception ignored) {
            // WebView not initialised yet or process is dying — nothing to do.
        }
    }

    public void onDestroy() {
        if (!clearSessionOnExit) flushCookies();
    }

    // ------------------------------------------------------------------------
    // Exit paths
    // ------------------------------------------------------------------------

    /** "Are you sure?" dialog — the positive button performs a normal exit. */
    public void showExitConfirmationDialog() {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        new android.app.AlertDialog.Builder(
                activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Exit Browser")
                .setMessage("Are you sure you want to exit the browser?")
                .setPositiveButton("Exit", (dialog, which) -> exitNow())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Exit immediately: flush cookies, drop saved tabs, kill the task. */
    public void exitNow() {
        clearSessionOnExit = false;
        flushCookies();
        if (tabPrefs != null) {
            tabPrefs.edit().remove(KEY_OPEN_TABS).remove(KEY_CURRENT_TAB).apply();
        }
        activity.finishAndRemoveTask();
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------
    private void flushCookies() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().flush();
            }
        } catch (Exception ignored) {
        }
    }
}
