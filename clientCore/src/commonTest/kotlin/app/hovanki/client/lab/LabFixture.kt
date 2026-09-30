package app.hovanki.client.lab

import app.hovanki.client.diagnostics.Diagnostics
import app.hovanki.client.diagnostics.DiagnosticsBench
import app.hovanki.client.session.FakeBackgroundTracker
import app.hovanki.client.session.FakeCarryMonitor
import app.hovanki.client.session.FakeLocationProvider
import app.hovanki.client.session.FakeRadio
import app.hovanki.device.BackgroundModes
import app.hovanki.device.ModeEvent
import app.hovanki.device.ModeIds
import app.hovanki.device.ModeResult
import app.hovanki.device.lab.HapticKind
import app.hovanki.device.lab.HapticResult
import app.hovanki.device.lab.LabBattery
import app.hovanki.device.lab.LabHaptics
import app.hovanki.device.lab.LabProbes
import app.hovanki.device.lab.LabScreen
import app.hovanki.device.lab.LabSensorReading
import app.hovanki.device.lab.NoopLabProbes
import app.hovanki.device.lab.NoopLabScreen
import app.hovanki.radar.PeerRange
import app.hovanki.radar.PrecisionRadio
import app.hovanki.radar.RadioApi
import app.hovanki.radar.lab.LabAir
import app.hovanki.radar.lab.LabFrame
import app.hovanki.radar.lab.ProbeEvent
import app.hovanki.radar.link.GattLink
import app.hovanki.radar.link.LinkReading
import app.hovanki.radar.link.LinkTrace
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.protocol.UwbPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

internal class FakeAir : LabAir {
    override val canListen = true
    override val canProbe = true
    val frames = MutableSharedFlow<LabFrame>(extraBufferCapacity = 16)
    val advertised = mutableListOf<Set<Int>>()

    override fun listen(): Flow<LabFrame> = frames

    override fun probe(bits: StateFlow<Set<Int>>): Flow<ProbeEvent> = bits.map {
        advertised += it
        ProbeEvent("start")
    }
}

internal class FakeHaptics(
    override val kinds: List<HapticKind> = listOf(HapticKind.CORE_HAPTICS, HapticKind.NOTIFY_NO_SOUND),
) : LabHaptics {
    val played = mutableListOf<HapticKind>()

    /** Kinds that answer `error` (still counted in [played]). */
    val failing = mutableSetOf<HapticKind>()

    override suspend fun play(kind: HapticKind, strength: Double): HapticResult {
        played += kind
        return if (kind in failing) HapticResult("error", "engine stopped") else HapticResult("played")
    }

    /** What [LabController.signal] told the tester by a notification. */
    val notified = mutableListOf<String>()

    override suspend fun notify(text: String) {
        notified += text
    }

    /** What the engine says by itself: the test emits `engine_stopped: …` here. */
    val engine = MutableSharedFlow<Pair<HapticKind, String>>(extraBufferCapacity = 4)

    override fun engineEvents(): Flow<Pair<HapticKind, String>> = engine
}

/** An iPhone's modes without the Live Activity: [on] what is switched on, [events] what they say. */
internal class FakeModes : BackgroundModes {
    override val available = setOf(ModeIds.AUDIO, ModeIds.NOTIFICATION_WAKE)
    val on = mutableSetOf<String>()
    val events = MutableSharedFlow<ModeEvent>(extraBufferCapacity = 16)
    var stoppedAll = 0

    override fun set(id: String, on: Boolean): ModeResult {
        if (on) this.on += id else this.on -= id
        return ModeResult(true)
    }

    override fun events(): Flow<ModeEvent> = events

    override fun stopAll() {
        stoppedAll++
        on.clear()
    }
}

/** A GATT link by hand: [readings] as the platform would emit them, [trace] and [token] of the running link. */
internal class FakeLink : GattLink {
    override val isSupported = true
    val readings = MutableSharedFlow<LinkReading>(extraBufferCapacity = 16)
    var token: StateFlow<String?>? = null
    var trace: LinkTrace? = null
    var running = false

    override fun run(token: StateFlow<String?>, trace: LinkTrace): Flow<LinkReading> = readings
        .onStart {
            this@FakeLink.token = token
            this@FakeLink.trace = trace
            running = true
        }.onCompletion { running = false }

    fun reading(peer: String, token: String?, rssi: Int?) =
        readings.tryEmit(LinkReading(peer, token, rssi, 0L, RadioApi.UNKNOWN))
}

/** An iPhone's UWB by hand: its [token] after [prepare], the [peers] of the running [range], [ranges] emitted. */
internal class FakePrecision(override val isSupported: Boolean = true) : PrecisionRadio {
    override val token = MutableStateFlow<String?>(null)
    val ranges = MutableSharedFlow<PeerRange>(extraBufferCapacity = 16)
    var peers: StateFlow<List<UwbPeer>>? = null
    var prepared = 0

    override fun prepare() {
        prepared++
        if (token.value == null) token.value = "uwb-token-1"
    }

    override fun range(peers: StateFlow<List<UwbPeer>>): Flow<PeerRange> = ranges
        .onStart { this@FakePrecision.peers = peers }
        .onCompletion { this@FakePrecision.peers = null }
}

/** The phone's sensors by hand: [readings] as the platform would emit them, [state] the app's state now. */
internal class FakeProbes(var state: String = "screen_on") : LabProbes {
    val readings = MutableSharedFlow<LabSensorReading>(extraBufferCapacity = 256)
    val life = MutableSharedFlow<String>(extraBufferCapacity = 16)

    override fun appState(): String = state

    override fun lifecycle(): Flow<String> = life

    override fun sensors(): Flow<LabSensorReading> = readings

    override fun battery(): Flow<LabBattery> = emptyFlow()
}

/** A screen the proximity sensor can turn off. */
internal class FakeScreen : LabScreen {
    override val canTurnOffByProximity = true
    var turnedOff = false

    override fun setOffByProximity(on: Boolean) {
        turnedOff = on
    }
}

internal class FakeFiles : LabFiles {
    val shared = mutableListOf<List<LabFile>>()

    override fun share(files: List<LabFile>) {
        shared += files
    }
}

/**
 * The lab on fake parts, time from the test's scheduler: the server's clock is 700 ms ahead of the device's; with
 * [clockWorks] false the server never answers the clock's questions; [labAir]: the lab's air on its log instead of
 * [FakeAir]; [probes] and [screen]: the phone's sensors and screen (none by default); [hapticKinds]: what the fake
 * haptics can play; [precisionSupported]: the fake UWB radio has the chip.
 */
internal class Lab(
    scope: TestScope,
    clockWorks: Boolean = true,
    probes: LabProbes = NoopLabProbes(),
    screen: LabScreen = NoopLabScreen(),
    hapticKinds: List<HapticKind>? = null,
    precisionSupported: Boolean = false,
    labAir: ((LabLog) -> LabAir)? = null,
) {
    val log = LabLog(isEnabled = true, { 1_790_000_000_000L + scope.currentTime }, { scope.currentTime })
    val radio = FakeRadio()
    val location = FakeLocationProvider()
    val tracker = FakeBackgroundTracker()
    val carry = FakeCarryMonitor()
    val air = FakeAir()
    val haptics = hapticKinds?.let(::FakeHaptics) ?: FakeHaptics()
    val modes = FakeModes()
    val link = FakeLink()
    val precision = FakePrecision(precisionSupported)
    val files = FakeFiles()
    var serverAsks = 0
    val inAGame = MutableStateFlow(false)
    val bench = DiagnosticsBench(
        radio,
        location,
        Diagnostics(isEnabled = true),
        scope.backgroundScope,
        Random(1),
        log,
    )
    val controller = LabController(
        log = log,
        bench = bench,
        probes = probes,
        air = labAir?.invoke(log) ?: air,
        screen = screen,
        haptics = haptics,
        files = files,
        radio = radio,
        carryMonitor = carry,
        backgroundTracker = tracker,
        clockSync = LabClockSync(
            serverTime = {
                serverAsks++
                check(clockWorks) { "offline" }
                1_790_000_000_000L + scope.currentTime + 700
            },
            deviceTimeMillis = { 1_790_000_000_000L + scope.currentTime },
            monotonicMillis = { scope.currentTime },
        ),
        about = { LabAbout("Fake 1", "Android 16", "1.0 (1)", "abc123") },
        scope = scope.backgroundScope as CoroutineScope,
        inAGame = inAGame,
        monotonicMillis = { scope.currentTime },
        random = Random(2),
        modes = modes,
        link = link,
        precision = precision,
    )

    fun events(): List<JsonObject> = log.lines().map { Json.parseToJsonElement(it).jsonObject }

    fun kinds(): List<String> = events().map { it[LabFields.K]!!.jsonPrimitive.content }
}
