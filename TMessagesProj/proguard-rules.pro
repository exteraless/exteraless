-keep public class com.google.android.gms.* { public *; }
-keepnames @com.google.android.gms.common.annotation.KeepName class *
-keepclassmembernames class * {
    @com.google.android.gms.common.annotation.KeepName *;
}
-keep class org.webrtc.* { *; }
-keep class org.webrtc.audio.* { *; }
-keep class org.webrtc.voiceengine.* { *; }
-keep class org.telegram.messenger.* { *; }
# org.telegram.messenger: only the names that native code and reflection resolve at runtime.
# Before changing these rules see TMessagesProj/proguard-checklist.md and run Tools/r8_keep_check.py.
# Static JNI exports Java_org_telegram_messenger_<Class>_<method> (Utilities, Intro, MediaController, MrzRecognizer).
-keepclasseswithmembers,includedescriptorclasses class org.telegram.messenger.* {
    native <methods>;
}
# gifvideo.cpp: FindClass + GetMethodID, called from the native reader.
-keep class org.telegram.messenger.AnimatedFileDrawableStream {
    int read(int, int);
    void cancel();
    boolean isFinishedLoadingFile();
    boolean isCanceled();
    java.lang.String getFinishedFilePath();
}
# NotificationBadge.BADGERS holds class literals, instances are made with Class.newInstance().
-keep class org.telegram.messenger.NotificationBadge$* implements org.telegram.messenger.NotificationBadge$Badger {
    <init>();
}
-keep class org.telegram.messenger.camera.* { *; }
-keep class org.telegram.messenger.secretmedia.* { *; }
-keep class org.telegram.messenger.support.* { *; }
-keep class org.telegram.messenger.support.* { *; }
-keep class org.telegram.messenger.time.* { *; }
-keep class org.telegram.messenger.video.* { *; }
-keep class org.telegram.messenger.voip.* { *; }
-keep class org.telegram.SQLite.** { *; }
-keep class org.telegram.tgnet.ConnectionsManager { *; }
-keep class org.telegram.tgnet.NativeByteBuffer { *; }
-keep class org.telegram.tgnet.RequestTimeDelegate { *; }
-keep class org.telegram.tgnet.RequestDelegate { *; }
-keep class org.telegram.ui.ActionBar.* { *; }
-keep class org.telegram.ui.Stories.recorder.FfmpegAudioWaveformLoader { *; }
-keep class org.telegram.ui.Components.BlurredFrameLayout { *; }
-keep class org.telegram.utils.glass.source.** { *; }
-keep class androidx.mediarouter.app.MediaRouteButton { *; }
-keep class org.telegram.messenger.AnimatedFileDrawableStream { <methods>; }
-keep class org.telegram.SQLite.SQLiteException { <methods>; }
-keep class org.telegram.tgnet.ConnectionsManager { <methods>; }
-keep class org.telegram.tgnet.NativeByteBuffer { <methods>; }
-keep class * extends org.telegram.ui.Components.UItem$UItemFactory { public <init>(...); }
-keep class org.telegram.tgnet.** { *; }
-keepnames class org.telegram.tgnet.** extends org.telegram.tgnet.TLObject
-keep class org.telegram.tgnet.tl.** { *; }
-keepclassmembernames,allowshrinking class org.telegram.ui.* { <fields>; }
-keepclassmembernames,allowshrinking class org.telegram.ui.Cells.* { <fields>; }
-keepclassmembernames,allowshrinking class org.telegram.ui.Components.* { <fields>; }

-keepclassmembers class ** {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class org.telegram.utils.code.highlight.PrismaHighlighter {
    private static native <methods>;
}

# https://developers.google.com/ml-kit/known-issues#android_issues
-keep class com.google.mlkit.nl.languageid.internal.ThickLanguageIdentifier { *; }

# WidgetSandbox: JNI_OnLoad uses RegisterNatives; backend methods are called by native workers.
-keep class org.telegram.utils.twidget.WidgetSandboxNative { *; }
# The client's share (filters, gesture arbiter) in the client library: static Java_* exports, resolved by name.
-keepclasseswithmembers,includedescriptorclasses class org.telegram.utils.twidget.WidgetClientNative {
    native <methods>;
}
-keep class org.telegram.utils.twidget.WidgetSandboxBackend { *; }
# The isolated engine process's backend (WidgetEngineService): the same JNI callbacks.
-keep class org.telegram.utils.twidget.WidgetEngineBackend { *; }

-keep class io.nano.tex.** {*;}

# JLatexMath: macro/atom classes are loaded reflectively by Class.forName
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**

# Used by AtomicReferenceFieldUpdater and sun.misc.Unsafe
-keepclassmembers class com.google.common.util.concurrent.AbstractFuture** {
  *** waiters;
  *** value;
  *** listeners;
  *** thread;
  *** next;
}

# Since Unsafe is using the field offsets of these inner classes, we don't want
# to have class merging or similar tricks applied to these classes and their
# fields. It's safe to allow obfuscation, since the by-name references are
# already preserved in the -keep statement above.
-keep,allowshrinking,allowobfuscation class com.google.common.util.concurrent.AbstractFuture** {
  <fields>;
}

-keepclasseswithmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# okhttp crashes on Android 16
-keep class okhttp3.internal.idn.IdnaMappingTable { *; }
-keep class okhttp3.internal.idn.IdnaMappingTableInstanceKt{ *; }

# Keep all classes of Apache Commons
-keep class org.apache.commons.text.** { *; }
-dontwarn org.apache.commons.text.**

# Prevent proguard from stripping interface information from TypeAdapter, TypeAdapterFactory,
# JsonSerializer, JsonDeserializer instances (so they can be used in @JsonAdapter)
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# Prevent R8 from leaving Data object members always null
-keepclasseswithmembers class * {
    <init>(...);
    @com.google.gson.annotations.SerializedName <fields>;
}
# Retain generic signatures of TypeToken and its subclasses with R8 version 3.0 and higher.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

-keepclassmembernames class androidx.core.widget.NestedScrollView {
    private android.widget.OverScroller mScroller;
    private void abortAnimatedScroll();
}

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
    public static void throw*(...);
}

-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}

-keepclassmembers enum * {
     public static **[] values();
     public static ** valueOf(java.lang.String);
}

-keepnames class androidx.recyclerview.widget.RecyclerView
-keepclassmembers class androidx.recyclerview.widget.RecyclerView {
    public void suppressLayout(boolean);
    public boolean isLayoutSuppressed();
}

-keepclassmembers class org.telegram.ui.** {
    public <init>(...);
}

-keep class org.slf4j.** { *; }
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
-keepclassmembernames,allowshrinking class tw.nekomimi.nekogram.ui.cells.AccountCell { <fields>; }
-keepclassmembernames,allowshrinking class tw.nekomimi.nekogram.ui.cells.EmojiSetCell { <fields>; }
-keepclassmembernames,allowshrinking class tw.nekomimi.nekogram.settings.NekoChatSettingsActivity$StickerSizeCell { <fields>; }

# --- Keep Gson-reflected model fields used by Regex Filters import/export ---
# Preserve fields annotated with @Expose so Gson can access them in release builds
-keepclassmembers class * {
    @com.google.gson.annotations.Expose <fields>;
}

# TransferData is an inner static class used only via Gson reflection; keep its fields
-keep class tw.nekomimi.nekogram.filters.RegexFiltersSettingActivity$TransferData { *; }

# Be explicit for AyuFilter models to be safe
-keepclassmembers class tw.nekomimi.nekogram.filters.AyuFilter$FilterModel { <fields>; }
-keepclassmembers class tw.nekomimi.nekogram.filters.AyuFilter$ChatFilterEntry { <fields>; }
-keepclassmembers class tw.nekomimi.nekogram.filters.AyuFilter$CustomFilteredUser { <fields>; }

# https://github.com/osmdroid/osmdroid/issues/633
-dontwarn org.osmdroid.tileprovider.modules.NetworkAvailabliltyCheck
# Osmdroid
-dontwarn org.osmdroid.**

# dnsjava
-dontwarn java.net.spi.InetAddressResolverProvider
-dontwarn org.xbill.DNS.spi.DnsjavaInetAddressResolverProvider

-dontwarn java.beans.**
-dontwarn lombok.**
-dontwarn org.slf4j.**
-dontwarn sun.net.spi.nameservice.**
-dontwarn com.google.j2objc.annotations.ReflectionSupport
-dontwarn com.google.j2objc.annotations.RetainedWith
-dontwarn com.google.j2objc.annotations.Weak
-dontwarn android.support.annotation.IntRange
-dontwarn android.support.annotation.NonNull
-dontwarn android.support.annotation.Nullable
-dontwarn android.support.annotation.RequiresApi
-dontwarn android.support.annotation.Size
-dontwarn android.support.annotation.VisibleForTesting
-dontwarn android.support.v4.app.NotificationCompat$Builder

-allowaccessmodification
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-dontoptimize
-dontobfuscate

# --- Движок плагинов -------------------------------------------------------
# Всё ниже достижимо только из Python через рефлексию Chaquopy, для статического
# анализатора это мёртвый код. minifyEnabled в релизе вырезал бы его целиком.
-keep class app.exteraless.plugins.** { *; }
-keep class app.exteraless.settings.utils.** { *; }
-keep class app.exteraless.utils.text.** { *; }
-keep class app.exteraless.pillstack.** { *; }
-keep class app.exteraless.player.Md3Player { *; }
-keep class app.exteraless.player.PlayerSheet { *; }
-keep class app.exteraless.appearance.AltSeekbar { *; }
-keep interface app.exteraless.appearance.AltSeekbar$OnDrag { *; }
-keep class com.exteragram.messenger.** { *; }
-keep class de.robv.android.xposed.** { *; }
-keep class kotlin.** { *; }
-keep class com.google.android.material.** { *; }
-keep class com.google.zxing.** { *; }
-keep class org.mvel2.** { *; }
-keep class com.android.dx.** { *; }
-keep class com.google.android.collect.** { *; }
-dontwarn com.android.dx.**
-dontwarn org.mvel2.**

# Классы Telegram, к которым плагины обращаются по имени: обфускация выключена
# (-dontobfuscate), но шринкер всё равно убирает недостижимые члены.
-keepclassmembers class org.telegram.tgnet.** { *; }
-keepclassmembers class org.telegram.messenger.MessageObject { *; }
-keepclassmembers class org.telegram.messenger.SendMessagesHelper { *; }
-keepclassmembers class org.telegram.messenger.BuildVars { *; }
-keepclassmembers class androidx.media3.exoplayer.ExoPlayerImpl {
    public void setAuxEffectInfo(androidx.media3.common.AuxEffectInfo);
    public void clearAuxEffectInfo();
}

-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * extends com.sun.jna.Structure { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

-keep class org.telegram.ui.Wallet.WalletPluginFreeze {
    static boolean frozen;
    static int bridgeState();
}
