# ============================================================
# General rules
# ============================================================
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}


# Agent (reflection/SPI)
-keep class com.nextflow.nftouch.agent.langchain.http.** { *; }
-keep class com.nextflow.nftouch.agent.** { *; }

# Tool registration (reflection)
-keep class com.nextflow.nftouch.tool.** { *; }

# Channel (DingTalk/Feishu callbacks, keep generic signatures)
-keep class com.nextflow.nftouch.channel.** { *; }

# ============================================================
# Gson
# ============================================================
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Gson TypeToken generics
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ============================================================
# OkHttp
# ============================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ============================================================
# Retrofit
# ============================================================
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# ============================================================
# LangChain4j
# ============================================================
-dontwarn dev.langchain4j.**
-keep class dev.langchain4j.** { *; }
-keep interface dev.langchain4j.** { *; }

# ============================================================
# Jackson (LangChain4j internal dep, keep constructors and fields for serialization)
# ============================================================
-dontwarn com.fasterxml.jackson.**
-keep class com.fasterxml.jackson.** { *; }
-keep interface com.fasterxml.jackson.** { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <init>(...);
}

# ============================================================
# Jackson (LangChain4j OpenAI internal JSON serialization dependency)
# Missing this rule causes R8 to obfuscate Jackson internal classes, runtime errors
# "Class xxx has no default (no arg) constructor"
# ============================================================
-dontwarn com.fasterxml.jackson.**
-keep class com.fasterxml.jackson.** { *; }
-keep interface com.fasterxml.jackson.** { *; }
-keepnames class com.fasterxml.jackson.** { *; }
# Keep Jackson-annotated class members (fields/methods)
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* *;
    @com.fasterxml.jackson.databind.annotation.* *;
}
# Keep no-arg constructors for Jackson reflection
-keepclassmembers,allowobfuscation class * {
    @com.fasterxml.jackson.annotation.JsonCreator <init>(...);
}

# ============================================================
# MMKV
# ============================================================
-keep class com.tencent.mmkv.** { *; }

# ============================================================
# Glide
# ============================================================
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
    *** rewind();
}
-dontwarn com.bumptech.glide.**


# ============================================================
# Feishu Lark OAPI SDK
# ============================================================
-dontwarn com.lark.oapi.**
-keep class com.lark.oapi.** { *; }

# ============================================================
# DingTalk Stream SDK
# ============================================================
-dontwarn com.dingtalk.**
-keep class com.dingtalk.** { *; }
-keep interface com.dingtalk.** { *; }
# Keep callback generic signatures (SDK checks generics via reflection)
-keep,allowobfuscation,allowshrinking class * implements com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener
-keepattributes Signature

# ============================================================
# Server-side classes required by SDKs (not present on Android, safe to ignore)
# ============================================================
# javax.naming (LDAP/JNDI - Apache HttpClient HostnameVerifier)
-dontwarn javax.naming.**

# Apache HttpClient
-dontwarn org.apache.http.**
-dontwarn org.apache.commons.**

# Log4j / Log4j2
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**

# Netty (shade + original packages)
-dontwarn shade.io.netty.**
-dontwarn io.netty.**
-keep class shade.io.netty.** { *; }
-keep class io.netty.** { *; }

# Netty tcnative (OpenSSL bindings)
-dontwarn shade.io.netty.internal.tcnative.**
-dontwarn io.netty.internal.tcnative.**

# Jetty ALPN / NPN
-dontwarn org.eclipse.jetty.alpn.**
-dontwarn org.eclipse.jetty.npn.**

# JetBrains Annotations
-dontwarn org.jetbrains.annotations.**

# ============================================================
# ZXing
# ============================================================
-dontwarn com.google.zxing.**
-keep class com.google.zxing.** { *; }

# ============================================================
# MultiType (drakeet)
# ============================================================
-dontwarn com.drakeet.multitype.**
-keep class com.drakeet.multitype.** { *; }

# ============================================================
# BlankJ UtilCode
# ============================================================
-dontwarn com.blankj.**
-keep class com.blankj.utilcode.** { *; }
-keep public class com.blankj.utilcode.util.** { *; }

# ============================================================
# EasyFloat
# ============================================================
-dontwarn com.lzf.easyfloat.**
-keep class com.lzf.easyfloat.** { *; }

# ============================================================
# ok2curl
# ============================================================
-dontwarn com.moczul.ok2curl.**
-keep class com.moczul.ok2curl.** { *; }

# ============================================================
# Kotlin / Coroutines
# ============================================================
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.**

# ============================================================
# AndroidX
# ============================================================
-dontwarn androidx.**
-keep class androidx.** { *; }
-keep interface androidx.** { *; }

# ============================================================
# glide-transformations (wasabeef)
# ============================================================
-dontwarn jp.wasabeef.glide.**
-keep class jp.wasabeef.glide.** { *; }
