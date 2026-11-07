# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.xycz.**$$serializer { *; }
-keepclassmembers class com.xycz.** { *** Companion; }
-keepclasseswithmembers class com.xycz.** { kotlinx.serialization.KSerializer serializer(...); }
# QuickJS 通过 JNI 回调
-keep class com.whl.quickjs.** { *; }
# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
