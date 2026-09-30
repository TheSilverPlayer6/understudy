# EXTRA R8 keeps for the CI release-E2E build ONLY, selected with
# -Punderstudy.keepTestRuntime (see app/build.gradle.kts; the `premise-release` workflow job
# passes it). An operator's release build must NOT carry these: it ships fully shaken.
#
# Why this file has to exist — two measured device crashes, same mechanism:
#
#  1. run #49: NoClassDefFoundError androidx.tracing.Trace at AndroidJUnitRunner.onCreate
#     (kept unconditionally in proguard-rules.pro — three classes, any instrumented release
#     build needs it just to start the runner).
#  2. run #53: NoSuchMethodError "<init>(I)V in class Lw90" — w90 is this build's renamed
#     kotlin.jvm.internal.Lambda. The instrumented process runs on a COMBINED classloader
#     (test APK over app APK), the test APK's own R8 pass rewrites its references through the
#     app's mapping, and AGP compiles androidTest with the app's runtime classpath as
#     *provided* — so the test APK carries no stdlib/coroutines of its own. Renames are
#     therefore consistent, but SHRINKING is not: any kotlin/coroutines member the tests use
#     and the app does not was removed from the app, and the test's rewritten reference dies
#     at runtime. Whack-a-mole per member is unwinnable (test lambdas alone touch Lambda's
#     arity constructors, SuspendLambda, Function refs, and the death test drives coroutines
#     via runBlocking), so for the test build the two libraries are kept whole.
#
# What this does NOT weaken: the app's OWN code is still minified and obfuscated exactly as
# an operator build would be — every dev.understudy class the tests do not link against is
# still shaken and renamed, the resource shrinking still runs, and the signing/permission/
# digest paths under test are untouched. R8 computes app-code reachability from app code;
# keeping stdlib whole cannot change which app members survive. The fidelity gap is precisely
# "stdlib members only tests call", which no user build can reach either.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**
