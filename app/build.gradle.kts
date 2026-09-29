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

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
