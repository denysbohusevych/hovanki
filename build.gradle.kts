plugins {
    // Declared once here so every module shares the same plugin classloader.
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.kotlinSpring) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.springBoot) apply false
    alias(libs.plugins.spotless)
}

// `./gradlew spotlessApply` formats everything, `spotlessCheck` runs in CI.
// ktlint settings live in .editorconfig.
spotless {
    kotlin {
        target("*/src/**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}
