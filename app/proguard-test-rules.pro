# R8 rules for the androidTest variant — only consulted when the instrument is minified, which
# happens exactly when it targets the minified release app (-Punderstudy.testBuildType=release,
# i.e. the release-E2E CI job). The debug instrument is never minified and never reads this file.
#
# Two jobs:
#
# 1. Keep the test classes' NAMES. The CI script addresses tests by name
#    (`am instrument -e class dev.understudy.instrumented.BridgePremiseTest`), and
#    AndroidJUnitRunner resolves that filter against the dex's real class names — a renamed
#    test class is a silently empty test run, which `run-premise-test.sh` would then report as
#    "no failures". Keeping the package by name makes that impossible.
#
# 2. Silence the two compile-only errorprone annotations androidx.test references but does not
#    bundle (verbatim from R8's own missing_rules.txt for this variant):
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed

-keep class dev.understudy.instrumented.** { *; }
