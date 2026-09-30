package app.hovanki.client.lab

import app.hovanki.shared.lab.DeviceStep
import app.hovanki.shared.lab.LabPlaces
import app.hovanki.shared.lab.LabRunScript
import app.hovanki.shared.lab.LabRunScripts
import app.hovanki.shared.lab.LabSchema
import app.hovanki.shared.lab.PhoneSetup
import app.hovanki.shared.lab.RunPhase
import app.hovanki.shared.lab.RunStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The run's announcement: the phone advertises it as its hider token while the screen is on, and the Mac, hearing it,
 * knows which script and when it starts. `1ab` + the script's version (one hex digit) + the start's second modulo 2¹⁶
 * (four hex digits); both clocks are the server's, so the second is enough.
 */
object LabRunToken {
    private const val PREFIX = "1ab"
    private const val SECONDS = 1L shl 16

    fun encode(version: Int, startAtMillis: Long): String {
        require(version in 0..15 && startAtMillis % 1000 == 0L)
        val second = (startAtMillis / 1000).mod(SECONDS)
        return PREFIX + version.toString(16) + second.toString(16).padStart(4, '0')
    }

    /** The version and the start of the run [token] announces, the start nearest [nowMillis]; null: not a run. */
    fun decode(token: String, nowMillis: Long): Pair<Int, Long>? {
        if (token.length != 8 || !token.startsWith(PREFIX)) return null
        val version = token[3].digitToIntOrNull(16) ?: return null
        val second = token.substring(4).toLongOrNull(16) ?: return null
        val now = nowMillis / 1000
        val base = now - (now - second).mod(SECONDS)
        val start = listOf(base, base + SECONDS).minBy { kotlin.math.abs(it - now) }
        return version to start * 1000
    }
}

/** A run in progress, or finished, for the screen. [label]: whose setups of the script the phone follows. */
data class LabRunState(
    val script: LabRunScript,
    val token: String,
    val startAtServer: Long,
    /** -1: before the start, while the Mac joins; then the step's index. */
    val index: Int = -1,
    val finished: Boolean = false,
    val stopped: Boolean = false,
    val macHeard: Boolean = false,
    val macBeaconHeard: Boolean = false,
    val warnings: List<String> = emptyList(),
    val label: String = LabLog.DEFAULT_LABEL,
) {
    val step: RunStep? get() = script.steps.getOrNull(index)

    /** What the phone does in the current step; null before the start. */
    val setup: PhoneSetup? get() = step?.let { script.setupOf(label, index) }
    val endsAtServer: Long get() = startAtServer + script.totalMillis
}

/**
 * Runs [script] on the phone (docs/radio-lab-tests.md, «Автоматический прогон»): one button, the steps change by
 * themselves by the server's clock, the Mac follows the same steps (`run.sh --lab --auto`, it hears the run's token).
 * Before the start the phone advertises the run's token as a hider for [leadMillis], so the Mac can join. The tester
 * only locks the phone when told. The phone follows the setups of the log's label when the script has it (and it is
 * not the Mac's), else of the script's first label that is not the Mac's. A local run, no run on the server. Main
 * thread.
 */
class LabRunner(
    private val controller: LabController,
    private val scope: CoroutineScope,
    private val script: LabRunScript = LabRunScripts.RADIO,
    private val appState: () -> String = { "-" },
    private val leadMillis: Long = LEAD_MILLIS,
) {
    private val log = controller.log
    private val mutableState = MutableStateFlow<LabRunState?>(null)
    val state: StateFlow<LabRunState?> = mutableState.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)

    /** Why the last run could not start or broke off; null: it didn't. */
    val error: StateFlow<String?> = mutableError.asStateFlow()

    private var job: Job? = null

    init {
        require(script.isTimed) { "the local run goes by its timers" }
    }

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Starts the run: a fresh log, the lab and «as in a game» on (the location permission is the caller's), the clock
     * measured. Without a server clock it ends at once with a warning: the Mac could not follow.
     */
    fun start() {
        if (isRunning) return
        mutableError.value = null
        job = scope.launch {
            try {
                run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: e::class.simpleName ?: "unknown"
                mutableError.value = reason
                warn("the run failed: $reason")
                finish(stopped = true)
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        job?.cancel()
        job = null
        log.mark("run: stopped", by = "tester")
        finish(stopped = true)
    }

    private suspend fun run() {
        controller.start()
        controller.clear()
        controller.setInGame(true)
        val noGps = !controller.inGame.value
        if (withTimeoutOrNull(CLOCK_WAIT_MILLIS) { log.clock.first { it != null } } == null) {
            mutableState.value = null
            log.note("run: no server clock")
            throw IllegalStateException("no server clock: check the network")
        }
        val startAt = ((log.serverNow() + leadMillis) / 1000 + 1) * 1000
        val token = LabRunToken.encode(script.version, startAt)
        val label = log.label.value.takeIf { it in script.labels && it != MAC_LABEL }
            ?: script.labels.firstOrNull { it != MAC_LABEL }
            ?: script.labels.first()
        mutableState.value = LabRunState(script, token, startAt, label = label)
        if (noGps) warn("no location permission: without GPS iOS may suspend the app when it is locked")
        log.note("run: script ${script.version}, token $token, starts at ${LabSchema.formatUtc(startAt)}")
        log.mark("run: announce", by = "run", place = LabPlaces.TABLE_UP, action = LabPlaces.LIE)
        // The announcement: the Mac hears the token as a hider's name and joins.
        controller.setProbe(null)
        controller.setBenchRadio(asSeeker = false, token = token)
        var current = -1
        while (true) {
            val now = log.serverNow()
            val at = script.at(now - startAt)
            if (at == null && now >= startAt) break
            if (at != null && at.index != current) {
                val previous = current.takeIf { it >= 0 }?.let { script.setupOf(label, it) }
                current = at.index
                enter(at.index, at.value, label, previous, token)
            }
            updateHeard(startAt)
            delay(TICK_MILLIS)
        }
        finish(stopped = false)
    }

    private suspend fun enter(index: Int, step: RunStep, label: String, previous: PhoneSetup?, token: String) {
        mutableState.value = mutableState.value?.copy(index = index)
        val device = step.devices[label] ?: DeviceStep()
        val setup = device.setup
        log.mark(
            "run: ${step.id}",
            by = "run",
            step = index + 1,
            place = device.place ?: LabPlaces.TABLE_UP,
            action = device.action ?: LabPlaces.LIE,
        )
        if (setup.phase == RunPhase.LOCKED) {
            if (appState() == "active") warn("${step.id}: the phone was not locked")
        } else {
            // The advertisement changes only while the phone is active; the locked steps keep the lock step's.
            if (previous?.probe != setup.probe) controller.setProbe(setup.probe)
            val hiderOn = controller.bench.radioMode.value != null
            if (setup.hider && !hiderOn) controller.setBenchRadio(asSeeker = false, token = token)
            if (!setup.hider && hiderOn) controller.setBenchRadio(null)
        }
        if (setup.rotateToken) controller.rotateProbeToken(delayMillis = 0)
        if (setup.phase == RunPhase.LOCK) controller.signal("Lock the phone now")
    }

    private fun updateHeard(startAt: Long) {
        val state = mutableState.value ?: return
        val since = startAt - (log.serverNow() - log.deviceNow()) - leadMillis
        val mac = (log.lastHeardMillis(LabRunScripts.MAC_HIDER_TOKEN) ?: Long.MIN_VALUE) >= since
        val beacon = (log.lastHeardMillis(LabRunScripts.MAC_BEACON_TOKEN) ?: Long.MIN_VALUE) >= since
        if (mac != state.macHeard || beacon != state.macBeaconHeard) {
            mutableState.value =
                state.copy(macHeard = state.macHeard || mac, macBeaconHeard = state.macBeaconHeard || beacon)
        }
    }

    private fun warn(text: String) {
        log.note("run: $text")
        mutableState.value = mutableState.value?.let { it.copy(warnings = it.warnings + text) }
    }

    private fun finish(stopped: Boolean) {
        controller.setProbe(null)
        controller.setBenchRadio(null)
        controller.setInGame(false)
        val state = mutableState.value
        if (state != null && !state.finished) {
            mutableState.value = state.copy(finished = true, stopped = stopped)
            if (!stopped) {
                log.mark("run: done", by = "run")
                scope.launch { controller.signal("Radio run done: unlock the phone") }
            }
        }
    }

    companion object {
        /** Before the first step: the Mac hears the announcement meanwhile. */
        const val LEAD_MILLIS = 10_000L
        const val TICK_MILLIS = 200L
        const val CLOCK_WAIT_MILLIS = 15_000L

        /** The Mac's label in the scripts: never the phone's. */
        const val MAC_LABEL = "mac"
    }
}
