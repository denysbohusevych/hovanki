package app.hovanki.client.lab

import app.hovanki.shared.rules.OverflowCode
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabRunTest {
    private val start = 1_790_000_060_000L

    @Test
    fun theRunsTokenCarriesTheScriptAndTheStart() {
        val token = LabRunToken.encode(LabRunScripts.RADIO.version, start)
        assertEquals(8, token.length)
        assertTrue(token.startsWith("1ab${LabRunScripts.RADIO.version}"))
        assertEquals(LabRunScripts.RADIO.version to start, LabRunToken.decode(token, start - 10_000))
        assertEquals(LabRunScripts.RADIO.version to start, LabRunToken.decode(token, start + 600_000), "heard late")
        assertNull(LabRunToken.decode("cafe0001", start))
        assertNull(LabRunToken.decode("1abx0000", start))
        assertFailsWith<IllegalArgumentException> { LabRunToken.encode(1, start + 500) }
    }

    @Test
    fun theStartsSecondWrapsAroundEvery18Hours() {
        // The second modulo 2¹⁶ just before it wraps: the start comes after the wrap.
        val wrap = (start / 1000 / 65_536 + 1) * 65_536 * 1000
        val token = LabRunToken.encode(1, wrap + 3_000)
        assertEquals(1 to wrap + 3_000, LabRunToken.decode(token, wrap - 5_000))
        assertEquals(1 to wrap + 3_000, LabRunToken.decode(token, wrap + 5_000))
    }

    @Test
    fun theScriptFindsItsStepsAndKeepsTheLockedAdvertisement() {
        val script = LabRunScripts.RADIO
        assertNull(script.at(-1))
        assertEquals(0, script.at(0)?.index)
        assertEquals(1, script.at(script.startOf(1))?.index)
        assertEquals(script.steps.lastIndex, script.at(script.totalMillis - 1)?.index)
        assertNull(script.at(script.totalMillis))
        assertEquals(script, LabRunScripts.of(script.version))
        assertNull(LabRunScripts.of(script.version + 1))
        val phases = script.steps.map { it.phase }
        assertEquals(listOf(RunPhase.LOCK), phases.filter { it == RunPhase.LOCK })
        assertTrue(phases.indexOf(RunPhase.LOCK) < phases.indexOf(RunPhase.LOCKED))
        assertTrue(script.steps.none { it.mac.advertise && it.mac.iBeacon })

        val step = script.steps.first()
        assertFailsWith<IllegalArgumentException> {
            LabRunScript(
                1,
                listOf(
                    step.copy(phase = RunPhase.LOCK, phone = PhoneSetup(hider = true)),
                    step.copy(phase = RunPhase.LOCKED, phone = PhoneSetup(probe = ProbeMode.Token)),
                ),
            )
        }
    }

    @Test
    fun oneButtonRunsEveryStepByTheServersClock() = runTest {
        val lab = Lab(this)
        val runner = LabRunner(lab.controller, backgroundScope, appState = { "active" })
        runner.start()
        runCurrent()
        advanceTimeBy(1_000)

        // The announcement: the bench as a hider with the run's token, the lab on, «as in a game».
        val state = assertNotNull(runner.state.value)
        assertEquals(-1, state.index)
        assertEquals(state.token, lab.radio.tokens?.value)
        assertEquals(false, lab.radio.asSeeker)
        assertEquals(LabRunScripts.RADIO.version to state.startAtServer, LabRunToken.decode(state.token, start))
        assertTrue(lab.controller.running.value)
        assertTrue(lab.tracker.running)
        assertEquals(0L, state.startAtServer % 1000)

        val until = state.startAtServer - lab.log.serverNow()
        advanceTimeBy(until + LabRunScripts.RADIO.startOf(1) + 1_000)
        assertEquals(1, runner.state.value?.index, "mask_pattern")
        assertEquals(ProbeMode.Pattern, lab.controller.probe.value)
        assertNull(lab.radio.tokens, "the hider is off while the probe shows the pattern")

        advanceTimeBy(LabRunScripts.RADIO.startOf(4) - LabRunScripts.RADIO.startOf(1))
        assertEquals(RunPhase.LOCK, runner.state.value?.step?.phase)
        assertEquals(ProbeMode.Token, lab.controller.probe.value)
        assertNotNull(lab.radio.tokens, "the pocket: the hider and the probe's token")
        advanceTimeBy(1_000)
        assertTrue("Lock the phone now" in lab.haptics.notified)

        val tokenBefore = lab.controller.probeToken.value
        advanceTimeBy(LabRunScripts.RADIO.startOf(6) - LabRunScripts.RADIO.startOf(4))
        assertEquals("token_rotates", runner.state.value?.step?.id)
        assertNotEquals(tokenBefore, lab.controller.probeToken.value)
        assertEquals(OverflowCode.encode(lab.controller.probeToken.value), lab.air.advertised.last())

        lab.radio.hears(LabRunScripts.MAC_HIDER_TOKEN, -55, lab.log.deviceNow())
        advanceTimeBy(LabRunScripts.RADIO.totalMillis - LabRunScripts.RADIO.startOf(6) + 60_000)
        val done = assertNotNull(runner.state.value)
        assertTrue(done.finished && !done.stopped)
        assertTrue(done.macHeard)
        assertFalse(done.macBeaconHeard)
        assertTrue(LabRunScripts.RADIO.steps.none { "haptic" in it.id }, "the vibration test is a test of its own")
        assertTrue("Radio run done: unlock the phone" in lab.haptics.notified)
        assertFalse(runner.isRunning)
        assertNull(lab.controller.probe.value)
        assertNull(lab.radio.tokens)
        assertFalse(lab.tracker.running)

        val marks = lab.events().filter { it["k"]!!.jsonPrimitive.content == "mark" }
            .map { it["label"]!!.jsonPrimitive.content }
            .filter { it.startsWith("run: ") }
        assertEquals(
            listOf("run: announce") + LabRunScripts.RADIO.steps.map { "run: ${it.id}" } + "run: done",
            marks,
        )
        assertTrue(
            done.warnings.any { "not locked" in it },
            "the fake phone stays active: the locked steps warn",
        )
    }

    @Test
    fun theTesterStopsTheRun() = runTest {
        val lab = Lab(this)
        val runner = LabRunner(lab.controller, backgroundScope, appState = { "background" })
        runner.start()
        advanceTimeBy(LabRunner.LEAD_MILLIS + 5_000)
        assertEquals(0, runner.state.value?.index)
        runner.stop()
        runCurrent()
        val state = assertNotNull(runner.state.value)
        assertTrue(state.finished && state.stopped)
        assertTrue(state.warnings.isEmpty())
        assertNull(lab.radio.tokens)
        assertFalse(lab.tracker.running)
        assertTrue(lab.events().any { it["label"]?.jsonPrimitive?.content == "run: stopped" })
    }
}
