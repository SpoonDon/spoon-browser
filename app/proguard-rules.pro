# Add project specific ProGuard rules here.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keepattributes *Annotation*

# Keep WebView classes
-keepclassmembers class android.webkit.WebView {
   public *;
}
-keepclassmembers class android.webkit.ValueCallback {
   public *;
}
-keepclassmembers class android.webkit.WebChromeClient$CustomViewCallback {
    public *;
}
