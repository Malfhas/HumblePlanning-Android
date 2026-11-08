# kotlinx.serialization : les classes @Serializable gardent leurs sérialiseurs générés
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.humbleplanning.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.humbleplanning.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.humbleplanning.app.**$$serializer { *; }

# OkHttp : dépendances facultatives absentes
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Socket.IO client: optional dependencies absent
-dontwarn org.json.**
-keep class io.socket.** { *; }
