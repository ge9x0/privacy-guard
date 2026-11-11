# libadb-android and BouncyCastle use reflection (crypto providers, TLS for Wireless debugging)
-keep class io.github.muntashirakon.adb.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.conscrypt.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.naming.**
# Readable stack traces in crash reports
-keepattributes SourceFile,LineNumberTable
