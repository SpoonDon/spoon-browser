# ==============================================================================
# Spoon Browser - Production R8/ProGuard Optimization Profile
# ==============================================================================

# 1. Core Code Optimization & Shrinking Directives
-allowaccessmodification
-flattenpackagehierarchy

# 2. Advanced Metadata Retention (Preserves reflection architectures)
-keepattributes JavaScriptInterface,Annotation,Signature,InnerClasses,EnclosingMethod

# 3. Production Diagnostics & Stack Trace De-obfuscation
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 4. Strict Android WebKit & WebView Engine Protection
-keep class android.webkit.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 5. Maintain View constructors for XML layout inflaters
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 6. Suppress safe compiler warning noise from core dependencies
-dontwarn android.webkit.**

