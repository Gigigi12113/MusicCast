# MusicCast R8 规则
#
# 目标：开 minify 压缩 dex（material-icons-extended 引入了上万个图标类，
# 实际只用几十个），但保证核心功能不受影响。
#
# 原则：只 keep 运行时反射需要的类，其余全交给 R8 裁剪。

# ── UPnPCast：它自带的 consumer 规则已 keep DLNACast 主 API 和 types.**，
#    这里补 keep 内部实现（SSDP/SOAP 用了反射回调），防裁剪后投送失败
-keep class com.yinnho.upnpcast.internal.** { *; }

# ── NanoHTTPD：onBind/onClose 等在父类中通过反射调用不确定
-keep class fi.iki.elonen.** { *; }

# ── Room：生成的 *_Impl 通过反射实例化；DAO 方法由注解处理
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }

# ── Compose：开 minify 后 compose 编译器已注入 keep 规则（2.0 起），
#    一般无需手写。但本机构建环境的合成类若被裁会直接崩 UI，
#    保守起见 keep 我们自己的 Composable 函数（体积可忽略）
-keep class com.aidigua.musiccast.** { *; }

# ── Kotlin 元数据/协程：R8 对 kotlinx 协程的默认规则已够用，
#    但 StateFlow/Channel 冷路径有回调反射，加一条保险
-dontwarn kotlinx.coroutines.**

# ── 通用：保留注解（Room/Compose 运行时读注解）
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ── 调试时排查用：保留行号（崩溃栈可读）
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
