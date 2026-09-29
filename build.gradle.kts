// Root build file.
//
// AGP 9 enables *built-in Kotlin*, so `org.jetbrains.kotlin.android` must NOT be applied
// anywhere in this project — AGP 9 fails the build if it is. The Compose compiler plugin is
// still a separate Kotlin compiler plugin and IS required in modules that use Compose.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
}
