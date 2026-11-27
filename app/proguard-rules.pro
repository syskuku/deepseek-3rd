# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.dschat.app.**$$serializer { *; }
-keepclassmembers class com.dschat.app.** { *** Companion; }
-keepclasseswithmembers class com.dschat.app.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Markwon
-dontwarn io.noties.markwon.**

# Tink (security-crypto) 引用的编译期注解，运行期不需要
-dontwarn com.google.errorprone.annotations.**