package app.hovanki.e2e.beacon

import app.hovanki.client.lab.HttpLabApi
import app.hovanki.client.lab.LabApi
import app.hovanki.client.lab.LabClockSync
import app.hovanki.client.lab.LabLog
import app.hovanki.client.lab.LabUploader
import app.hovanki.client.network.HttpGameApi
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.createHttpClient
import app.hovanki.client.radio.RadioApi
import app.hovanki.e2e.cli.CliArgs
import app.hovanki.shared.lab.LabJoinCode
import app.hovanki.shared.lab.LabRunScripts
import app.hovanki.shared.lab.LabSchema
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.LabCapabilities
import app.hovanki.shared.protocol.LabJoinRequest
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.rules.AppleData
import app.hovanki.shared.rules.OverflowArea
import app.hovanki.shared.rules.OverflowCode
import app.hovanki.shared.rules.RadarToken
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
 * `--run <code>` (docs/radar-run.md step 1): the Mac joins a run an admin created in the admin, by its code, as
 * `--label` (`mac`, the plans' label for it), follows its plan by the server's clock ([MacServerRun]: its label's
 * setup of every step, advertising the radar token the server gave it), uploads its log to the run as the phones do
 * ([LabUploader]) and ends when the run is over; sniffing stays on. `q` leaves the run early (the rest of the log goes
 * up first).
 *
 * Start it with `e2e/mac-beacon/run.sh --lab --auto`, `e2e/mac-beacon/run.sh --lab --run <code>` (or with the flags by
 * hand).
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
        val runCode = options.single("run")?.let { code ->
            requireNotNull(LabJoinCode.normalize(code)) {
                "A run's code is ${LabJoinCode.LENGTH} letters and digits: '$code'"
            }
        }
        require(runCode == null || (!auto && advertise == null && iBeacon == null)) {
            "--run follows the run's plan: no --auto, --advertise or --ibeacon"
        }
        val sniff = auto || runCode != null || options.single("sniff") == "on"
        val out = File(options.single("out") ?: "e2e/build/lab").apply { mkdirs() }
        val commit = options.single("commit")

        val mainThread = Dispatchers.Default.limitedParallelism(1)
        val scope = CoroutineScope(SupervisorJob() + mainThread)
        val log = LabLog(isEnabled = true)
        log.setLabel(options.single("label") ?: MacRunFollower.MAC_LABEL)
        val files = LogFiles(out, log)
        log.isRecording = true
        val httpClient = createHttpClient(OkHttp.create(), logRequests = false)
        val api = HttpGameApi(httpClient, ServerUrl(serverUrl))
        val labApi = HttpLabApi(httpClient, ServerUrl(serverUrl))
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
                log.note("run: script ${script.version}, token $token, starts at ${LabSchema.formatUtc(startAt)}")
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

                runCode != null -> "run $runCode"

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
        val uploader = LabUploader(log, labApi, scope)
        // Ends the command: q (or the end of stdin), or the server's run over and its log uploaded.
        val done = CompletableDeferred<Unit>()

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
            val joined = runCode?.let { code ->
                val joined = withContext(mainThread) {
                    measure(log, clock)
                    joinRun(
                        code = code,
                        log = log,
                        labApi = labApi,
                        uploader = uploader,
                        command = macHelper::command,
                        commit = commit,
                        session = { session("run $code") },
                        onFinished = {
                            scope.launch {
                                flush(uploader, "Run over")
                                done.complete(Unit)
                            }
                        },
                    )
                }
                if (joined == null) {
                    withContext(mainThread) { macHelper.close() }
                    scope.cancel()
                    httpClient.close()
                    return@runBlocking 1
                }
                joined
            }
            if (joined != null) {
                scope.launch {
                    while (!joined.run.finished) {
                        joined.run.tick()
                        delay(FOLLOW_MILLIS)
                    }
                }
                scope.launch { pollRun(joined, log, labApi) }
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
                done.complete(Unit)
            }
            done.await()
            if (joined != null && !joined.run.finished) {
                withContext(mainThread) {
                    log.mark("run: left", by = "mac")
                    macHelper.command("stop")
                    log.adv("stop", "mac")
                    flush(uploader, "Leaving the run")
                }
            }
            val last = withContext(mainThread) {
                log.note("lab stopped")
                macHelper.close()
                files.current.also { files.close() }
            }
            scope.cancel()
            httpClient.close()
            say("Saved ${last?.path} and its summary.")
            0
        }
    }

    /**
     * Joins the run of [code] as the log's label and starts uploading to it; null when the server refused or the run's
     * plan is one this build doesn't know (said on the terminal). [onFinished]: the run is over by its plan or the
     * admin's button.
     */
    private suspend fun joinRun(
        code: String,
        log: LabLog,
        labApi: LabApi,
        uploader: LabUploader,
        command: (String) -> Unit,
        commit: String?,
        session: () -> Unit,
        onFinished: () -> Unit,
    ): JoinedRun? {
        val label = log.label.value
        val request = LabJoinRequest(
            code = code,
            label = label,
            model = "MacBook",
            os = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
            build = "e2e beacon-lab",
            commit = commit,
            capabilities = LabCapabilities(platform = Platform.OTHER, bluetooth = BluetoothState.ON),
        )
        val startedAt = log.monoNow()
        val response = try {
            labApi.join(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = LabUploader.describe(e)
            log.net("join", ok = false, millis = log.monoNow() - startedAt, error = reason)
            say("Could not join the run $code as $label: $reason")
            return null
        }
        log.net("join", ok = true, millis = log.monoNow() - startedAt)
        val script = LabRunScripts.byId(response.scenarioId)?.takeIf { it.version == response.scenarioVersion }
        if (script == null) {
            say("This build doesn't know the plan ${response.scenarioId} v${response.scenarioVersion}: update it")
            return null
        }
        log.setRun(response.runId.value, response.salt)
        session()
        uploader.start(response.runId, response.token)
        say("In the run ${response.runId.value} as $label: ${script.title}, token ${response.radarToken}")
        log.mark("run: joined", by = "run")
        val run = MacServerRun(
            runId = response.runId,
            script = script,
            radarToken = response.radarToken,
            initial = response.state,
            serverNow = log::serverNow,
            command = { line ->
                command(line)
                val parts = line.split(' ')
                when (parts[0]) {
                    "advertise" -> log.adv("start", "hider_name", parts[1])
                    "ibeacon" -> log.adv("start", "ibeacon", parts[1])
                    else -> log.adv("stop", "mac")
                }
            },
            onStep = { index, step, revision ->
                log.step(index, step.id, step.title, revision)
                log.mark("run: ${step.id}", by = "run", step = index + 1)
                val seconds = step.seconds?.let { " (${it}s)" } ?: ""
                say("Step ${index + 1} of ${script.steps.size}: ${step.title}$seconds")
            },
            onFinished = {
                log.mark("run: done", by = "run")
                onFinished()
            },
            label = label,
            clockKnown = { log.clock.value != null },
        )
        return JoinedRun(run, response.token)
    }

    /** The rest of the log up to the run, then no more uploads. */
    private suspend fun flush(uploader: LabUploader, why: String) {
        say("$why: uploading the rest of the log…")
        val all = uploader.flush()
        uploader.stop()
        say(if (all) "Uploaded." else "Not everything uploaded: ${uploader.lastError.value ?: "timed out"}")
    }

    /** Asks the server for the run's state every [POLL_MILLIS] until it is over; the timed steps go on meanwhile. */
    private suspend fun pollRun(joined: JoinedRun, log: LabLog, labApi: LabApi) {
        val run = joined.run
        while (!run.finished) {
            delay(POLL_MILLIS)
            val startedAt = log.monoNow()
            try {
                run.onAnswer(labApi.state(run.runId, joined.token))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = LabUploader.describe(e)
                log.net("state", ok = false, millis = log.monoNow() - startedAt, error = reason)
                say("run: the server didn't answer ($reason)")
            }
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
    private const val POLL_MILLIS = 2_000L
    private const val MAC_TOKEN = LabRunScripts.MAC_HIDER_TOKEN
    private const val SUMMARY_EVERY_MILLIS = 2_000L
    private const val CLOCK_TIMEOUT_MILLIS = 10_000L

    private val USAGE = """
        Usage: e2e/mac-beacon/run.sh --lab --auto [--label mac] [--out e2e/build/lab] [--server https://...]
               e2e/mac-beacon/run.sh --lab --run <code> [--label mac] [--out e2e/build/lab] [--server https://...]
               e2e/mac-beacon/run.sh --lab [--advertise <token> | --ibeacon <token>] [--sniff] [...]
    """.trimIndent()
}

/** The Mac in a run on the server: its [run] and the device [token] of the join for the phone routes. */
private class JoinedRun(val run: MacServerRun, val token: String)

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
