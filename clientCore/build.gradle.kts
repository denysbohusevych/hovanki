import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Client logic without UI: server API, connection (HTTP polling), location outbox, server clock and the game session.
// Used by :composeApp (the app) and by the headless e2e bots in :e2e, so the bots run exactly the app's network code.
// No Compose and no platform APIs: platform services are interfaces here, implemented in :composeApp.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    // Consumed by :e2e (headless bots).
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Consumed by :composeApp on Android.
    android {
        namespace = "app.hovanki.client.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Consumed by :composeApp on iOS (device + Apple Silicon simulator).
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Protocol types, Flow and Ktor types are part of this module's API.
            api(projects.shared)
            api(libs.kotlinx.coroutines.core)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.turbine)
        }
    }
}

// Keep Java bytecode level in line with jvmTarget above (Kotlin validates that they match).
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
}
