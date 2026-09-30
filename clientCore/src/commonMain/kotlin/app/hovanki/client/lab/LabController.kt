package app.hovanki.client.lab

import app.hovanki.client.diagnostics.DiagnosticsBench
import app.hovanki.client.tracking.BackgroundTracker
import app.hovanki.device.ActivityClassifier
import app.hovanki.device.CarryClassifier
import app.hovanki.device.CarryInputs
import app.hovanki.device.CarryMonitor
import app.hovanki.device.Gravity
import app.hovanki.device.Orientation
import app.hovanki.device.lab.HapticKind
import app.hovanki.device.lab.ImpactDetector
import app.hovanki.device.lab.LabHaptics
import app.hovanki.device.lab.LabProbes
import app.hovanki.device.lab.LabScreen
import app.hovanki.device.lab.LabSensorReading
import app.hovanki.device.lab.MotionFeatures
import app.hovanki.device.lab.MotionWindow
import app.hovanki.radar.ProximityRadio
import app.hovanki.radar.RadarCatalog
import app.hovanki.radar.RadioApi
import app.hovanki.radar.SightingVia
import app.hovanki.radar.channel.overflow.OverflowChannel
import app.hovanki.radar.lab.LabAir
import app.hovanki.radar.lab.LabFrame
import app.hovanki.shared.lab.ProbeMode
import app.hovanki.shared.lab.RunStep
import app.hovanki.shared.rules.HeartbeatRules
import app.hovanki.shared.rules.OverflowArea
import app.hovanki.shared.rules.OverflowCode
import app.hovanki.shared.rules.OverflowProbe
import app.hovanki.shared.rules.RadarToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** Who this device is, for the log's `session` event and the export's summary. */
data class LabAbout(val model: String?, val os: String?, val build: String?, val commit: String?) {
    val lines: List<String>
        get() = listOfNotNull(
            model?.let { "model: $it" },
            os?.let { "os: $it" },
            build?.let { "build: $it" },
            commit?.let { "commit: $it" },
        )
}

/** The pulse the lab beats from its own loudest band: by haptics, by a notification, or off. */
enum class LabPulse { OFF, HAPTICS, NOTIFICATION }

/**
 * The radio lab (docs/radio-lab.md): the debug build's experiments, each behind a switch, all written into [log]. It
 * runs outside a game only, like the [bench] it builds on (the owner stops it when a game appears). While it runs
 * ([start]): a tick a second, the clock measured against the server's, the app's life, the sensors, the battery, the
 * Bluetooth state and the game's carry monitor. The switches: «as in a game» ([setInGame]: GPS in the background as in
 * a round), the bench's radio, the overflow probe ([setProbe]), listening to everything ([setListening]), the screen
 * off by the proximity sensor, the vibration test, the pulse, marks and scenarios. Besides, the knocks the
 * accelerometer feels (`impact`, for the touch calibration, [touched]) and, once a second, `carry.v2` in the shadow of
 * the game's carry monitor ([CarryClassifier]). Nothing of it changes the game. Main thread.
 */
class LabController(
    val log: LabLog,
    val bench: DiagnosticsBench,
    private val probes: LabProbes,
    private val air: LabAir,
    private val screen: LabScreen,
    private val haptics: LabHaptics,
    private val files: LabFiles,
    private val radio: ProximityRadio,
    private val carryMonitor: CarryMonitor,
    private val backgroundTracker: BackgroundTracker,
    private val clockSync: LabClockSync,
    private val about: () -> LabAbout,
    private val scope: CoroutineScope,
    /** True while the phone is in a game: the lab stops then, whether its screen is open or not. */
    private val inAGame: Flow<Boolean> = flowOf(false),
    private val monotonicMillis: () -> Long = log::monoNow,
    private val random: Random = Random.Default,
) {
    private val mutableRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    private val mutableInGame = MutableStateFlow(false)

    /** «As in a game»: GPS every second, allowed in the background, as in a round. */
    val inGame: StateFlow<Boolean> = mutableInGame.asStateFlow()

    private val mutableProbe = MutableStateFlow<ProbeMode?>(null)
    val probe: StateFlow<ProbeMode?> = mutableProbe.asStateFlow()

    private val probeBits = MutableStateFlow<Set<Int>>(emptySet())
    private val mutableProbeToken = MutableStateFlow(newToken())

    /** The token the probe advertises in [ProbeMode.Token]. */
    val probeToken: StateFlow<String> = mutableProbeToken.asStateFlow()

    private val mutableRotateAt = MutableStateFlow<Long?>(null)

    /** When the probe's token will change (monotonic); null: no change pending. */
    val rotateAt: StateFlow<Long?> = mutableRotateAt.asStateFlow()

    private val mutableTechniques = MutableStateFlow<Set<String>>(emptySet())

    /** The channels the bench's radio runs ([setTechniques]); empty: the game's. */
    val techniques: StateFlow<Set<String>> = mutableTechniques.asStateFlow()

    private val mutableListening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = mutableListening.asStateFlow()

    private val mutableScreenOff = MutableStateFlow(false)
    val screenOff: StateFlow<Boolean> = mutableScreenOff.asStateFlow()

    private val mutablePulse = MutableStateFlow(LabPulse.OFF)
    val pulse: StateFlow<LabPulse> = mutablePulse.asStateFlow()

    private val mutableHapticTest = MutableStateFlow<String?>(null)

    /** The vibration test's progress for the screen; null: not running. */
    val hapticTest: StateFlow<String?> = mutableHapticTest.asStateFlow()

    private val mutableFelt = MutableStateFlow<Set<Int>>(emptySet())

    /** The groups of the last vibration test the tester marked as felt ([toggleFelt]). */
    val felt: StateFlow<Set<Int>> = mutableFelt.asStateFlow()

    private val mutableElapsed = MutableSharedFlow<LabStep>(extraBufferCapacity = 4)

    /** A scenario's step is over: the next one starts by itself, with a signal. */
    val elapsed: SharedFlow<LabStep> = mutableElapsed.asSharedFlow()

    val scenarios = LabScenarioRunner(
        autoAdvance = true,
        nowMillis = monotonicMillis,
        onStep = { scenario, index, step ->
            log.mark(
                label = "${scenario.id}: ${step.label}",
                by = "scenario",
                step = index + 1,
                place = step.place,
                action = step.action,
                distance = step.distance,
            )
            // The step changed by itself: tell the tester what to do now, by a notification when locked.
            if (index > 0) {
                val text = listOf(step.label, step.hint).filter { it.isNotEmpty() }.joinToString(". ")
                scope.launch { signal(text) }
            }
        },
        onElapsed = { step -> mutableElapsed.tryEmit(step) },
        onFinished = {
            log.mark("${it.id}: done", by = "scenario")
            scope.launch { signal("${it.title}: done") }
        },
    )

    /** Who this device is: for the log's header, and for a run on the server when the phone joins it. */
    val device: LabAbout get() = about()

    val canProbe: Boolean get() = air.canProbe
    val canListen: Boolean get() = air.canListen
    val canTurnScreenOff: Boolean get() = screen.canTurnOffByProximity
    val hapticKinds: List<HapticKind> get() = haptics.kinds

    private val jobs = ArrayList<Job>()
    private var probeJob: Job? = null
    private var rotateJob: Job? = null
    private var listenJob: Job? = null
    private var pulseJob: Job? = null
    private var hapticTestJob: Job? = null
    private var ticks = 0L

    /** Starts recording: the tick, the clock, the life, the sensors, the battery, Bluetooth and the carry monitor. */
    fun start() {
        if (mutableRunning.value) return
        mutableRunning.value = true
        log.appState = probes::appState
        log.isRecording = true
        writeSession()
        jobs += scope.launch { tickLoop() }
        jobs += scope.launch { clockLoop() }
        jobs += scope.launch {
            probes.lifecycle().collect {
                log.life(it)
                screenOnOf(it)?.let { on -> lifeScreenOn = on }
            }
        }
        jobs += scope.launch { sensorLoop() }
        jobs += scope.launch { probes.battery().collect { log.battery(it.level, it.state, it.lowPower) } }
        jobs += scope.launch { radio.state.collect { log.bt(it.name.lowercase()) } }
        jobs += scope.launch { carryMonitor.carry().collect { log.carry(it.name.lowercase()) } }
        jobs += scope.launch {
            // «engine_stopped: audio_session_interrupt (1)»: the result and the reason, so the report counts the stops.
            haptics.engineEvents().collect { (kind, event) ->
                val result = event.substringBefore(':').trim()
                val reason = event.substringAfter(':', "").trim().ifEmpty { null }
                log.haptic(kind.key, result, reason = reason)
            }
        }
        jobs += scope.launch {
            inAGame.first { it }
            log.note("a game started: the lab stops")
            stop()
        }
    }

    /** Everything off, the log kept for the export. */
    fun stop() {
        if (!mutableRunning.value) return
        scenarios.stop()
        setProbe(null)
        setListening(false)
        setScreenOff(false)
        setPulse(LabPulse.OFF)
        stopHapticTest()
        setInGame(false)
        bench.stopRadio()
        setTechniques(emptySet())
        log.note("lab stopped")
        jobs.forEach { it.cancel() }
        jobs.clear()
        resetSensors()
        log.isRecording = false
        mutableRunning.value = false
    }

    fun setLabel(label: String) {
        log.setLabel(label)
        if (mutableRunning.value) writeSession()
    }

    fun setInGame(on: Boolean) {
        if (on == mutableInGame.value) return
        if (on) {
            if (!bench.startGps()) {
                log.note("as in a game: no location permission")
                return
            }
            backgroundTracker.start()
        } else {
            bench.stopGps()
            backgroundTracker.stop()
        }
        mutableInGame.value = on
        log.note("as in a game ${if (on) "on" else "off"}")
    }

    /**
     * The game's radio on the bench as a hider or a seeker ([token]: the bench's own unless given), with the channels
     * of [techniques]; null: off.
     */
    fun setBenchRadio(asSeeker: Boolean?, token: String? = null) {
        if (asSeeker == null) {
            bench.stopRadio()
        } else {
            bench.startRadio(asSeeker, token ?: bench.token, mutableTechniques.value)
        }
    }

    /**
     * The channels the bench's radio runs from now on, by id (`RadarCatalog`; a step of a run names them); empty: the
     * game's. An id this build doesn't know is noted and left out. A radio on the bench starts again with them.
     */
    fun setTechniques(ids: Set<String>) {
        val known = ids.filterTo(LinkedHashSet()) { RadarCatalog.byId(it) != null }
        val unknown = ids - known
        if (unknown.isNotEmpty()) log.note("unknown techniques ${unknown.sorted().joinToString(",")}: left out")
        if (known == mutableTechniques.value) return
        mutableTechniques.value = known
        log.note("techniques ${known.sorted().joinToString(",").ifEmpty { "of the game" }}")
        val running = bench.radioMode.value ?: return
        bench.startRadio(running.asSeeker, bench.radioToken ?: bench.token, known)
    }

    fun setProbe(mode: ProbeMode?) {
        rotateJob?.cancel()
        rotateJob = null
        mutableRotateAt.value = null
        mutableProbe.value = mode
        if (mode == null) {
            probeJob?.cancel()
            probeJob = null
            return
        }
        probeBits.value = bitsOf(mode)
        if (probeJob == null) {
            probeJob = scope.launch {
                try {
                    air.probe(probeBits).collect { event ->
                        log.adv(
                            event.action,
                            "overflow_probe",
                            token = probeTokenFor(mutableProbe.value),
                            payload = probeBits.value.sorted().joinToString(","),
                            error = event.error,
                            tech = PROBE_TECH,
                            layout = event.layout,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.adv("failed", "overflow_probe", error = e.message ?: e::class.simpleName)
                }
            }
        }
    }

    /**
     * The probe advertises [token] from now on in [ProbeMode.Token] (a run on the server gives every device its own); a
     * change pending by [rotateProbeToken] is called off.
     */
    fun setProbeToken(token: String) {
        rotateJob?.cancel()
        rotateJob = null
        mutableRotateAt.value = null
        if (token == mutableProbeToken.value) return
        mutableProbeToken.value = token
        log.note("probe token now $token")
        if (mutableProbe.value == ProbeMode.Token) probeBits.value = bitsOf(ProbeMode.Token)
    }

    /** The probe's token changes in [delayMillis] (H5: does the mask survive a change while locked?). */
    fun rotateProbeToken(delayMillis: Long = ROTATE_DELAY_MILLIS) {
        rotateJob?.cancel()
        mutableRotateAt.value = monotonicMillis() + delayMillis
        log.note("probe token changes in ${delayMillis / 1000} s")
        rotateJob = scope.launch {
            delay(delayMillis)
            mutableProbeToken.value = newToken()
            mutableRotateAt.value = null
            log.note("probe token now ${mutableProbeToken.value}")
            mutableProbe.value?.let { probeBits.value = bitsOf(it) }
        }
    }

    fun setListening(on: Boolean) {
        if (on == mutableListening.value) return
        mutableListening.value = on
        listenJob?.cancel()
        listenJob = null
        if (!on) {
            log.scan("stop", RadioApi.UNKNOWN, "lab: everything")
            return
        }
        log.scan("start", RadioApi.UNKNOWN, "lab: everything")
        listenJob = scope.launch {
            try {
                air.listen().collect { frame ->
                    when (frame) {
                        is LabFrame.Mask -> {
                            val decoded = OverflowCode.decode(frame.bits)
                            log.mask(frame.bits, frame.rssi, frame.api, frame.hex, frame.peer, decoded)
                            // A decoded token is a reading like any other: the lab's band follows it.
                            decoded.singleOrNull()?.let { token ->
                                val via = overflowVia(frame)
                                log.rx(token, frame.rssi, frame.api, via, frame.peer, frame.atMillis, PROBE_TECH)
                            }
                        }

                        is LabFrame.Token -> log.rx(
                            frame.token,
                            frame.rssi,
                            frame.api,
                            frame.via,
                            frame.peer,
                            frame.atMillis,
                            frame.tech,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.note("listening failed: ${e.message ?: e::class.simpleName}")
                mutableListening.value = false
            }
        }
    }

    fun setScreenOff(on: Boolean) {
        if (on == mutableScreenOff.value) return
        screen.setOffByProximity(on)
        mutableScreenOff.value = on
        log.note("screen off by proximity ${if (on) "on" else "off"}")
    }

    fun setPulse(pulse: LabPulse) {
        if (pulse == mutablePulse.value) return
        pulseJob?.cancel()
        pulseJob = null
        mutablePulse.value = pulse
        log.note("pulse ${pulse.name.lowercase()}")
        val kind = when (pulse) {
            LabPulse.OFF -> return
            LabPulse.HAPTICS -> haptics.kinds.firstOrNull { !it.isNotification } ?: return
            LabPulse.NOTIFICATION -> haptics.kinds.firstOrNull { it == HapticKind.NOTIFY_SILENT_SOUND } ?: return
        }
        pulseJob = scope.launch { pulseLoop(kind) }
    }

    /**
     * The vibration test (H2): [leadMillis] to lock the phone and put it away, then every kind in turn, group N with N
     * beats, so the tester tells a group by its count even when another group stays silent: 1 Core Haptics, 2 impact,
     * 3 a notification with a silent sound, 4 one without. [HAPTIC_TEST_PAUSE_MILLIS] between the groups; at the end a
     * notification with text says the test is over. The tester marks what they felt afterwards ([toggleFelt]).
     */
    fun startHapticTest(leadMillis: Long = HAPTIC_TEST_LEAD_MILLIS) {
        stopHapticTest()
        val kinds = haptics.kinds
        if (kinds.isEmpty()) {
            log.note("vibration test: no haptics here")
            return
        }
        mutableFelt.value = emptySet()
        hapticTestJob = scope.launch {
            try {
                haptics.prepare()
                log.mark("vibration test: start", by = "lab")
                if (leadMillis > 0) signal("Lock the phone now: the vibration test starts in ${leadMillis / 1000} s")
                countdown(leadMillis) { "lock the phone: ${it}s" }
                for ((index, kind) in kinds.withIndex()) {
                    val group = index + 1
                    mutableHapticTest.value = "group $group: ${kind.key}, $group ${if (group == 1) "beat" else "beats"}"
                    repeat(group) {
                        val result = haptics.play(kind)
                        log.haptic(kind.key, result.result, result.error, group = group)
                        delay(if (kind.isNotification) NOTIFICATION_GAP_MILLIS else HAPTIC_BEAT_GAP_MILLIS)
                    }
                    delay(HAPTIC_TEST_PAUSE_MILLIS)
                }
                log.mark("vibration test: over", by = "lab")
                haptics.notify("Vibration test over: unlock and mark how many beats you felt in each burst")
            } finally {
                mutableHapticTest.value = null
            }
        }
    }

    fun stopHapticTest() {
        hapticTestJob?.cancel()
        hapticTestJob = null
        mutableHapticTest.value = null
    }

    /** The tester felt the vibration test's group [group] (its count of beats), or takes it back. */
    fun toggleFelt(group: Int) {
        val felt = group !in mutableFelt.value
        mutableFelt.value = if (felt) mutableFelt.value + group else mutableFelt.value - group
        log.mark(if (felt) "felt group $group" else "not felt group $group", by = "tester", step = group)
    }

    /** A mark by hand: the distance, the place, what the tester does, or any text. */
    fun mark(label: String, place: String? = null, action: String? = null, distance: Double? = null) =
        log.mark(label, by = "tester", place = place, action = action, distance = distance)

    /**
     * «Touched with [otherLabel]»: the tester knocked this phone back to back with that one just now, the truth of the
     * touch calibration (docs/adr/0017-radar-techniques-and-big-run.md §3): a mark `touch <pair>` (the pair's key,
     * [RunStep.pairKey], of this device's label and [otherLabel]) with the action `touch`. The report finds the touch
     * itself from both phones' `impact` events and the RSSI; this mark tells it which ones were real. False: no mark
     * (no label, or this device's own).
     */
    fun touched(otherLabel: String): Boolean {
        val other = otherLabel.trim()
        val own = log.label.value
        if (other.isEmpty() || other == own) return false
        log.mark("$TOUCH_ACTION ${RunStep.pairKey(own, other)}", by = "user", action = TOUCH_ACTION)
        return true
    }

    /** Measures the clock, writes the header again and hands the log and its summary to the system «Share». */
    suspend fun export() {
        measureClock()
        writeSession()
        val export = log.export(about().lines)
        files.share(
            listOf(
                LabFile(export.fileName, "application/x-ndjson", export.jsonl),
                LabFile(export.summaryName, "text/plain", export.summary),
            ),
        )
    }

    fun clear() {
        log.clear()
        if (mutableRunning.value) writeSession()
    }

    private fun writeSession() {
        val about = about()
        val mode = listOfNotNull(
            "in_game".takeIf { mutableInGame.value },
            bench.radioMode.value?.let { if (it.asSeeker) "bench_seeker" else "bench_hider" },
            mutableProbe.value?.let { "probe" },
            "listen".takeIf { mutableListening.value },
            "screen_off".takeIf { mutableScreenOff.value },
        ).joinToString(",").ifEmpty { null }
        log.session(about.model, about.os, about.build, about.commit, mode)
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            log.tick(ticks++)
            scenarios.tick()
            motionSecond()
            carrySecond()
            delay(TICK_MILLIS)
        }
    }

    private suspend fun clockLoop() {
        while (currentCoroutineContext().isActive) {
            measureClock()
            delay(LabClockSync.EVERY_MILLIS)
        }
    }

    private suspend fun measureClock() {
        val estimate = withTimeoutOrNull(CLOCK_TIMEOUT_MILLIS) { clockSync.measure() }
        if (estimate == null) log.clockEvent(failed = true) else log.setClock(estimate)
    }

    private val motionWindow = MotionWindow()
    private val activity = ActivityClassifier()
    private val impacts = ImpactDetector()
    private var carryV2 = CarryClassifier()
    private var gravity: Gravity? = null
    private var lastMotionAt: Long? = null

    /** When the last motion reading came, by [monotonicMillis]: the sensors' own clock can't tell how old it is. */
    private var lastMotionMono: Long? = null
    private var near: Boolean? = null
    private var lux: Double? = null

    /** The screen by the last life event (`screen_on`, `did_enter_background`…), where the app's state says nothing. */
    private var lifeScreenOn: Boolean? = null

    private suspend fun sensorLoop() {
        probes.sensors().collect { reading ->
            when (reading) {
                is LabSensorReading.Motion -> {
                    motionWindow.add(reading.atMillis, reading.magnitudeG)
                    activity.add(reading.atMillis, reading.magnitudeG * STANDARD_GRAVITY)
                    reading.gravity?.let { gravity = it }
                    lastMotionAt = reading.atMillis
                    lastMotionMono = monotonicMillis()
                    // Told at least [ImpactDetector.PEAK_MILLIS] after the knock: how long ago on the sensors' clock.
                    impacts.add(reading.atMillis, reading.magnitudeG)?.let { impact ->
                        log.impact(impact.peakG, reading.atMillis - impact.atMillis)
                    }
                }

                is LabSensorReading.Proximity -> {
                    near = reading.near
                    log.prox(reading.near, reading.rawCm, reading.maxCm, reading.monitoring)
                }

                is LabSensorReading.Light -> {
                    lux = reading.lux
                    log.light(reading.lux)
                }
            }
        }
    }

    /** Once a second: the motion of the last seconds, when the sensors said anything. */
    private fun motionSecond() {
        if (lastMotionAt == null) return
        val gravity = gravity
        log.motion(MotionFeatures(motionWindow.std(), gravity, gravity?.let(Orientation::of), activity.classify()))
    }

    /**
     * Once a second: `carry.v2` in the shadow ([CarryClassifier]) from this second's screen (the app's state, else the
     * last life event), the lab's «screen off by proximity» switch, the last proximity and light, and the motion as
     * `motion` has it (none when the sensors said nothing for [MOTION_STALE_MILLIS]: the classifier keeps its state).
     * Nothing when the lab knows neither the screen nor the motion.
     */
    private fun carrySecond() {
        val screenOn = screenOnOf(probes.appState()) ?: lifeScreenOn
        val motionMono = lastMotionMono
        val fresh = motionMono != null && monotonicMillis() - motionMono <= MOTION_STALE_MILLIS
        if (screenOn == null && !fresh) return
        val gravity = gravity.takeIf { fresh }
        val verdict = carryV2.add(
            CarryInputs(
                atMillis = monotonicMillis(),
                screenOn = screenOn,
                screenOffByProximity = mutableScreenOff.value,
                near = near,
                lux = lux,
                orientation = gravity?.let(Orientation::of),
                std = if (fresh) motionWindow.std() else null,
                activity = if (fresh) activity.classify() else null,
            ),
        )
        log.shadow(CARRY_V2, verdict.carry.name.lowercase(), verdict.reason)
    }

    /** Everything the sensors said, forgotten when the lab stops: a new start doesn't read old knocks or motion. */
    private fun resetSensors() {
        motionWindow.clear()
        impacts.clear()
        gravity = null
        lastMotionAt = null
        lastMotionMono = null
        near = null
        lux = null
        lifeScreenOn = null
        carryV2 = CarryClassifier()
    }

    private suspend fun pulseLoop(kind: HapticKind) {
        var lastNotification: Long? = null
        while (currentCoroutineContext().isActive) {
            val beat = HeartbeatRules.beat(log.strongestBand())
            if (beat == null) {
                delay(PULSE_IDLE_MILLIS)
                continue
            }
            val now = monotonicMillis()
            val last = lastNotification
            if (!kind.isNotification || last == null || now - last >= NOTIFICATION_GAP_MILLIS) {
                val result = haptics.play(kind, beat.strongAmplitude)
                if (kind.isNotification) lastNotification = now
                if (result.result != "played") log.haptic(kind.key, result.result, result.error, reason = "pulse")
            }
            delay(beat.periodMillis)
        }
    }

    /**
     * Tells the tester something happened: two taps on the screen and, off it, a notification with [text] (the taps
     * may not get through there, docs/radio-lab.md H2).
     */
    suspend fun signal(text: String? = null) {
        if (text != null && probes.appState() != "active" && probes.appState() != "screen_on") haptics.notify(text)
        beepOnScreen()
    }

    private suspend fun beepOnScreen() {
        val kind = haptics.kinds.firstOrNull { !it.isNotification } ?: return
        repeat(2) {
            haptics.play(kind)
            delay(HAPTIC_BEAT_GAP_MILLIS)
        }
    }

    private suspend fun countdown(millis: Long, text: (Long) -> String) {
        var left = millis
        while (left > 0) {
            mutableHapticTest.value = text((left + 999) / 1000)
            val step = minOf(TICK_MILLIS, left)
            delay(step)
            left -= step
        }
    }

    private fun bitsOf(mode: ProbeMode): Set<Int> = when (mode) {
        ProbeMode.Pattern -> OverflowProbe.PATTERN
        is ProbeMode.Bit -> OverflowProbe.single(mode.bit)
        ProbeMode.Token -> OverflowCode.encode(mutableProbeToken.value)
    }

    private fun probeTokenFor(mode: ProbeMode?): String? = mutableProbeToken.value.takeIf { mode == ProbeMode.Token }

    private fun overflowVia(frame: LabFrame.Mask) = if (frame.hex != null) {
        SightingVia.OVERFLOW_RAW
    } else {
        SightingVia.OVERFLOW_UUIDS
    }

    private fun newToken(): String = random.nextBytes(RadarToken.LENGTH / 2).joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    companion object {
        const val TICK_MILLIS = 1_000L
        const val ROTATE_DELAY_MILLIS = 60_000L
        const val CLOCK_TIMEOUT_MILLIS = 10_000L
        const val HAPTIC_TEST_LEAD_MILLIS = 15_000L
        const val HAPTIC_TEST_PAUSE_MILLIS = 6_000L
        const val HAPTIC_BEAT_GAP_MILLIS = 700L

        /** Notifications at most this often: iOS piles up more. */
        const val NOTIFICATION_GAP_MILLIS = 4_000L
        const val PULSE_IDLE_MILLIS = 500L
        private const val STANDARD_GRAVITY = 9.81

        /** The mark's action of «touched with …» ([touched]) and the first word of its label. */
        const val TOUCH_ACTION = "touch"

        /** The classifier the lab runs in the shadow of the game's carry monitor (`shadow` events' `tech`). */
        const val CARRY_V2 = "carry.v2"

        /** Motion readings older than this are none: the platform stopped the sensors (the app in the background). */
        const val MOTION_STALE_MILLIS = 3_000L

        /**
         * The screen by the app's state or a life event: lit on `screen_on` (Android) and `active` (iOS: the app in
         * front; the proximity sensor may still have turned it off, the lab's switch says); dark on `screen_off`,
         * `inactive`, `background`, `did_enter_background`, `will_resign`; null: says nothing of the screen.
         */
        internal fun screenOnOf(state: String): Boolean? = when (state) {
            "screen_on", "active", "did_become_active" -> true
            "screen_off", "inactive", "background", "did_enter_background", "will_resign" -> false
            else -> null
        }

        /** The overflow channel's id: the probe's `adv` and a mask's reading carry it. */
        private val PROBE_TECH = OverflowChannel.id

        /** The bits no probe may set, for the screen's bit picker. */
        val FORBIDDEN_BITS: Set<Int> = setOf(OverflowArea.APPLE_WATCH_BIT)
    }
}
