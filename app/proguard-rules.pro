# libadb-android dhe BouncyCastle perdorin reflection (ofruesit e kriptografise, TLS per Wireless debugging)
-keep class io.github.muntashirakon.adb.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.conscrypt.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.naming.**
# Stack trace te lexueshme ne raportet e gabimeve
-keepattributes SourceFile,LineNumberTable
