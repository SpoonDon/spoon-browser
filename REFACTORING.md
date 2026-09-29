# Spoon Browser — God-Object Split Plan

**Status:** Slices 1, 2, and 3 delivered.
Slices 4–5 speced below.

Goal: reduce `MainActivity.java` from ~2000 lines to a ~300-line orchestrator.
Each slice is independently buildable and reversible.

---

## Slice 1 — DELIVERED ✅

Files: `TabManager.java`, `DownloadHandler.java`.
See earlier version of this doc for migration steps.

---

## Slice 2 — DELIVERED ✅

File: `ToolbarController.java`.
See earlier version of this doc for migration steps.

---

## Slice 3 — DELIVERED ✅

### New files
- `android/app/src/main/java/com/spoondon/browser/VaultController.java`
- `android/app/src/main/java/com/spoondon/browser/AdBlockController.java`
- `android/app/src/main/java/com/spoondon/browser/HistoryController.java`

### Fields to remove from `MainActivity.java`

```java
// Adblock prefs keys — now in AdBlockController
private static final String KEY_FILTER_LISTS = "filter_lists";
private static final String KEY_FILTER_REFRESH_TIME = "filter_refresh_time";
```

Everything else (filterLists list, dbHelper, secureCredentialManager) stays
on MainActivity and is passed *by reference* to the controllers.

### Methods to delete from `MainActivity.java`

**Vault:**

- `showVaultForCurrentSite()`
- `parseAccountsForHost(String)`
- `showSavedPasswordsDialog()`
- `copyTextToClipboard(String, String)`
- `makeSmallButton(String)` — only used by CredentialAdapter
- Inner class `CredentialAdapter`

**AdBlock:**

- `showFilterListsDialog()`
- `showFilterListOptions()`
- `showSubscribedFilterLists()`
- `saveFilterLists()`

**History:**

- `showHistoryDialog()`
- `showBookmarks()`
- `migrateLegacyBookmarksToDatabase()`

### New fields in `MainActivity.java`

```
VaultController vaultController;
AdBlockController adBlockController;
HistoryController historyController;
```

### Wiring in `onCreate` — add AFTER the slice-2 toolbar block

```
// --- Slice 3: extracted UI controllers ---
vaultController = new VaultController(this,
        secureCredentialManager,
        backgroundExecutor,
        new VaultController.Callbacks() {
            @Nullable @Override
            public String getCurrentHost() {
                WebView wv = tabManager.getCurrentWebView();
                if (wv == null || wv.getUrl() == null) return null;
                return Uri.parse(wv.getUrl()).getHost();
            }

            @Override
            public void copyToClipboard(@NonNull String value, @NonNull String message) {
                if (clipboardManager != null) {
                    clipboardManager.setPrimaryClip(
                            ClipData.newPlainText("spoon_copy", value));
                }
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });

adBlockController = new AdBlockController(
        this, filterLists, backgroundExecutor, prefs);

historyController = new HistoryController(
        this, dbHelper, backgroundExecutor,
        new HistoryController.Callbacks() {
            @Override public void onNavigate(@NonNull String url) { openUrl(url); }
            @Override public void openInNewTab(@NonNull String url) {
                tabManager.openUrlInNewTab(url);
            }
        });
```

### Replace the old `migrateLegacyBookmarksToDatabase()` call

Search `onCreate` for:

```
migrateLegacyBookmarksToDatabase();
```

Replace with:

```
historyController.migrateLegacyBookmarksToDatabase();
```

### Update references in `showMainMenu(View)` (from slice 2)

The menu handler currently calls these old method names. Update:

| Old call ↕▾ | New call ↕▾ |
|---|---|
| −`showSavedPasswordsDialog()` | `vaultController.showSavedPasswordsDialog()` |
| `showBookmarks()` | `historyController.showBookmarks()` |
| `showHistoryDialog()` | `historyController.showHistoryDialog()` |
| `dbHelper.clearHistory(); Toast...` | `historyController.clearHistory()` |
| `dbHelper.addBookmark(wv.getUrl(), wv.getTitle()); Toast...` | `historyController.addBookmark(wv.getUrl(), wv.getTitle())` |
| `showFilterListsDialog()` | `adBlockController.showFilterListsDialog()` |
| The inline enable/disable toggle block | `adBlockController.toggleEngine(getCurrentWebView())` |
| The menu label check `AdBlockEngine.checkIsEngineEnabled(this)` | `adBlockController.isEngineEnabled()` |
| `showVaultForCurrentSite()` | `vaultController.showVaultForCurrentSite()` |
⚙

### Update the "Passwords" menu option

The current handler:

```
case "Passwords":
    String[] options = {"Saved Passwords", "Import from CSV", "Export to CSV"};
    new AlertDialog.Builder(this).setItems(options, (dialog, which) -> {
        if (which == 0) {
            createNewTab();
            openUrl("file:///android_asset/vault.html");
        } else if (which == 1) {
            passwordImportLauncher.launch("text/*");
        } else if (which == 2) {
            exportCsvLauncher.launch("spoon_passwords.csv");
        }
    }).show();
    return true;
```

Leave the "Import from CSV" / "Export to CSV" branches unchanged (they use
Activity Result launchers still owned by MainActivity). Only "Saved Passwords"
should now route to `vaultController.showSavedPasswordsDialog()` *or* the
vault.html tab — your call. Recommend keeping the vault.html tab since it's
the richer UX.

### Bugfix shipped in this slice

`VaultController.showSavedPasswordsDialog()` now reads from
`SecureCredentialManager.getAllCredentialsAsJson()` instead of directly
parsing the legacy `secure_vault.dat` file. The legacy file is deleted on
first launch by `migrateLegacyVault()`, so the old code always showed "No
passwords saved yet" for anyone past first-run. Behavior is now correct.

### Smoke tests for slice 3

- Menu → Passwords → Saved Passwords shows the real list
- Tap an entry → shows username + password → Delete removes it and refreshes
- On a site with saved credentials, `🔑 Vault / Autofill` shows the picker
- Copy ID / Copy Pass copy to clipboard (60s auto-clear still works)
- Menu → Filter Lists → add a URL, view subscriptions, remove one
- Menu → Enable/Disable Filterlists toggles and reloads the page
- Menu → History / Bookmarks / Add Bookmark / Clear History all work
- On upgrade from a pre-3.x install, bookmarks migrate once

---

## Slice 4 — PermissionController + MenuController

### `PermissionController.java`

Extract:

- `webPermissionLauncher` field + `registerForActivityResult` call
- `currentPermissionRequest` / `currentGeolocation*` fields
- `requestWebPermissions(String[])`
- `mFilePathCallback` + `FILECHOOSER_RESULTCODE`
- `onActivityResult` file-chooser handling

`SpoonWebChromeClient` gains a `PermissionController` dep instead of calling
`activity.requestWebPermissions(...)` directly. This is the biggest change in
slice 4 — SpoonWebChromeClient.java needs a small refactor.

### `MenuController.java`

Extract the body of `showMainMenu(View)` plus:

- `showSearchEngineDialog()`
- `showAbout()` / `createStatRow(...)`
- `showFindInPageDialog()`
- `getSearchUrlFor(String)` + `KEY_SEARCH_ENGINE` handling
- `getAppVersion()`

Constructor deps (all interface-typed to keep it testable):

- `TabManager`, `ToolbarController`, `HistoryController`,
`AdBlockController`, `VaultController`, `BrowserDatabaseHelper`
- A small `Callbacks` interface for: `onExit()`, `onReload()`, `onClearCache()`,
`onToggleDesktop()`, `openUrl(String)`, `showSearchEngine()`, etc.

---

## Slice 5 — SessionManager + NavigationHelper

### `SessionManager.java`

Extract:

- `clearSessionOnExit` flag
- Cookie flush + WebStorage wipe in `onPause` / `onStop` / `onDestroy`
- `showExitConfirmationDialog()`

### `NavigationHelper.java` (static utility class)

Extract:

- `openUrl(String)` — the URL-vs-search decision + auto-HTTPS upgrade
- `normalizeDesktopHost(String)`
- `isDesktopHostEnabled(Context, String)`
- `applyDesktopUa(WebView, boolean)`

Pure functions over Context / WebView — static utility is the right shape.

---

## After slice 5 — security batch (A/B/C + items #1–#8)

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

---

## Safety notes for each slice

1. **Build after every slice.** Do not stack slices without a green CI run.
2. **Commit granularly.** One slice per commit.
3. **Smoke test each slice** using the checklist under that slice's section.
4. **Do NOT touch the WebView client during refactor.** Leave
`SpoonWebViewClient` / `SpoonWebChromeClient` alone until slice 4.

