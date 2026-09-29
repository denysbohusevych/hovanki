package app.hovanki.client.lab

import app.hovanki.client.diagnostics.Diagnostics
import app.hovanki.client.diagnostics.DiagnosticsBench
import app.hovanki.client.radio.RadioApi
import app.hovanki.client.radio.SightingVia
import app.hovanki.client.session.FakeBackgroundTracker
import app.hovanki.client.session.FakeCarryMonitor
import app.hovanki.client.session.FakeLocationProvider
import app.hovanki.client.session.FakeRadio
import app.hovanki.shared.protocol.Carry
import app.hovanki.shared.rules.OverflowCode
import app.hovanki.shared.rules.OverflowProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LabControllerTest {
    private class FakeAir : LabAir {
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

    private class FakeHaptics : LabHaptics {
        override val kinds = listOf(HapticKind.CORE_HAPTICS, HapticKind.NOTIFY_NO_SOUND)
        val played = mutableListOf<HapticKind>()

        override suspend fun play(kind: HapticKind, strength: Double): HapticResult {
            played += kind
            return HapticResult("played")
        }
    }

    private class FakeFiles : LabFiles {
        val shared = mutableListOf<List<LabFile>>()

        override fun share(files: List<LabFile>) {
            shared += files
        }
    }

    private class Lab(scope: TestScope) {
        val log = LabLog(isEnabled = true, { 1_790_000_000_000L + scope.currentTime }, { scope.currentTime })
        val radio = FakeRadio()
        val location = FakeLocationProvider()
        val tracker = FakeBackgroundTracker()
        val carry = FakeCarryMonitor()
        val air = FakeAir()
        val haptics = FakeHaptics()
        val files = FakeFiles()
        var serverAsks = 0
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
            monotonicMillis = { scope.currentTime },
            random = Random(2),
        )

        fun events(): List<JsonObject> = log.lines().map { Json.parseToJsonElement(it).jsonObject }

        fun kinds(): List<String> = events().map { it[LabFields.K]!!.jsonPrimitive.content }
    }

    @Test
    fun runningRecordsTicksTheClockBluetoothAndTheCarry() = runTest {
        val lab = Lab(this)
        lab.controller.start()
        runCurrent()
        advanceTimeBy(3_500)
        lab.carry.state.value = Carry.IN_POCKET
        runCurrent()

        val kinds = lab.kinds()
        assertEquals("session", kinds.first())
        assertEquals(4, kinds.count { it == "tick" })
        assertTrue("clock" in kinds && "bt" in kinds, "$kinds")
        assertEquals(5, lab.serverAsks)
        assertEquals(700L, lab.log.clock.value?.offsetMillis)
        assertEquals(
            "in_pocket",
            lab.events().last {
                it["k"]!!.jsonPrimitive.content == "carry"
            }["state"]!!.jsonPrimitive.content,
        )

        lab.controller.stop()
        runCurrent()
        val count = lab.log.count.value
        advanceTimeBy(5_000)
        assertEquals(count, lab.log.count.value, "stopped: nothing more")
        assertFalse(lab.controller.running.value)
    }

    @Test
    fun listeningDecodesMasksAndFollowsTheirBand() = runTest {
        val lab = Lab(this)
        lab.controller.start()
        lab.controller.setListening(true)
        runCurrent()
        val bits = OverflowCode.encode("0a1b2c3d")
        lab.air.frames.emit(AirFrame.Mask(bits, "00", -60, "peer-1", 1_790_000_000_000L, RadioApi.ANDROID_LE))
        lab.air.frames.emit(
            AirFrame.Mask(OverflowProbe.PATTERN, null, -70, "peer-2", 1_790_000_000_000L, RadioApi.COREBLUETOOTH),
        )
        runCurrent()

        val masks = lab.events().filter { it["k"]!!.jsonPrimitive.content == "mask" }
        assertEquals(2, masks.size)
        assertTrue("0a1b2c3d" in masks[0]["decoded"].toString())
        val rx = lab.events().single { it["k"]!!.jsonPrimitive.content == "rx" }
        assertEquals(SightingVia.OVERFLOW_RAW.key, rx["via"]!!.jsonPrimitive.content)
        assertTrue("band" in lab.kinds())
    }

    @Test
    fun theProbeAdvertisesTheTokenAndChangesItLater() = runTest {
        val lab = Lab(this)
        lab.controller.start()
        lab.controller.setProbe(ProbeMode.Token)
        runCurrent()
        val first = lab.controller.probeToken.value
        assertEquals(OverflowCode.encode(first), lab.air.advertised.last())

        lab.controller.rotateProbeToken()
        advanceTimeBy(LabController.ROTATE_DELAY_MILLIS + 1)
        runCurrent()
        val second = lab.controller.probeToken.value
        assertNotEquals(first, second)
        assertEquals(OverflowCode.encode(second), lab.air.advertised.last())

        lab.controller.setProbe(ProbeMode.Pattern)
        runCurrent()
        assertEquals(OverflowProbe.PATTERN, lab.air.advertised.last())
        assertTrue(
            lab.events().any {
                it["k"]!!.jsonPrimitive.content == "adv" &&
                    it["mode"]!!.jsonPrimitive.content == "overflow_probe"
            },
        )
    }

    @Test
    fun theVibrationTestPlaysEveryKindInTurn() = runTest {
        val lab = Lab(this)
        lab.controller.start()
        lab.controller.startHapticTest()
        runCurrent()
        advanceTimeBy(LabController.HAPTIC_TEST_LEAD_MILLIS - 1)
        assertEquals(emptyList(), lab.haptics.played, "time to lock the phone first")
        advanceTimeBy(60_000)
        assertEquals(
            List(3) { HapticKind.CORE_HAPTICS } + List(3) { HapticKind.NOTIFY_NO_SOUND } + HapticKind.CORE_HAPTICS,
            lab.haptics.played,
        )
        lab.controller.felt(1)
        val marks = lab.events().filter {
            it["k"]!!.jsonPrimitive.content == "mark"
        }.map { it["label"]!!.jsonPrimitive.content }
        assertEquals(listOf("vibration test: start", "vibration test: over", "felt group 1"), marks)
    }

    @Test
    fun asInAGameRunsTheGpsAndTheBackgroundTracker() = runTest {
        val lab = Lab(this)
        lab.controller.start()
        lab.controller.setInGame(true)
        runCurrent()
        assertTrue(lab.tracker.running)
        assertEquals(1, lab.location.collectors)
        lab.controller.stop()
        runCurrent()
        assertFalse(lab.tracker.running)
        assertEquals(0, lab.location.collectors)
    }

    @Test
    fun theExportSharesTheLogAndItsSummary() = runTest {
        val lab = Lab(this)
        lab.controller.setLabel("droid")
        lab.controller.start()
        runCurrent()
        lab.controller.export()
        val files = lab.files.shared.single()
        assertEquals(listOf("jsonl", "txt"), files.map { it.name.substringAfterLast('.') })
        assertTrue(files[0].name.startsWith("hovanki-lab-droid-"))
        assertTrue("model: Fake 1" in files[1].text)
        assertTrue(files[0].text.lines().count { "\"k\":\"session\"" in it } >= 2, "a header at the start and export")
    }
}
