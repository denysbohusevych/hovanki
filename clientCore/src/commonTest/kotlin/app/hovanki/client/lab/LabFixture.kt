package app.hovanki.client.lab

import app.hovanki.client.diagnostics.Diagnostics
import app.hovanki.client.diagnostics.DiagnosticsBench
import app.hovanki.client.session.FakeBackgroundTracker
import app.hovanki.client.session.FakeCarryMonitor
import app.hovanki.client.session.FakeLocationProvider
import app.hovanki.client.session.FakeRadio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
    val frames = MutableSharedFlow<AirFrame>(extraBufferCapacity = 16)
    val advertised = mutableListOf<Set<Int>>()

    override fun listen(): Flow<AirFrame> = frames

    override fun probe(bits: StateFlow<Set<Int>>): Flow<ProbeEvent> = bits.map {
        advertised += it
        ProbeEvent("start")
    }
}

internal class FakeHaptics : LabHaptics {
    override val kinds = listOf(HapticKind.CORE_HAPTICS, HapticKind.NOTIFY_NO_SOUND)
    val played = mutableListOf<HapticKind>()

    override suspend fun play(kind: HapticKind, strength: Double): HapticResult {
        played += kind
        return HapticResult("played")
    }

    /** What [LabController.signal] told the tester by a notification. */
    val notified = mutableListOf<String>()

    override suspend fun notify(text: String) {
        notified += text
    }
}

internal class FakeFiles : LabFiles {
    val shared = mutableListOf<List<LabFile>>()

    override fun share(files: List<LabFile>) {
        shared += files
    }
}

/** The lab on fake parts, time from the test's scheduler: the server's clock is 700 ms ahead of the device's. */
internal class Lab(scope: TestScope) {
    val log = LabLog(isEnabled = true, { 1_790_000_000_000L + scope.currentTime }, { scope.currentTime })
    val radio = FakeRadio()
    val location = FakeLocationProvider()
    val tracker = FakeBackgroundTracker()
    val carry = FakeCarryMonitor()
    val air = FakeAir()
    val haptics = FakeHaptics()
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
        probes = NoopLabProbes(),
        air = air,
        screen = NoopLabScreen(),
        haptics = haptics,
        files = files,
        radio = radio,
        carryMonitor = carry,
        backgroundTracker = tracker,
        clockSync = LabClockSync(
            serverTime = {
                serverAsks++
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
    )

    fun events(): List<JsonObject> = log.lines().map { Json.parseToJsonElement(it).jsonObject }

    fun kinds(): List<String> = events().map { it[LabFields.K]!!.jsonPrimitive.content }
}
