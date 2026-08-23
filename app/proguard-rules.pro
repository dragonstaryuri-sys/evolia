# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,Annotation

# keep kotlinx serializable classes
-keep @kotlinx.serialization.Serializable class * {*;}

# keep jlatexmath
-keep class org.scilab.forge.jlatexmath.** {*;}

# JavaMail / Jakarta Mail rules
-keep class javax.mail.** {*;}
-keep class com.sun.mail.** {*;}
-keep class jakarta.mail.** {*;}
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**
-dontwarn jakarta.mail.**

-dontobfuscate

# Apache POI 5.2.5 and its dependencies
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class org.openxmlformats.schemas.** { *; }
-keep class com.microsoft.schemas.** { *; }
-keep class org.apache.commons.collections4.properties.SortedProperties { *; }

-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn javax.xml.stream.**
-dontwarn com.microsoft.schemas.**
-dontwarn org.openxmlformats.schemas.**
-dontwarn java.awt.**
-dontwarn net.sf.saxon.**
-dontwarn org.apache.batik.**
-dontwarn org.osgi.framework.**
-dontwarn aQute.bnd.annotation.**

# Apache Commons Compress (Used by POI for ZIP/OOXML)
# Fixes NoSuchMethodException for ZipExtraField implementations
-keep class org.apache.commons.compress.archivers.zip.** { *; }
-keepclassmembers class * implements org.apache.commons.compress.archivers.zip.ZipExtraField {
    public <init>();
}
-dontwarn org.apache.commons.compress.**

# Log4j 2 (Crucial for POI 5.x)
# The InstantiationException on DefaultFlowMessageFactory is a known issue with R8
-keep class org.apache.logging.log4j.** { *; }
-keep interface org.apache.logging.log4j.** { *; }
-keepclassmembers class org.apache.logging.log4j.** { *; }
-dontwarn org.apache.logging.log4j.**

# Explicitly keep constructors for Log4j factories loaded via reflection
-keepclassmembers class * implements org.apache.logging.log4j.message.MessageFactory {
    public <init>();
}
-keepclassmembers class * implements org.apache.logging.log4j.message.FlowMessageFactory {
    public <init>();
}
-keepclassmembers class org.apache.logging.log4j.message.DefaultFlowMessageFactory {
    public <init>();
}
-keepclassmembers class org.apache.logging.log4j.message.ParameterizedMessageFactory {
    public <init>();
}
-keepclassmembers class org.apache.logging.log4j.message.ReusableMessageFactory {
    public <init>();
}

# Bugly 混淆规则
-keep class com.tencent.bugly.** { *; }
-dontwarn com.tencent.bugly.**

# 移除 Release 版本的 Log.v 和 Log.d 日志
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
