# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep yggstack mobile bindings
-keep class yggstack.** { *; }
-keep interface yggstack.** { *; }

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class link.yggdrasil.yggstack.android.**$$serializer { *; }
-keepclassmembers class link.yggdrasil.yggstack.android.** {
    *** Companion;
}
-keepclasseswithmembers class link.yggdrasil.yggstack.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep data classes used with DataStore
-keep class link.yggdrasil.yggstack.android.data.** { *; }

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep Service and BroadcastReceiver
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

