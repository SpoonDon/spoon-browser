package com.spoondon.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Persistent store for ad-block filter-list subscriptions.
 *
 * Phase-2 (2026-10-02): replaces the old "newline-separated URL list in
 * browser_prefs.filter_lists" model with a rich JSON structure that carries
 * per-list title, source ("preset" or "custom"), and enabled state.
 *
 * Design:
 *   - AdBlockPreferences owns the file-level SharedPreferences
 *     "SpoonAdBlockPrefs" (same file AdBlockEngine uses for
 *     adblock_enabled/adblock_whitelist, but disjoint keys, namespaced
 *     with the "pref_" prefix to avoid future collision).
 *   - The engine only ever sees a List<String> of URLs. Which URLs are in
 *     that list is decided by getEnabledListUrls(). Per-list enable/disable
 *     is therefore a controller-level concern, not an engine concern.
 *   - Subscribed list order: enabled first, then by title. That makes the
 *     management UI (which reads allLists()) deterministic.
 *   - Migration: on first construct, if AdBlockPreferences has no stored
 *     JSON but the legacy browser_prefs.filter_lists key exists and is
 *     non-empty, URLs from it are imported as custom lists and the old
 *     key is cleared. Idempotent — safe to run on every cold start.
 *
 * No dependencies beyond androidx.annotation and org.json.
 */
public final class AdBlockPreferences {

    private static final String PREFS_FILE = "SpoonAdBlockPrefs";

    private static final String KEY_LISTS           = "pref_subscribed_lists";
    private static final String KEY_AUTO_HOURS      = "pref_auto_update_hours";
    private static final String KEY_LAST_REFRESH    = "pref_last_auto_refresh";
    private static final String KEY_LAST_PARSED     = "pref_last_parsed";
    private static final String KEY_LAST_SKIPPED    = "pref_last_skipped";

    // Legacy location — the old AdBlockController saved newline-separated
    // URLs here. We only read it once for migration, then never touch it.
    private static final String LEGACY_PREFS_FILE   = "browser_prefs";
    private static final String LEGACY_KEY_LISTS    = "filter_lists";

    public static final int UPDATE_OFF   = 0;
    public static final int UPDATE_6H    = 6;
    public static final int UPDATE_12H   = 12;
    public static final int UPDATE_24H   = 24;
    public static final int UPDATE_72H   = 72;
    public static final int UPDATE_168H  = 168;
    public static final int UPDATE_DEFAULT = UPDATE_24H;

    public static final String SOURCE_PRESET = "preset";
    public static final String SOURCE_CUSTOM = "custom";

    // ------------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------------

    public static final class SubscribedList {
        public final String url;
        public final String source;
        public String title;
        public boolean enabled;

        SubscribedList(String url, String title, boolean enabled, String source) {
            this.url = url;
            this.title = title != null ? title : url;
            this.enabled = enabled;
            this.source = source != null ? source : SOURCE_CUSTOM;
        }

        @NonNull
        @Override
        public String toString() {
            return title + " (" + url + ")";
        }
    }

    public static final class Preset {
        public final String url;
        public final String title;
        public final String description;

        public Preset(String url, String title, String description) {
            this.url = url;
            this.title = title;
            this.description = description;
        }
    }

    private static final List<Preset> PRESET_CATALOG =
            Collections.unmodifiableList(Arrays.asList(
                    new Preset(
                            "https://big.oisd.nl/",
                            "OISD Basic",
                            "Balanced ad + tracker blocking. Best single-list choice."),
                    new Preset(
                            "https://easylist.to/easylist/easylist.txt",
                            "EasyList",
                            "The classic community ad list. Widest coverage."),
                    new Preset(
                            "https://easylist.to/easylist/easyprivacy.txt",
                            "EasyPrivacy",
                            "Tracker and analytics blocking."),
                    new Preset(
                            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
                            "uBO Filters",
                            "uBlock Origin's own curated ad list."),
                    new Preset(
                            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt",
                            "uBO Privacy",
                            "uBlock Origin's privacy list."),
                    new Preset(
                            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt",
                            "uBO Badware",
                            "Malware and scam domain blocking.")
            ));

    @NonNull
    public static List<Preset> presetCatalog() {
        return PRESET_CATALOG;
    }

    // ------------------------------------------------------------------------
    // Singleton
    // ------------------------------------------------------------------------

    private static volatile AdBlockPreferences instance;

    @NonNull
    public static AdBlockPreferences get(@NonNull Context context) {
        if (instance == null) {
            synchronized (AdBlockPreferences.class) {
                if (instance == null) {
                    instance = new AdBlockPreferences(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final SharedPreferences prefs;
    private final List<SubscribedList> lists = new ArrayList<>();

    private AdBlockPreferences(Context appContext) {
        prefs = appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
        load();
        migrateLegacyIfNeeded(appContext);
    }

    // ------------------------------------------------------------------------
    // Load / save
    // ------------------------------------------------------------------------

    private void load() {
        lists.clear();
        String raw = prefs.getString(KEY_LISTS, null);
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "");
                if (url.isEmpty()) continue;
                String title = o.optString("title", url);
                boolean enabled = o.optBoolean("enabled", true);
                String source = o.optString("source", SOURCE_CUSTOM);
                lists.add(new SubscribedList(url, title, enabled, source));
            }
        } catch (Exception ignored) {
            // Corrupt JSON — start clean. The next save() overwrites.
            lists.clear();
        }
        sortLists();
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (SubscribedList l : lists) {
                JSONObject o = new JSONObject();
                o.put("url", l.url);
                o.put("title", l.title);
                o.put("enabled", l.enabled);
                o.put("source", l.source);
                arr.put(o);
            }
            prefs.edit().putString(KEY_LISTS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /**
     * Enabled first (alphabetically by title), then disabled (alphabetically).
     */
    private void sortLists() {
        Collections.sort(lists, new Comparator<SubscribedList>() {
            @Override
            public int compare(SubscribedList a, SubscribedList b) {
                if (a.enabled != b.enabled) return a.enabled ? -1 : 1;
                String at = a.title != null ? a.title.toLowerCase(Locale.ROOT) : "";
                String bt = b.title != null ? b.title.toLowerCase(Locale.ROOT) : "";
                return at.compareTo(bt);
            }
        });
    }

    /**
     * One-time migration from the legacy browser_prefs.filter_lists key.
     * Only runs if the new store is empty AND the legacy key has content.
     * Clears the legacy key afterward so this is a no-op on subsequent launches.
     */
    private void migrateLegacyIfNeeded(Context appContext) {
        if (!lists.isEmpty()) return;
        try {
            SharedPreferences legacy =
                    appContext.getSharedPreferences(LEGACY_PREFS_FILE, Context.MODE_PRIVATE);
            String raw = legacy.getString(LEGACY_KEY_LISTS, null);
            if (raw == null || raw.isEmpty()) return;

            String[] urls = raw.split("\n");
            for (String u : urls) {
                u = u.trim();
                if (u.isEmpty()) continue;
                if (find(u) != null) continue;
                if (!u.toLowerCase(Locale.ROOT).startsWith("https://")) continue;
                lists.add(new SubscribedList(u, u, true, SOURCE_CUSTOM));
            }
            if (!lists.isEmpty()) {
                sortLists();
                save();
                legacy.edit().remove(LEGACY_KEY_LISTS).apply();
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------------

    @NonNull
    public List<SubscribedList> allLists() {
        return new ArrayList<>(lists);
    }

    @NonNull
    public List<String> enabledListUrls() {
        List<String> out = new ArrayList<>(lists.size());
        for (SubscribedList l : lists) {
            if (l.enabled) out.add(l.url);
        }
        return out;
    }

    public int size() {
        return lists.size();
    }

    public int enabledCount() {
        int n = 0;
        for (SubscribedList l : lists) if (l.enabled) n++;
        return n;
    }

    @Nullable
    public SubscribedList find(@Nullable String url) {
        if (url == null) return null;
        for (SubscribedList l : lists) {
            if (l.url.equals(url)) return l;
        }
        return null;
    }

    public boolean isSubscribed(@Nullable String url) {
        return find(url) != null;
    }

    public boolean isEnabled(@Nullable String url) {
        SubscribedList l = find(url);
        return l != null && l.enabled;
    }

    // ------------------------------------------------------------------------
    // Mutate
    // ------------------------------------------------------------------------

    /**
     * Adds a subscription. If the URL already exists, the existing entry is
     * left alone (title/source/enabled unchanged). Returns true if a new
     * entry was created.
     */
    public boolean subscribe(@NonNull String url,
                             @Nullable String title,
                             @NonNull String source) {
        url = url.trim();
        if (url.isEmpty()) return false;
        if (!url.toLowerCase(Locale.ROOT).startsWith("https://")) return false;
        if (find(url) != null) return false;

        SubscribedList l = new SubscribedList(
                url,
                title != null && !title.isEmpty() ? title : url,
                true,
                source);
        lists.add(l);
        sortLists();
        save();
        return true;
    }

    /**
     * Removes a subscription entirely. Returns true if a list was removed.
     */
    public boolean unsubscribe(@Nullable String url) {
        if (url == null) return false;
        for (int i = 0; i < lists.size(); i++) {
            if (lists.get(i).url.equals(url)) {
                lists.remove(i);
                sortLists();
                save();
                return true;
            }
        }
        return false;
    }

    /**
     * Flips or sets the enabled state. Returns true if the state changed.
     */
    public boolean setEnabled(@Nullable String url, boolean enabled) {
        SubscribedList l = find(url);
        if (l == null || l.enabled == enabled) return false;
        l.enabled = enabled;
        sortLists();
        save();
        return true;
    }

    /**
     * Renames a list's display title. Only meaningful for custom lists;
     * preset lists keep their catalog title.
     */
    public boolean setTitle(@Nullable String url, @Nullable String newTitle) {
        SubscribedList l = find(url);
        if (l == null || newTitle == null) return false;
        newTitle = newTitle.trim();
        if (newTitle.isEmpty() || newTitle.equals(l.title)) return false;
        l.title = newTitle;
        sortLists();
        save();
        return true;
    }

    /**
     * Imports URLs from a blob of text (clipboard payload, file contents).
     * Each non-empty line is treated as a candidate URL. Lines that don't
     * start with https:// are skipped. Returns the number of new
     * subscriptions added.
     */
    public int importFromText(@Nullable String text) {
        if (text == null || text.isEmpty()) return 0;
        int added = 0;
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (!line.toLowerCase(Locale.ROOT).startsWith("https://")) continue;
            if (subscribe(line, null, SOURCE_CUSTOM)) added++;
        }
        return added;
    }

    // ------------------------------------------------------------------------
    // Auto-update
    // ------------------------------------------------------------------------

    public int getAutoUpdateHours() {
        return prefs.getInt(KEY_AUTO_HOURS, UPDATE_DEFAULT);
    }

    public void setAutoUpdateHours(int hours) {
        prefs.edit().putInt(KEY_AUTO_HOURS, hours).apply();
    }

    public long getLastAutoRefreshMillis() {
        return prefs.getLong(KEY_LAST_REFRESH, 0L);
    }

    public void setLastAutoRefreshMillis(long ms) {
        prefs.edit().putLong(KEY_LAST_REFRESH, ms).apply();
    }

    /**
     * True if auto-update is enabled and the interval has elapsed since the
     * last refresh. Callers check this at cold start or resume.
     */
    public boolean isAutoUpdateDue() {
        int h = getAutoUpdateHours();
        if (h <= UPDATE_OFF) return false;
        long last = getLastAutoRefreshMillis();
        if (last <= 0L) return true;
        long intervalMs = (long) h * 60L * 60L * 1000L;
        return System.currentTimeMillis() - last >= intervalMs;
    }

    // ------------------------------------------------------------------------
    // Engine stats cache (last parse pass)
    // ------------------------------------------------------------------------

    public void setLastParseStats(int parsed, int skipped) {
        prefs.edit()
                .putInt(KEY_LAST_PARSED, parsed)
                .putInt(KEY_LAST_SKIPPED, skipped)
                .apply();
    }

    public int getLastParsedCount() {
        return prefs.getInt(KEY_LAST_PARSED, 0);
    }

    public int getLastSkippedCount() {
        return prefs.getInt(KEY_LAST_SKIPPED, 0);
    }

    /**
     * Human-readable summary for the management UI.
     */
    @NonNull
    public String summaryLine() {
        return enabledCount() + " of " + size() + " lists active";
    }

    /**
     * Convenience: join enabled URLs with newlines for legacy debugging.
     */
    @NonNull
    public String debugEnabledUrls() {
        return TextUtils.join("\n", enabledListUrls());
    }
}
