# R8 rules for the proxy TEMPLATE module.
#
# The template's classes.dex is extracted by ProxyApkFactory and re-signed verbatim into the
# generated proxy, so the rules here have an unusual job: shrink the APK (the Kotlin stdlib is
# ~95% of the dex and the proxy uses a sliver of it) WITHOUT renaming or restructuring anything
# of ours. Predictable names are load-bearing for this module — the manifest's component names
# must resolve inside the dex on a device, and a support logcat naming ProxyFileBridge has to
# mean ProxyFileBridge.
#
# So: keep every class this module and :proxy-core define, exactly as named, and let R8 do the
# one thing that matters here — tree-shake the unused stdlib. Obfuscation of stdlib internals
# is fine (nobody debugs into kotlin.* on a proxy stack trace we own), but nothing under our
# namespaces moves.
-keep class dev.understudy.proxytpl.** { *; }

# The provider's Binder surface is reached through framework reflection on ContentProvider
# overrides; R8 models that correctly via the aapt-generated rules for manifest components,
# but the belt costs 40 bytes and the braces cost a field investigation on someone's phone.
-keepclassmembers class * extends android.content.ContentProvider {
    public *;
}
