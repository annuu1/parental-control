# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep all project application code
-keep class com.example.telegramsender.** { *; }

# Keep WorkManager Worker classes instantiated via reflection
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Coroutines
-dontwarn org.jetbrains.kotlinx.coroutines.**
-keep class org.jetbrains.kotlinx.coroutines.** { *; }

# Android Lifecycle & Services
-keep class androidx.lifecycle.** { *; }
-keepattributes *Annotation*