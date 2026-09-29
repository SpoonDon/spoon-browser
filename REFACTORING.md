# Spoon Browser — God-Object Split Plan

**Status:** Slices 1 and 2 delivered.
Slices 3–5 speced below.

Goal: reduce `MainActivity.java` from ~2000 lines to a ~300-line orchestrator.
Each slice is independently buildable and reversible.

---

## Slice 1 — DELIVERED ✅

New files:
- `TabManager.java`
- `DownloadHandler.java`

See previous version of this doc for the full migration instructions.

---

## Slice 2 — DELIVERED ✅

### New file
- `android/app/src/main/java/com/spoondon/browser/ToolbarController.java`

### Fields to remove from `MainActivity.java`

```java
AutoCompleteTextView addressBar;
private SuggestionAdapter addressBarAdapter;
LinearLayout toolbar;
private Button forwardButton;
private Button prevTabButton;
private Button nextTabButton;
private Button newTabButton;
private Button menuButton;
private Button tabBadgeButton;
private TextView tabIndicator;

// Suggestion classes — now nested in ToolbarController
private static class Suggestion { ... }
private static class SuggestionAdapter { ... }
```

### Methods to delete from `MainActivity.java`

- `createToolbarViews()`
- `setupToolbarListeners()` — **BUT** see the caveat below.
- `makeButton(String)` — moved into ToolbarController
- `getToolbarButtonSize()`
- `updateAddressBarSuggestions(String)`
- `navigate()` — logic now lives in `ToolbarController.submitAddress()` → `callbacks.onNavigate()`

**Caveat on `setupToolbarListeners()`:** the original method also sets up
address-bar Enter handling. That's now inside `ToolbarController`. Delete the
whole method.

### New field in `MainActivity.java`

```
ToolbarController toolbarController;
```

### Wiring in `onCreate` — replace the old `createToolbarViews()`

### + `setupToolbarListeners()` + `setupMenuButton()` block with:

```
// --- Slice 2: extracted toolbar controller ---
toolbarController = new ToolbarController(this, new ToolbarController.Callbacks() {

    @Override
    public void onNavigate(@NonNull String input) {
        openUrl(input);
    }

    @Override
    public void onForward() {
        WebView wv = tabManager.getCurrentWebView();
        if (wv != null && wv.canGoForward()) wv.goForward();
    }

    @Override
    public void onPreviousTab() {
        int count = tabManager.getTabCount();
        if (count > 1) {
            int prev = tabManager.getCurrentPosition() - 1;
            if (prev < 0) prev = count - 1;
            tabManager.switchToTab(prev);
        }
    }

    @Override
    public void onNextTab() {
        int count = tabManager.getTabCount();
        if (count > 1) {
            tabManager.switchToTab((tabManager.getCurrentPosition() + 1) % count);
        }
    }

    @Override
    public void onNewTab() {
        tabManager.createNewTab();
        showHome();
    }

    @Override
    public void onShowTabSwitcher() {
        tabManager.showTabSwitcher();
    }

    @Override
    public void onMenuClicked(@NonNull View anchor) {
        // Slice 4 will extract this into MenuController.
        showMainMenu(anchor);
    }

    @NonNull @Override
    public List<ToolbarController.Suggestion> fetchSuggestions(@NonNull String query) {
        return queryHistoryForSuggestions(query);
    }

    @NonNull @Override
    public Executor getBackgroundExecutor() {
        return backgroundExecutor;
    }
});

// Add the toolbar to the root layout (was previously done inline).
root.addView(toolbarController.getRootView());
```

### Helper methods to add to `MainActivity.java`

Two small methods that used to be inline in `updateAddressBarSuggestions()`
and `setupMenuButton()`:

```
/**
 * Query the DB and produce a deduped, ranked suggestion list.
 * Runs on a background thread (called from ToolbarController).
 */
@NonNull
private List<ToolbarController.Suggestion> queryHistoryForSuggestions(@NonNull String query) {
    List<ToolbarController.Suggestion> out = new ArrayList<>();
    if (dbHelper == null) return out;

    List<String[]> rawResults = dbHelper.getMatchingHistory(query);
    Set<String> seenHosts = new HashSet<>();
    Set<String> seenUrls = new HashSet<>();

    // 1) Host-level suggestions
    for (String[] row : rawResults) {
        try {
            Uri uri = Uri.parse(row[0]);
            String host = uri.getHost();
            if (host == null) continue;
            String cleanHost = host.replaceFirst("^www\\.", "");
            if (cleanHost.toLowerCase().contains(query.toLowerCase())
                    && seenHosts.add(cleanHost)) {
                out.add(new ToolbarController.Suggestion(cleanHost, cleanHost));
                seenUrls.add(cleanHost);
            }
        } catch (Exception ignored) {}
    }

    // 2) Up to 3 deep-link suggestions
    int deepLinkLimit = 3;
    int deepLinksAdded = 0;
    for (String[] row : rawResults) {
        if (deepLinksAdded >= deepLinkLimit) break;
        String rawUrl = row[0];
        String title = (row[1] != null && !row[1].isEmpty()) ? row[1] : rawUrl;
        String displayUrl = rawUrl.replaceFirst("^https?://(www\\.)?", "");
        if (!seenUrls.contains(displayUrl) && !seenHosts.contains(displayUrl)) {
            out.add(new ToolbarController.Suggestion(title, displayUrl));
            seenUrls.add(displayUrl);
            deepLinksAdded++;
        }
    }
    return out;
}

/**
 * Menu builder. Will become MenuController in slice 4.
 */
private void showMainMenu(@NonNull View anchor) {
    // ... move the body of the CURRENT `menuButton.setOnClickListener`
    //     (the one inside setupMenuButton()) here, minus the listener wrapper.
}
```

### Delegating helpers — update existing call sites

Anywhere `MainActivity` still references `addressBar`, `tabIndicator`,
`forwardButton`, `menuButton`, etc., replace with:

```
toolbarController.getAddressBar()
toolbarController.setAddress(tabManager.getCurrentWebView().getUrl())
toolbarController.setTabCounter(tabManager.getCurrentPosition(), tabManager.getTabCount())
toolbarController.setIncognito(state.isIncognito())
toolbarController.setForwardEnabled(wv.canGoForward())
toolbarController.hideSuggestions()
toolbarController.clearAddressFocus()
```

### Update `TabManager.Callbacks.onCurrentTabChanged` wiring

Replace the address-bar block inside the slice-1 wiring with:

```
@Override
public void onCurrentTabChanged(WebView webView, TabState state) {
    if (toolbarController != null) {
        toolbarController.setAddress(webView != null ? webView.getUrl() : null);
        toolbarController.setIncognito(state != null && state.isIncognito());
        toolbarController.setForwardEnabled(webView != null && webView.canGoForward());
    }

    if (webView != null) {
        String host = webView.getUrl() != null
                ? Uri.parse(webView.getUrl()).getHost() : null;
        applyDesktopUa(webView, isDesktopHostEnabled(host));
    }

    updateScreenShield();
}

@Override
public void onTabCountChanged(int count) {
    if (toolbarController != null) {
        toolbarController.setTabCounter(tabManager.getCurrentPosition(), count);
    }
}
```

### In `onCreate` — remove the old `root.addView(toolbar)` line

The toolbar is now added via `root.addView(toolbarController.getRootView())`.
Search for the old `if (toolbar != null) root.addView(toolbar);` line and
delete it.

### Smoke tests for slice 2

- Address bar shows the current URL after every navigation
- Tapping a suggestion loads it and dismisses the dropdown
- Forward button lights up (tablet) / no-ops (phone)
- Prev/next tab buttons work (tablet only)
- Tab counter shows "N/M" after opening / closing tabs
- Menu button opens the popup menu
- Long-press on the tab indicator does nothing (regression guard)

---

## Slice 3 — VaultController + AdBlockController + HistoryController

Three UI-only controllers, each ~150 lines.

### `VaultController.java`

Extract:

- `showVaultForCurrentSite()`
- `parseAccountsForHost(String)`
- `showSavedPasswordsDialog()`
- Nested `CredentialAdapter`
- `copyTextToClipboard(String, String)`
- `makeSmallButton(String)` — only used by CredentialAdapter

Constructor deps: `MainActivity`, `SecureCredentialManager`, `Executor`.

**Does NOT include** `PasswordAutosaveBridge`. That stays tied to WebView
construction and is resolved in the security batch (item #1).

### `AdBlockController.java`

Extract:

- `showFilterListsDialog()`
- `showFilterListOptions()`
- `showSubscribedFilterLists()`
- `saveFilterLists()`

Constructor deps: `MainActivity`, `AdBlockEngine` (static),
`CopyOnWriteArrayList<String> filterLists`, `Executor`, `SharedPreferences`.

### `HistoryController.java`

Extract:

- `showHistoryDialog()`
- `showBookmarks()`
- `migrateLegacyBookmarksToDatabase()`

Constructor deps: `MainActivity`, `BrowserDatabaseHelper`, `Executor`,
`TabManager`, `Callback` for "open in new tab".

---

## Slice 4 — PermissionController + MenuController

### `PermissionController.java`

Extract:

- `webPermissionLauncher` field + registration
- `currentPermissionRequest` / `currentGeolocation*` fields
- `requestWebPermissions(String[])`
- `mFilePathCallback` + `FILECHOOSER_RESULTCODE`
- `onActivityResult` file-chooser handling

`SpoonWebChromeClient` gains a `PermissionController` dep instead of calling
`activity.requestWebPermissions(...)` directly.

### `MenuController.java`

Extract:

- `showMainMenu(View anchor)` — the popup currently built in `setupMenuButton()`
- `showSearchEngineDialog()`
- `showAbout()` / `createStatRow(...)`
- `showFindInPageDialog()`
- `getSearchUrlFor(String)` + `KEY_SEARCH_ENGINE` handling

Constructor deps: `MainActivity`, `TabManager`, `ToolbarController`,
`BrowserDatabaseHelper`, `AdBlockController`, `VaultController`, plus a
small set of callbacks for tab-close, cache-clear, exit, etc.

---

## Slice 5 — SessionManager + NavigationHelper

### `SessionManager.java`

Extract:

- `clearSessionOnExit` flag
- Cookie flush + WebStorage wipe logic in `onPause` / `onStop` / `onDestroy`
- `showExitConfirmationDialog()`

### `NavigationHelper.java` (static utility class)

Extract:

- `openUrl(String)` — the URL-vs-search decision + auto-HTTPS upgrade
- `normalizeDesktopHost(String)`
- `isDesktopHostEnabled(Context, String)`
- `applyDesktopUa(WebView, boolean)`

These are pure functions on top of Context / WebView — no state, so a static
utility class is the right shape.

---

## After slice 5 — apply the security batch (A/B/C + items #1–#8)

Once `MainActivity` is ~300 lines, the security batch becomes surgical:

| Fix ↕▾ | File ↕▾ | Effort ↕▾ |
|---|---|---|
| −A: `usesCleartextTraffic=false` | `AndroidManifest.xml`, `network_security_config.xml` | 15 min |
| B: WebViewAssetLoader for vault | new `VaultAssetProvider.java` + `createConfiguredWebView()` | 1 hr |
| C: AdBlock `@@` + `$domain=` | `AdBlockEngine.parseFilterLines()` | 1 hr |
| #1: Delete `getPassword`/`getUsername` | `PasswordAutosaveBridge` in MainActivity | 5 min |
| MIXED_CONTENT switch | `configureWebSettings()` | 5 min |
| Duplicate `checkAndRefreshFilters` | `loadSavedData()` | 2 min |
⚙

Estimated post-refactor effort: **2–3 hours**.

---

## Safety notes for each slice

1. **Build after every slice.** Do not stack slices without a green CI run.
2. **Commit granularly.** One slice per commit.
3. **Smoke test each slice** using the checklist under that slice's section.
4. **Do NOT touch the WebView client during refactor.** Leave
`SpoonWebViewClient` / `SpoonWebChromeClient` alone until slice 5.

