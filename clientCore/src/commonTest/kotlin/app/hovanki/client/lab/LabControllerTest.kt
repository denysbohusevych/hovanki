package app.hovanki.client.lab

import app.hovanki.device.Gravity
import app.hovanki.device.lab.HapticKind
import app.hovanki.device.lab.ImpactDetector
import app.hovanki.device.lab.LabSensorReading
import app.hovanki.radar.RadioApi
import app.hovanki.radar.SightingVia
import app.hovanki.radar.lab.LabFrame
import app.hovanki.shared.lab.ProbeMode
import app.hovanki.shared.protocol.Carry
import app.hovanki.shared.rules.OverflowCode
import app.hovanki.shared.rules.OverflowProbe
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LabControllerTest {
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
    fun anEngineStopIsWrittenWithItsReason() = runTest {
        val lab = Lab(this)
        lab.controller.start()
        runCurrent()
        lab.haptics.engine.tryEmit(HapticKind.CORE_HAPTICS to "engine_stopped: audio_session_interrupt (1)")
        runCurrent()

        val haptic = lab.events().last { it["k"]!!.jsonPrimitive.content == "haptic" }
        assertEquals("core_haptics", haptic["kind"]!!.jsonPrimitive.content)
        assertEquals("engine_stopped", haptic["result"]!!.jsonPrimitive.content)
        assertEquals("audio_session_interrupt (1)", haptic["reason"]!!.jsonPrimitive.content)
        lab.controller.stop()
    }

    @Test
    fun aGameStopsTheLab() = runTest {
        val lab = Lab(this)
        lab.controller.start()
        lab.controller.setInGame(true)
        runCurrent()
        lab.inAGame.value = true
        runCurrent()
        assertFalse(lab.controller.running.value)
        assertFalse(lab.tracker.running)
    }

    @Test
    fun listeningDecodesMasksAndFollowsTheirBand() = runTest {
        val lab = Lab(this)
        lab.controller.start()
        lab.controller.setListening(true)
        runCurrent()
        val bits = OverflowCode.encode("0a1b2c3d")
        lab.air.frames.emit(LabFrame.Mask(bits, "00", -60, "peer-1", 1_790_000_000_000L, RadioApi.ANDROID_LE))
        lab.air.frames.emit(
            LabFrame.Mask(OverflowProbe.PATTERN, null, -70, "peer-2", 1_790_000_000_000L, RadioApi.COREBLUETOOTH),
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
    fun theVibrationTestTellsItsGroupsByTheirCount() = runTest {
        val lab = Lab(this)
        lab.controller.start()
        lab.controller.startHapticTest()
        runCurrent()
        advanceTimeBy(LabController.HAPTIC_TEST_LEAD_MILLIS - 1)
        val signal = List(2) { HapticKind.CORE_HAPTICS }
        assertEquals(signal, lab.haptics.played, "only the signal to lock the phone first")
        advanceTimeBy(60_000)
        assertEquals(
            signal + HapticKind.CORE_HAPTICS + List(2) { HapticKind.NOTIFY_NO_SOUND },
            lab.haptics.played,
            "group N: N beats",
        )
        assertTrue(lab.haptics.notified.last().startsWith("Vibration test over"))
        lab.controller.toggleFelt(2)
        lab.controller.toggleFelt(1)
        lab.controller.toggleFelt(2)
        assertEquals(setOf(1), lab.controller.felt.value)
        val marks = lab.events().filter {
            it["k"]!!.jsonPrimitive.content == "mark"
        }.map { it["label"]!!.jsonPrimitive.content }
        assertEquals(
            listOf("vibration test: start", "vibration test: over", "felt group 2", "felt group 1", "not felt group 2"),
            marks,
        )
        lab.controller.startHapticTest()
        assertEquals(emptySet(), lab.controller.felt.value, "a new test starts clean")
    }

    @Test
    fun aScenarioStepsOnByItselfAndSaysWhatToDoNext() = runTest {
        val lab = Lab(this)
        lab.controller.start()
        lab.controller.scenarios.start(LabScenarios.E6)
        runCurrent()
        advanceTimeBy(61_000)
        assertEquals(1, lab.controller.scenarios.run.value?.index)
        assertEquals(listOf("locked. Lock."), lab.haptics.notified, "off the screen: a notification with the step")
        advanceTimeBy(360_000)
        assertEquals(null, lab.controller.scenarios.run.value)
        assertEquals("E6 / E9 iBeacon ranging while locked: done", lab.haptics.notified.last())
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

    @Test
    fun aKnockWritesAnImpact() = runTest {
        val probes = FakeProbes()
        val lab = Lab(this, probes = probes)
        lab.controller.start()
        runCurrent()
        // 50 Hz on the sensors' own clock, far from the device's: a knock of 2.1 g at 5 020.
        for (at in 5_000L..5_400L step 20) {
            val magnitude = if (at == 5_020L) 2.1 else 1.0
            probes.readings.emit(LabSensorReading.Motion(at, magnitude, Gravity(0.0, 0.0, -1.0)))
        }
        runCurrent()

        val impact = lab.events().single { it.kind == "impact" }
        assertEquals(1.1, impact["peak"]!!.jsonPrimitive.double)
        // Told by the first reading at least PEAK_MILLIS after the knock (5 180), on the sensors' clock.
        assertEquals(ImpactDetector.PEAK_MILLIS + 10, impact["ago"]!!.jsonPrimitive.long)
        lab.controller.stop()
    }

    @Test
    fun everySecondWritesCarryV2InTheShadow() = runTest {
        val probes = FakeProbes(state = "screen_off")
        val lab = Lab(this, probes = probes)
        lab.controller.start()
        runCurrent()
        // Walking upright with the screen off: in the pocket.
        for (at in 0L until 1_000L step 20) {
            val magnitude = 1.0 + if ((at / 100) % 2 == 0L) 0.2 else -0.2
            probes.readings.emit(LabSensorReading.Motion(at, magnitude, Gravity(0.0, -1.0, 0.0)))
        }
        runCurrent()
        advanceTimeBy(1_001)
        val pocket = lab.events().last { it.kind == "shadow" }
        assertEquals(LabController.CARRY_V2, pocket["tech"]!!.jsonPrimitive.content)
        assertEquals("in_pocket", pocket["state"]!!.jsonPrimitive.content)
        assertEquals("dark+moving", pocket["reason"]!!.jsonPrimitive.content)

        probes.state = "screen_on"
        advanceTimeBy(1_000)
        val hand = lab.events().last { it.kind == "shadow" }
        assertEquals("in_hand", hand["state"]!!.jsonPrimitive.content)
        assertEquals("screen_on", hand["reason"]!!.jsonPrimitive.content)
        lab.controller.stop()
    }

    @Test
    fun theScreenOffByProximityIsDarkWithTheSensorCovered() = runTest {
        val probes = FakeProbes(state = "active")
        val screen = FakeScreen()
        val lab = Lab(this, probes = probes, screen = screen)
        lab.controller.start()
        lab.controller.setScreenOff(true)
        runCurrent()
        probes.readings.emit(LabSensorReading.Proximity(near = true, monitoring = true))
        for (at in 0L until 1_000L step 20) {
            probes.readings.emit(LabSensorReading.Motion(at, 1.0, Gravity(0.3, -0.9, -0.2)))
        }
        runCurrent()
        advanceTimeBy(1_001)
        assertTrue(screen.turnedOff)
        val shadow = lab.events().last { it.kind == "shadow" }
        assertEquals("in_pocket", shadow["state"]!!.jsonPrimitive.content)
        assertEquals("dark+upright", shadow["reason"]!!.jsonPrimitive.content)
        lab.controller.stop()
    }

    @Test
    fun noScreenAndNoSensorsNoShadow() = runTest {
        val lab = Lab(this)
        lab.controller.start()
        advanceTimeBy(3_500)
        assertTrue("shadow" !in lab.kinds(), "${lab.kinds()}")
        lab.controller.stop()
    }

    @Test
    fun touchedMarksThePair() = runTest {
        val lab = Lab(this)
        lab.controller.setLabel("droid")
        lab.controller.start()
        runCurrent()
        assertTrue(lab.controller.touched("B"))
        assertFalse(lab.controller.touched("droid"), "not with itself")
        assertFalse(lab.controller.touched(" "))

        val mark = lab.events().single { it.kind == "mark" }
        assertEquals("touch B|droid", mark["label"]!!.jsonPrimitive.content)
        assertEquals("touch", mark["action"]!!.jsonPrimitive.content)
        assertEquals("user", mark["by"]!!.jsonPrimitive.content)
        lab.controller.stop()
    }

    private val JsonObject.kind: String get() = this["k"]!!.jsonPrimitive.content
}
