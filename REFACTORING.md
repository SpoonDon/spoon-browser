# Spoon Browser — God-Object Split Plan

**Status:** Slices 1, 2, 3, 4 delivered.
Slice 5 remains, then the security batch.

Goal: reduce `MainActivity.java` from ~2000 lines to a ~300-line orchestrator.

---

## Slices 1–3 — DELIVERED ✅

Files: `TabManager`, `DownloadHandler`, `ToolbarController`,
`VaultController`, `AdBlockController`, `HistoryController`.
See earlier versions of this doc for migration steps.

---

## Slice 4 — DELIVERED ✅

### New files
- `android/app/src/main/java/com/spoondon/browser/PermissionController.java`
- `android/app/src/main/java/com/spoondon/browser/MenuController.java`

### Rewritten file
- `android/app/src/main/java/com/spoondon/browser/SpoonWebChromeClient.java`
  (now takes a `PermissionController` and delegates all permission callbacks)

### Fields to remove from `MainActivity.java`

```java
// Now in PermissionController
private androidx.activity.result.ActivityResultLauncher<String[]> webPermissionLauncher;
public android.webkit.PermissionRequest currentPermissionRequest;
public android.webkit.GeolocationPermissions.Callback currentGeolocationCallback;
public String currentGeolocationOrigin;
public android.webkit.ValueCallback<android.net.Uri[]> mFilePathCallback;
public static final int FILECHOOSER_RESULTCODE = 100;

// Now in MenuController
private static final String KEY_SEARCH_ENGINE = "search_engine";
```

### Methods to delete from `MainActivity.java`

**Menu-related:**

- `setupMenuButton()`
- `showSearchEngineDialog()`
- `getSearchUrlFor(String)`
- `showFindInPageDialog()`
- `showAbout()`
- `createStatRow(...)`
- `getAppVersion()`
- The `findInPageBar` field and its cleanup logic
- The private `showMainMenu(View)` helper introduced in slice 2

**Permission-related:**

- The entire `webPermissionLauncher` registration block in `onCreate`
- The `webPermissionLauncher.launch(...)` body
- `requestWebPermissions(String[])` — replaced by
`permissionController` calls from `SpoonWebChromeClient`
- Any `onActivityResult` override used for the file chooser
(now handled by `ActivityResultContracts.StartActivityForResult`)

### Fields to keep in `MainActivity.java`

`customView` and `customViewCallback` remain on MainActivity because they
are shared between the fullscreen flow and other UI state. `SpoonWebChromeClient`
calls thin setter methods on MainActivity (see below) instead of touching
them directly.

### New fields in `MainActivity.java`

```
PermissionController permissionController;
MenuController menuController;
```

### Wiring in `onCreate` — add BEFORE `createConfiguredWebView` is ever called

```
// --- Slice 4: permission + menu controllers ---
permissionController = new PermissionController(this);

menuController = new MenuController(this, prefs, new MenuController.Callbacks() {

    @Override public void newTab(boolean incognito) {
        tabManager.createNewTab(incognito);
        showHome();
    }
    @Override public void reload() {
        WebView wv = tabManager.getCurrentWebView();
        if (wv != null) wv.reload();
    }
    @Override public void openDownloads() {
        try {
            Intent i = new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(MainActivity.this, "No download manager found",
                    Toast.LENGTH_SHORT).show();
        }
    }
    @Override public void findInPage() {
        menuController.showFindInPageDialog(tabManager.getCurrentWebView());
    }
    @Override public void showBookmarks() {
        historyController.showBookmarks();
    }
    @Override public void addBookmark() {
        WebView wv = tabManager.getCurrentWebView();
        if (wv != null && wv.getUrl() != null) {
            historyController.addBookmark(wv.getUrl(), wv.getTitle());
        }
    }
    @Override public void showHistory() {
        historyController.showHistoryDialog();
    }
    @Override public void clearHistory() {
        historyController.clearHistory();
    }
    @Override public void clearCache() {
        WebView wv = tabManager.getCurrentWebView();
        if (wv != null) wv.clearCache(true);
        Toast.makeText(MainActivity.this, "Cache cleared", Toast.LENGTH_SHORT).show();
    }
    @Override public void showFilterLists() {
        adBlockController.showFilterListsDialog();
    }
    @Override public void toggleFilterEngine() {
        adBlockController.toggleEngine(tabManager.getCurrentWebView());
    }
    @Override public void toggleDesktopMode() {
        MainActivity.this.toggleDesktopMode();
    }
    @Override public boolean isDesktopEnabledForCurrentSite() {
        String host = getCurrentHost();
        return isDesktopHostEnabled(host);
    }
    @Override public void showSavedPasswords() {
        vaultController.showSavedPasswordsDialog();
    }
    @Override public void importPasswords() {
        passwordImportLauncher.launch("text/*");
    }
    @Override public void exportPasswords() {
        exportCsvLauncher.launch("spoon_passwords.csv");
    }
    @Override public void showVaultForCurrentSite() {
        vaultController.showVaultForCurrentSite();
    }
    @Override public void toggleStartupAnimation() {
        android.content.SharedPreferences splashPrefs =
                getSharedPreferences("browser_prefs", MODE_PRIVATE);
        boolean was = splashPrefs.getBoolean("show_splash_screen", true);
        splashPrefs.edit().putBoolean("show_splash_screen", !was).apply();
        Toast.makeText(MainActivity.this,
                !was ? "Startup Animation Enabled" : "Startup Animation Disabled",
                Toast.LENGTH_SHORT).show();
    }
    @Override public void exit() {
        clearSessionOnExit = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.webkit.CookieManager.getInstance().flush();
        }
        prefs.edit().remove("open_tabs").remove("current_tab").apply();
        finishAndRemoveTask();
    }
    @Override public WebView getCurrentWebView() { return tabManager.getCurrentWebView(); }
    @Override public Context getContext() { return MainActivity.this; }
    @Override public SharedPreferences getPreferences() { return prefs; }
});
```

### Update the slice-2 ToolbarController callback

Replace:

```
@Override public void onMenuClicked(@NonNull View anchor) {
    showMainMenu(anchor);
}
```

with:

```
@Override public void onMenuClicked(@NonNull View anchor) {
    menuController.showMainMenu(anchor);
}
```

### New helper methods on MainActivity (used by MenuController + SpoonWebChromeClient)

```
void setToolbarVisible(boolean visible) {
    if (toolbarController != null && toolbarController.getRootView() != null) {
        toolbarController.getRootView().setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}

void setBrowserVisible(boolean visible) {
    if (browserContainer != null) {
        browserContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}

void attachFullscreenView(View view) {
    if (root != null && view != null && view.getParent() == null) {
        root.addView(view);
    }
}

void detachFullscreenView(View view) {
    if (root != null && view != null
            && view.getParent() instanceof ViewGroup) {
        ((ViewGroup) view.getParent()).removeView(view);
    }
}

int getTabCount()      { return tabManager != null ? tabManager.getTabCount() : 0; }
int getBookmarkCount() { return dbHelper != null ? dbHelper.getBookmarkCount() : 0; }
int getHistoryCount()  { return dbHelper != null ? dbHelper.getHistoryCount() : 0; }
```

### Update `createConfiguredWebView()`

Change:

```
webView.setWebChromeClient(new SpoonWebChromeClient(this));
```

to:

```
webView.setWebChromeClient(new SpoonWebChromeClient(this, permissionController));
```

### Delete these fields/methods (replaced by MenuController)

The fullscreen-video block in `SpoonWebChromeClient` used to touch
`activity.toolbar`, `activity.browserContainer`, `activity.root` directly.
Those are now private and accessed via the helper methods above.

### Smoke tests for slice 4

- Menu → every item triggers the correct action
- Menu → Passwords → Saved Passwords / Import / Export all work
- Menu → Search Engine switches the default
- Menu → About shows WebView version + live adblock rule count
- Menu → Find in Page opens the overlay and highlights
- On a site requesting camera/mic → OS permission prompt appears
- On a site requesting geolocation → OS permission prompt appears
- Click `<input type="file">` → file chooser opens, selected file is delivered
- YouTube video in fullscreen → enters and exits correctly

---

## Slice 5 — SessionManager + NavigationHelper

### `SessionManager.java`

Extract:

- `clearSessionOnExit` flag
- Cookie flush + WebStorage wipe in `onPause` / `onStop` / `onDestroy`
- `showExitConfirmationDialog()`

### `NavigationHelper.java` (static utility class)

Extract:

- `openUrl(String)` — URL-vs-search decision + auto-HTTPS upgrade
- `normalizeDesktopHost(String)`
- `isDesktopHostEnabled(Context, String)`
- `applyDesktopUa(WebView, boolean)`

---

## After slice 5 — security batch (A/B/C + items #1–#8)

| Fix ↕▾ | File ↕▾ | Effort ↕▾ |
|---|---|---|
| −A: `usesCleartextTraffic=false` | `AndroidManifest.xml`, `network_security_config.xml` | 15 min |
| B: WebViewAssetLoader for vault | new `VaultAssetProvider.java` + `createConfiguredWebView()` | 1 hr |
| C: AdBlock `@@` + `$domain=` | `AdBlockEngine.parseFilterLines()` | 1 hr |
| #1: Delete `getPassword`/`getUsername` | `PasswordAutosaveBridge` in MainActivity | 5 min |
| MIXED_CONTENT switch | `configureWebSettings()` | 5 min |
| Duplicate `checkAndRefreshFilters` | `loadSavedData()` | 2 min |
⚙

---

## Safety notes

1. Build after every slice. Green CI before the next slice.
2. Commit granularly — one slice per commit.
3. Do NOT touch `SpoonWebViewClient` during refactor (only `SpoonWebChromeClient`
was touched in slice 4, and only for permission delegation).

