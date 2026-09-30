rootProject.name = "hovanki"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    // Lets Gradle download the JDK requested by `jvmToolchain(...)` if it is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Code shared by the mobile client and the server: protocol, TOTP, geo & game rules.
include(":shared")
// Spring Boot game server.
include(":server")
// The radar between the phones: Bluetooth LE, the precision radar's seam, the radio lab's air (Android, iOS, JVM).
include(":radar")
// The phone itself: carry, motion, the pulse's vibration, DeviceInfo, the radio lab's probes (Android, iOS, JVM).
include(":device")
// Client logic without UI (API, connection, session), shared by the app and the e2e bots.
include(":clientCore")
// Mobile client (Compose Multiplatform UI + platform services) for Android and iOS.
include(":composeApp")
// Android entry point (Activity, Application). The iOS entry point is the Xcode project in /iosApp.
include(":androidApp")
// End-to-end tests: headless bots on the real client code play whole games against the real server.
include(":e2e")
