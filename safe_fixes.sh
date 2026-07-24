#!/bin/bash

set -e

echo "🔧 Applying safe, minimal fixes..."

cd android

# 1. Add webkit dependency if missing
if ! grep -q "androidx.webkit:webkit" app/build.gradle; then
    sed -i '/dependencies {/a \    implementation '\''androidx.webkit:webkit:1.14.0'\''' app/build.gradle
    echo "✅ Added webkit dependency."
else
    echo "ℹ️  webkit already present."
fi

# 2. Add WebView startup optimisation to SplashActivity
SPLASH="app/src/main/java/com/spoondon/browser/SplashActivity.java"

# Ensure imports exist
if ! grep -q "import androidx.webkit.WebViewCompat;" "$SPLASH"; then
    sed -i '/import android.os.Looper;/a import androidx.webkit.WebViewCompat;\nimport androidx.webkit.WebViewFeature;\nimport androidx.webkit.WebViewStartUpConfig;' "$SPLASH"
fi

# Add startup code after super.onCreate()
if ! grep -q "WebViewFeature.isFeatureSupported(WebViewFeature.START_UP_WEB_VIEW)" "$SPLASH"; then
    sed -i '/super.onCreate(savedInstanceState);/a \
        if (WebViewFeature.isFeatureSupported(WebViewFeature.START_UP_WEB_VIEW)) {\
            java.util.concurrent.Executor executor = java.util.concurrent.Executors.newSingleThreadExecutor();\
            WebViewStartUpConfig config = new WebViewStartUpConfig.Builder()\
                .setBackgroundThreadExecutor(executor)\
                .build();\
            WebViewCompat.startUpWebView(\
                getApplicationContext(),\
                config,\
                (result, error) -> { /* startup complete */ }\
            );\
        }' "$SPLASH"
    echo "✅ Added WebView startup."
else
    echo "ℹ️  WebView startup already present."
fi

# 3. Remove unused ContentFilterEngine inner class
MAIN="app/src/main/java/com/spoondon/browser/MainActivity.java"
if grep -q "static class ContentFilterEngine" "$MAIN"; then
    sed -i '/static class ContentFilterEngine {/,/^    }/d' "$MAIN"
    # Also remove any reference to filterEngine field (if present)
    sed -i '/ContentFilterEngine filterEngine/d' "$MAIN"
    echo "✅ Removed ContentFilterEngine."
else
    echo "ℹ️  ContentFilterEngine not found."
fi

cd ..
echo ""
echo "✅ Safe fixes applied successfully!"
echo "Now commit and push:"
echo "  git add ."
echo "  git commit -m 'Apply safe optimizations: webkit dependency and WebView startup'"
echo "  git push origin main"
