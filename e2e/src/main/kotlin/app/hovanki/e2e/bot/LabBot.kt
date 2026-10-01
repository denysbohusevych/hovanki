package app.hovanki.e2e.bot

import app.hovanki.client.diagnostics.Diagnostics
import app.hovanki.client.diagnostics.DiagnosticsBench
import app.hovanki.client.lab.HttpLabApi
import app.hovanki.client.lab.LabAbout
import app.hovanki.client.lab.LabClockSync
import app.hovanki.client.lab.LabController
import app.hovanki.client.lab.LabFollowState
import app.hovanki.client.lab.LabLog
import app.hovanki.client.lab.LabRadioTrace
import app.hovanki.client.lab.LabRunFollower
import app.hovanki.client.lab.LabUploader
import app.hovanki.client.lab.NoopLabFiles
import app.hovanki.client.network.HttpGameApi
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.createHttpClient
import app.hovanki.device.lab.NoopLabHaptics
import app.hovanki.device.lab.NoopLabProbes
import app.hovanki.device.lab.NoopLabScreen
import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.scenario.Timeline
import app.hovanki.radar.lab.NoopLabAir
import app.hovanki.shared.protocol.Carry
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LabCapabilities
import app.hovanki.shared.protocol.LabRunAction
import app.hovanki.shared.protocol.LabRunStatus
import app.hovanki.shared.protocol.Platform
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A headless phone in the radio lab (docs/adr/0017-radar-techniques-and-big-run.md §5): the debug build's lab as the
 * app wires it ([LabLog], [DiagnosticsBench], [LabController], [LabUploader], [LabRunFollower] over [HttpLabApi]) on a
 * simulated phone: its Bluetooth is a [FakeRadio] in the scenario's [RadioWorld], so the lab phones hear each other
 * by their true positions, and its clock a [DeviceClock]. The platform's own lab parts (sensors, the overflow probe,
 * listening, haptics, the screen) are the no-op ones: what the run records here is the bench radio, the ticks, the
 * clock and the run's steps. The phone is in the hand ([carry]), so every platform is heard.
 *
 * [label] is the phone's name in the run; [uploadIntervalMillis] is shorter than the app's so a test waits less.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LabBot(
    val label: String,
    at: GeoPoint,
    val platform: Platform,
    serverUrl: String,
    radioWorld: RadioWorld,
    private val timeline: Timeline,
    uploadIntervalMillis: Long = UPLOAD_INTERVAL_MILLIS,
) : AutoCloseable {
    val clock = DeviceClock()
    val gps = FakeGps(at, GpsNoise.openSky(seed = label.hashCode().toLong()), clock)

    /** Where the phone is: in the hand unless the scenario puts it away. */
    val carry = MutableStateFlow(Carry.IN_HAND)
    val log = LabLog(isEnabled = true, deviceTimeMillis = clock::now)

    /** The radio writes into the lab's log as the app's does ([LabRadioTrace]): its advertisement, frames, the air. */
    val radio = FakeRadio(radioWorld, platform, { gps.truePosition }, { carry.value }, clock::now, LabRadioTrace(log))
    val backgroundTracker = FakeBackgroundTracker()

    /** The app's "main thread": the lab's parts are confined to it, as on the phone. */
    private val mainThread = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + mainThread)
    private val httpClient = createHttpClient(OkHttp.create(), logRequests = false)
    private val url = ServerUrl(serverUrl)

    private val bench = DiagnosticsBench(
        radio,
        gps,
        Diagnostics(isEnabled = true, deviceTimeMillis = clock::now),
        scope,
        lab = log,
        deviceTimeMillis = clock::now,
    )
    private val gameApi = HttpGameApi(httpClient, url)
    val controller = LabController(
        log = log,
        bench = bench,
        probes = NoopLabProbes(),
        air = NoopLabAir(),
        screen = NoopLabScreen(),
        haptics = NoopLabHaptics(),
        files = NoopLabFiles(),
        radio = radio,
        carryMonitor = FakeCarryMonitor(carry),
        backgroundTracker = backgroundTracker,
        clockSync = LabClockSync(gameApi::serverTime, clock::now, log::monoNow),
        about = { LabAbout("Bot ${platform.name.lowercase()}", "e2e", "e2e lab bot", null) },
        scope = scope,
    )
    private val uploader = LabUploader(log, HttpLabApi(httpClient, url), scope, intervalMillis = uploadIntervalMillis)
    private val follower = LabRunFollower(
        controller,
        HttpLabApi(httpClient, url),
        uploader,
        scope,
        capabilities = {
            LabCapabilities(
                platform = platform,
                bluetooth = radio.state.value,
                uwb = false,
                locationPermission = gps.hasPermission(),
            )
        },
    )

    /** The run this phone follows, as the lab's card shows it; null: never joined one. */
    val follow: StateFlow<LabFollowState?> = follower.state

    /** Events written after the last one the server acknowledged. */
    val uploadPending: StateFlow<Long> = uploader.pending

    /** Why the last upload failed; null: it went through. */
    val uploadError: StateFlow<String?> = uploader.lastError

    init {
        scope.launch {
            var previous: LabFollowState? = null
            follower.state.collect { state ->
                if (state != null) logChanges(previous, state)
                previous = state
            }
        }
    }

    /**
     * Types the run's [code] and [label] into the lab's card and taps «Join». Throws what the server answered
     * ([app.hovanki.client.network.ApiException]) or what the app refused ([LabRunFollower.join]).
     */
    suspend fun joinRun(code: String, label: String = this.label) {
        log("joins the lab run $code as $label")
        try {
            withContext(mainThread) { follower.join(code, label) }
        } catch (e: Exception) {
            log("could not join: ${e.message ?: e::class.simpleName}")
            throw e
        }
    }

    /** Taps one of the run's buttons on the phone. */
    fun advance(action: LabRunAction) {
        log("taps ${action.name.lowercase()}")
        scope.launch { follower.advance(action) }
    }

    /** Taps «Leave»: the rest of the log goes up, the uploads stop. */
    suspend fun leave() {
        withContext(mainThread) { follower.leave() }
        log("left the lab run")
    }

    /** Whether the lab records (it stops by itself when the run is over). */
    val isRecording: Boolean get() = controller.running.value

    fun log(text: String) = timeline.log("lab $label", text)

    private fun logChanges(before: LabFollowState?, after: LabFollowState) {
        if (before == null) log("in the run ${after.runId.value}, token ${after.radarToken}")
        val step = after.step
        if (after.plan.status != LabRunStatus.FINISHED && step != null && before?.step?.id != step.id) {
            log("step ${after.plan.stepIndex + 1}: ${step.id}")
        }
        if (before?.plan?.status != after.plan.status) log("run ${after.plan.status}")
        if (after.left && before?.left != true) log("left")
    }

    override fun close() {
        scope.cancel()
        httpClient.close()
    }

    companion object {
        /** The tests' upload interval: the app's is 5 s. */
        const val UPLOAD_INTERVAL_MILLIS = 2_000L
    }
}
