package app.hovanki.e2e.beacon

import app.hovanki.client.lab.LabClockSync
import app.hovanki.client.lab.LabLog
import app.hovanki.client.lab.LabRunScripts
import app.hovanki.client.network.HttpGameApi
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.createHttpClient
import app.hovanki.client.radio.RadioApi
import app.hovanki.e2e.cli.CliArgs
import app.hovanki.shared.rules.AppleData
import app.hovanki.shared.rules.OverflowArea
import app.hovanki.shared.rules.OverflowCode
import app.hovanki.shared.rules.RadarToken
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * `e2e beacon-lab` (docs/radio-lab.md §6): the MacBook in the radio lab, without a game. A still listener and sender on
 * the table: every reading the Bluetooth helper reports goes into the lab's log (`LabLog`, the phones' schema) on the
 * server's clock, streamed into `hovanki-lab-<label>-<start>.jsonl`.
 *
 * `--auto on` (docs/radio-lab-tests.md): start it once and leave it. It sniffs, advertises as a hider between runs (the
 * manual scenarios need that), and when a phone starts the automatic radio run it hears the run's token and follows its
 * steps by itself ([MacRunFollower]), every run in a file of its own. By hand instead: `--sniff on` adds the overflow
 * masks in Apple's raw frames, `--advertise <token>` sends as an iPhone hider (the game's service and the token as the
 * name), `--ibeacon <token>` as a seeker, if macOS lets it. Lines on stdin: `m <text>` puts a mark, `clock` measures the
 * clock again, `q` ends. The terminal shows a summary every 2 seconds for the eyes; the file has everything.
 *
 * Start it with `e2e/mac-beacon/run.sh --lab --auto` (or with the flags by hand).
 */
object BeaconLabCli {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun run(options: CliArgs): Int {
        val serverUrl = options.single("server")
        val helper = options.single("helper")?.let(::File)
        if (serverUrl.isNullOrEmpty() || helper == null) {
            System.err.println(USAGE)
            return 2
        }
        require(helper.canExecute()) { "No Bluetooth helper at $helper: start through e2e/mac-beacon/run.sh" }
        val advertise = options.single("advertise")
        val iBeacon = options.single("ibeacon")
        for (token in listOfNotNull(advertise, iBeacon)) {
            require(RadarToken.isWellFormed(token)) { "A token is 8 hex characters, lower case: '$token'" }
        }
        require(advertise == null || iBeacon == null) { "--advertise or --ibeacon, not both" }
        val auto = options.single("auto") == "on"
        require(!auto || (advertise == null && iBeacon == null)) { "--auto sets the advertising itself" }
        val sniff = auto || options.single("sniff") == "on"
        val out = File(options.single("out") ?: "e2e/build/lab").apply { mkdirs() }
        val commit = options.single("commit")

        val mainThread = Dispatchers.Default.limitedParallelism(1)
        val scope = CoroutineScope(SupervisorJob() + mainThread)
        val log = LabLog(isEnabled = true)
        log.setLabel(options.single("label") ?: "mac")
        val files = LogFiles(out, log)
        log.isRecording = true
        val api = HttpGameApi(createHttpClient(OkHttp.create(), logRequests = false), ServerUrl(serverUrl))
        val clock = LabClockSync({ api.serverTime() }, log::deviceNow, log::monoNow)
        val summary = AirSummary()

        fun session(mode: String?) = log.session(
            model = "MacBook",
            os = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
            build = "e2e beacon-lab",
            commit = commit,
            mode = mode,
        )

        lateinit var follower: MacRunFollower
        // The helper's lines come on its reader thread: into the log on the one "main thread".
        val macHelper = MacHelper(helper) { line ->
            scope.launch {
                onHelperLine(log, summary, line)
                if (auto && line is HelperLine.Heard) follower.onHeard(line.token)
            }
        }
        follower = MacRunFollower(
            serverNow = log::serverNow,
            command = { command ->
                macHelper.command(command)
                val parts = command.split(' ')
                when (parts[0]) {
                    "advertise" -> log.adv("start", "hider_name", parts[1])
                    "ibeacon" -> log.adv("start", "ibeacon", parts[1])
                    else -> log.adv("stop", "mac")
                }
            },
            onRunStart = { token, script, startAt ->
                files.open()
                session("auto run $token")
                log.note("run: script ${script.version}, token $token, starts at ${LabLog.formatUtc(startAt)}")
                val inSeconds = (startAt - log.serverNow()) / 1000
                say(
                    "Run $token heard: script ${script.version}, ${script.totalMillis / 60_000} min, starts in ${inSeconds}s",
                )
                say("Log: ${files.current?.path}")
            },
            onStep = { index, step ->
                log.mark("run: ${step.id}", by = "mac", step = index + 1)
                say("Step ${index + 1}: ${step.title} (${step.seconds}s)")
            },
            onRunEnd = { token ->
                log.mark("run: done", by = "mac")
                val done = files.current
                files.open()
                session("auto idle")
                say("Run $token over: ${done?.path}")
                say("Waiting for the next run; the manual scenarios work meanwhile (advertising $MAC_TOKEN).")
            },
        )

        files.open()
        session(
            when {
                auto -> "auto idle"

                else -> listOfNotNull(
                    advertise?.let { "hider_name" },
                    iBeacon?.let { "ibeacon" },
                    "sniff".takeIf { sniff },
                ).joinToString(",").ifEmpty { null }
            },
        )
        when {
            auto -> follower.idle()

            advertise != null -> {
                macHelper.command("advertise $advertise")
                log.adv("start", "hider_name", advertise)
            }

            iBeacon != null -> {
                macHelper.command("ibeacon $iBeacon")
                log.adv("start", "ibeacon", iBeacon)
            }
        }
        if (sniff) macHelper.command("sniff on")
        say("Lab log: ${files.current?.path}")
        if (auto) {
            say("Auto: waiting for a radio run from the phone (Lab → Start the radio run). Leave this running.")
        }
        say("Lines: m <text> puts a mark, clock measures the clock again, q ends.")

        return runBlocking {
            scope.launch {
                var n = 0L
                while (true) {
                    log.tick(n++)
                    delay(TICK_MILLIS)
                }
            }
            scope.launch {
                while (true) {
                    measure(log, clock)
                    delay(LabClockSync.EVERY_MILLIS)
                }
            }
            if (auto) {
                scope.launch {
                    while (true) {
                        follower.tick()
                        delay(FOLLOW_MILLIS)
                    }
                }
            }
            scope.launch {
                while (true) {
                    delay(SUMMARY_EVERY_MILLIS)
                    summary.flush().forEach(::say)
                }
            }
            val input = thread(isDaemon = true, name = "lab-stdin") {
                while (true) {
                    val line = readlnOrNull()?.trim() ?: break
                    when {
                        line == "q" -> break

                        line == "clock" -> scope.launch { measure(log, clock) }

                        line.startsWith("m ") -> scope.launch {
                            log.mark(line.removePrefix("m ").trim(), by = "mac")
                            say("mark: ${line.removePrefix("m ").trim()}")
                        }

                        line.isNotEmpty() -> say("? m <text>, clock or q")
                    }
                }
            }
            withContext(Dispatchers.IO) { input.join() }
            val last = withContext(mainThread) {
                log.note("lab stopped")
                macHelper.close()
                files.current.also { files.close() }
            }
            scope.cancel()
            say("Saved ${last?.path} and its summary.")
            0
        }
    }

    private suspend fun measure(log: LabLog, clock: LabClockSync) {
        val estimate = withTimeoutOrNull(CLOCK_TIMEOUT_MILLIS) { clock.measure() }
        if (estimate == null) {
            log.clockEvent(failed = true)
            say("clock: no answer from the server")
        } else {
            log.setClock(estimate)
            say("clock: ${estimate.offsetMillis} ms from the server's (rtt ${estimate.rttMillis} ms)")
        }
    }

    /** One line of the helper into the log (and the terminal's summary). */
    internal fun onHelperLine(log: LabLog, summary: AirSummary, line: HelperLine) {
        when (line) {
            is HelperLine.State -> {
                log.bt(line.state)
                say("Bluetooth: ${line.state}")
            }

            is HelperLine.Heard -> {
                log.rx(line.token, line.rssi, API, line.via, line.peer)
                summary.heard(line.token, line.rssi, line.how)
            }

            is HelperLine.Raw -> {
                val mask = AppleData.overflowMask(line.data) ?: return
                val bits = OverflowArea.bitsOf(mask)
                val decoded = OverflowCode.decode(bits)
                log.mask(bits, line.rssi, API, mask.joinToString("") { "%02x".format(it) }, line.peer, decoded)
                summary.mask(bits, decoded, line.rssi)
            }

            is HelperLine.Overflow -> {
                val bits = line.uuids.mapNotNull(OverflowArea::bitOf).toSet()
                if (bits.isEmpty()) return
                val decoded = OverflowCode.decode(bits)
                log.mask(bits, line.rssi, API, null, line.peer, decoded)
                summary.mask(bits, decoded, line.rssi)
            }

            is HelperLine.Log -> {
                if (line.text.startsWith("advertising failed")) log.adv("failed", "mac", error = line.text)
                log.note(line.text)
                say(line.text)
            }
        }
    }

    private fun say(text: String) {
        println("${LocalTime.now().format(TIME)}  $text")
    }

    private val API = RadioApi.MAC_COREBLUETOOTH
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
    private const val TICK_MILLIS = 1_000L
    private const val FOLLOW_MILLIS = 200L
    private const val MAC_TOKEN = LabRunScripts.MAC_HIDER_TOKEN
    private const val SUMMARY_EVERY_MILLIS = 2_000L
    private const val CLOCK_TIMEOUT_MILLIS = 10_000L

    private val USAGE = """
        Usage: e2e/mac-beacon/run.sh --lab --auto [--label mac] [--out e2e/build/lab] [--server https://...]
               e2e/mac-beacon/run.sh --lab [--advertise <token> | --ibeacon <token>] [--sniff] [...]
    """.trimIndent()
}

/**
 * The Mac's lab log as files: every [open] starts a new one (a fresh [LabLog], its lines streamed into
 * `hovanki-lab-<label>-<start>.jsonl`) and closes the one before with its summary next to it.
 */
private class LogFiles(private val out: File, private val log: LabLog) {
    var current: File? = null
        private set
    private var writer: java.io.BufferedWriter? = null

    fun open() {
        close()
        log.clear()
        val file = File(out, log.export().fileName)
        val opened = file.bufferedWriter()
        log.onLine = { line ->
            opened.write(line)
            opened.newLine()
            opened.flush()
        }
        writer = opened
        current = file
    }

    fun close() {
        val opened = writer ?: return
        File(out, log.export().summaryName).writeText(log.summary(listOf("model: MacBook")))
        log.onLine = null
        opened.close()
        writer = null
    }
}

/** What the terminal shows every 2 seconds: per token and per mask, the best reading and how many. */
internal class AirSummary {
    private class Seen(var best: Int, var count: Int, val what: String)

    private val seen = LinkedHashMap<String, Seen>()

    @Synchronized
    fun heard(token: String, rssi: Int, how: String) = add("hears $token", rssi, how)

    @Synchronized
    fun mask(bits: Set<Int>, decoded: List<String>, rssi: Int) {
        val what = if (decoded.isEmpty()) "not ours" else "token ${decoded.joinToString("/")}"
        add("mask ${bits.sorted().joinToString(",")}", rssi, what)
    }

    private fun add(key: String, rssi: Int, what: String) {
        val entry = seen.getOrPut(key) { Seen(rssi, 0, what) }
        entry.best = maxOf(entry.best, rssi)
        entry.count++
    }

    @Synchronized
    fun flush(): List<String> =
        seen.map { (key, entry) -> "$key: ${entry.best} dBm, ${entry.count}×, ${entry.what}" }.also { seen.clear() }
}

/** The Bluetooth helper as a process: commands on its stdin, every line it prints to [onLine] on its own thread. */
private class MacHelper(helper: File, private val onLine: (HelperLine) -> Unit) : AutoCloseable {
    private val process = ProcessBuilder(helper.path).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val input = process.outputStream.bufferedWriter()

    init {
        thread(isDaemon = true, name = "mac-beacon") {
            process.inputStream.bufferedReader().forEachLine { onLine(HelperLine.parse(it)) }
            val exit = runCatching { process.exitValue() }.getOrNull()
            onLine(HelperLine.Log("the Bluetooth helper stopped (exit $exit)"))
        }
    }

    @Synchronized
    fun command(line: String) {
        runCatching {
            input.write(line)
            input.newLine()
            input.flush()
        }
    }

    override fun close() {
        runCatching { input.close() }
        process.destroy()
    }
}
