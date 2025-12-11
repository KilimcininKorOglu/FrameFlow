# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# ========================================
# Meta Wearables DAT SDK
# ========================================
-keep class com.meta.wearable.** { *; }
-keepclassmembers class com.meta.wearable.** { *; }
-dontwarn com.meta.wearable.**

# ========================================
# RootEncoder RTMP Library
# ========================================
-keep class com.pedro.** { *; }
-keepclassmembers class com.pedro.** { *; }
-keep class org.apache.** { *; }
-dontwarn com.pedro.**

# ========================================
# MediaCodec (Video Encoding)
# ========================================
-keep class android.media.** { *; }
-keepclassmembers class android.media.** { *; }

# ========================================
# Kotlin Coroutines
# ========================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# ========================================
# Kotlin Serialization (if used)
# ========================================
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# ========================================
# DataStore Preferences
# ========================================
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite { *; }

# ========================================
# FrameFlow App Classes
# ========================================
-keep class com.keremgok.frameflow.data.** { *; }
-keep class com.keremgok.frameflow.streaming.** { *; }

# ========================================
# General Android Rules
# ========================================
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
