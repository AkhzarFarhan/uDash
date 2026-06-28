# ProGuard rules for uDash

# Room
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.RoomOpenHelper

# OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

# Gson / YouTube API Response Models
-keep class com.utility.dashcam.network.YouTubeChannelResponse { *; }
-keep class com.utility.dashcam.network.PageInfo { *; }
-keep class com.utility.dashcam.network.ChannelItem { *; }
-keep class com.utility.dashcam.network.ChannelSnippet { *; }
-keep class com.utility.dashcam.network.Thumbnails { *; }
-keep class com.utility.dashcam.network.ThumbnailInfo { *; }
-keep class com.utility.dashcam.network.ChannelContentDetails { *; }
-keep class com.utility.dashcam.network.RelatedPlaylists { *; }
-keep class com.utility.dashcam.network.ChannelStatistics { *; }
-keep class com.utility.dashcam.network.TokenManager$* { *; }
-keepattributes *Annotation*

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