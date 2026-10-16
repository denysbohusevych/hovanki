// The server's performance lab (docs/perf.md): Gatling simulations in Kotlin on the protocol's own DTOs from :shared,
// against a server started apart (perf/server.sh or perf/up.sh --container, the profiles `e2e,perf`), and the
// profiler's agent for perf/profile.sh. A simulation runs only when asked for; `check` only compiles them, so a change
// of the protocol that breaks one fails CI, not the next run.
//   ./gradlew :perf:gatlingRun --simulation app.hovanki.perf.OrdinaryGames -Pperf.games=20
// Every `-Pperf.<name>=<value>` reaches the simulation as the system property `perf.<name>` (LabConfig). Reports:
// perf/build/reports/gatling/<simulation>-<time>/index.html.
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.gatling)
}

kotlin {
    jvmToolchain(21)
}

val asyncProfilerJar = configurations.dependencyScope("asyncProfilerJar")
val asyncProfiler = configurations.resolvable("asyncProfiler") { extendsFrom(asyncProfilerJar.get()) }

dependencies {
    gatlingImplementation(projects.shared)
    asyncProfilerJar(libs.async.profiler)
}

// Read at configuration: a changed property is a new configuration, which the configuration cache knows.
val labProperties: Map<String, String> = providers.gradlePropertiesPrefixedBy("perf.").get()

tasks.named<io.gatling.gradle.GatlingRunTask>("gatlingRun") {
    systemProperties = (systemProperties ?: emptyMap<String, Any>()) + labProperties
    // A load test is a measurement of now: never up to date.
    outputs.upToDateWhen { false }
}

// `./gradlew :perf:udpRun -Pperf.games=100`: UdpGames, OrdinaryGames over the live channel v2's UDP (docs/perf.md,
// «UDP»), a program of its own on the simulations' classpath: Gatling has no UDP. Its report: perf/build/reports/udp/.
tasks.register<JavaExec>("udpRun") {
    group = "perf"
    description = "Runs UdpGames: ordinary games whose phones sync over the live channel v2's UDP."
    classpath = sourceSets["gatling"].runtimeClasspath
    mainClass = "app.hovanki.perf.UdpGames"
    jvmArgs("-Xmx3g", "-XX:+UseParallelGC")
    systemProperties = labProperties + mapOf(
        "hovanki.perf.reports" to layout.buildDirectory.dir("reports/udp").get().asFile.absolutePath,
    )
    outputs.upToDateWhen { false }
}

// `./gradlew :perf:gatlingClasspath`: the simulations' classpath as a java argument file
// (perf/build/gatling-classpath.txt), for perf/gatling.sh, which runs Gatling inside the lab's Docker network.
tasks.register("gatlingClasspath") {
    group = "perf"
    description = "Writes the Gatling simulations' runtime classpath for perf/gatling.sh."
    val runtime: FileCollection = sourceSets["gatling"].runtimeClasspath
    val file = layout.buildDirectory.file("gatling-classpath.txt")
    inputs.files(runtime)
    outputs.file(file)
    doLast { file.get().asFile.writeText("-cp \"${runtime.asPath}\"\n") }
}

tasks.named("check") {
    dependsOn("compileGatlingKotlin")
}

/** Unpacks jars without a reference to this script, which the configuration cache can't keep. */
interface Archives {
    @get:Inject val operations: ArchiveOperations
}

// `./gradlew :perf:asyncProfiler`: async-profiler's agents (linux-x64, linux-arm64, macos) from its jar on Maven
// Central into perf/build/async-profiler/<platform>/libasyncProfiler.so, for perf/profile.sh.
tasks.register<Sync>("asyncProfiler") {
    group = "perf"
    description = "Unpacks async-profiler's native agents for perf/profile.sh."
    // Locals, not script properties: the configuration cache can't keep a lambda that refers to the script.
    val jars: FileCollection = asyncProfiler.get()
    val archives = objects.newInstance<Archives>().operations
    from(jars.elements.map { files -> files.map { archives.zipTree(it.asFile) } }) {
        include("*/libasyncProfiler.so")
    }
    into(layout.buildDirectory.dir("async-profiler"))
}
