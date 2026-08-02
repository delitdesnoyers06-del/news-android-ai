# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /opt/android-studio/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the ProGuard
# include property in project.properties.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Required for Test execution
-dontwarn org.xmlpull.v1.**
-dontwarn org.apache.tools.ant.**
-dontwarn java.beans.**
-dontwarn javax.naming.**
-dontwarn sun.misc.Unsafe


# Mockito
-dontwarn org.mockito.**


-keepnames class * implements java.io.Serializable

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}


# AndroidSlidingUpPanel
# https://github.com/umano/AndroidSlidingUpPanel/issues/921
-dontwarn com.sothree.slidinguppanel.SlidingUpPanelLayout

# jsoup
-dontwarn com.google.re2j.*

# Other Libraries
-dontwarn org.apache.velocity.**
-dontwarn freemarker.**
-dontwarn com.google.auto.value.**
-dontwarn autovalue.shaded.**
#-keep class com.gu.option.Option
#-keep class com.gu.option.UnitFunction

# keep application classes used as database and network models
-keep class de.luhmer.owncloudnewsreader.database.model.** { *; }
-keep class de.luhmer.owncloudnewsreader.reader.nextcloud.ItemIds { *; }
-keep class de.luhmer.owncloudnewsreader.reader.nextcloud.ItemMap { *; }
-keep class de.luhmer.owncloudnewsreader.model.** { *; }
# keep the name of SyncItemStateService so SyncItemStateService.isMyServiceRunning works
-keepnames class de.luhmer.owncloudnewsreader.services.SyncItemStateService
# keep fields necessary for NewsReaderListActivity.adjustEdgeSizeOfDrawer and NewsReaderListActivity.getEdgeSizeOfDrawer to work
-keepclassmembers class androidx.drawerlayout.widget.DrawerLayout {
    private androidx.customview.widget.ViewDragHelper mLeftDragger;
}
-keepclassmembers class androidx.customview.widget.ViewDragHelper {
    private int mEdgeSize;
}

-printmapping out.map
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

###############
# GreenDAO
-keep class de.greenrobot.** { *; }
-dontwarn de.greenrobot.daogenerator.DaoGenerator

-keepclassmembers class * extends de.greenrobot.dao.AbstractDao { *; }


###############
# Guava (official)
## Not yet defined: follow https://github.com/google/guava/issues/2117
# Guava (unofficial)
## https://github.com/google/guava/issues/2926#issuecomment-325455128
## https://stackoverflow.com/questions/9120338/proguard-configuration-for-guava-with-obfuscation-and-optimization
-dontwarn com.google.common.base.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn java.lang.ClassValue
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn javax.inject.**
-dontwarn sun.misc.Unsafe

# Added for guava 23.5-android
-dontwarn afu.org.checkerframework.**
-dontwarn org.checkerframework.**


# Required for unit tests

# https://stackoverflow.com/a/39777485
# Also, note that this rule should be added to the regular proguard file(the one of listed in proguardFiles) and not the test one(declared as testProguardFile)
# java.lang.NoSuchMethodError: No virtual method getParameter
-keepclasseswithmembers public class com.nextcloud.android.sso.aidl.NextcloudRequest { *; }
-keepclasseswithmembers public class com.nextcloud.android.sso.AccountImporter { *; }

# NewsReaderListActivityTests
-keepclasseswithmembers public class androidx.recyclerview.widget.RecyclerView { *; }

###############################################################################
# On-device AI (mlGemma flavor). See docs/ai/PLAN.md §4.6.
#
# Neither litertlm-android:0.15.0 nor tasks-text:1.0.0 ships a consumer proguard.txt
# (verified: `unzip -l <aar> | grep -i proguard` is empty), and gradle.properties sets
# android.r8.strictFullModeForKeepRules=true — under which keeping an interface does NOT
# keep its implementers. Debug builds pass without these rules; the release build compiles
# fine and then crashes at runtime. Spike S7 (assembleOssMlGemmaRelease + an on-device
# smoke run) is the only thing that actually validates this block.
###############################################################################

# --- LiteRT-LM ---------------------------------------------------------------
# Native code does FindClass/GetMethodID on these names (strings present in
# liblitertlm_jni.so), so R8 renaming any of them breaks the JNI bridge.
-keep class com.google.ai.edge.litertlm.LiteRtLmJni { *; }
-keep class com.google.ai.edge.litertlm.LiteRtLmJni$* { *; }
-keep class com.google.ai.edge.litertlm.NativeLibraryLoader { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.google.ai.edge.litertlm.** {
    native <methods>;
}
-keep class com.google.ai.edge.litertlm.BenchmarkInfo { *; }
-keep class com.google.ai.edge.litertlm.InputData { *; }
-keep class com.google.ai.edge.litertlm.InputData$* { *; }
-keep class com.google.ai.edge.litertlm.LiteRtLmJniException { *; }
-keep class com.google.ai.edge.litertlm.Message { *; }
-keep class com.google.ai.edge.litertlm.Content { *; }
-keep class com.google.ai.edge.litertlm.Content$* { *; }
-keep class com.google.ai.edge.litertlm.Contents { *; }
-keep class com.google.ai.edge.litertlm.SamplerConfig { *; }
-keep class com.google.ai.edge.litertlm.ThinkingConfig { *; }
-keep class com.google.ai.edge.litertlm.Backend { *; }
-keep class com.google.ai.edge.litertlm.Backend$* { *; }
-keep class com.google.ai.edge.litertlm.ResponseFormat { *; }
-keep class com.google.ai.edge.litertlm.ResponseFormat$* { *; }
-keep interface com.google.ai.edge.litertlm.MessageCallback { *; }
-keep interface com.google.ai.edge.litertlm.ResponseCallback { *; }
# strictFullMode: our own implementers must be named explicitly.
-keep class de.luhmer.owncloudnewsreader.ai.** implements com.google.ai.edge.litertlm.MessageCallback { *; }
-dontwarn kotlin.reflect.**
-keep class kotlin.Metadata { *; }

# --- MediaPipe Tasks (TextEmbedder) ------------------------------------------
# AutoValue + protolite + JNI; also ships no consumer rules.
-keep class com.google.mediapipe.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.flogger.**
# --- datatransport: the no-op stubs, not the real library ---------------------
# com.google.android.datatransport is EXCLUDED from tasks-text in build.gradle (PRIVACY.md), but
# MediaPipe reaches TransportRuntime.initialize() unconditionally from
# TextEmbedder.createFromOptions, so src/mlGemma/java/com/google/android/datatransport/ supplies
# that API as no-ops. Read the package-info there before touching any of this.
#
# The stubs are referenced from RemoteLoggingClient, which the -keep above already retains, so R8
# would keep them anyway; naming them explicitly is documentation and insurance against the day
# the MediaPipe keep rule is narrowed. It is nine tiny classes.
-keep class com.google.android.datatransport.** { *; }
# Retained for the members of the real API that our stubs deliberately do NOT provide (Priority,
# ProductData, TransportScheduleCallback, the 3-arg getTransport, ...): nothing on the reachable
# path references them, and a warning about them is not actionable.
-dontwarn com.google.android.datatransport.**

# --- Gson DTOs for the model catalogue ---------------------------------------
-keep class de.luhmer.owncloudnewsreader.ai.model.AiCatalogEntry { *; }
-keep class de.luhmer.owncloudnewsreader.ai.model.AiPartMeta { *; }

# -keepattributes APPENDS across rule files, so this does not disturb line 75.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
