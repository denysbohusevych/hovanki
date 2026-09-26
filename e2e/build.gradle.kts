// End-to-end tests (docs/e2e.md): headless bots run the app's client code (:clientCore over Ktor/OkHttp, as on
// Android) with simulated GPS, clock and network, and play whole games against a real server.
// `./gradlew :e2e:test` starts the server in-process on a random port; HOVANKI_E2E_SERVER_URL points the same
// scenarios at an external server started with the Spring profile `e2e`.
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

val externalServerUrl = providers.environmentVariable("HOVANKI_E2E_SERVER_URL").orElse("")

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
