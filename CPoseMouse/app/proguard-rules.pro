# NCNN 和 Kotlin 序列化保留
-keep class com.dutyclock.core.NcnnEngine { *; }
-keep class com.dutyclock.core.** { *; }
-keep class com.dutyclock.** { *; }

# Kotlin 序列化
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
