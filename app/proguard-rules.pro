# ProGuard rules for Web Video Caster

# Keep Cast SDK classes
-keep class com.google.android.gms.cast.** { *; }
-keep class com.google.android.gms.cast.framework.** { *; }

# Keep Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }

# Keep Jsoup (if used in future)
-keep class org.jsoup.** { *; }

# Keep Timber logging
-keep class timber.log.** { *; }

# Keep application class
-keep class com.instantbits.cast.webvideo.WebVideoCasterApplication { *; }

# Keep activity and fragments
-keep class com.instantbits.cast.webvideo.ui.** { *; }
-keep class com.instantbits.cast.webvideo.cast.** { *; }

# Keep video extractor
-keep class com.instantbits.cast.webvideo.video.** { *; }

# Keep view binding
-keep class com.instantbits.cast.webvideo.databinding.** { *; }

# Keep R classes
-keep class com.instantbits.cast.webvideo.R$* { *; }

# Suppress warnings
-dontwarn com.google.android.gms.**
-dontwarn kotlinx.coroutines.**
-dontwarn timber.log.**