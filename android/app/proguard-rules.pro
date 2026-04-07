# Keep Gson serialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.claudeusage.widget.UsageData { *; }
-keep class com.claudeusage.widget.UsageLimit { *; }
-keep class com.claudeusage.widget.PlanUsage { *; }
-keep class com.google.gson.** { *; }
