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
        assertEquals(listOf("radio", "e2e", "big_run"), LabRunScripts.ALL.map { it.id })
        assertSame(LabRunScripts.E2E, LabRunScripts.byId("e2e"))
        assertSame(LabRunScripts.BIG_RUN, LabRunScripts.byId("big_run"))
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
        val builtIn = listOf(LabRunScripts.RADIO, LabRunScripts.E2E).flatMap { it.steps }.flatMap { it.devices.values }
        assertTrue(builtIn.all { it.setup.techniques.isEmpty() }, "the runs before the big one are as they were")
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

    @Test
    fun theBigRunGoesByItselfForAboutAnHundredMinutes() {
        val run = LabRunScripts.BIG_RUN
        assertEquals(1, run.version)
        assertEquals(listOf("A", "B", "droid", "mac"), run.labels)
        assertTrue(run.isTimed, "every step has a timer: the run goes by itself")
        val minutes = run.totalMillis / 60_000.0
        assertTrue(minutes in 90.0..110.0, "the big run lasts $minutes min")
        assertNull(LabRunScripts.of(run.version), "the Mac's local run is the radio run only")
        assertSame(LabRunScripts.RADIO, LabRunScripts.of(LabRunScripts.RADIO.version))
    }

    @Test
    fun theBigRunHasEveryBlockOfTheProgramme() {
        val run = LabRunScripts.BIG_RUN
        val blocks = run.steps.map { step -> blockOf(step) }
        assertEquals((0..8).toList(), blocks.distinct(), "blocks 0–8, in order, each in one piece")
        // ADR 0017 §6: the minutes of each block, within a minute.
        val planned = listOf(10, 5, 20, 15, 20, 15, 10, 10, 3)
        for ((block, minutes) in planned.withIndex()) {
            val seconds = run.steps.filter { blockOf(it) == block }.sumOf { it.seconds!! }
            assertTrue(kotlin.math.abs(seconds - minutes * 60) <= 60, "block $block: $seconds s, planned $minutes min")
        }
    }

    @Test
    fun everyStepOfTheBigRunNamesEveryPhone() {
        for (step in LabRunScripts.BIG_RUN.steps) {
            for (label in PHONES) {
                val device = step.devices[label]
                assertTrue(device != null, "${step.id}: $label has a part")
                assertTrue(device.hint.isNotBlank(), "${step.id}: $label is told what to do")
                assertTrue(device.place != null && device.action != null, "${step.id}: $label's truth")
            }
            assertTrue(step.hint.isNotBlank(), "${step.id}: the console's hint")
        }
    }

    @Test
    fun everyPairTouchesThreeTimesFirstAndOnceAtTheEnd() {
        val steps = LabRunScripts.BIG_RUN.steps
        for ((first, second) in PAIRS) {
            val prefix = "touch_${first}_$second"
            assertEquals(3, steps.count { it.id.startsWith("b1_$prefix") }, "block 1: $first and $second")
            assertEquals(1, steps.count { it.id.startsWith("b8_$prefix") }, "block 8: $first and $second")
            for (step in steps.filter { it.id.contains(prefix) }) {
                assertEquals(
                    "Touched with $second",
                    step.devices.getValue(first).hint.substringAfter("«").substringBefore("»"),
                )
                assertEquals(
                    "Touched with $first",
                    step.devices.getValue(second).hint.substringAfter("«").substringBefore("»"),
                )
            }
        }
    }

    @Test
    fun block5GivesEveryPairEveryDistanceInTheHandAndInThePocket() {
        val steps = LabRunScripts.BIG_RUN.steps.filter { blockOf(it) == 5 }
        val distances = listOf(1.0, 3.0, 5.0, 10.0, 20.0, 40.0)
        for ((first, second) in PAIRS) {
            val pair = RunStep.pairKey(first, second)
            fun at(places: (String?) -> Boolean): Set<Double> = steps
                .filter { step ->
                    places(step.devices.getValue(first).place) &&
                        places(step.devices.getValue(second).place)
                }
                .mapNotNull { it.distances[pair] }
                .toSet()
            assertTrue(at { it == LabPlaces.HAND }.containsAll(distances), "$pair in the hand")
            assertTrue(at { it in LabPlaces.CARRIED_HIDDEN }.containsAll(distances), "$pair in the pocket")
        }
    }

    @Test
    fun theBigRunNamesOnlyTheCatalogsTechniques() {
        val used = LabRunScripts.BIG_RUN.steps.flatMap { it.devices.values }.flatMap { it.setup.techniques }.toSet()
        assertEquals(emptySet(), used - KNOWN_TECHNIQUES, "ids outside ADR 0017 §2.3")
        assertTrue("wifi.aware" !in used, "Wi-Fi Aware is deferred (docs/radar-run.md §6)")
        assertTrue(used.containsAll(listOf("gatt.link", "uwb.ni", "mode.audio", "mode.notification_wake")))
    }

    @Test
    fun theBigRunLocksTheIPhonesWithoutChangingWhatTheyAdvertise() {
        val run = LabRunScripts.BIG_RUN
        // The validator: a locked step keeps its lock step's advertisement (the script would not build otherwise).
        for (label in listOf("A", "B")) {
            val lockedOnTheTable = run.steps.indices.filter { index ->
                blockOf(run.steps[index]) == 2 && run.setupOf(label, index).phase == RunPhase.LOCKED
            }
            assertTrue(lockedOnTheTable.isNotEmpty(), "block 2 locks $label")
            // A phone is still locked when the step after a locked one starts, and iOS can't change its
            // advertisement in the background: that step keeps it, the one after the unlock may change it.
            for (index in 1 until run.steps.size) {
                val before = run.setupOf(label, index - 1)
                if (before.phase == RunPhase.SCREEN) continue
                assertEquals(
                    before.advertisement,
                    run.setupOf(label, index).advertisement,
                    "$label, ${run.steps[index].id}: starts while the phone is locked",
                )
            }
        }
        val mac = run.steps.filter { "mac" in it.devices }
        assertTrue(mac.isNotEmpty(), "the Mac listens somewhere")
        assertTrue(mac.all { it.devices.getValue("mac").setup.phase == RunPhase.SCREEN })
    }

    private fun blockOf(step: RunStep): Int = step.id.removePrefix("b").substringBefore('_').toInt()

    private companion object {
        val PHONES = listOf("A", "B", "droid")
        val PAIRS = listOf("A" to "B", "A" to "droid", "B" to "droid")

        /** The ids of ADR 0017 §2.3 the big run may name. */
        val KNOWN_TECHNIQUES = setOf(
            // The channels in `RadarCatalog` (step 3).
            "ble.service_data.scan_response",
            "ble.service_data.bare",
            "ble.service_data.mfr",
            "ble.name",
            "ble.ibeacon",
            "ble.ibeacon.region",
            "ble.overflow",
            // The lab's pulse by haptics (`PhoneSetup.pulse`): the id only names it.
            "pulse.core_haptics",
            // Step 5 of docs/radar-run.md: not in code yet, the phone notes them as unknown and leaves them out.
            "mode.audio",
            "mode.notification_wake",
            "mode.live_activity",
            "pulse.core_haptics.audio",
            "pulse.live_activity",
            "gatt.link",
            "uwb.ni",
        )
    }
}
