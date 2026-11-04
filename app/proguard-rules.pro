# MP3tify proguard rules — keep media3 + mp3tify

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# MP3tify
-keep class com.mp3tify.** { *; }
-dontwarn com.mp3tify.**

# Gson / reflection friendly data classes
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.mp3tify.backup.** { *; }
