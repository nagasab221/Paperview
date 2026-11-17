# PDFium's JNI layer looks these up by name from native code — R8 must not
# rename or strip them, or every render call throws NoSuchMethodError.
-keep class io.legere.pdfiumandroid.** { *; }
-keep class io.legere.pdfiumandroid.api.** { *; }

# kotlinx.serialization keeps generated serializers reachable via reflection.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.paperview.mobile.** {
    *** Companion;
}
-keepclasseswithmembers class com.paperview.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# PDFBox resolves fonts and filters reflectively, and references desktop-Java
# classes that don't exist on Android — warn about neither, and keep the font
# machinery intact or text drawing fails at runtime.
-keep class com.tom_roush.pdfbox.pdmodel.font.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**

# BouncyCastle backs PDF encryption. Its post-quantum parameter tables are
# excluded from packaging (see build.gradle.kts), so silence those references.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**

# OkHttp's optional platform integrations are absent at runtime by design.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# JSch (SSH cloud accounts) loads its ciphers, key exchanges and signatures by
# class name from its config, so R8 can't see they're used.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
