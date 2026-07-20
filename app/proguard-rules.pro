# ProGuard / R8 — règles pour CineFlight Stage
# DJI Mobile SDK v5 : ne pas obfusquer/supprimer les classes du SDK.
-keep class dji.** { *; }
-keep class com.dji.** { *; }
-dontwarn dji.**
-dontwarn com.dji.**
# Kotlin coroutines
-keepclassmembtypes class kotlinx.coroutines.** { volatile <fields>; }
