import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The radar between the phones (docs/adr/0017-radar-techniques-and-big-run.md, sections 1 and 2.2): the game's
// Bluetooth LE (`ProximityRadio` on a platform's `AirHost` with the channels of `RadarCatalog`), the precision
// radar's seam (`PrecisionRadio`) and the radio lab's own air (`lab/`). The channels (`channel/<x>/`) are common
// Kotlin; the hosts are per platform: Android, iOS, and on the JVM the simulator of the air the e2e bots use
// (`host/SimulatedAir`, the OS's rules in `OsRules`). Used by :clientCore (the game and the lab) and, through it, by
// :composeApp and the e2e bots. Knows nothing of the phone's sensors (:device) or the game's session: whoever uses
// both joins them. ModuleBoundariesTest checks the packages' borders: a channel imports only the root package.
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
        namespace = "app.hovanki.radar"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Consumed by :composeApp on iOS (device + Apple Silicon simulator).
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Protocol types (BluetoothState, UwbPeer), the radar's rules (RadarToken, OverflowArea) and Flow are
            // part of this module's API.
            api(projects.shared)
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            // ContextCompat.checkSelfPermission in host.AndroidAirHost.
            implementation(libs.androidx.core.ktx)
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
