# Media3 and OkHttp ship their own consumer rules; these cover our own reflection surface.

# Room entities are constructed reflectively by generated code.
-keep class app.opentv.data.model.** { *; }

# kotlinx.serialization -----------------------------------------------------------------
# The generated $$serializer classes and Companion.serializer() accessors are reached
# reflectively, so R8 must not rename or strip them. Without these a minified release
# crashes the moment it parses a JSON payload — which every source load does.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keep,includedescriptorclasses class **$$serializer { *; }

# Keep `Companion` object fields of serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
# Keep `serializer()` on companion objects.
-if @kotlinx.serialization.Serializable class ** {
    static **$Companion Companion;
}
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Our own @Serializable DTOs (Xtream API models, GitHub release model). Belt-and-braces on
# top of the generic rules above, because these are the payloads a broken release fails on.
-keep class app.opentv.data.remote.** { *; }
-keepclassmembers class app.opentv.data.remote.** { *; }
-keep class app.opentv.sync.** { *; }
-keepclassmembers class app.opentv.sync.** { *; }

# OkHttp pulls in optional Conscrypt/BouncyCastle providers it does not need on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# SMBJ (NAS recording) and its transitive deps. It uses reflection (mbassador event bus) and
# pulls optional bits R8 would otherwise trip over. Keep its classes and silence the warnings so
# the shrunk release still connects to a NAS.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.**
