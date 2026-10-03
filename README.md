# Spoon Browser

Android WebView browser. Single-activity app, Java 21.

## Requirements

- Android 7.0+ (minSdk 24)
- compileSdk 36, targetSdk 36
- JDK 21
- AGP 8.13.0, Gradle 8.14.3

## Build

Debug:
    cd android
    ./gradlew assembleDebug

Release:
    cd android
    ./gradlew assembleRelease

Release signing uses `release.keystore` at the repo root. In CI it is written
from the `ANDROID_KEYSTORE_BASE64` secret; see `.github/workflows/android-build.yml`.

## Features

- Multi-tab with session restore (URL list persistence, no WebView.saveState)
- Tab switcher with thumbnail previews (sync capture on switch + postVisualStateCallback fallback)
- Bookmarks and history managers: search, sort, inline edit, batch delete,
  "save as bookmark" from history
- Home page with bookmarks grid and cached favicons (local disk cache, no third-party services)
- Password vault (EncryptedSharedPreferences) with a floating autofill pill on login pages
- Ad blocker: hosts-only engine, accepts OISD / StevenBlack / EasyList hostname rules
- Downloads via OkHttp parallel-chunk engine, files written directly to MediaStore
- Find in page, per-host desktop site toggle, pull-to-refresh via JS scroll hook
- Cleartext HTTP interstitial with per-session approval and a user-editable whitelist

## Security posture

- `setAllowFileAccess(false)`, `setAllowContentAccess(true)`
- `MIXED_CONTENT_NEVER_ALLOW`
- `Network Security Config` is permissive by design; cleartext enforcement is in app code
  (`CleartextPolicy` + `SpoonWebViewClient`) so the trusted-host list is user-editable at runtime
- Vault page served via `WebViewAssetLoader` at a synthetic HTTPS origin;
  `WebMessageListener` scoped to that origin
- JS bridges: `AndroidDownloader`, `SpoonVault`, `SpoonCleartext`, `SpoonScroll`

## CI

- `.github/workflows/android-build.yml` — release build on push to `main`, tag push,
  and manual dispatch. Attaches the raw APK to the GitHub release on `v*` tags.
- `.github/workflows/main.yml` — debug build on PR to `main` and manual dispatch.

## Thanks

See [THANKS.md](THANKS.md).
