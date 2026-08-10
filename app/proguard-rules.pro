# DawaSafe — ProGuard / R8 rules
#
# minifyEnabled is currently FALSE, so none of this is active. It is kept
# because the day someone turns minification on to shrink the APK, the failure
# it would otherwise cause is invisible: the build succeeds, the app installs,
# and dose alarms silently never fire.
#
# The reason is that R8 has no way to see how these classes are used. The
# @JavascriptInterface methods are called by NAME from JavaScript, and the
# receivers and activities are named as STRINGS in AndroidManifest.xml. To
# R8's static analysis they all look like dead code.

# --- The JS bridge -----------------------------------------------------------
# Every public method here is reachable only from the page. Renaming even one
# of them breaks the alarm bridge with no error anywhere.
-keepclassmembers class com.dawasafe.app.NativeBridge {
    public *;
}
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- Manifest-declared components --------------------------------------------
# Instantiated reflectively by the framework from the manifest strings.
-keep public class com.dawasafe.app.MainActivity
-keep public class com.dawasafe.app.AlarmActivity
-keep public class com.dawasafe.app.AlarmReceiver
-keep public class com.dawasafe.app.ActionReceiver
-keep public class com.dawasafe.app.BootReceiver
-keep public class com.dawasafe.app.CacheProvider
-keep public class com.dawasafe.app.DawaSafeApp

# --- WebView plumbing --------------------------------------------------------
-keep class android.webkit.** { *; }
-dontwarn android.webkit.**

# Keep line numbers so a stack trace from a user is still readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
