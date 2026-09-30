# Understudy — R8 / ProGuard rules for the release build type.
#
# The proxy module is deliberately NOT minified (see proxy/build.gradle.kts): its classes.dex
# is extracted and re-signed verbatim, so its symbol names must stay predictable. These rules
# apply to :app only.

# ---- the proxy template asset -----------------------------------------------------------
# Nothing to keep: proxy-template.apk is an asset, not compiled code, so R8 never sees it.
# Do NOT try to "optimise" it away with resource shrinking — see the res/raw rule below.

# ---- Compose ----------------------------------------------------------------------------
# The Compose compiler plugin emits its own consumer rules; these cover the reflective edges
# that R8 otherwise strips (tooling previews and the runtime's save/restore of state).
-keepclassmembers class * extends androidx.compose.ui.platform.ViewBoundFinder { *; }
-dontwarn androidx.compose.**

# ---- Parcelable / serializable state ----------------------------------------------------
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}

# ---- Kotlin metadata --------------------------------------------------------------------
# Keep annotations so reflection-based tooling (and crash reporters) can still read class
# metadata; cheap, and losing it makes release-only crashes much harder to triage.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ---- coroutines -------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ---- our own reflective / cross-process edges --------------------------------------------
# BridgeContract is an `object` whose fields are read from :app only, but it mirrors the
# contract implemented by the proxy's ContentProvider across a process boundary. Keeping it
# unobfuscated means a log line or a bug report naming a method stays meaningful.
-keep class dev.understudy.proxy.** { *; }
-keepclassmembers class dev.understudy.** {
    public static final java.lang.String *;
}

# ---- instrumented tests link against the minified release app ----------------------------
# The release-E2E CI job (`premise-release` in emulator.yml) runs BridgePremiseTest and
# CallerAuthPremiseTest against the R8-minified release APK. The test APK compiles against
# unobfuscated symbols and is itself NOT minified, so every app class it references must keep
# its name or the suite dies with NoClassDefFoundError at runtime — a failure that would look
# like a product regression. These two packages are exactly the surface the tests touch
# (BridgeClient/BridgeError/StorageRoot), and both are cross-process contract types where a
# stable name keeps stack traces in bug reports readable anyway.
-keep class dev.understudy.bridge.** { *; }
-keep class dev.understudy.core.model.** { *; }

# ---- native (none today; guard against future additions) ---------------------------------
-keepclasseswithmembernames class * { native <methods>; }

# ---- resource shrinking ------------------------------------------------------------------
# `assets/proxy-template.apk` is read via AssetManager by name at runtime, which resource
# shrinking cannot see. It lives in assets/ (not res/), so shrinkResources does not touch it,
# but keep this rule if it is ever moved.
-keep class dev.understudy.packaging.ProxyApkFactory { *; }

# ---- diagnostics -------------------------------------------------------------------------
# Line numbers + source file survive so release stack traces are retraceable against the
# mapping.txt that AGP emits.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
