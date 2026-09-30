# Locked toolchain & architecture decisions

## Build environment (verified working in Daytona sandbox `43dc2686-d791-4325-b084-ae3454057e1c`)

| Component | Version | Notes |
|---|---|---|
| Sandbox | Debian 13, 4 vCPU, 8 GB RAM, **10 GB disk** (hard cap) | ~8.9 GB free after toolchain |
| JDK | OpenJDK 21.0.12.1 | AGP 9 needs ≥17 |
| Gradle | 9.8.0 | AGP 9.4 requires ≥9.6.0 |
| AGP | **9.4.1** (latest stable) | max supported API = **37** |
| Kotlin | **2.4.20** | via AGP 9 *built-in Kotlin* |
| compileSdk | **37** | platform `android-37.2` installed (`ApiLevel=37.2`, `ExtensionLevel=24`) |
| build-tools | 37.0.0 | aapt2 2.20, apksigner, d8, zipalign |
| platform-tools | 37.0.1 | adb 1.0.41 |
| Compose BOM | **2026.09.00** | |

### Sandbox network constraints (important)
* `services.gradle.org` and `plugins.gradle.org` → **blocked** (connection reset).
* `dl.google.com` (google maven) and `repo.maven.apache.org` → **reachable**.
* `github.com` → reachable.
* ⇒ Gradle distribution pulled from `github.com/gradle/gradle-distributions/releases`.
* ⇒ `settings.gradle.kts` must use `google()` + `mavenCentral()` only, **no** `gradlePluginPortal()`.
* ⇒ Maven Central only mirrors AGP ≤ 2.3.0, so AGP **must** come from `google()`.
* ⇒ Gradle wrapper `distributionUrl` must point at the GitHub mirror (committed for reproducibility).

### Library versions (stable, verified present)
`activity-compose 1.13.0` · `core-ktx 1.19.1` · `lifecycle-* 2.11.0` · `navigation-compose 2.10.2` ·
`documentfile 1.1.0` · `datastore-preferences 1.2.1` · `kotlinx-coroutines 1.11.0` ·
tests: `junit 4.13.2`, `kotlin-test 2.4.20`, `robolectric 4.17`, `turbine 1.2.1`

## AGP 9 gotchas that shape the build files
1. **Built-in Kotlin is on by default.** Do **not** apply `org.jetbrains.kotlin.android` — AGP 9 hard-fails with
   *"The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0."*
2. **`android.kotlinOptions {}` is gone** → use top-level `kotlin { compilerOptions { … } }`.
3. **New DSL only.** `applicationVariants` / `variantFilter` / `sdkDirectory` / `bootClasspath` are removed;
   use `androidComponents { onVariants { … } }` and `androidComponents.sdkComponents`.
4. Kotlin source dirs go in `android.sourceSets[].kotlin`, not `kotlin.sourceSets`.
5. The **Compose Compiler plugin is still required**: `org.jetbrains.kotlin.plugin.compose` at the Kotlin version.

## Proxy-APK strategy (decided)
The proxy must carry the **target's package name** — that is the whole mechanism — so the APK cannot be a
static asset. Plan:

1. `:proxy` is a real Gradle module (`com.android.application`), so its Kotlin is compiled, type-checked and
   unit-tested by the normal toolchain. Its `applicationId` is the **template id** `dev.understudy.proxytpl`.
2. Its `FileProviderBridge` derives everything at runtime from `context.packageName` — no baked-in constants.
   Provider authority is `${applicationId}` so a single string substitution retargets it.
3. The built template APK ships as an **asset** of `:app`.
4. At runtime `:app` **patches the binary `AndroidManifest.xml`**: rewrite the string-pool entry
   `dev.understudy.proxytpl` → `<target>`, fixing offsets/sizes. Everything else (resource IDs, element
   tree, `classes.dex`) is untouched, so correctness is inherited from AGP/aapt2 rather than reimplemented.
5. Re-zip, `zipalign`, sign with our own key (v1+v2+v3).

Rejected: hand-writing AXML from scratch (must hard-code framework attr resource IDs — avoidable risk),
and re-signing an extracted third-party APK (we don't hold their private key).

## Signature-conflict handling (corrects the brief)
Package identity/signing is **device-wide**. The user's proposal — *re-sign the real app with an app-managed
key* — works **only** once we hold the package name, because installing our re-signed APK over an
existing developer-signed install is itself `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. And `pm uninstall -k`
*retains* the signature record, so it does not free the name either.

Therefore:
* **No conflict** (target installed nowhere) → pure in-profile flow, zero shell.
* **Conflict** → requires shell. Backend chosen: **self-paired wireless ADB** (LADB-style), which the app
  drives itself. Used for `mv` rename-aside + `pm uninstall` + `pm uninstall -k --user N`.
* Steady state after conversion → proxy and re-signed real app share our key, so they swap freely forever.
