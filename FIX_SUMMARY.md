# Pull Request: Fix NullPointerException on App Startup

## Issue
App crashes on startup with the following error:
```
java.lang.NullPointerException: Attempt to invoke virtual method 'java.util.ArrayList m.q.j()' on a null object reference
at com.spoondon.browser.MainActivity.J(SourceFile:3)
at m.q.e(SourceFile:99)
at m.q.<init>(SourceFile:21)
at com.spoondon.browser.MainActivity.onCreate(SourceFile:247)
```

## Root Cause
Initialization order bug in `MainActivity.onCreate()`:
- `TabManager` was being initialized before `AdBlockEngine` was initialized
- When `TabManager` constructor calls `loadSavedTabs()` → `createNewTab()` → `createWebView()`, it triggers WebView creation
- The newly created WebView uses `SpoonWebViewClient`, which calls `AdBlockEngine.hasRules()` and `AdBlockEngine.shouldBlock()` during request interception
- Since `AdBlockEngine.init()` hadn't been called yet, its static collections (`blockedDomains`, `scopedPathRules`, etc.) were null, causing the NPE

## Solution
Moved `AdBlockEngine.init(this, filterLists)` to be called **before** `TabManager` is instantiated. This ensures all static collections in `AdBlockEngine` are properly initialized before any WebView tries to access them.

## Files Changed
- `android/app/src/main/java/com/spoondon/browser/MainActivity.java`

## Code Changes

### MainActivity.java (lines 232-270)

**Before:**
```java
prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

isDesktopMode = prefs.getBoolean("isDesktopMode", false);
setupRootLayout();
tabManager = new TabManager(this, prefs, browserContainer, this);
// ...
if (backgroundExecutor != null) {
    backgroundExecutor.execute(() -> {
        AdBlockEngine.init(MainActivity.this, filterLists);  // Too late!
        AdBlockEngine.checkAndRefreshFilters(...);
    });
}
```

**After:**
```java
prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

isDesktopMode = prefs.getBoolean("isDesktopMode", false);

// Initialize AdBlockEngine BEFORE TabManager to prevent NullPointerException
// when WebView tries to access filter rules during creation
AdBlockEngine.init(this, filterLists);

setupRootLayout();
tabManager = new TabManager(this, prefs, browserContainer, this);  // Now safe!
// ...
if (backgroundExecutor != null) {
    backgroundExecutor.execute(() -> {
        AdBlockEngine.checkAndRefreshFilters(...);  // Only refresh needed
    });
}
```

## Testing
- Clean build required
- Test app launch on fresh install
- Verify no crash occurs during MainActivity creation
- Check that WebView loads properly with ad blocking enabled

## Impact
- **Critical**: Fixes app startup crash
- **Low Risk**: Only changes initialization order, no logic changes
- **Backward Compatible**: No API or behavior changes

## Related Files
See `CRASH_LOG_ANALYSIS.md` for detailed crash analysis.
