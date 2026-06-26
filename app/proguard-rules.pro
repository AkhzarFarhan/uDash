# ProGuard rules for uDash

# Room
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.RoomOpenHelper

# OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**

# Google API Client
-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.youtube.** { *; }

# FFmpegKit
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# EncryptedSharedPreferences / Security Crypto
-keep class androidx.security.crypto.** { *; }

# Kotlin Coroutines
-keep class kotlinx.coroutines.** { *; }

# Avoid shrinking names of entities used via reflection
-keepclassmembers class com.utility.dashcam.data.local.** {
    <fields>;
}