# Spoon Browser

A privacy-first, lightweight Android browser built on the system WebView engine. Spoon combines a lean, modular Kotlin-free Java architecture with a genuine ad blocker, an encrypted credential vault, a custom parallel-chunk download engine, and adaptive thermal throttling.

Spoon is deliberately small in scope. It does not try to be Chrome. It tries to be the browser you keep installed because it respects your device, your battery, and your privacy.

---

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [Building](#building)
- [Project Structure](#project-structure)
- [Security Model](#security-model)
- [Session Persistence](#session-persistence)
- [Downloads Engine](#downloads-engine)
- [Thermal Optimizations](#thermal-optimizations)
- [CI / CD](#ci--cd)
- [Development Notes](#development-notes)
- [Testing Checklist](#testing-checklist)
- [Known Limitations](#known-limitations)
- [License](#license)

---

## Features

### Browsing

- Multi-tab browsing with a full-screen ViewPager2 tab switcher and live page thumbnails
- Incognito tabs with isolated cache, DOM storage, and form-data
- Desktop-mode toggle per host (frozen modern desktop User-Agent)
- Pull-to-refresh driven by a JavaScript scroll hook — works correctly on SPAs (YouTube, Twitter/X, Reddit, Instagram) instead of a hardcoded host list
- Address-bar search with inline suggestions from browsing history
- Long-press image to download
- Find in page
- Bookmarks, history with a 90-day cleanup, and a full-screen history dialog
- New-tab home page with a bookmarks shortcut grid
- Session restore across OS-initiated process death

### Privacy

- Built-in ad blocker (network + cosmetic rules)
  - Supports Adblock Plus syntax: element hiding (`##`), exception hiding (`#@#`), `~host` negation, `hostA,~hostB##selector`
  - Supports `$domain=` allow/deny, `$third-party`, resource-type filters
  - Exception rules (`@@`) for whitelisted sites
  - Cosmetic rules with negative-context support
- Global Privacy Control (`navigator.globalPrivacyControl = true`) injected on every page
- WebRTC IP-leak sanitizer rewrites local IPs in SDP offers to `0.0.0.0`
- Tracking-parameter stripper (`utm_*`, `fbclid`, `gclid`, `msclkid`, and ~20 more)
- Screen shield (`FLAG_SECURE`) while the vault is open

### Security

- **Encrypted credential vault** backed by `EncryptedSharedPreferences` (Tink)
  - Origin-scoped `WebMessageListener` — vault only responds to requests from its own synthetic HTTPS origin
  - Password autosave across multi-step logins (Google, Microsoft, Amazon, banks)
  - Shadow-DOM aware — finds password fields inside Web Components
  - CSV import and export
- **Cleartext policy** enforced in app code (not `network_security_config.xml`, which is compile-time)
  - Whitelist of router brands, gateway IPs, `localhost`, and mDNS hosts
  - User-editable additional trusted hosts via the menu
  - Cleartext interstitial with "Proceed once / Upgrade / Cancel" for non-whitelisted HTTP sites
  - SSL-bypass scoped to the same whitelist
- **`WebViewAssetLoader`** serves the vault page from `https://appassets.androidplatform.net/assets/vault.html` — a synthetic HTTPS origin with a stable, unforgeable authority
- `setAllowFileAccess(false)` — file:// navigation is disabled app-wide; the browser rejects `file:`, `content:`, `javascript:`, and `data:` URLs from both typed input and page-initiated navigations
- `MIXED_CONTENT_NEVER_ALLOW` on every WebView

### Downloads

- Custom OkHttp parallel-chunk download engine (replaces `DownloadManager`)
- Per-download pause / resume
- Cookie, `User-Agent`, and `Referer` headers forwarded from the originating WebView — fixes CDN 403s
- Blob-URL downloads via an injected `URL.createObjectURL` hook
- System downloader fallback for users who prefer it
- Downloads persisted to `downloads.json` with atomic writes (tmp + rename)
- `FileProvider` sharing

### Performance

- Session restore via URL-list persistence — never touches `WebView.saveState()`
- Pull-to-refresh scroll hook with a composed-path inner-scroller detector
- Autosave script fast-path skips the periodic sweep on pages without a password field
- Renderer priority policy: `IMPORTANT` for the visible tab, `WAIVED` for hidden tabs
- Adaptive thermal throttling (see [Thermal Optimizations](#thermal-optimizations))

---

## Architecture

Spoon follows a thin-Activity / heavy-collaborator pattern. `MainActivity` is a ~500-line orchestrator that owns the Android lifecycle, the root view tree, file pickers, the clipboard watcher, and the public API surface. All browser logic lives in dedicated collaborators.

```

MainActivity (lifecycle + view tree + public API)
│
└── AppWiring (constructs and wires every collaborator)
│
├── TabManager          — tab lifecycle, switcher overlay, thermal response
├── ToolbarController   — phone + tablet layouts, suggestions
├── MenuController      — 20-entry menu, search engine, settings dialogs
├── SessionManager      — cookie flush, tab persistence, exit dialog
├── HistoryController   — bookmarks, history, migration
├── VaultController     — native credential UI
├── AdBlockController   — filter list management
├── PermissionController — web permissions, file chooser
├── DownloadsController — parallel-chunk download UI
├── WebViewFactory      — WebView construction, JS bridges, asset loader
├── HomePageRenderer    — new-tab page
├── SuggestionProvider  — address-bar suggestions
├── ThermalController   — thermal status listener singleton
└── NavigationHelper    — UA ownership, openUrl decision tree

```

Cross-cutting utilities:

- `SpoonWebViewClient` — adblock interception, URL loading, cleartext interstitial, autosave injection, scroll hook injection
- `SpoonWebChromeClient` — window creation, fullscreen video, permission delegation
- `AdBlockEngine` — parser and matcher
- `CleartextPolicy` / `CleartextPreferences` / `CleartextInterstitial` / `CleartextBridge`
- `VaultUrls` — the single source of truth for the vault's synthetic origin
- `SecureCredentialManager` — encrypted credential storage
- `BrowserDatabaseHelper` — SQLite for bookmarks and history

---

## Building

### Requirements

| Tool | Version |
|---|---|
| JDK | 21 |
| Android Gradle Plugin | 8.13.0 |
| Gradle | 8.14.3 |
| `compileSdk` | 36 |
| `minSdk` | 24 |
| `targetSdk` | 36 |

### Debug build

```bash
cd android
./gradlew assembleDebug
```

### Release build (signed)

Release builds require a signing keystore. CI expects these secrets:

| Secret ↕▾ | Purpose ↕▾ |
|---|---|
| −`ANDROID_KEYSTORE_BASE64` | Base64-encoded keystore file |
| −`ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |
⚙

The keystore is expected at `android/release.keystore` at build time (`SIGNING_STORE_FILE=../release.keystore`).

> **Note:** If you rename or move the JS bridge classes (`PasswordAutosaveBridge`, `BlobDownloader`) you must update `proguard-rules.pro`. The default config anchors `-keep` rules to these class names. See Development Notes.

---

## Project Structure

```
android/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/spoondon/browser/
│       │   ├── MainActivity.java
│       │   ├── SplashActivity.java
│       │   ├── AppWiring.java
│       │   ├── TabManager.java
│       │   ├── TabState.java
│       │   ├── TabAdapter.java
│       │   ├── ToolbarController.java
│       │   ├── MenuController.java
│       │   ├── SessionManager.java
│       │   ├── HistoryController.java
│       │   ├── VaultController.java
│       │   ├── AdBlockController.java
│       │   ├── PermissionController.java
│       │   ├── DownloadsController.java
│       │   ├── DownloadHandler.java
│       │   ├── DownloadEngine.java
│       │   ├── DownloadService.java
│       │   ├── DownloadStore.java
│       │   ├── DownloadTask.java
│       │   ├── DownloadTaskState.java
│       │   ├── DownloadSpec.java
│       │   ├── ThermalController.java
│       │   ├── WebViewFactory.java
│       │   ├── SpoonWebViewClient.java
│       │   ├── SpoonWebChromeClient.java
│       │   ├── NavigationHelper.java
│       │   ├── CleartextPolicy.java
│       │   ├── CleartextPreferences.java
│       │   ├── CleartextInterstitial.java
│       │   ├── CleartextBridge.java
│       │   ├── VaultUrls.java
│       │   ├── AdBlockEngine.java
│       │   ├── SecureCredentialManager.java
│       │   ├── BrowserDatabaseHelper.java
│       │   ├── BrowserItemAdapter.java
│       │   ├── BlobDownloader.java
│       │   ├── HomePageRenderer.java
│       │   ├── SuggestionProvider.java
│       │   └── PersistedTab.java
│       ├── assets/
│       │   └── vault.html
│       └── res/
│           ├── layout/
│           ├── values/
│           ├── xml/
│           │   ├── network_security_config.xml
│           │   └── file_paths.xml
│           └── mipmap-*/
└── gradlew
```

---

## Security Model

### Cleartext (HTTP) policy

The network security config is **permissive** at the platform level (`cleartextTrafficPermitted=true`). This is deliberate: Android's NSC is compile-time, and Spoon's whitelist is user-editable at runtime. All enforcement happens in app code via `CleartextPolicy` + `SpoonWebViewClient.handleUrlLoading`, in three tiers:

1. **Whitelisted host** → load as-is
2. **Session-approved host** → load as-is (user tapped "Proceed" in the interstitial)
3. **Otherwise** → replace the page with an interstitial offering three options:

- **Proceed once** (session-scoped, not persisted)
- **Upgrade to HTTPS**
- **Cancel**

The SSL-error handler uses the same whitelist, so SSL bypass is scoped to hosts the user has explicitly trusted.

### Vault origin scoping

The vault page is served over a synthetic HTTPS origin. All communication between the vault HTML and native code goes through a `WebMessageListener` that:

- Is registered only on the vault WebView
- Rejects any message whose `view.getUrl()` is not the exact vault URL
- Is scoped to the exact vault origin, not a host pattern

This means even if a malicious page injected a message to `spoonVaultMessage`, native code would reject it before any handler runs.

### JS bridges

Spoon registers four JavaScript interfaces. Each is deliberately minimal:

| Bridge ↕▾ | Methods ↕▾ | Scope ↕▾ |
|---|---|---|
| −`AndroidDownloader` | `saveBase64ToFile(data, mime, filename)` | Global; only writes to the app's downloads dir |
| `SpoonVault` | `saveCredentials(host, user, pass)` | Global; writes to encrypted storage |
| `SpoonCleartext` | `proceed(url)`, `upgrade(url)`, `cancel()` | The interstitial's about:blank frame only |
| `SpoonScroll` | `setAtTop(boolean)` | Global; toggles `SwipeRefreshLayout` state |
⚙

None of them expose readable secrets. `SpoonVault` is write-only — reading credentials requires opening the vault UI, which is gated behind the `FLAG_SECURE` screen shield.

---

## Session Persistence

Spoon's session restore **never touches `WebView.saveState()` / `restoreState()`**. That API is process-scoped, hits the 1 MB Binder transaction limit on complex pages, and cannot survive OS-initiated process death. It is the direct cause of many crash reports in browsers that try to use it for cross-restart persistence.

Instead:

1. On `onPause`, `SessionManager.persistTabs()` walks the tab list and serializes each tab's `url` and `title` to a JSON array in `browser_prefs` (`open_tabs` key).
2. **Only if the Activity is not finishing.** This is critical — without the finishing check, `exitNow()` wipes the keys and `onPause` immediately re-writes them from the live tab list, resurrecting tabs on the next launch.
3. Filtered entries: incognito tabs, `about:blank`, and vault URLs are all excluded, both on write and on read.
4. A hard cap of 20 tabs prevents disk bloat.
5. On cold start (only when `savedInstanceState == null`), `MainActivity` reads the persisted list and calls `TabManager.restoreTabs(...)` — which recreates each tab and calls `loadUrl`.

**What is lost across cold start:** scroll position, in-page JS state, form input, back/forward history beyond the current URL. This matches the design of Chrome, Firefox, and Brave on Android — all of them re-navigate tabs rather than restore WebView state.

**What is preserved:** the tab list itself, each tab's URL, the active tab index, and the desktop-UA preference per host (re-derived from prefs).

---

## Downloads Engine

Spoon does not use `DownloadManager`. It ships its own parallel-chunk downloader:

- **HTTP client:** OkHttp 4.12.0
- **Parallelism:** multiple byte-range requests per file
- **Storage:** `getExternalFilesDir(DIRECTORY_DOWNLOADS)` (app-scoped, no runtime permission needed on any API level)
- **Persistence:** `downloads.json` in `filesDir`, written atomically (`.tmp` + rename)
- **Headers:** Cookie, `User-Agent`, and `Referer` are forwarded from the originating WebView; many CDNs 403 a request without them
- **Blob URLs:** handled by injecting a `URL.createObjectURL` hook on every page, so blob-backed downloads (Google Docs exports, Figma exports, etc.) route through the same engine
- **Fallback:** a "System Downloader" option in the download dialog hands off to `DownloadManager` for users who prefer the OS tray

The `FileProvider` authority is `${applicationId}.fileprovider`, and `res/xml/file_paths.xml` declares an `<external-files-path>` for the downloads directory.

---

## Thermal Optimizations

Spoon ships a three-tier thermal strategy. All three are adaptive — the visible tab is **never** throttled, and throttling is **reversible** the moment the device cools.

### Tier 1 — static fixes

- **Autosave fast-path guard:** `buildAutosaveScript()` returns early on pages without a password field, so the 2-second polling sweep never runs on the ~90% of pages that don't need it. This is the single largest thermal win.
- **Renderer priority policy:** the visible tab is marked `RENDERER_PRIORITY_IMPORTANT`; hidden tabs are marked `RENDERER_PRIORITY_WAIVED` with `waivedWhenNotVisible = true`. Chromium itself then knows to run hidden renderers at a lower priority.

### Tier 2 — adaptive response to OS thermal status

`ThermalController` is a singleton that wraps `PowerManager.getCurrentThermalStatus()` (API 29+). On API < 29 it always reports `STATUS_NONE` and does nothing.

`TabManager` subscribes and reacts:

| OS thermal status ↕▾ | Spoon response ↕▾ |
|---|---|
| −`NONE` / `LIGHT` | Normal operation |
| −`MODERATE` | Disable `setOffscreenPreRaster` on all tabs; inject a requestAnimationFrame wrapper into **hidden** tabs that limits callback dispatch to ~4 fps |
| `SEVERE` and above | Above, plus downgrade hidden tabs to `LAYER_TYPE_NONE` |
⚙

When the device cools, `ThermalController` fires the change, `TabManager` restores the original RAF and the hardware layer, and `offscreenPreRaster` is re-enabled.

### Tier 3 — idle detection (not currently shipped)

A proposed additional tier: after ~30 s of no touch input, throttle RAF on the **visible** tab as well. This carries real regressions (video overlays, live sports scoreboards, animated dashboards) and is not shipped by default.

---

## CI / CD

Two GitHub Actions workflows.

### `.github/workflows/android-build.yml` — Release

- **Triggers:** push to `main`, push of any tag matching `v*`, manual dispatch
- **Concurrency:** `release-${{ github.ref }}`, `cancel-in-progress: true`
- **Runner steps:**

- `actions/setup-java@v5` (JDK 21)
- `gradle/actions/setup-gradle@v4`
- Keystore decoding from `ANDROID_KEYSTORE_BASE64` to `android/release.keystore`
- Keystore validation via `keytool`
- `./gradlew assembleRelease --no-daemon`
- **Timeout:** 20 minutes per job
- **Permissions:** `contents: write`
- **Release attachment:** on tag pushes, `softprops/action-gh-release@v2` publishes the raw `.apk` files as release assets

### `.github/workflows/main.yml` — Debug

- **Triggers:** pull request to `main`, manual dispatch
- **Concurrency:** `debug-${{ github.ref }}`, `cancel-in-progress: true`
- **Artifact:** `spoon-browser-debug-apk` (GitHub always wraps artifacts in a zip — this cannot be changed)

### Downloading a raw APK

GitHub Actions artifacts are always zipped. To get a raw `.apk`:

1. Tag a release: `git tag v1.0.0 && git push origin v1.0.0`
2. Wait for the workflow to attach the APK to the release
3. Download from `https://github.com/<owner>/<repo>/releases/download/<tag>/<apk-filename>`

---

## Development Notes

### Delivery and file-size gotchas

- **BDS `create_file` truncates silently** at roughly 400 lines. Files above ~350 lines should be split into Part 1 / Part 2 with a marker comment.
- **Compile errors cut mid-expression** (not at a line boundary) almost always mean truncation, not a syntax error.
- **Markdown-rendered regex escapes get eaten.** `\\?` and `\\.` in a Java source file will render as `\?` and `\.` in a fenced code block, which produces `illegal escape character` at compile time. Always double-check after pasting from chat.

### Android XML gotcha

Android resource XML rejects `--` inside `<!-- -->` comments (`XMLStreamException` on build). Never use `--------` dividers or em-dashes inside XML comments.

### ProGuard / R8

The default R8 config is minimal and specific. It anchors `-keep` rules to the JS bridge class names, strips `Log.d` / `Log.v` / `Log.i`, and uses `-repackageclasses ''`.

**Critical:** `EncryptedSharedPreferences` uses Tink, which relies on reflective class lookups. R8 will strip the required classes and produce a silently broken release build (a `GeneralSecurityException` at first credential read) unless these rules are present:

```
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.crypto.tink.**
```

This is **release-only** and does not appear in debug builds. If autosave "silently stops working" in a release APK but works in debug, this is the first thing to check.

### Adding new JS bridges

New bridges must be registered in `WebViewFactory.create()`. The bridge class should be a private inner class of `WebViewFactory` so R8 keeps its methods anchored to the outer class. After adding a bridge, add a matching `-keep` rule if its class name is not already in `proguard-rules.pro`.

### Threading

- `TabManager`, `SessionManager`, `ToolbarController`, `MenuController`, and all `*Controller` classes require main-thread access. Do not call them from `backgroundExecutor` without a `mainHandler.post`.
- JS bridge methods arrive on a WebView-owned background thread. Always post to the UI thread before touching views.

---

## Testing Checklist

Run through this list after any non-trivial change.

### Browsing

- □  
Open 6 tabs across a mix of static sites, SPAs, and video sites
- □  
Switch between tabs — no crash, correct thumbnail, correct address bar
- □  
Close tabs — no leak, correct tab counter
- □  
Incognito tab does not appear in history and does not persist
- □  
Pull-to-refresh works on a scrolled page, does not fire when the page is already at the top
- □  
Pull-to-refresh on YouTube, Twitter/X, Reddit — verify no double-refresh
- □  
Long-press an image — download dialog appears

### Session restore

- □  
Open 3 tabs, swipe the app away from Recents
- □  
Wait 30 seconds, relaunch — all 3 tabs restored, active tab preserved
- □  
Menu → Exit — relaunch — tab list is empty, home page shown
- □  
Vault page does not persist across restore
- □  
Incognito tab does not persist across restore

### Downloads

- □  
Click a `.pdf` link — in-app dialog appears, no browser redirect
- □  
Click a `.zip` link — same
- □  
Blob download (e.g., Google Docs "Download as PDF") — file lands in downloads list
- □  
Pause a download, resume it, verify final file integrity
- □  
"System Downloader" hands off to the OS tray

### Password autosave

- □  
GitHub multi-step login — credentials land in vault under `github.com`
- □  
Google multi-step login — credentials land under `accounts.google.com`
- □  
Amazon multi-step login — same
- □  
Vault list correctly shows saved credentials, edit and copy work

### Cleartext policy

- □  
Visit an HTTP URL that is not whitelisted — interstitial appears with three options
- □  
Tap "Proceed" — page loads, tap "Cancel" — stays on prior page
- □  
Visit `http://192.168.1.1` — loads directly if the router is on the whitelist
- □  
Add a custom host to the trusted list via the menu — verify it loads without the interstitial

### Thermal

- □  
Run `adb shell dumpsys thermalservice` after 30 min of heavy tab use — note the current thermal status
- □  
If status is `MODERATE` or higher, verify tab switching is not unacceptably slow (offscreenPreRaster is disabled at this tier)
- □  
Confirm that a hidden YouTube tab does not prevent the device from cooling

---

## Known Limitations

- **UA-bound sessions:** switching a site to desktop mode signs the user out on sites that key sessions to User-Agent (Google among them). This is a design consequence of UA-scoped cookies, not a bug — every browser with a UA toggle behaves this way.
- **Frozen desktop UA:** the desktop-mode User-Agent string is a static Chrome build number, not the live system UA. This will drift as the installed WebView updates. Unifying desktop and mobile UA sources is on the roadmap.
- **Compile-time cleartext whitelist:** the base list of router brands and gateway IPs is compiled into `CleartextPolicy.java`. Users can add hosts at runtime; they cannot remove compiled entries.
- **Session restore loses scroll position and in-page state.** This is intentional — see Session Persistence.
- **No `saveState` / `restoreState`.** Also intentional, for the same reason.
- **`setAllowFileAccess(false)`** disables `file://` browsing entirely. Re-enabling requires changes in three places (`WebViewFactory`, `NavigationHelper`, and the menu) — the flag alone is not sufficient because `file://` free-form typing is an attack vector.

---

## License

This project is licensed under the MIT License. See `LICENSE` for details.

---

## Acknowledgements

- AndroidX WebKit team for `WebViewAssetLoader` and `WebViewCompat`
- OkHttp for the HTTP client used by the download engine
- The EasyList and EasyPrivacy filter list maintainers
- Tink and AndroidX Security for the encrypted credential storage primitives
