import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.understudy"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.understudy"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        // Operator-supplied release identity. Deliberately NOT committed and NOT defaulted:
        // a release key in the repo is a release key in every clone.
        //
        //   keystore.properties (gitignored, at the repo root):
        //     storeFile=/absolute/path/to/release.p12
        //     storePassword=...
        //     keyAlias=...
        //     keyPassword=...
        //
        // Generate one with:
        //   keytool -genkeypair -keystore release.p12 -storetype PKCS12 -alias understudy \
        //     -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=Understudy, O=Understudy"
        //
        // Every property defaults to the empty string rather than using require(): Gradle calls
        // `property()` on a Project inside the android {} block, where `project` shadows the
        // kotlin.io accessor of the same name, so an unqualified property(...) does not resolve.
        // NOTE: `Properties`, not `java.util.Properties` — inside a Gradle Kotlin script `java`
        // resolves to the JavaPluginExtension, not the package, so the qualified name fails.
        val props = Properties()
        val keystoreProps = rootProject.file("keystore.properties")
        if (keystoreProps.isFile) {
            keystoreProps.inputStream().use { stream -> props.load(stream) }
        }
        val releaseStoreFile = props.getProperty("storeFile", "").takeIf { it.isNotBlank() }
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = props.getProperty("storePassword", "")
                keyAlias = props.getProperty("keyAlias", "")
                keyPassword = props.getProperty("keyPassword", "")
                storeType = props.getProperty("storeType", "PKCS12")
            }
        }

        // Test-only identity, shared with ProxyApkFactoryTest via the `understudy.testKeystore`
        // system property. The proxy's provider is guarded by a `signature`-level permission, so
        // the app and the generated proxy MUST share a signer or every bridge call fails with
        // SecurityException on a real device. Falls back to the standard debug key when the
        // keystore is absent, so a checkout without it still builds.
        val testKeystore = rootProject.file("keystore/understudy-test.p12")
        if (testKeystore.isFile) {
            create("understudyTest") {
                storeFile = testKeystore
                storePassword = "understudy"
                keyAlias = "understudy"
                keyPassword = "understudy"
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            signingConfigs.findByName("understudyTest")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed only when the operator supplies their own identity via keystore.properties
            // (see signingConfigs). Without it the output stays -unsigned rather than falling
            // back to the committed test key: that key is public, and shipping a release build
            // signed with a key from the repo would let anyone publish updates to it.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            // Lets ProxyApkFactoryTest sign the generated proxy with the same identity this
            // module's debug build uses. Without it the two APKs differ in signer and the
            // signature-level BRIDGE permission cannot be granted.
            all {
                it.systemProperty(
                    "understudy.testKeystore",
                    rootProject.file("keystore/understudy-test.p12").absolutePath,
                )
                it.systemProperty("understudy.testKeystorePassword", "understudy")
                it.systemProperty("understudy.testKeystoreAlias", "understudy")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(false)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

/**
 * The proxy APK is generated *by* this app at runtime, but built *from* the `:proxy` module
 * so that its Kotlin is compiled, type-checked and unit-tested by the normal toolchain
 * instead of being hand-assembled.
 *
 * The built APK is committed at `src/main/assets/proxy-template.apk` rather than generated
 * into `build/`. That is a deliberate trade:
 *
 *  - a generated assets dir has to be wired into *every* task that consumes assets —
 *    `merge*Assets`, `generate*Assets`, and the `lintVital*` family — and missing one fails
 *    the release build with an implicit-dependency validation error;
 *  - a committed asset needs no wiring at all, makes release builds reproducible, and lets
 *    the repo build without first assembling `:proxy`.
 *
 * The cost is that the asset can go stale, so [syncProxyTemplate] below both refreshes it and
 * asserts the refresh matched. Run it after any change to `:proxy`:
 *
 *     ./gradlew :app:syncProxyTemplate
 *
 * The release variant is used because a `debuggable` proxy would install with the debuggable
 * flag set — a needless security smell and a source of install-time prompts on some OEM builds.
 */
val proxyTemplateAsset = layout.projectDirectory.file("src/main/assets/proxy-template.apk")

val syncProxyTemplate = tasks.register<Copy>("syncProxyTemplate") {
    group = "build"
    description = "Builds :proxy and refreshes src/main/assets/proxy-template.apk"
    dependsOn(":proxy:assembleRelease")
    // Resolved lazily so the configuration cache need not serialise a task graph.
    from(provider { project(":proxy").layout.buildDirectory.dir("outputs/apk/release") })
    include("*.apk")
    rename { "proxy-template.apk" }
    into(proxyTemplateAsset.asFile.parentFile)

    // No doLast here on purpose: a closure capturing script-scope objects (logger, imported
    // classes) cannot be serialised by the configuration cache, and the failure message
    // ("cannot serialize Gradle script object references") does not point at the cause.
    // Verification of the refreshed asset belongs in a test, not in the copy task.
}

/**
 * Refreshes the binary-manifest fixture `ManifestPatcherTest` parses.
 *
 * The fixture is `proxy-debug.apk!/AndroidManifest.xml`, committed at
 * `src/test/resources/dev/understudy/packaging/axml/template-manifest.axml`. Testing against real
 * aapt2 output rather than a synthetic AXML is the whole point of that suite — it pins the
 * string-pool layout, flags and alignment the toolchain actually emits — but it only works while
 * the fixture tracks the sources it came from.
 *
 * Nothing else would notice if it drifted: `checkProxyTemplateFresh` guards the *release* asset
 * the app ships, not this *debug* test resource, and a stale fixture keeps passing while asserting
 * the shape of a manifest that no longer exists. That is the failure mode this project has already
 * been burned by once — a green JVM suite asserting behaviour a device contradicted.
 *
 *     ./gradlew :app:syncManifestFixture
 *
 * Standalone for the same reason as [syncProxyTemplate]: it writes into a source directory
 * (`src/test/resources`), and a task that does so cannot share a task graph with the `lintVital*`
 * family without tripping Gradle 9's implicit-dependency validation.
 */
val manifestFixture = layout.projectDirectory.file(
    "src/test/resources/dev/understudy/packaging/axml/template-manifest.axml"
)

tasks.register("syncManifestFixture") {
    group = "build"
    description = "Extracts proxy-debug.apk's binary manifest into the ManifestPatcherTest fixture"
    dependsOn(":proxy:assembleDebug")

    // Resolved at configuration time. Referring to `project` from inside doLast fails under the
    // configuration cache with "Invocation of 'Task.project' by task ... at execution time is
    // unsupported"; plain File values are what the action gets.
    val source: File = layout.projectDirectory
        .dir("../proxy/build/outputs/apk/debug")
        .file("proxy-debug.apk")
        .asFile
    val destination: File = manifestFixture.asFile

    inputs.file(source)
    outputs.file(destination)

    doLast {
        if (!source.isFile) {
            throw GradleException("Expected the :proxy debug APK at ${source.path} but it is not there.")
        }
        val bytes = ZipFile(source).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
                ?: throw GradleException("${source.path} has no AndroidManifest.xml")
            zip.getInputStream(entry).readBytes()
        }
        destination.parentFile.mkdirs()
        destination.writeBytes(bytes)
    }
}

/**
 * Fails the build if the committed template has drifted from what `:proxy` builds today.
 *
 * [syncProxyTemplate] is manual, so nothing else stops `:app` from shipping a template that is
 * older than the code it came from — and a stale template is silent: the app still builds, still
 * generates an APK, and still installs it. The bug only appears at runtime, as a proxy missing
 * whatever fix went into `:proxy-core`.
 *
 * The comparison is over zip entry *contents*, not over the file hash. Two builds of unchanged
 * sources do not produce identical bytes: `apksigner` embeds a PKCS#7 signingTime, so the
 * `META-INF` signature entries differ on every run (measured: 338 differing bytes across two
 * consecutive builds of the same sources, all of them in the signature). A hash check would
 * therefore fail on a clean checkout and train everyone to ignore it. Every *payload* entry —
 * `AndroidManifest.xml`, `classes*.dex`, `resources.arsc`, the Kotlin builtins — is
 * byte-identical across builds, which is what actually matters: those are the bytes
 * `ProxyApkFactory` repacks into the generated APK.
 *
 * Wired into `check` rather than `assemble*`. It writes nothing, but it does depend on
 * `:proxy:assembleRelease`, and a task that writes into `src/main/assets` cannot share a task
 * graph with `assembleRelease` at all — the `lintVital*` family reads that source directory and
 * Gradle 9 fails on the implicit dependency. Keeping this read-only is what lets it hook
 * `check` safely.
 */
val checkProxyTemplateFresh = tasks.register("checkProxyTemplateFresh") {
    group = "verification"
    description = "Fails if app/src/main/assets/proxy-template.apk is older than :proxy's sources"
    dependsOn(":proxy:assembleRelease")

    // Everything the action touches is resolved HERE, at configuration time. Referring to
    // `project` (or `project.layout`) from inside doLast fails under the configuration cache
    // with "Invocation of 'Task.project' by task ... at execution time is unsupported", and a
    // doLast closure that captures script-scope objects fails to serialise at all. Plain
    // java.io.File and String values are what the action gets.
    val assetFile: File = proxyTemplateAsset.asFile
    val builtFile: File = layout.projectDirectory
        .dir("../proxy/build/outputs/apk/release")
        .file("proxy-release.apk")
        .asFile

    inputs.file(assetFile)
    inputs.file(builtFile)
    // Always run: the whole point is to notice that :proxy moved under the committed asset.
    outputs.upToDateWhen { false }

    doLast {
        if (!assetFile.isFile) {
            throw GradleException(
                "Missing ${assetFile.path}. Run: ./gradlew :app:syncProxyTemplate"
            )
        }
        if (!builtFile.isFile) {
            throw GradleException(
                "Expected the :proxy release APK at ${builtFile.path} but it is not there."
            )
        }

        fun entries(f: File): Map<String, Long> = ZipFile(f).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory }
                .associate { it.name to it.crc }
        }

        // The signature entries are deliberately excluded. They are not reproducible — apksigner
        // embeds a PKCS#7 signingTime, so two builds of identical sources differ (measured: 338
        // bytes, all inside the signature) — and ProxyApkFactory drops META-INF anyway when it
        // repacks, re-signing with the device's own identity. Every payload entry IS stable.
        fun payload(m: Map<String, Long>) = m.filterKeys { !it.startsWith("META-INF/") }

        val pa = payload(entries(assetFile))
        val pb = payload(entries(builtFile))
        val missing = pb.keys - pa.keys
        val extra = pa.keys - pb.keys
        val changed = pa.keys.intersect(pb.keys).filter { pa[it] != pb[it] }

        if (missing.isEmpty() && extra.isEmpty() && changed.isEmpty()) {
            return@doLast
        }
        val detail = buildString {
            if (changed.isNotEmpty()) append("\n  changed: " + changed.sorted().joinToString())
            if (missing.isNotEmpty()) {
                append("\n  missing from the committed asset: " + missing.sorted().joinToString())
            }
            if (extra.isNotEmpty()) {
                append("\n  only in the committed asset: " + extra.sorted().joinToString())
            }
        }
        throw GradleException(
            "app/src/main/assets/proxy-template.apk is STALE — it does not match what :proxy " +
                "builds from the current sources.$detail\n\n" +
                "Run:  ./gradlew :app:syncProxyTemplate\n" +
                "then commit the refreshed asset. Shipping a stale template fails at runtime, " +
                "not at build time: the generated proxy would be missing whatever changed in " +
                ":proxy or :proxy-core."
        )
    }
}

tasks.named("check") { dependsOn(checkProxyTemplateFresh) }

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Test-only: lets a Robolectric test register the REAL ProxyFileBridge and drive it with
    // the REAL BridgeClient, so the whole bridge protocol is verified on the JVM. Never
    // packaged into the app.
    testImplementation(project(":proxy-core"))

    // Robolectric needs androidx.test for ApplicationProvider / ApplicationProvider-based setup.
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Instrumented tests: these are what actually verify the FUSE premise on a real device,
    // which no JVM test can do. Run in CI via .github/workflows/emulator.yml.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(project(":proxy-core"))
}
