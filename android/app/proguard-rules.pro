# libsignal uses JNI and reflection-adjacent native bindings. Keep its API surface.
-keep class org.signal.libsignal.** { *; }
-keep interface org.signal.libsignal.** { *; }
-keep class org.whispersystems.** { *; }

# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Room generated code is referenced directly
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }

# SQLCipher loads JNI entry points
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.nexchat.app.**$$serializer { *; }
-keepclassmembers class com.nexchat.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.nexchat.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
