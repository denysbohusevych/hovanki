package app.hovanki.e2e.cli

import app.hovanki.e2e.devices.AndroidDevice
import app.hovanki.e2e.devices.Device
import app.hovanki.e2e.devices.DeviceRun
import app.hovanki.e2e.devices.DeviceScenarios
import app.hovanki.e2e.devices.IosDevice
import app.hovanki.e2e.devices.Maestro
import app.hovanki.e2e.devices.Shell
import app.hovanki.e2e.devices.warmUpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes

/**
 * Command line of `:e2e` (`./gradlew :e2e:installDist` → `e2e/build/install/e2e/bin/e2e`):
 *
 * - `devices`: runs device scenarios on already booted emulators/simulators with the debug app installed and a server
 *   with the `e2e` profile running; `e2e/run-devices.sh` prepares all of that and calls it.
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
    val devices: List<Device> =
        options.list("android").mapIndexed { i, serial -> AndroidDevice(serial, "Android-${i + 1}", shell) } +
            options.list("ios").mapIndexed { i, udid -> IosDevice(udid, "iOS-${i + 1}", shell, iosBundleId) }
    require(devices.isNotEmpty()) { "No devices: pass --android <serial,...> and/or --ios <udid,...>" }
    // iOS asks when the app first needs location; the flows answer like a player (allow-location.yaml).
    devices.filterIsInstance<AndroidDevice>().forEach { it.grantPermissions() }
    val maestro = Maestro(
        shell,
        File(options.single("flows") ?: "e2e/maestro"),
        binary = options.single("maestro") ?: "maestro",
        mcpDevices = Maestro.Mode.valueOf((options.single("maestro-mode") ?: "auto").uppercase()).mcpDevices(devices),
        logDir = File(reportRoot, "logs"),
    )
    return maestro.use { runScenarios(options, devices, maestro, serverUrl, port, reportRoot) }
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
        val run = DeviceRun(name, serverUrl, port, devices, bots, maestro, reportRoot)
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
      e2e devices --android emulator-5554,emulator-5556 [--ios <udid,...>] [--bots 3] [--scenario full-round|restart|all]
        [--fail-fast true] [--maestro-mode auto|mcp|cli]
                  [--port 8080] [--server http://localhost:8080] [--flows e2e/maestro] [--report e2e/build/reports/devices]
      e2e route   --to <lat,lon> [--to <lat,lon> ...] [--from <lat,lon>] [--speed 1.5] [--interval 1000] [--hold 0]
                  [--noise none|open-sky|city] [--seed 1] [--format csv|geo-fix] [--adb <serial> | --simctl <udid>]
""".trimIndent()
