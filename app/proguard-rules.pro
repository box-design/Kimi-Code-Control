# Kimi Code 客户端混淆规则

# kotlinx-serialization：保留生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.kimicode.companion.**$$serializer { *; }
-keepclassmembers class com.kimicode.companion.** { *** Companion; }
-keepclasseswithmembers class com.kimicode.companion.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
