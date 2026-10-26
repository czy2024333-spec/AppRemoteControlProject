# Keep generic signatures of kotlinx.serialization classes
-keepattributes Signature, InnerClasses, EnclosingMethod

# kotlinx.serialization 官方推荐规则
# https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serialization-guide.md
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.vrxfusion.remote.**$$serializer { *; }
-keepclassmembers class com.vrxfusion.remote.** {
    *** Companion;
}
-keepclasseswithmembers class com.vrxfusion.remote.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Kotlin 反射与协程
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# OkHttp / Okio（WebSocket 传输层）
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# R8 full mode 下对空规则类保持安静
-dontnote kotlin.internal.PlatformImplementationsKt
