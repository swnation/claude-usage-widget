# Keep Gson serialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.claudeusage.widget.UsageData { *; }
-keep class com.google.gson.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
