package app.hovanki.shared.lab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LabRunScriptTest {
    @Test
    fun theCatalogValidates() {
        assertEquals(listOf("radio", "e2e"), LabRunScripts.ALL.map { it.id })
        assertSame(LabRunScripts.E2E, LabRunScripts.byId("e2e"))
        assertNull(LabRunScripts.byId("nope"))
        for (script in LabRunScripts.ALL) {
            assertTrue(script.isTimed, script.id)
            assertEquals(script.steps.size, script.stepViews().size)
            val summary = script.summary()
            assertEquals(script.labels, summary.labels)
            assertEquals((script.totalMillis / 1000).toInt(), summary.totalSeconds)
        }
    }

    @Test
    fun theRadioRunStaysAsTheMacKnowsIt() {
        val radio = LabRunScripts.RADIO
        assertEquals(2, radio.version, "the Mac learns the version from the phone's announcement")
        assertEquals(
            listOf(
                "baseline",
                "mask_pattern",
                "mask_token",
                "ibeacon_screen",
                "lock",
                "ibeacon_locked",
                "token_rotates",
                "mac_hider_locked",
            ),
            radio.steps.map { it.id },
        )
        assertEquals(listOf("A", "mac"), radio.labels)
        assertEquals(545_000L, radio.totalMillis)
        assertSame(radio, LabRunScripts.of(2))
        assertNull(LabRunScripts.of(3), "the e2e plan is not announced over the air")
        val phases = radio.steps.indices.map { radio.setupOf("A", it).phase }
        assertEquals(listOf(RunPhase.LOCK), phases.filter { it == RunPhase.LOCK })
        assertTrue(phases.indexOf(RunPhase.LOCK) < phases.indexOf(RunPhase.LOCKED))
        val mac = radio.steps.indices.map { radio.setupOf("mac", it) }
        assertTrue(mac.none { it.hider && it.seeker })
        assertEquals(1.0, radio.steps.first().distances[RunStep.pairKey("mac", "A")])
    }

    @Test
    fun theStepsAreFoundByTime() {
        val script = LabRunScripts.RADIO
        assertNull(script.at(-1))
        assertEquals(0, script.at(0)?.index)
        assertEquals(1, script.at(script.startOf(1))?.index)
        assertEquals(script.steps.lastIndex, script.at(script.totalMillis - 1)?.index)
        assertNull(script.at(script.totalMillis))
    }

    @Test
    fun setupOfNamesEveryLabel() {
        val e2e = LabRunScripts.E2E
        assertEquals(PhoneSetup(hider = true, probe = ProbeMode.Token), e2e.setupOf("A", 1))
        assertEquals(PhoneSetup(hider = true, listen = true), e2e.setupOf("droid", 1))
        val partial = LabRunScript(
            "partial",
            0,
            "Partial",
            listOf("A", "B"),
            listOf(RunStep("one", "One", 5, mapOf("A" to DeviceStep(PhoneSetup(hider = true))))),
        )
        assertEquals(PhoneSetup(), partial.setupOf("B", 0), "a label a step doesn't name does nothing")
        assertEquals("hider", partial.stepViews().single().hints["A"])
        assertEquals("quiet", partial.stepViews().single().hints["B"])
    }

    @Test
    fun aLockedStepMayNotChangeTheAdvertisement() {
        fun step(id: String, setup: PhoneSetup) = RunStep(id, id, 10, mapOf("A" to DeviceStep(setup)))
        fun script(vararg steps: RunStep) = LabRunScript("s", 1, "S", listOf("A"), steps.toList())

        val lock = PhoneSetup(hider = true, probe = ProbeMode.Token, phase = RunPhase.LOCK)
        script(step("lock", lock), step("locked", lock.copy(phase = RunPhase.LOCKED, listen = true)))
        assertFailsWith<IllegalArgumentException> {
            script(step("lock", lock), step("locked", PhoneSetup(probe = ProbeMode.Token, phase = RunPhase.LOCKED)))
        }
        assertFailsWith<IllegalArgumentException> {
            script(step("lock", lock), step("locked", lock.copy(seeker = true, phase = RunPhase.LOCKED)))
        }
        assertFailsWith<IllegalArgumentException>("locked without a lock step before") {
            script(step("locked", lock.copy(phase = RunPhase.LOCKED)))
        }
    }

    @Test
    fun aStepNamesItsChannelsByAnyId() {
        val channels = setOf("ble.service_data.bare", "ble.name", "not.in.any.catalog")
        val script = LabRunScript(
            "channels",
            0,
            "Channels",
            listOf("A", "B"),
            listOf(
                RunStep(
                    "one",
                    "One",
                    5,
                    mapOf("A" to DeviceStep(PhoneSetup(techniques = channels)), "B" to DeviceStep(PhoneSetup())),
                ),
            ),
        )
        assertEquals(channels, script.setupOf("A", 0).techniques, "the ids are the phone's to check")
        assertEquals(emptySet(), script.setupOf("B", 0).techniques, "none: as the switches say")
        // The console sees them in the step's hints, the only part of a plan on the wire (LabStepView).
        assertEquals(
            "channels ble.name, ble.service_data.bare, not.in.any.catalog",
            script.stepViews().single().hints["A"],
        )
        val builtIn = LabRunScripts.ALL.flatMap { it.steps }.flatMap { it.devices.values }
        assertTrue(builtIn.all { it.setup.techniques.isEmpty() }, "the built-in runs are as they were")
    }

    @Test
    fun aLockedStepKeepsItsChannels() {
        fun step(id: String, setup: PhoneSetup) = RunStep(id, id, 10, mapOf("A" to DeviceStep(setup)))
        fun script(vararg steps: RunStep) = LabRunScript("s", 1, "S", listOf("A"), steps.toList())

        val lock = PhoneSetup(hider = true, techniques = setOf("ble.overflow"), phase = RunPhase.LOCK)
        script(step("lock", lock), step("locked", lock.copy(phase = RunPhase.LOCKED)))
        assertFailsWith<IllegalArgumentException> {
            script(step("lock", lock), step("locked", lock.copy(techniques = emptySet(), phase = RunPhase.LOCKED)))
        }
    }

    @Test
    fun badScriptsAreRefused() {
        val one = RunStep("one", "One", 5, emptyMap())
        assertFailsWith<IllegalArgumentException> { LabRunScript("s", 16, "S", listOf("A"), listOf(one)) }
        assertFailsWith<IllegalArgumentException> { LabRunScript("s", 1, "S", listOf("A"), emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            LabRunScript("s", 1, "S", listOf("A"), listOf(one.copy(devices = mapOf("B" to DeviceStep()))))
        }
        val manual = LabRunScript("s", 1, "S", listOf("A"), listOf(one.copy(seconds = null)))
        assertFalse(manual.isTimed)
        assertNull(manual.summary().totalSeconds)
        assertFailsWith<IllegalArgumentException> { manual.totalMillis }
    }
}
