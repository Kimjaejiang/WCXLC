# ─── Xposed Module Entry Points ────────────────────────────────────
# These MUST be kept with original names — Xposed framework loads them
-keep class de.robv.android.xposed.** { *; }
-keep class io.github.libxposed.** { *; }
-keep class com.Johnny.wcx.entry.** { *; }
-keep class com.Johnny.wcx.application.** { *; }

# ─── Feature / Hook Classes ─────────────────────────────────────────
# Keep class structure (for reflection/Xposed callback) but allow obfuscation
-keep class com.Johnny.wcx.features.** { *; }
-keep,allowobfuscation class com.Johnny.wcx.hooks.** { *; }
-keep,allowobfuscation class com.Johnny.wcx.datas.** { *; }
# ─── 热更新 SPI ──────────────────────────────────────────────────
# SPI 接口与数据类由**外部插件 APK** 按原名引用，绝不能被混淆/裁剪，
# 否则插件实现 HotPlugin 时 ART 解析接口失败，会一路委托到微信的
# BaseDexClassLoader 子类并触发其 findClass→loadClass 回退递归（死循环）。
# 必须彻底 -keep（不能用 allowobfuscation）：allowobfuscation 仍会改名，
# 插件按原名引用就找不到接口了。
-keep class com.Johnny.wcx.hot.HotApi { *; }
-keep class com.Johnny.wcx.hot.HotManifest { *; }
-keep interface com.Johnny.wcx.hot.HotPlugin { *; }
-keep interface com.Johnny.wcx.hot.HotHost { *; }
-keep interface com.Johnny.wcx.hot.HotHandle { *; }
-keep interface com.Johnny.wcx.hot.HotPrefs { *; }


# Keep annotation-annotated members (used by compile-time processors)
-keepclassmembers,allowobfuscation class * {
    @com.Johnny.wcx.annotations.* *;
}

# ─── Kotlin ────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keep class kotlin.coroutines.Continuation { *; }
-dontwarn kotlinx.coroutines.**

# kotlin-reflect 经传递依赖存在于 APK：R8 不得裁剪其内建表
# （否则 KotlinBuiltIns.getBuiltInClassByFqName 返回 null → 启用功能时 IllegalStateException）
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

# ─── Serialization ──────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-keep class kotlinx.serialization.** { *; }
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.Johnny.wcx.**$$serializer { *; }
-keepclassmembers class com.Johnny.wcx.** {
    *** Companion;
}
-keepclasseswithmembers class com.Johnny.wcx.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ─── Room ───────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ─── Compose ────────────────────────────────────────────────────────
# -keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ─── Third-party (dontwarn only, allow R8 optimization) ──────────────
-dontwarn com.alibaba.fastjson2.**
-dontwarn io.netty.**
-dontwarn com.google.protobuf.**
-dontwarn com.tencent.wcdb.**
-dontwarn org.slf4j.**
-dontwarn org.mozilla.javascript.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn io.ktor.**
-dontwarn com.materialkolor.**
-dontwarn miuix.**
-dontwarn javax.**
-dontwarn java.lang.invoke.**

# ─── WeChat Stubs ───────────────────────────────────────────────────
-keep class com.tencent.mm.** { *; }

# ─── Obfuscation Enhancements ───────────────────────────────────────
-repackageclasses
-allowaccessmodification
-overloadaggressively
-useuniqueclassmembernames

# ─── Attributes ─────────────────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes Exceptions
-keepattributes SourceFile,LineNumberTable

# ─── Keep resource names used in code ───────────────────────────────
-keepclassmembers class **.R$* {
    public static <fields>;
}

# R$plurals 类本身必须保留：R8 会因 R$plurals 成员被常量内联而整体移除该类，
# 但 Kotlin 对 pluralStringResource(R.plurals.*) 的编译引用仍指向它。
-keep class **.R$plurals { *; }
