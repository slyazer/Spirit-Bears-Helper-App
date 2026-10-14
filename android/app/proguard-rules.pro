# Keep Gson model classes used for API (de)serialization.
-keep class com.macromonitor.app.data.api.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
