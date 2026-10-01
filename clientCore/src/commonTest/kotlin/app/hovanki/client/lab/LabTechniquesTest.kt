package app.hovanki.client.lab

import app.hovanki.device.ModeEvent
import app.hovanki.device.ModeIds
import app.hovanki.device.lab.HapticKind
import app.hovanki.radar.PeerRange
import app.hovanki.radar.RadioApi
import app.hovanki.radar.SightingVia
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.UwbPeer
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The techniques of step 5 (docs/radar-run.md §5) the lab switches by id: modes, the GATT link, UWB, the pulse. */
class LabTechniquesTest {
    private fun Lab.ofKind(kind: String): List<JsonObject> = events().filter { it.text(LabFields.K) == kind }

    private fun Lab.notes(): List<String> = ofKind("note").mapNotNull { it.text("text") }

    @Test
    fun aModeGoesOnByItsIdAndWhatItSaysIsLogged() = runTest {
        val lab = Lab(this)
        lab.controller.start()
        runCurrent()
        lab.controller.setTechniques(setOf(ModeIds.AUDIO, "ble.name"))
        runCurrent()
        assertEquals(setOf(ModeIds.AUDIO), lab.modes.on)
        assertEquals(setOf("ble.name"), lab.controller.techniques.value, "the channels go to the bench as before")
        assertEquals(setOf(ModeIds.AUDIO), lab.controller.labTechniques.value)
        assertTrue(lab.notes().none { it.startsWith("unknown techniques") }, "${lab.notes()}")

        lab.modes.events.tryEmit(ModeEvent(ModeIds.AUDIO, "interruption_began", "phone call"))
        runCurrent()
        val modes = lab.ofKind("mode")
        assertEquals(listOf("on", "interruption_began"), modes.map { it.text("event") })
        assertEquals(ModeIds.AUDIO, modes.last().text("mode"))
        assertEquals("phone call", modes.last().text("reason"))

        // A mode this phone hasn't: noted once and skipped; the audio goes off when the step doesn't name it.
        lab.controller.setTechniques(setOf(ModeIds.LIVE_ACTIVITY))
        lab.controller.setTechniques(setOf(ModeIds.LIVE_ACTIVITY, ModeIds.NOTIFICATION_WAKE))
        assertEquals(setOf(ModeIds.NOTIFICATION_WAKE), lab.modes.on)
        val live = lab.ofKind("mode").filter { it.text("mode") == ModeIds.LIVE_ACTIVITY }
        assertEquals(listOf("unavailable"), live.map { it.text("event") })
        val audio = lab.ofKind("mode").filter { it.text("mode") == ModeIds.AUDIO }
        assertEquals(listOf("on", "interruption_began", "off"), audio.map { it.text("event") })

        lab.controller.stop()
        assertEquals(emptySet(), lab.modes.on)
        assertEquals(1, lab.modes.stoppedAll)
    }

    @Test
    fun aSwitchTurnsOneLabTechniqueOnAndOffKeepingTheRest() = runTest {
        val lab = Lab(this)
        lab.controller.start()
        runCurrent()
        lab.controller.setTechniques(setOf("ble.name", ModeIds.NOTIFICATION_WAKE))
        lab.controller.setLabTechnique(ModeIds.AUDIO, true)
        runCurrent()
        assertEquals(setOf(ModeIds.AUDIO, ModeIds.NOTIFICATION_WAKE), lab.modes.on)
        assertEquals(setOf("ble.name"), lab.controller.techniques.value)
        lab.controller.setLabTechnique(ModeIds.NOTIFICATION_WAKE, false)
        runCurrent()
        assertEquals(setOf(ModeIds.AUDIO), lab.modes.on)
        assertEquals(setOf("ble.name"), lab.controller.techniques.value, "the channels stay")
        lab.controller.stop()
    }

    @Test
    fun theLinkRunsWithTheBenchTokenAndWritesItsReadings() = runTest {
        val lab = Lab(this)
        lab.controller.start()
        runCurrent()
        lab.controller.setTechniques(setOf("gatt.link"))
        lab.controller.setBenchRadio(asSeeker = false, token = "a1b2c3d4")
        runCurrent()
        assertTrue(lab.link.running)
        assertEquals("a1b2c3d4", lab.link.token?.value, "the link writes the token the bench advertises")

        lab.link.trace!!.link("connected", "peer-1")
        lab.link.reading("peer-1", "0a0b0c0d", null)
        lab.link.reading("peer-1", "0a0b0c0d", -61)
        runCurrent()
        assertEquals(1, lab.controller.linkPeers.value)
        val links = lab.ofKind("link")
        assertEquals(listOf("connected", "reading", "reading"), links.map { it.text("action") })
        val rssi = links.last()
        assertEquals("0a0b0c0d", rssi.text("token"))
        assertEquals(-61, rssi.getValue("rssi").jsonPrimitive.int)
        assertEquals(lab.log.peerId("peer-1"), rssi.text("peer"))
        assertNotEquals("peer-1", rssi.text("peer"), "the OS's id is hashed")

        lab.link.trace!!.link("disconnected", "peer-1", error = "timeout")
        assertEquals(0, lab.controller.linkPeers.value)

        lab.controller.setTechniques(emptySet())
        runCurrent()
        assertFalse(lab.link.running, "a step without the id stops the link")
        lab.controller.stop()
    }

    @Test
    fun uwbRangesWithTheRunsOtherDevicesAndWritesRange() = runTest {
        val lab = Lab(this, precisionSupported = true)
        lab.controller.setLabel("A")
        lab.controller.start()
        runCurrent()
        lab.controller.setUwbPeers(mapOf("A" to "token-a", "B" to "token-b"))
        lab.controller.setTechniques(setOf("uwb.ni", ModeIds.LIVE_ACTIVITY))
        runCurrent()
        assertTrue(lab.precision.prepared > 0)
        assertEquals(listOf(UwbPeer(PlayerId("B"), "token-b", Platform.IOS)), lab.precision.peers?.value)

        lab.precision.ranges.tryEmit(PeerRange(PlayerId("B"), 2.346, 31.6, 0))
        runCurrent()
        LabRangeTrace(lab.log).range("suspended", "B")
        runCurrent()
        val (reading, suspended) = lab.ofKind("range")
        assertEquals("reading", reading.text("action"))
        assertEquals("B", reading.text("peer"))
        assertEquals(2.35, reading.getValue("m").jsonPrimitive.double)
        assertEquals(32L, reading.getValue("deg").jsonPrimitive.long)
        assertEquals("suspended", suspended.text("action"))
        assertEquals("2.35 m to B", lab.controller.lastRange.value)
        assertTrue(lab.log.lines().none { "token-b" in it }, "the discovery tokens are never in the log")

        lab.controller.setUwbPeers(mapOf("A" to "token-a", "B" to "token-b", "C" to "token-c"))
        runCurrent()
        assertEquals(listOf("B", "C"), lab.precision.peers?.value?.map { it.playerId.value })

        lab.controller.setTechniques(emptySet())
        runCurrent()
        assertNull(lab.precision.peers, "ranging stops with the step")
        assertNull(lab.controller.lastRange.value)
        lab.controller.stop()
    }

    @Test
    fun withoutUwbTheIdIsNotedOnce() = runTest {
        val lab = Lab(this, precisionSupported = false)
        lab.controller.start()
        runCurrent()
        lab.controller.setTechniques(setOf("uwb.ni"))
        lab.controller.setTechniques(emptySet())
        lab.controller.setTechniques(setOf("uwb.ni"))
        runCurrent()
        assertNull(lab.precision.peers)
        assertEquals(listOf("unsupported"), lab.ofKind("range").map { it.text("action") })
        lab.controller.stop()
    }

    @Test
    fun thePulseVibratesTheWayTheStepChoseAndFallsToTheNext() = runTest {
        val kinds = listOf(HapticKind.CORE_HAPTICS, HapticKind.CORE_HAPTICS_AUDIO, HapticKind.NOTIFY_NO_SOUND)
        val lab = Lab(this, hapticKinds = kinds)
        lab.controller.start()
        runCurrent()
        lab.log.rx("0a0b0c0d", -45, RadioApi.UNKNOWN, SightingVia.NAME)

        lab.controller.setTechniques(setOf("pulse.core_haptics.audio", ModeIds.AUDIO))
        lab.controller.setPulse(LabPulse.HAPTICS)
        advanceTimeBy(2_000)
        assertTrue(lab.haptics.played.isNotEmpty())
        assertEquals(setOf(HapticKind.CORE_HAPTICS_AUDIO), lab.haptics.played.toSet())
        assertEquals(setOf(ModeIds.AUDIO), lab.modes.on)

        // Two candidates: the first while it plays, the next for good once it fails.
        lab.haptics.played.clear()
        lab.haptics.failing += HapticKind.CORE_HAPTICS
        lab.log.rx("0a0b0c0d", -45, RadioApi.UNKNOWN, SightingVia.NAME)
        lab.controller.setTechniques(setOf("pulse.core_haptics", "pulse.core_haptics.audio"))
        advanceTimeBy(2_000)
        assertEquals(HapticKind.CORE_HAPTICS, lab.haptics.played.first())
        assertEquals(HapticKind.CORE_HAPTICS_AUDIO, lab.haptics.played.last())
        assertEquals(1, lab.haptics.played.count { it == HapticKind.CORE_HAPTICS })

        // No pulse id: the default, the first kind that is no notification.
        lab.haptics.failing.clear()
        lab.haptics.played.clear()
        lab.log.rx("0a0b0c0d", -45, RadioApi.UNKNOWN, SightingVia.NAME)
        lab.controller.setTechniques(emptySet())
        advanceTimeBy(2_000)
        assertEquals(setOf(HapticKind.CORE_HAPTICS), lab.haptics.played.toSet())
        lab.controller.stop()
    }

    @Test
    fun pulseLiveActivityAsksForTheLiveActivity() = runTest {
        val lab = Lab(this)
        lab.controller.start()
        runCurrent()
        lab.controller.setTechniques(setOf("pulse.live_activity"))
        val live = lab.ofKind("mode").single()
        assertEquals(ModeIds.LIVE_ACTIVITY, live.text("mode"))
        assertEquals("unavailable", live.text("event"), "the fake phone has no Live Activity host")
        lab.controller.stop()
    }
}

private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content
