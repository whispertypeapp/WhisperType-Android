# WhisperType release rules.
# Compose and Kotlin metadata are already handled by their respective libraries.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# kotlinx-serialization (compiler-plugin generated serializers must survive R8).
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.whispertype.android.**$$serializer { *; }
-keepclassmembers class com.whispertype.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.whispertype.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-dontwarn okhttp3.**
