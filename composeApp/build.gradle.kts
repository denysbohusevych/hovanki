import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Mobile client: Compose Multiplatform UI and platform services for Android and iOS; client logic is in :clientCore.
// Android: an Android library packaged by :androidApp. iOS: the `ComposeApp` framework embedded by /iosApp.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Build-time settings baked into the app, same on Android and iOS (docs/ci-cd.md, «Как поставить сборку на телефон»).
val generateBuildConstants by tasks.registering(GenerateBuildConstants::class) {
    // Default server of non-debug builds (preview, TestFlight, release); empty: the player types the address.
    serverUrl.set(providers.gradleProperty("hovanki.serverUrl").orElse(""))
    // Shown on the start screen next to the version. Asked from git when the task runs, not while configuring.
    commit.set(
        providers.gradleProperty("hovanki.commit").orElse(
            providers.exec {
                commandLine("git", "describe", "--always", "--dirty", "--abbrev=7", "--exclude=*")
                isIgnoreExitValue = true
            }.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } },
        ),
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/buildConstants/kotlin"))
}

kotlin {
    android {
        namespace = "app.hovanki.client"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        // Compose resources (strings) and the tracking notification texts are Android resources.
        androidResources { enable = true }
        // Runs commonTest on the JVM.
        withHostTest {}
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        // BuildConstants, see generateBuildConstants above.
        commonMain { kotlin.srcDir(generateBuildConstants) }
        commonMain.dependencies {
            implementation(projects.shared)
            // Part of this module's API (LaunchOptions, SessionState): :androidApp needs it too.
            api(projects.clientCore)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.play.services.location)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.koin.android)
            implementation(libs.kotlinx.coroutines.android)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

compose.resources {
    packageOfResClass = "app.hovanki.client.resources"
}

// Keep Java bytecode level in line with jvmTarget above (Kotlin validates that they match).
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
}

/** Writes `BuildConstants` (internal, package `app.hovanki.client`) into a generated commonMain source directory. */
abstract class GenerateBuildConstants : DefaultTask() {
    @get:Input
    abstract val serverUrl: Property<String>

    @get:Input
    abstract val commit: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val url = serverUrl.get().trim().trimEnd('/')
        // Non-debug builds talk HTTPS only: no cleartext on Android outside debug, App Transport Security on iOS.
        if (url.isNotEmpty() && !url.startsWith("https://")) {
            throw GradleException("hovanki.serverUrl must be an https:// address, got '$url'")
        }
        val file = outputDirectory.file("app/hovanki/client/BuildConstants.kt").get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package app.hovanki.client
            |
            |// Generated by :composeApp:generateBuildConstants (composeApp/build.gradle.kts). Do not edit.
            |internal object BuildConstants {
            |    /** Gradle property `hovanki.serverUrl`: default server of non-debug builds, empty when not set. */
            |    const val SERVER_URL: String = "${url.escaped()}"
            |
            |    /** Commit the app was built from; `-dirty` when it had uncommitted changes. */
            |    const val COMMIT: String = "${commit.get().escaped()}"
            |}
            |
            """.trimMargin(),
        )
    }

    private fun String.escaped(): String = replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
}
