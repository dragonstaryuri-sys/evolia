# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# keep kotlinx serializable classes
-keep @kotlinx.serialization.Serializable class * {*;}

# keep jlatexmath
-keep class org.scilab.forge.jlatexmath.** {*;}

# JavaMail / Jakarta Mail rules to prevent connection failures in Release builds
# These ensure that protocol providers (like imaps) can be loaded via reflection
-keep class javax.mail.** {*;}
-keep class com.sun.mail.** {*;}
-keep class jakarta.mail.** {*;}
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**
-dontwarn jakarta.mail.**

# Keep generic signatures for reflection (often needed by mail libraries)
-keepattributes Signature,InnerClasses,EnclosingMethod

-dontobfuscate
