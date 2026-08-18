# Keep Room generated implementations
-keep class androidx.room.** { *; }
-keep class live.nikro.pinglab.data.db.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class live.nikro.pinglab.** {
    *** Companion;
}
-keepclasseswithmembers class live.nikro.pinglab.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Compose
-dontwarn androidx.compose.**

# Keep enum values used by name in persisted settings
-keepclassmembers enum live.nikro.pinglab.core.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
