package app.hovanki.client.lab

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

/** Where the automatic run is: the screen on, the moment to lock the phone, the phone locked. */
enum class RunPhase { SCREEN, LOCK, LOCKED }

/**
 * What the phone does in a step. [hider]: the game's radio as a hider with the run's token (it also scans: the game's
 * service by CoreBluetooth, the seekers' iBeacon by ranging). [probe]: the overflow probe. [rotateToken]: the probe's
 * token changes when the step starts. The vibration test is a test of its own, not a step.
 */
data class PhoneSetup(val hider: Boolean = false, val probe: ProbeMode? = null, val rotateToken: Boolean = false)

/** What the Mac does in a step, besides sniffing (always): advertise as a hider, or as a seeker's iBeacon. */
data class MacSetup(val advertise: Boolean = false, val iBeacon: Boolean = false)

data class RunStep(
    val id: String,
    val title: String,
    val seconds: Int,
    val phase: RunPhase,
    val phone: PhoneSetup,
    val mac: MacSetup,
    val hint: String = "",
)

/**
 * The radio lab's automatic run (docs/radio-lab-tests.md): steps with timers the phone and the Mac both follow by the
 * server's clock from the same start. The phone can't change its advertisement in the background, so the locked steps
 * keep what the lock step set; they change only what the Mac sends and what the phone does besides advertising.
 */
class LabRunScript(val version: Int, val steps: List<RunStep>) {
    init {
        require(version in 0..15 && steps.isNotEmpty())
        val locked = steps.filter { it.phase == RunPhase.LOCKED }
        val lock = steps.lastOrNull { it.phase == RunPhase.LOCK }
        require(locked.all { it.phone.hider == lock?.phone?.hider && it.phone.probe == lock.phone.probe }) {
            "a locked step can't change the advertisement"
        }
    }

    val totalMillis: Long = steps.sumOf { it.seconds * 1000L }

    fun startOf(index: Int): Long = steps.take(index).sumOf { it.seconds * 1000L }

    /** The step at [elapsedMillis] since the start; null before it or after the end. */
    fun at(elapsedMillis: Long): IndexedValue<RunStep>? {
        if (elapsedMillis < 0) return null
        var end = 0L
        for ((index, step) in steps.withIndex()) {
            end += step.seconds * 1000L
            if (elapsedMillis < end) return IndexedValue(index, step)
        }
        return null
    }
}

object LabRunScripts {
    /** What the Mac advertises as a hider (the game's service, the token as the name). */
    const val MAC_HIDER_TOKEN = "cafe0001"

    /** What the Mac advertises as a seeker's iBeacon. */
    const val MAC_BEACON_TOKEN = "cafe0002"

    private val pocket = PhoneSetup(hider = true, probe = ProbeMode.Token)

    /** Version 2: 9 minutes of Bluetooth only, the phone and the Mac on the table 1 m apart. */
    val RADIO = LabRunScript(
        2,
        listOf(
            RunStep(
                "baseline",
                "Both advertise as hiders",
                60,
                RunPhase.SCREEN,
                PhoneSetup(hider = true),
                MacSetup(advertise = true),
                "Keep the screen on. Each hears the other's name: the RSSI both ways, on the table.",
            ),
            RunStep(
                "mask_pattern",
                "Overflow mask: 0x5A",
                40,
                RunPhase.SCREEN,
                PhoneSetup(probe = ProbeMode.Pattern),
                MacSetup(),
                "The Mac should hear bits 1 3 4 6 9 11 12 14.",
            ),
            RunStep(
                "mask_token",
                "Overflow mask: a token",
                40,
                RunPhase.SCREEN,
                PhoneSetup(probe = ProbeMode.Token),
                MacSetup(),
                "The Mac should decode the probe's token.",
            ),
            RunStep(
                "ibeacon_screen",
                "The Mac as a seeker's iBeacon",
                60,
                RunPhase.SCREEN,
                PhoneSetup(hider = true),
                MacSetup(iBeacon = true),
                "Does the phone hear cafe0002 by ranging, on the screen?",
            ),
            RunStep(
                "lock",
                "Lock the phone now",
                45,
                RunPhase.LOCK,
                pocket,
                MacSetup(iBeacon = true),
                "Press the side button and leave the phone on the table until it says the run is over.",
            ),
            RunStep(
                "ibeacon_locked",
                "iBeacon while locked",
                120,
                RunPhase.LOCKED,
                pocket,
                MacSetup(iBeacon = true),
                "Does ranging go on with the phone locked (H1)? The Mac hears the frozen mask.",
            ),
            RunStep(
                "token_rotates",
                "The token changes in the background",
                90,
                RunPhase.LOCKED,
                pocket.copy(rotateToken = true),
                MacSetup(),
                "iOS keeps the old advertisement: the Mac should keep decoding the old token (H5).",
            ),
            RunStep(
                "mac_hider_locked",
                "The Mac as a hider while locked",
                90,
                RunPhase.LOCKED,
                pocket,
                MacSetup(advertise = true),
                "Does the locked phone's CoreBluetooth scan hear the Mac's name?",
            ),
        ),
    )

    fun of(version: Int): LabRunScript? = RADIO.takeIf { it.version == version }
}

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

/** A run in progress, or finished, for the screen. */
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
) {
    val step: RunStep? get() = script.steps.getOrNull(index)
    val endsAtServer: Long get() = startAtServer + script.totalMillis
}

/**
 * Runs [script] on the phone (docs/radio-lab-tests.md, «Автоматический прогон»): one button, the steps change by
 * themselves by the server's clock, the Mac follows the same steps (`run.sh --lab --auto`, it hears the run's token).
 * Before the start the phone advertises the run's token as a hider for [leadMillis], so the Mac can join. The tester
 * only locks the phone when told. Main thread.
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
        mutableState.value = LabRunState(script, token, startAt)
        if (noGps) warn("no location permission: without GPS iOS may suspend the app when it is locked")
        log.note("run: script ${script.version}, token $token, starts at ${LabLog.formatUtc(startAt)}")
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
                val previous = script.steps.getOrNull(current)
                current = at.index
                enter(at.index, at.value, previous, token)
            }
            updateHeard(startAt)
            delay(TICK_MILLIS)
        }
        finish(stopped = false)
    }

    private suspend fun enter(index: Int, step: RunStep, previous: RunStep?, token: String) {
        mutableState.value = mutableState.value?.copy(index = index)
        log.mark(
            "run: ${step.id}",
            by = "run",
            step = index + 1,
            place = LabPlaces.TABLE_UP,
            action = LabPlaces.LIE,
        )
        if (step.phase == RunPhase.LOCKED) {
            if (appState() == "active") warn("${step.id}: the phone was not locked")
        } else {
            // The advertisement changes only while the phone is active; the locked steps keep the lock step's.
            if (previous?.phone?.probe != step.phone.probe) controller.setProbe(step.phone.probe)
            val hiderOn = controller.bench.radioMode.value != null
            if (step.phone.hider && !hiderOn) controller.setBenchRadio(asSeeker = false, token = token)
            if (!step.phone.hider && hiderOn) controller.setBenchRadio(null)
        }
        if (step.phone.rotateToken) controller.rotateProbeToken(delayMillis = 0)
        if (step.phase == RunPhase.LOCK) controller.signal("Lock the phone now")
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
    }
}
