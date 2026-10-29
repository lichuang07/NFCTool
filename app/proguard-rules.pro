# R8 / ProGuard 保留规则
#
# 开启混淆后，以下几类代码会被裁掉或改名，导致运行时崩溃：
#   1. kotlinx.serialization 的 @Serializable 类与生成的 serializer
#   2. 通过反射访问的枚举 / 数据类（本项目导出功能会反射读字段）
#   3. Compose 的编译器生成代码
#   4. NDEF 相关的手工构造（部分用到了反射式取值）

# ---- kotlinx.serialization ----
# @Serializable 注解的类及其生成的 Companion.serializer()
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable 标注的类：保留其字段名（序列化按字段名匹配）
-keep @kotlinx.serialization.Serializable class ** { *; }

# ---- 本项目的核心数据结构（跨模块传递，必须保留原名）----
# 这些类在多个模块间传递（core ↔ ui），且带枚举与 when 匹配。
# R8 的类内联/合并优化可能改写它们，显式 keep 可彻底消除疑虑。
# 成本极低（几十个类），但能避免「编译通过、真机运行崩溃」这类难查的问题。
-keep class com.nfctool.app.core.nfc.ndef.SharedContentParser { *; }
-keep class com.nfctool.app.core.nfc.ndef.SharedContentParser$* { *; }
-keep class com.nfctool.app.core.share.ShareIntentRouter { *; }
-keep class com.nfctool.app.core.share.ShareIntentRouter$* { *; }
-keep class com.nfctool.app.core.ndef.QrCodeEncoder { *; }
-keep class com.nfctool.app.core.ndef.QrCodeEncoder$* { *; }
-keep class com.nfctool.app.core.ndef.ImageCompressor { *; }
-keep class com.nfctool.app.core.ndef.ImageCompressor$* { *; }
-keep class com.nfctool.app.core.ndef.VCardCodec { *; }
-keep class com.nfctool.app.core.ndef.VCardCodec$* { *; }
-keep class com.nfctool.app.core.ndef.WifiWscCodec { *; }
-keep class com.nfctool.app.core.ndef.WifiWscCodec$* { *; }
-keep class com.nfctool.app.core.ndef.NdefKind { *; }
-keep class com.nfctool.app.core.ndef.NdefKind$* { *; }

# ---- 本项目的序列化模型 ----
# 只有 Repositories.kt 里的 AppSettings 用了 @Serializable，
# 保留它（序列化按字段名匹配，混淆后字段名变化会导致存取的设置读不出来）。
# ⚠️ 不要整包 `-keep class com.nfctool.app.** { *; }` ——
# 那会让 R8 几乎无法裁剪，classes.dex 会从 ~1.5MB 膨胀到 11MB。
-keep @kotlinx.serialization.Serializable class com.nfctool.app.** { *; }

# ---- manifest 里按名字引用的组件必须保留原名 ----
# AGP 会自动为这些生成 keep 规则，这里再显式声明一遍以防万一。
# ⚠️ 如果 manifest 里的类名在 dex 里找不到，安装会直接失败，
# 且报错信息与真实原因完全无关（只显示 "packageInfo is null"）。
-keep class com.nfctool.app.MainActivity { *; }
-keep class com.nfctool.app.NfctoolApp { *; }
-keep class com.nfctool.app.core.hce.NfcHceService { *; }
-keep class com.nfctool.app.core.service.BackgroundScanService { *; }

# ---- Compose ----
# Compose 编译器生成的代码会引用运行时内部 API，但 R8 自带的
# androidx.compose 规则已经处理好了，**不需要**整包 keep。
# ⚠️ 曾经写 `-keep class androidx.compose.runtime.** { *; }`，
# 那会保留 1 万多个类，classes.dex 直接涨到 11MB。
-dontwarn androidx.compose.**

# ---- Kotlin 元数据 ----
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keep class kotlin.Metadata { *; }

# ---- 保留行号，方便崩溃堆栈定位 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
