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
    // The server of non-debug builds (preview, TestFlight, release): there is no address field in the app.
    serverUrl.set(providers.gradleProperty("hovanki.serverUrl").orElse(""))
    // The channel of the build: `preview` is the field test build (docs/adr/0018-field-test-build.md §1), anything
    // else the release one. Debug builds are told apart at run time (`BuildInfo.channel`).
    channel.set(providers.gradleProperty("hovanki.channel").orElse("release"))
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
            implementation(libs.maplibre.compose)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.play.services.location)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.zxing.core)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.koin.android)
            implementation(libs.kotlinx.coroutines.android)
            runtimeOnly(libs.maplibre.compose.runtime.opengl.android)
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

// Compose resources take strings.xml as written: an Android escape such as `\'` shows up with its backslash.
val checkStringResources by tasks.registering(CheckStringResources::class) {
    strings.from(fileTree("src/commonMain/composeResources") { include("values*/strings.xml") })
    report.set(layout.buildDirectory.file("reports/checkStringResources.txt"))
}
tasks.named("check") { dependsOn(checkStringResources) }

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

    @get:Input
    abstract val channel: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val url = serverUrl.get().trim().trimEnd('/')
        // Non-debug builds talk HTTPS only (no cleartext on Android outside debug, App Transport Security on iOS), and
        // only to this server: players can't type another one.
        if (!url.startsWith("https://") || url.length == "https://".length) {
            throw GradleException(
                "hovanki.serverUrl must be the https:// address of the game server (gradle.properties or " +
                    "-Phovanki.serverUrl=https://...), got '$url'",
            )
        }
        val channelName = channel.get().trim()
        if (channelName != "release" && channelName != "preview") {
            throw GradleException("hovanki.channel must be release or preview, got '$channelName'")
        }
        val file = outputDirectory.file("app/hovanki/client/BuildConstants.kt").get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package app.hovanki.client
            |
            |// Generated by :composeApp:generateBuildConstants (composeApp/build.gradle.kts). Do not edit.
            |internal object BuildConstants {
            |    /** Gradle property `hovanki.serverUrl`: the server of non-debug builds (https). */
            |    const val SERVER_URL: String = "${url.escaped()}"
            |
            |    /** Commit the app was built from; `-dirty` when it had uncommitted changes. */
            |    const val COMMIT: String = "${commit.get().escaped()}"
            |
            |    /** Gradle property `hovanki.channel`: `release` (the default) or `preview`, the field test build. */
            |    const val CHANNEL: String = "$channelName"
            |}
            |
            """.trimMargin(),
        )
    }

    private fun String.escaped(): String = replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
}

/**
 * Fails on Android's escaped quotes in the Compose string resources, which the app would show with the backslash
 * («won\'t»). The texts use typographic quotes instead: ’ “ ” « ».
 */
abstract class CheckStringResources : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val strings: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val problems = strings.files.sortedBy { it.path }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val escaped = "\\'" in line || "\\\"" in line
                val where = "${file.parentFile.name}/${file.name}:${index + 1}"
                when {
                    escaped -> "$where: a backslash shows up in the app (write ’ “ ” instead): ${line.trim()}"
                    tooLongCaption(line) -> "$where: a caption is at most $MAX_CAPTION chars: ${line.trim()}"
                    else -> null
                }
            }
        }
        val text = problems.joinToString("\n")
        report.get().asFile.writeText(text)
        if (problems.isNotEmpty()) throw GradleException("String resources to fix:\n$text")
    }

    /**
     * The settings' text budget (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 1): a `*_caption`
     * under a setting's name fits one line.
     */
    private fun tooLongCaption(line: String): Boolean {
        val caption = CAPTION.find(line) ?: return false
        return caption.groupValues[1].length > MAX_CAPTION
    }

    private companion object {
        val CAPTION = Regex("""<string name="[a-z0-9_]+_caption">(.*)</string>""")
        const val MAX_CAPTION = 40
    }
}
