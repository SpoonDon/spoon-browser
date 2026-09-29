# Spoon Browser — God-Object Split Plan

**Status:** Slice 1 delivered (`TabManager`, `DownloadHandler`).
Slices 2–5 speced below.

The goal is to reduce `MainActivity.java` from ~2000 lines to a ~300-line
orchestrator. Each slice is independently buildable and reversible.

---

## Slice 1 — DELIVERED ✅

### New files
- `android/app/src/main/java/com/spoondon/browser/TabManager.java`
- `android/app/src/main/java/com/spoondon/browser/DownloadHandler.java`

### What to delete from `MainActivity.java`

**Fields to remove** (now owned by `TabManager`):
```java
private java.util.List<TabState> tabList;
private int currentTabPosition;
private TabAdapter tabAdapter;
private android.view.View tabSwitcherOverlay;
private androidx.recyclerview.widget.RecyclerView tabsRecyclerView;   // already dead
private android.view.ViewGroup webViewContainer;                      // already dead
```

**Methods to delete** (now in `TabManager`):

- `createNewTab()` / `createNewTab(boolean)`
- `switchToTab(int)`
- `closeTab(int)`
- `buildTabItems()`
- `showTabSwitcher()` / `hideTabSwitcher()`
- `updateTabCountersUI()`
- `updateTabBadgeCount()`
- `handleDeadRenderProcess(WebView)`
- `openUrlInNewTab(String)`
- `saveOpenTabs()` (replaced by `tabManager.getNonIncognitoUrls()`)
- `captureWebViewSnapshotAsync(...)`

**Download code to delete** (now in `DownloadHandler`):

- The entire `webView.setDownloadListener(new DownloadListener() { ... })` block
inside `createConfiguredWebView()` — replace with `downloadHandler.attach(webView);`
- `triggerManualDownload(String, String)` — replaced by
`downloadHandler.triggerExternalDownload(url, mime)`
- `triggerExternalDownload(String, String)` — moved into `DownloadHandler`

### New fields in `MainActivity`

```
TabManager tabManager;
DownloadHandler downloadHandler;
```

### Wiring in `onCreate` (after `browserContainer` is created)

Place this **after** the existing `root.addView(browserWrapper)` block and
**before** `loadSavedData()`:

```
// --- Slice 1: extracted managers ---
downloadHandler = new DownloadHandler(this, () -> tabManager.getCurrentWebView());

tabManager = new TabManager(this, browserContainer, new TabManager.Callbacks() {
    @NonNull @Override
    public WebView createConfiguredWebView() {
        return MainActivity.this.createConfiguredWebView();
    }

    @Override
    public void onCurrentTabChanged(WebView webView, TabState state) {
        // Sync the address bar to the new tab's URL
        if (addressBar != null) {
            String url = webView != null ? webView.getUrl() : null;
            boolean blank = (url == null || url.isEmpty() || "about:blank".equals(url));
            addressBar.setText(blank ? "" : url);

            if (state != null && state.isIncognito()) {
                addressBar.setBackgroundColor(Color.parseColor("#3c1f40"));
                addressBar.setHint("Incognito Search or URL");
            } else {
                addressBar.setBackgroundColor(Color.parseColor("#222222"));
                addressBar.setHint("Search or enter address");
            }
        }

        // Reapply per-site desktop UA for the newly active tab
        if (webView != null) {
            String host = webView.getUrl() != null
                    ? Uri.parse(webView.getUrl()).getHost() : null;
            applyDesktopUa(webView, isDesktopHostEnabled(host));
        }

        updateScreenShield();
    }

    @Override
    public void onTabCountChanged(int count) {
        if (tabIndicator != null) {
            tabIndicator.setText((tabManager.getCurrentPosition() + 1) + "/" + count);
        }
        if (tabBadgeButton != null) {
            tabBadgeButton.setText((tabManager.getCurrentPosition() + 1) + "/" + count);
        }
    }

    @Override
    public void onNewTabRequested() {
        tabManager.createNewTab();
        showHome();
    }

    @Override
    public void onAllTabsClosed() {
        showExitConfirmationDialog();
    }
});
```

### Delegating helpers

Keep these **private** methods in `MainActivity` so all ~40 existing
call sites continue to compile unchanged:

```
private WebView getCurrentWebView()          { return tabManager.getCurrentWebView(); }
private TabState getCurrentTabState()        { return tabManager.getCurrentTabState(); }
private int getCurrentTabPosition()          { return tabManager.getCurrentPosition(); }
```

Then replace every direct `tabList` / `currentTabPosition` access with the
helper. Search-and-replace:

- `tabList.size()` → `tabManager.getTabCount()`
- `tabList.get(...)` → `tabManager.getTabs().get(...)`
- `currentTabPosition` → `tabManager.getCurrentPosition()`
- `tabList.isEmpty()` → `tabManager.isEmpty()`

### Lifecycle delegation

```
@Override protected void onResume() {
    super.onResume();
    handleIncomingIntent(getIntent());
    setIntent(new Intent());
    if (tabManager != null) tabManager.resumeActiveTab();
}

@Override protected void onPause() {
    super.onPause();
    // ... existing cookie flush ...
    if (tabManager != null) tabManager.pauseActiveTab();
}

@Override protected void onDestroy() {
    // ... existing cookie flush ...
    if (backgroundExecutor != null) backgroundExecutor.shutdownNow();
    if (tabManager != null) tabManager.destroyAll();
    // ... clipboard cleanup ...
    super.onDestroy();
}

@Override public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    if (tabManager != null) tabManager.onTrimMemory(level);
}
```

### Back-press handler

Update the existing `OnBackPressedCallback`:

```
getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
    @Override public void handleOnBackPressed() {
        if (tabManager.isTabSwitcherVisible()) { tabManager.hideTabSwitcher(); return; }
        WebView wv = tabManager.getCurrentWebView();
        if (wv != null && wv.canGoBack()) { wv.goBack(); return; }
        if (tabManager.getTabCount() > 1) {
            tabManager.closeTab(tabManager.getCurrentPosition());
        } else {
            showExitConfirmationDialog();
        }
    }
});
```

### `saveOpenTabs()` replacement

```
private void saveOpenTabs() {
    java.util.List<String> urls = tabManager.getNonIncognitoUrls();
    backgroundExecutor.execute(() ->
        prefs.edit().putString(KEY_OPEN_TABS, TextUtils.join("\n", urls)).apply());
}
```

### In `createConfiguredWebView()`

Replace the entire download listener block with:

```
downloadHandler.attach(webView);
```

### SplashActivity `handleIncomingIntent`

Replace the `tabList.isEmpty()` check with `tabManager.isEmpty()`.

---

## Slice 2 — ToolbarController (next)

**Extract:** everything about the top toolbar — address bar, buttons, tab
badge, autocomplete dropdown.

**New file:** `ToolbarController.java`

**Public API sketch:**

```
class ToolbarController {
    interface Callbacks {
        void onNavigate(String input);
        void onForward();
        void onPreviousTab();
        void onNextTab();
        void onNewTab();
        void onShowTabSwitcher();
        void onShowMenu(View anchor);
        List<Suggestion> fetchSuggestions(String query);
    }

    ToolbarController(MainActivity activity, Callbacks callbacks);

    View getRootView();                        // the toolbar LinearLayout
    void setAddress(String url);
    void setTabCounter(int current, int total);
    void setIncognito(boolean incognito);
    void setForwardEnabled(boolean enabled);
    void hideSuggestions();
    void clearFocus();
}
```

**Moves out of `MainActivity`:**

- `createToolbarViews()`
- `setupToolbarListeners()` (the address-bar / button portions)
- `makeButton()`, `makeSmallButton()`, `getToolbarButtonSize()`
- `updateAddressBarSuggestions()`
- Nested classes: `Suggestion`, `SuggestionAdapter`

**Careful:** `onTabCountChanged` in slice 1 currently calls
`tabIndicator.setText(...)` and `tabBadgeButton.setText(...)` directly.
In slice 2 those become `toolbar.setTabCounter(...)`.

---

## Slice 3 — VaultController + AdBlockController + HistoryController

Three UI-only controllers, each ~150 lines.

### `VaultController.java`

- `showVaultForCurrentSite()`
- `parseAccountsForHost(String)`
- `showSavedPasswordsDialog()`
- `CredentialAdapter` (nested)
- `copyTextToClipboard(String, String)`

**Does NOT include** `PasswordAutosaveBridge` — that stays tied to WebView
construction and will be resolved in the security batch (item #1).

### `AdBlockController.java`

- `showFilterListsDialog()`
- `showFilterListOptions()`
- `showSubscribedFilterLists()`
- `saveFilterLists()`

Depends on `AdBlockEngine` (static) + `backgroundExecutor` + `filterLists`
list. Pass those in via constructor.

### `HistoryController.java`

- `showHistoryDialog()`
- `showBookmarks()`
- `migrateLegacyBookmarksToDatabase()`

Depends on `dbHelper`. Pass in via constructor.

---

## Slice 4 — PermissionController + MenuController

### `PermissionController.java`

- `webPermissionLauncher`
- `currentPermissionRequest` / `currentGeolocation*`
- `requestWebPermissions(String[])`
- `mFilePathCallback` + `FILECHOOSER_RESULTCODE`
- `onActivityResult` file-chooser handling

### `MenuController.java`

- The popup menu builder in `setupMenuButton()`
- `showSearchEngineDialog()`
- `showAbout()` / `createStatRow()`
- `showFindInPageDialog()`
- `getSearchUrlFor(String)`

---

## Slice 5 — SessionManager + NavigationHelper

### `SessionManager.java`

- `clearSessionOnExit`
- Cookie flush / WebStorage wipe logic in `onPause` / `onStop` / `onDestroy`
- `showExitConfirmationDialog()`

### `NavigationHelper.java` (static utility class)

- `openUrl(String)` — URL classification + search-vs-navigate decision
- `normalizeDesktopHost(String)`
- `isDesktopHostEnabled(Context, String)`
- `applyDesktopUa(WebView, boolean)`

---

## Safety notes for each slice

1. **Build after every slice.** Do not stack slices without a green CI run.
2. **Commit granularly.** One slice per commit, so `git revert` is surgical.
3. **Smoke test each slice:**

- Open new tab → home page appears
- Type URL → loads
- Open 3+ tabs → switcher works, thumbnails render
- Close tab → other tab becomes active
- Back-press → goes back / closes tab / shows exit dialog
- Rotate device → state preserved
4. **Do NOT touch the WebView client during refactor.** Leave
`SpoonWebViewClient` / `SpoonWebChromeClient` alone until slice 5.

---

## After slice 5 — apply the security batch

Once `MainActivity` is ~300 lines, the security fixes become trivial:

| Fix | Where it lands after refactor |
|---|---|
| A: `usesCleartextTraffic=false` | `AndroidManifest.xml` + `network_security_config.xml` |
| B: WebViewAssetLoader for vault | `createConfiguredWebView()` (or a new `VaultAssetProvider` helper) |
| C: AdBlock `@@` + `$domain=` | `AdBlockEngine.parseFilterLines()` — isolated |
| #1: Delete `getPassword`/`getUsername` | `PasswordAutosaveBridge` in MainActivity |
| MIXED_CONTENT switch | `configureWebSettings()` in MainActivity |

Estimated post-refactor effort for the entire security batch: **2–3 hours**.
Pre-refactor it would have been a week of careful surgery.

