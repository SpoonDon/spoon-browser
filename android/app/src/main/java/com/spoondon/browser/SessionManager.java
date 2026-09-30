package com.spoondon.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.WebStorage;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

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
 * 2026-09-30 - Session restore added.
 *   - Tabs are persisted as a JSON array in SharedPreferences
 *     ({@link #KEY_OPEN_TABS}) on onPause.
 *   - The reader ({@link #loadPersistedTabs()}) is called once from
 *     MainActivity.onCreate to rehydrate the tab list.
 *   - We deliberately never touch WebView.saveState() / restoreState().
 *     That API is process-scoped, hits the 1 MB Binder limit on complex
 *     pages, and cannot survive OS-initiated process death - it was the
 *     source of earlier cold-start crashes.
 *
 * Restore policy:
 *   - Process kill (OS reclaim)     -> tabs restored on next launch.
 *   - Clean exit via menu / dialog  -> tab keys are wiped, no restore.
 *
 * Threading: all public methods must be called on the main thread.
 */
public class SessionManager {

    private static final String TAB_PREFS = "browser_prefs";
    private static final String KEY_OPEN_TABS = "open_tabs";
    private static final String KEY_CURRENT_TAB = "current_tab";

    /** Hard cap on persisted tab count. Protects against disk bloat. */
    private static final int MAX_TABS = 20;

    private final MainActivity activity;
    private final SharedPreferences tabPrefs;

    /**
     * When true, {@link #onStop()} wipes cookies + WebStorage instead of
     * flushing them, and {@link #onPause()} skips tab persistence. Set by
     * features that want a clean slate on exit.
     */
    private boolean clearSessionOnExit = false;

    /**
     * Supplier for the live tab list. Wired by MainActivity after the
     * TabManager is constructed. Null until then - persistence is skipped
     * if we're asked to save before the wiring is complete.
     *
     * Kept as a narrow interface so SessionManager does not depend on
     * TabManager directly. Keeps this class testable and decoupled.
     */
    private TabSource tabSource;

    public interface TabSource {
        @NonNull List<PersistedTab> snapshotTabs();
        int getActiveIndex();
    }

    public SessionManager(@NonNull MainActivity activity) {
        this.activity = activity;
        this.tabPrefs = activity.getSharedPreferences(TAB_PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------------

    /**
     * Called once from MainActivity.onCreate, immediately after
     * {@code wiring.initialize()}. Must be set before the first onPause,
     * otherwise the very first pause is silently skipped (safe, but the
     * first cold-start restore will be a no-op).
     */
    public void setTabSource(@NonNull TabSource source) {
        this.tabSource = source;
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
        // If the Activity is finishing for good (menu Exit, dialog Exit,
        // swipe-away via finishAndRemoveTask), do NOT re-save tabs.
        // exitNow() already wiped the persisted keys; re-saving here would
        // undo that and cause tabs to resurrect on next launch.
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (clearSessionOnExit) return;
        persistTabs();
        flushCookies();
    }

    public void onStop() {
        if (!clearSessionOnExit) return;
        try {
            WebStorage.getInstance().deleteAllData();
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
        } catch (Exception ignored) {
            // WebView not initialised yet or process is dying - nothing to do.
        }
    }

    public void onDestroy() {
        if (!clearSessionOnExit) flushCookies();
    }

    // ------------------------------------------------------------------------
    // Tab persistence
    // ------------------------------------------------------------------------

    /**
     * Write the current tab list to SharedPreferences. Called from onPause
     * only when the Activity is NOT finishing.
     *
     * The TabManager snapshot already filters incognito tabs and blank
     * pages; the checks below are defense-in-depth so a future change to
     * TabManager can't accidentally leak a vault URL or an incognito tab
     * onto disk.
     */
    private void persistTabs() {
        if (tabSource == null) return;
        try {
            List<PersistedTab> tabs = tabSource.snapshotTabs();
            if (tabs == null || tabs.isEmpty()) {
                tabPrefs.edit()
                        .remove(KEY_OPEN_TABS)
                        .remove(KEY_CURRENT_TAB)
                        .apply();
                return;
            }

            JSONArray arr = new JSONArray();
            for (PersistedTab t : tabs) {
                if (t == null || t.url == null || t.url.isEmpty()) continue;
                if (t.url.equals("about:blank")) continue;
                if (VaultUrls.isVaultUrl(t.url)) continue;

                JSONObject o = new JSONObject();
                o.put("url", t.url);
                o.put("title", t.title != null ? t.title : "");
                arr.put(o);

                if (arr.length() >= MAX_TABS) break;
            }

            int active = tabSource.getActiveIndex();
            if (active < 0 || active >= arr.length()) active = 0;

            tabPrefs.edit()
                    .putString(KEY_OPEN_TABS, arr.toString())
                    .putInt(KEY_CURRENT_TAB, active)
                    .apply();
        } catch (Exception ignored) {
            // Never let persistence crash the pause path.
        }
    }

    /**
     * Read the persisted tab list. Called once from MainActivity.onCreate.
     * Returns an empty list when there is nothing to restore.
     *
     * Filters applied on read (defense in depth against stale data):
     *   - blank URLs and about:blank are skipped
     *   - vault URLs are skipped (never resurrect a secure screen)
     *   - list is capped at MAX_TABS
     *
     * Corrupt JSON (truncated write, manual prefs edit, format change) is
     * handled by wiping the keys and returning an empty list, so the next
     * launch starts on the home page rather than crashing.
     */
    @NonNull
    public List<PersistedTab> loadPersistedTabs() {
        List<PersistedTab> out = new ArrayList<>();
        try {
            String raw = tabPrefs.getString(KEY_OPEN_TABS, null);
            if (raw == null || raw.isEmpty()) return out;

            JSONArray arr = new JSONArray(raw);
            int max = Math.min(arr.length(), MAX_TABS);
            for (int i = 0; i < max; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;

                String url = o.optString("url", null);
                if (url == null || url.isEmpty()) continue;
                if (url.equals("about:blank")) continue;
                if (VaultUrls.isVaultUrl(url)) continue;

                PersistedTab t = new PersistedTab();
                t.url = url;
                t.title = o.optString("title", "");
                out.add(t);
            }
        } catch (Exception e) {
            // Corrupt JSON - wipe and start fresh.
            tabPrefs.edit()
                    .remove(KEY_OPEN_TABS)
                    .remove(KEY_CURRENT_TAB)
                    .apply();
            out.clear();
        }
        return out;
    }

    /** @return the persisted active tab index, or 0 if unknown. */
    public int loadPersistedActiveIndex() {
        return tabPrefs.getInt(KEY_CURRENT_TAB, 0);
    }

    // ------------------------------------------------------------------------
    // Exit paths
    // ------------------------------------------------------------------------

    /** "Are you sure?" dialog - the positive button performs a normal exit. */
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

    /**
     * Exit immediately: flush cookies, wipe persisted tabs, kill the task.
     *
     * The persisted tab keys are removed BEFORE finishAndRemoveTask, so
     * when onPause subsequently fires, the Activity is already finishing
     * and onPause short-circuits without re-saving. Net effect: clean exit
     * -> no restore on next launch.
     */
    public void exitNow() {
        clearSessionOnExit = false;
        flushCookies();
        if (tabPrefs != null) {
            tabPrefs.edit()
                    .remove(KEY_OPEN_TABS)
                    .remove(KEY_CURRENT_TAB)
                    .apply();
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
