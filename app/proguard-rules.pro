# Proguard rules for Twitter PWA Android

# Keep serialization
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# Keep JavaScript Interface
-keepclassmembers class net.kgtkr.polytwi.injector.PushJsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class net.kgtkr.polytwi.injector.CpftConfigBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class net.kgtkr.polytwi.injector.MediaBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
