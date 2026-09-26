package app.hovanki.e2e.cli

import app.hovanki.e2e.devices.AndroidDevice
import app.hovanki.e2e.devices.Device
import app.hovanki.e2e.devices.DeviceRun
import app.hovanki.e2e.devices.DeviceScenarios
import app.hovanki.e2e.devices.IosDevice
import app.hovanki.e2e.devices.LocalServer
import app.hovanki.e2e.devices.LocalSetup
import app.hovanki.e2e.devices.Maestro
import app.hovanki.e2e.devices.Shell
import app.hovanki.e2e.devices.warmUpServer
import app.hovanki.shared.protocol.GeoPoint
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes

/**
 * Command line of `:e2e` (`./gradlew :e2e:installDist` → `e2e/build/install/e2e/bin/e2e`):
 *
 * - `devices`: runs device scenarios on already booted emulators/simulators with the debug app installed and a server
 *   with the `e2e` profile running; `e2e/run-devices.sh` prepares all of that and calls it. With `--android auto`,
 *   `--install-apk` and `--server-jar` it finds the running emulators, installs the app and starts the server itself:
 *   `./gradlew :e2e:devices`, e.g. from Android Studio.
 * - `route`: prints the fixes of a route or feeds them to an emulator/simulator in real time (see [RouteCli]).
 */
fun main(args: Array<String>) {
    val options = CliArgs(args.drop(1))
    val code = when (args.firstOrNull()) {
        "devices" -> runDevices(options)

        "route" -> RouteCli.run(options)

        else -> {
            System.err.println(USAGE)
            2
        }
    }
    exitProcess(code)
}

private fun runDevices(options: CliArgs): Int {
    val port = options.single("port")?.toInt() ?: 8080
    val serverUrl = options.single("server") ?: "http://localhost:$port"
    val reportRoot = File(options.single("report") ?: "e2e/build/reports/devices").apply { mkdirs() }
    val shell = Shell(File(reportRoot, "commands.log"))
    val iosBundleId = options.single("ios-bundle-id") ?: "app.hovanki.ios"
    val maestroBinary = options.single("maestro") ?: "maestro"
    // `--android auto`: the emulators already running on this machine (./gradlew :e2e:devices, Android Studio).
    val androidSerials = options.list("android").let { serials ->
        if (serials != listOf("auto")) return@let serials
        LocalSetup.runningEmulators(shell).also {
            check(it.isNotEmpty()) { "No running emulators: start one or two in Android Studio (Device Manager)" }
            println("[devices] running emulators: ${it.joinToString()}")
        }
    }
    val androidDevices = androidSerials.mapIndexed { i, serial -> AndroidDevice(serial, "Android-${i + 1}", shell) }
    val iosDevices = options.list("ios").mapIndexed { i, udid -> IosDevice(udid, "iOS-${i + 1}", shell, iosBundleId) }
    val devices: List<Device> = androidDevices + iosDevices
    require(devices.isNotEmpty()) { "No devices: pass --android <serial,...|auto> and/or --ios <udid,...>" }

    // What e2e/run-devices.sh otherwise does: install the app, start the server.
    val apk = options.single("install-apk")?.let(::File)
    val serverJar = options.single("server-jar")?.let(::File)
    if (apk != null || serverJar != null) LocalSetup.checkMaestro(maestroBinary)
    val restoreSettings = mutableListOf<() -> Unit>()
    var server: LocalServer? = null
    try {
        if (apk != null) {
            require(apk.isFile) { "No APK $apk: build it with ./gradlew :androidApp:assembleDebug" }
            for (device in androidDevices) {
                println("[devices] installing the debug app on ${device.id}")
                restoreSettings += LocalSetup.prepare(device, apk)
            }
        }
        if (serverJar != null) {
            val log = File(reportRoot, "logs/server.log")
            val buildings = options.single("buildings") ?: "overpass"
            println("[devices] starting the server on :$port (profile e2e, buildings: $buildings), log: $log")
            server = LocalServer.start(serverJar, port, File(reportRoot, "logs"), buildings)
        }
        // iOS asks when the app first needs location; the flows answer like a player (allow-location.yaml).
        androidDevices.forEach { it.grantPermissions() }
        val maestro = Maestro(
            shell,
            File(options.single("flows") ?: "e2e/maestro"),
            binary = maestroBinary,
            mcpDevices = Maestro.Mode.valueOf((options.single("maestro-mode") ?: "auto").uppercase())
                .mcpDevices(devices),
            logDir = File(reportRoot, "logs"),
        )
        return maestro.use { runScenarios(options, devices, maestro, serverUrl, port, reportRoot) }
    } finally {
        server?.close()
        restoreSettings.forEach { runCatching { it() } }
    }
}

private fun runScenarios(
    options: CliArgs,
    devices: List<Device>,
    maestro: Maestro,
    serverUrl: String,
    port: Int,
    reportRoot: File,
): Int {
    val bots = options.single("bots")?.toInt() ?: 3
    val location = options.single("location")?.let(::parseLocation)
    runCatching { runBlocking { warmUpServer(serverUrl) } }
        .onFailure { println("[warm-up] ${it.message} (the scenarios run anyway)") }
    val requested = options.single("scenario") ?: "full-round"
    val names = if (requested == "all") DeviceScenarios.all.keys.toList() else listOf(requested)
    val failFast = options.single("fail-fast")?.toBooleanStrict() ?: false

    val results = mutableListOf<Triple<String, Boolean, String>>()
    for (name in names) {
        if (failFast && results.any { !it.second }) {
            println("[$name] skipped (--fail-fast)")
            results += Triple(name, false, " — skipped after a failure (--fail-fast)")
            continue
        }
        val scenario = DeviceScenarios.all[name]
            ?: error("Unknown scenario '$name', known: ${DeviceScenarios.all.keys.joinToString()} or all")
        val run = DeviceRun(name, serverUrl, port, devices, bots, maestro, reportRoot, location)
        val passed = run.execute(timeout = 20.minutes, block = scenario)
        val reason = run.failure?.let { " — ${it.message?.lines()?.first()}" }.orEmpty()
        println("[$name] ${if (passed) "passed" else "FAILED$reason"}: ${File(run.reportDir, "report.md")}")
        // The whole message (Maestro output, what was on screen) right away: a CI log shows it while the next
        // scenario still runs.
        run.failure?.message?.let { message -> println(message.prependIndent("    ")) }
        results += Triple(name, passed, reason)
    }
    File(reportRoot, "index.md").writeText(
        buildString {
            appendLine("# Device scenarios")
            appendLine()
            appendLine("Devices: ${devices.joinToString { "${it.label} (${it.id})" }}; bots: $bots; server: $serverUrl")
            appendLine()
            for ((name, passed, reason) in results) {
                appendLine("- [$name]($name/index.html): ${if (passed) "passed" else "**FAILED**$reason"}")
            }
        },
    )
    return if (results.all { it.second }) 0 else 1
}

/** `52.2297,21.0122` → a point; anything else fails the run with the expected format. */
internal fun parseLocation(text: String): GeoPoint {
    val parts = text.split(',').map { it.trim().toDoubleOrNull() }
    require(parts.size == 2 && parts.all { it != null }) { "--location takes LAT,LON, e.g. 52.2297,21.0122: '$text'" }
    val (lat, lon) = parts.map { checkNotNull(it) }
    require(lat in -90.0..90.0 && lon in -180.0..180.0) { "--location out of range: '$text'" }
    return GeoPoint(lat, lon)
}

/** `--key value` pairs; a key may repeat, list values may also be comma-separated. */
class CliArgs(args: List<String>) {
    private val values = mutableMapOf<String, MutableList<String>>()

    init {
        var i = 0
        while (i < args.size) {
            require(args[i].startsWith("--") && i + 1 < args.size) { "Expected --key value at '${args[i]}'\n$USAGE" }
            values.getOrPut(args[i].removePrefix("--")) { mutableListOf() } += args[i + 1]
            i += 2
        }
    }

    fun single(key: String): String? = values[key]?.last()

    fun list(key: String): List<String> =
        values[key].orEmpty().flatMap { it.split(',') }.map(String::trim).filter(String::isNotEmpty)
}

private val USAGE = """
    Usage:
      e2e devices --android emulator-5554,emulator-5556|auto [--ios <udid,...>] [--bots 3]
                  [--scenario full-round|restart|all] [--fail-fast true] [--maestro-mode auto|mcp|cli]
                  [--maestro <path>] [--install-apk androidApp-debug.apk] [--server-jar hovanki-server.jar]
                  [--port 8080] [--server http://localhost:8080] [--flows e2e/maestro] [--report e2e/build/reports/devices]
                  [--location <lat,lon>] [--buildings overpass|fake|off]
      e2e route   --to <lat,lon> [--to <lat,lon> ...] [--from <lat,lon>] [--speed 1.5] [--interval 1000] [--hold 0]
                  [--noise none|open-sky|city] [--seed 1] [--format csv|geo-fix] [--adb <serial> | --simctl <udid>]
""".trimIndent()
