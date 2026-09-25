import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Code shared by the mobile client and the server. Pure Kotlin, no platform APIs:
// wire protocol (kotlinx.serialization), catch codes (TOTP), geo math and game rules.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // Consumed by :server.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Consumed by :composeApp on Android.
    android {
        namespace = "app.hovanki.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        // Runs commonTest against the Android variant too.
        withHostTest {}
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Consumed by :composeApp on iOS (device + Apple Silicon simulator).
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// Keep Java bytecode level in line with jvmTarget above (Kotlin validates that they match).
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
}
