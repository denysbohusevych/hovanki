import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Android application: only the entry points (Application, Activity). UI and logic live in :composeApp.
// AGP 9 compiles Kotlin itself (built-in Kotlin), so no Kotlin Android plugin here.
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// Release signing comes from CI secrets; without them the release build stays unsigned.
val releaseKeystorePath: String? = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseKeystorePassword: String? = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias: String? = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword: String? = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val hasReleaseSigning =
    listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword).all {
        !it.isNullOrBlank()
    }

android {
    namespace = "app.hovanki.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.hovanki"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // CI passes -Phovanki.versionCode=<run number> and, for a release, -Phovanki.versionName=<tag>.
        versionCode = providers.gradleProperty("hovanki.versionCode").map { it.toInt() }.getOrElse(1)
        versionName = providers.gradleProperty("hovanki.versionName").getOrElse("0.1.0")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        // Tester builds from main (preview.yml, docs/ci-cd.md): the release build (R8, same key, HTTPS only, no UI
        // automation hooks) as a separate app. It installs next to a store release, which Google Play re-signs
        // with its own key, and its version codes (preview run numbers) never clash with the release ones.
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            matchingFallbacks += "release"
        }
    }

    sourceSets {
        // No UI automation hooks, like release (src/release has the no-op twins of src/debug).
        named("preview") { kotlin.directories += "src/release/kotlin" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    // AutomationRoot (debug): Box + semantics for testTagsAsResourceId.
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.koin.android)
}
