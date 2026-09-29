// The proxy module builds the *template* APK that the main app later re-targets to an
// arbitrary package name at runtime.
//
// Hard constraints, because this APK gets deconstructed and re-signed on-device:
//   * Zero dependencies. Everything must live in classes.dex with no runtime classpath
//     beyond the Android framework — the re-signed APK is installed standalone.
//   * No Compose, no androidx, no resources. Keeps the dex a few tens of KB and the
//     provider cold-start fast.
//   * applicationId is a placeholder; the real identity is substituted by the packager.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.understudy.proxytpl"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.understudy.proxytpl"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    // The provider authority is derived from applicationId at runtime, so a single
    // string-pool substitution in the binary manifest retargets both together.
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // Deliberately NOT minified: the packager extracts this dex verbatim and we
            // need predictable, stable class/method names for our own reflection-free code.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/**", "DebugProbesKt.bin", "**.kotlin_module")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions")
    }
}

dependencies {
    // `implementation`, not `compileOnly`: these classes must end up inside the template APK's
    // classes.dex, which is what gets extracted and re-signed at runtime.
    implementation(project(":proxy-core"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
