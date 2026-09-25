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
// Client logic without UI (API, connection, session), shared by the app and the e2e bots.
include(":clientCore")
// Mobile client (Compose Multiplatform UI + platform services) for Android and iOS.
include(":composeApp")
// Android entry point (Activity, Application). The iOS entry point is the Xcode project in /iosApp.
include(":androidApp")
