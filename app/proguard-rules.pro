# 混淆规则（当前模板阶段 minifyEnabled = false，正式发版时再启用）

# Room —— 保留数据库相关类与字段名
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# kotlinx.serialization —— 保留 @Serializable 类的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class **$Companion { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class team.bhe.**$$serializer { *; }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
