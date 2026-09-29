// The proxy's actual code, as an Android *library* so that :app's tests can compile against it.
//
// This module exists for one reason: the bridge protocol has two ends, and without the proxy's
// classes on a test classpath the only way to exercise them together is on a device. Putting
// them in a library lets a Robolectric test register the REAL ProxyFileBridge and drive it with
// the REAL BridgeClient — the whole protocol, cursor shapes, ParcelFileDescriptor streaming and
// path-safety rules, verified on the JVM against actual Android framework classes.
//
// Hard constraints, unchanged from when this lived in :proxy — the code here ends up inside the
// generated APK's classes.dex, which is installed standalone:
//   * ZERO dependencies. Framework APIs only: no androidx, no Kotlin stdlib extras beyond what
//     the compiler adds, no Compose, no resources.
//   * The CLASS package MUST stay `dev.understudy.proxytpl`. ManifestPatcher re-targets the
//     binary manifest by prefix-substituting that string, which is what makes the
//     fully-qualified component names follow the new package. Renaming the classes silently
//     breaks proxy installation with a ClassNotFoundException at first contact.
//   * The module NAMESPACE, by contrast, must be unique across modules (AGP refuses to merge
//     two modules sharing one), so it is `…proxycore`. Namespace only governs BuildConfig and
//     the R class — neither of which this module has — so it does not affect the class names
//     baked into the dex.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.understudy.proxycore"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        // No consumerProguardFiles: this code is re-signed into a standalone APK that is never
        // minified, so R8 rules here would be dead weight.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.robolectric)
}
