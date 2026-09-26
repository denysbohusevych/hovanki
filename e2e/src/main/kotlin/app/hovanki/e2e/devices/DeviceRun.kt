package app.hovanki.e2e.devices

import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.Scenario
import app.hovanki.e2e.scenario.ScenarioReport
import app.hovanki.e2e.scenario.Timeline
import app.hovanki.shared.debug.DebugGameState
import app.hovanki.shared.protocol.protocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.time.Duration

/**
 * One scenario on real emulators/simulators, mixed with headless bots ([scenario] holds the bots, the observer
 * and the checks). Writes `<reportRoot>/<scenario>/`: report.md, index.html, screenshots/, logs/, final-state.json.
 */
class DeviceRun(
    name: String,
    serverUrl: String,
    /** Port of the server on the host machine; devices reach it at their own address for the host. */
    val port: Int,
    val devices: List<Device>,
    val botCount: Int,
    val maestro: Maestro,
    reportRoot: File,
) {
    val scenario = Scenario(name, serverUrl)
    val timeline: Timeline get() = scenario.timeline
    val reportDir = File(reportRoot, ScenarioReport.slug(name))
    private val screenshotDir = File(reportDir, "screenshots")
    private val shots = mutableListOf<Shot>()
    private val gpsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    class Shot(val label: String, val device: String, val file: File)

    /** Players on the devices, in the order of [devices]; the first one hosts. */
    val devicePlayers: List<DevicePlayer> by lazy {
        devices.mapIndexed { index, device ->
            DevicePlayer(device, START.offset(eastMeters = 8.0 * index), this).also { it.startGps(gpsScope) }
        }
    }

    suspend fun screenshot(label: String, of: List<DevicePlayer> = devicePlayers) = withContext(Dispatchers.IO) {
        screenshotDir.mkdirs()
        for (player in of) {
            val file =
                File(screenshotDir, "%02d-%s-%s.png".format(shots.size + 1, ScenarioReport.slug(label), player.name))
            player.device.screenshot(file)
            synchronized(shots) { shots += Shot(label, player.name, file) }
        }
        timeline.log("report", "screenshot: $label")
    }

    /** Why the run failed; null while it passes. */
    var failure: Throwable? = null
        private set

    /** Runs [block] and writes the report; returns whether it passed (see [failure]). */
    fun execute(timeout: Duration, block: suspend DeviceRun.() -> Unit): Boolean {
        reportDir.mkdirs()
        devices.forEach { it.clearLogs() }
        try {
            runBlocking {
                withTimeout(timeout) {
                    block()
                    scenario.checkPrivacy()
                }
            }
        } catch (e: Throwable) {
            failure = e
            timeline.log("scenario", "FAILED: ${e.message}")
            runCatching { runBlocking { screenshot("failure") } }
        }
        val finalState = runCatching {
            runBlocking { scenario.gameIdOrNull?.let { scenario.observer.game(it) } }
        }.getOrNull()
        gpsScope.cancel()
        devices.forEach { device ->
            runCatching { device.saveLogs(File(reportDir, "logs/${device.label}.log").apply { parentFile.mkdirs() }) }
        }
        scenario.close()
        writeReport(finalState, failure)
        return failure == null
    }

    private fun writeReport(state: DebugGameState?, failure: Throwable?) {
        if (state != null) File(reportDir, "final-state.json").writeText(protocolJson.encodeToString(state))
        File(reportDir, "timeline.txt").writeText(timeline.render())
        val result = if (failure == null) "passed" else "FAILED — ${failure.message}"
        val devicesLine = devices.joinToString { "${it.label} (${it.id})" }
        File(reportDir, "report.md").writeText(
            buildString {
                appendLine("# ${scenario.name}")
                appendLine()
                appendLine("**Result: $result**")
                appendLine()
                appendLine(
                    "Devices: $devicesLine. Bots: $botCount. Server: ${scenario.serverUrl}, game ${scenario.gameIdOrNull?.value ?: "—"}.",
                )
                appendLine()
                appendLine("## Checks and timeline")
                appendLine()
                appendLine("```")
                appendLine(timeline.render())
                appendLine("```")
                appendLine()
                appendLine("## Screenshots")
                appendLine()
                for (shot in shots) {
                    appendLine(
                        "- ${shot.label} — ${shot.device}: [${shot.file.name}](screenshots/${shot.file.name})",
                    )
                }
                appendLine()
                appendLine("Logs: `logs/`, final server state: `final-state.json`.")
            },
        )
        File(reportDir, "index.html").writeText(html(result, devicesLine))
    }

    private fun html(result: String, devicesLine: String): String = buildString {
        fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        appendLine("<!doctype html><html><head><meta charset=\"utf-8\"><title>${esc(scenario.name)}</title>")
        appendLine(
            "<style>body{font-family:sans-serif;margin:24px}pre{background:#f4f4f4;padding:12px;overflow:auto}" +
                ".shots{display:flex;flex-wrap:wrap;gap:12px}figure{margin:0}img{width:240px;border:1px solid #ccc}</style>",
        )
        appendLine("</head><body><h1>${esc(scenario.name)}</h1>")
        appendLine("<p><b>Result: ${esc(result)}</b></p><p>${esc(devicesLine)}; bots: $botCount</p>")
        appendLine("<h2>Screenshots</h2><div class=\"shots\">")
        for (shot in shots) {
            val src = "screenshots/${shot.file.name}"
            appendLine(
                "<figure><a href=\"$src\"><img src=\"$src\"></a><figcaption>${esc(
                    shot.label,
                )} — ${esc(shot.device)}</figcaption></figure>",
            )
        }
        appendLine("</div><h2>Timeline</h2><pre>${esc(timeline.render())}</pre>")
        appendLine(
            "<p>Logs: <code>logs/</code>, server state: <a href=\"final-state.json\">final-state.json</a></p></body></html>",
        )
    }

    private companion object {
        val START = GameSetups.PARK
    }
}
