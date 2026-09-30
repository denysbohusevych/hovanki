import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The phone itself (docs/adr/0017-radar-techniques-and-big-run.md, section 1): where it is carried (`CarryMonitor`),
// its motion (`ActivityMonitor`, `ActivityClassifier`), the pulse's vibration (`PocketPulse`), what it is
// (`DeviceInfo`) and the radio lab's probes of it (`lab/`: sensors, battery, the screen, haptics), with the Android and
// iOS implementations. Used by :clientCore (the game and the lab) and, through it, by :composeApp and the e2e bots.
// Knows nothing of the radar (:radar) or the game's session; no string resources: the app passes its texts in.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    // Consumed by :clientCore's JVM target (the e2e bots, the Mac beacon).
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Consumed by :composeApp on Android.
    android {
        namespace = "app.hovanki.device"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Consumed by :composeApp on iOS (device + Apple Silicon simulator).
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Protocol types (Carry, Activity, Platform, RadarBand), the pulse's rules (HeartbeatRules) and Flow are
            // part of this module's API.
            api(projects.shared)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Keep Java bytecode level in line with jvmTarget above (Kotlin validates that they match).
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
}
