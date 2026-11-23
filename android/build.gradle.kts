// AGP 9 compiles Kotlin itself; the Kotlin Gradle Plugin is kept on the
// build classpath at 2.4.21 (above AGP's built-in floor) so the compose
// compiler and serialization plugins share one Kotlin toolchain.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}
