# PDFBox-Android reflects over its resource loader and font mapping tables.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.apache.**
-dontwarn javax.**

# kotlinx.serialization keeps generated serializers via reflection on companions.
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclassmembers class kotlinx.serialization.json.** { *** INSTANCE; }
-keepattributes *Annotation*, InnerClasses, Signature

# SQLDelight / SQLite
-keep class app.cash.sqldelight.** { *; }
-dontwarn app.cash.sqldelight.**
