# Rules for the instrumented-test APK only.
#
# The AndroidX test libraries reference ErrorProne's compile-time annotations,
# which are deliberately absent at runtime. Nothing here affects the shipped
# app — app/proguard-rules.pro governs that.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Keep the tests themselves and the code under test discoverable by name.
-keep class com.paperview.mobile.** { *; }
-keep class org.junit.** { *; }
-keep class androidx.test.** { *; }
