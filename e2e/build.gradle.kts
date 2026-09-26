// End-to-end tests (docs/e2e.md): headless bots run the app's client code (:clientCore over Ktor/OkHttp, as on
// Android) with simulated GPS, clock and network, and play whole games against a real server.
// `./gradlew :e2e:test` starts the server in-process on a random port; HOVANKI_E2E_SERVER_URL points the same
// scenarios at an external server started with the Spring profile `e2e`. Not part of `check` (see `unitTest`).
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinJvm)
    // `e2e` command line: device scenarios (e2e/run-devices.sh) and routes for devices.
    application
}

application {
    applicationName = "e2e"
    mainClass.set("app.hovanki.e2e.cli.MainKt")
}

kotlin {
    // Same toolchain as :server, which the tests run in-process.
    jvmToolchain(21)
}

dependencies {
    implementation(projects.clientCore)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(projects.server)
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// `./gradlew :e2e:route --args="--to 50.4481,30.5402 --adb emulator-5554"`: the bots' Route and GpsNoise for devices.
tasks.register<JavaExec>("route") {
    group = "e2e"
    description = "Prints a route's GPS fixes or feeds them to an emulator/simulator (see RouteCli)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("app.hovanki.e2e.cli.RouteCliKt")
}

// `./gradlew :e2e:devices`: the device scenarios on the emulators already running on this machine, e.g. from Android
// Studio (run configurations in .run/). Builds the server jar and the debug APK, installs the app on every running
// emulator, starts the server with the `e2e` profile, runs Maestro and the bots; the emulators stay as they were.
// Options: -Pe2e.scenario=full-round|restart|all -Pe2e.bots=3 -Pe2e.emulators=auto|emulator-5554,... -Pe2e.port=8080
// -Pe2e.failFast=true -Pe2e.maestro=<path> -Pe2e.location=<lat,lon> (default: where the first emulator is)
// -Pe2e.buildings=overpass|fake|off (default overpass: real buildings around the game).
// Report: e2e/build/reports/devices/. e2e/run-devices.sh does the same with headless emulators it starts itself (CI)
// and iOS simulators.
val rootDirectory = rootProject.layout.projectDirectory
// Like AGP: sdk.dir from local.properties (Android Studio writes it), then ANDROID_HOME. adb comes from there, so the
// task works from the IDE, whose PATH often has no platform-tools.
val androidSdk = providers.fileContents(rootDirectory.file("local.properties")).asText
    .map { text -> Properties().apply { load(text.reader()) }.getProperty("sdk.dir").orEmpty() }
    .filter { it.isNotBlank() }
    .orElse(providers.environmentVariable("ANDROID_HOME"))
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse("")

tasks.register<JavaExec>("devices") {
    group = "e2e"
    description = "Device scenarios (Maestro + bots) on the running Android emulators; see docs/e2e.md."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("app.hovanki.e2e.cli.MainKt")
    dependsOn(":server:bootJar", ":androidApp:assembleDebug")
    workingDir = rootDirectory.asFile
    maxHeapSize = "768m"
    // Locals, not script properties: the configuration cache can't serialize a doFirst that references the script.
    val sdk = androidSdk
    val scenario = providers.gradleProperty("e2e.scenario").orElse("full-round")
    val bots = providers.gradleProperty("e2e.bots").orElse("3")
    val port = providers.gradleProperty("e2e.port").orElse("8080")
    val emulators = providers.gradleProperty("e2e.emulators").orElse("auto")
    val failFast = providers.gradleProperty("e2e.failFast").orElse("false")
    val maestro = providers.gradleProperty("e2e.maestro").orElse("")
    val location = providers.gradleProperty("e2e.location").orElse("")
    val buildings = providers.gradleProperty("e2e.buildings").orElse("overpass")
    val home = providers.systemProperty("user.home")
    val windows = providers.systemProperty("os.name").map { it.startsWith("Windows") }
    val serverJar = rootDirectory.file("server/build/libs/hovanki-server.jar").asFile
    val apk = rootDirectory.file("androidApp/build/outputs/apk/debug/androidApp-debug.apk").asFile
    val flows = rootDirectory.dir("e2e/maestro").asFile
    val report = layout.buildDirectory.dir("reports/devices")
    // Everything is resolved at execution: the run talks to the devices, so it is never up to date.
    doFirst {
        val pathKey = environment.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        val maestroBin = File(home.get(), ".maestro/bin")
        val platformTools = sdk.get().takeIf { it.isNotEmpty() }?.let { File(it, "platform-tools").path }
        val extraPath = listOfNotNull(platformTools, maestroBin.path)
        environment(pathKey, (extraPath + environment[pathKey]?.toString().orEmpty()).joinToString(File.pathSeparator))
        environment("MAESTRO_CLI_NO_ANALYTICS", "1")
        environment("MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED", "true")
        // The install script puts Maestro into ~/.maestro/bin; on Windows the launcher is maestro.bat, which
        // a bare "maestro" would not find.
        val installed = File(maestroBin, if (windows.get()) "maestro.bat" else "maestro")
        val maestroBinary = maestro.get().ifEmpty { if (installed.isFile) installed.path else "maestro" }
        args(
            "devices",
            "--android", emulators.get(),
            "--scenario", scenario.get(),
            "--bots", bots.get(),
            "--port", port.get(),
            "--fail-fast", failFast.get(),
            "--maestro", maestroBinary,
            "--install-apk", apk.absolutePath,
            "--server-jar", serverJar.absolutePath,
            "--flows", flows.absolutePath,
            "--report", report.get().asFile.absolutePath,
            "--buildings", buildings.get(),
        )
        if (location.get().isNotBlank()) args("--location", location.get())
    }
}

val externalServerUrl = providers.environmentVariable("HOVANKI_E2E_SERVER_URL").orElse("")

// `test` plays whole games (~3 min): it runs only when asked for, `./gradlew :e2e:test`, and nightly
// (.github/workflows/nightly.yml), never as part of `check`. `check` runs this module's own unit tests instead.
val unitTest = tasks.register<Test>("unitTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Unit tests of the e2e tooling (routes, GPS noise, UI tree) without the game scenarios."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { excludeTestsMatching("app.hovanki.e2e.scenarios.*") }
}

tasks.check {
    setDependsOn(dependsOn.filterNot { it is Named && it.name == JavaPlugin.TEST_TASK_NAME })
    dependsOn(unitTest)
}

tasks.test {
    useJUnitPlatform()
    // Scenarios run in real time and mostly wait on game timers: run them in parallel (junit-platform.properties).
    maxHeapSize = "2g"
    // A different target server is a different test run.
    inputs.property("externalServerUrl", externalServerUrl)
    // Scenario reports (timeline, final state, sync latency): e2e/build/reports/e2e/<scenario>.md.
    jvmArgumentProviders += ReportDir(layout.buildDirectory.dir("reports/e2e"))
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/** Passes the report directory to the tests and declares it as an output (relocatable, unlike an absolute path). */
class ReportDir(@get:OutputDirectory val dir: Provider<Directory>) : CommandLineArgumentProvider {
    override fun asArguments(): List<String> = listOf("-Dhovanki.e2e.reportDir=${dir.get().asFile.absolutePath}")
}
