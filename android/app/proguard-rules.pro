# ===========================================================================
# Spoon Browser - R8 / ProGuard configuration
# ===========================================================================

# --- Core optimization ------------------------------------------------------
-allowaccessmodification
-repackageclasses ''

# --- Metadata retention ----------------------------------------------------
# JavascriptInterface : required so R8 keeps @JavascriptInterface methods
# Annotation          : needed by AndroidX Security Crypto (Tink) and others
# Signature, InnerClasses, EnclosingMethod : needed by reflection paths
-keepattributes JavascriptInterface,Annotation,Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- JavaScript bridges ----------------------------------------------------
# This is the correct, standard rule.
# Do NOT add "-keep class android.webkit.** { *; }" - framework classes
# live on the boot classpath and cannot be obfuscated regardless.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Explicitly anchor our bridges so R8 cannot inline/merge them into an
# inaccessible location. Cheap insurance against future refactoring.
-keep class com.spoondon.browser.BlobDownloader {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.spoondon.browser.MainActivity$PasswordAutosaveBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# --- XML layout inflater support -------------------------------------------
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# --- Strip verbose logs from release builds --------------------------------
# Keep w() and e() so you still get warnings/errors in the field.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# --- Suppress warnings from transitive dependencies ------------------------
-dontwarn android.webkit.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
