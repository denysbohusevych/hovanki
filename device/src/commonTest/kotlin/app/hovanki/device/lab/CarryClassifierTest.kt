package app.hovanki.device.lab

import app.hovanki.shared.protocol.Carry
import kotlin.test.Test
import kotlin.test.assertEquals

/** `carry.v2` (docs/radio-lab.md §7.3) on invented seconds: what the game's monitors get wrong, it should not. */
class CarryClassifierTest {
    private val classifier = CarryClassifier()

    private fun second(
        s: Int,
        screenOn: Boolean? = false,
        near: Boolean? = null,
        orientation: Orientation? = Orientation.UPRIGHT,
        std: Double? = 0.05,
        moving: Boolean? = false,
    ) = classifier.classify(CarrySignals(s * 1_000L, screenOn, near, orientation, std, moving))

    @Test
    fun theScreenOnIsTheHand() {
        assertEquals(CarryVerdict(Carry.IN_HAND, "screen"), second(0, screenOn = true))
        assertEquals(Carry.UNKNOWN, second(1, screenOn = null).state)
    }

    @Test
    fun aStillHiderStaysInThePocket() {
        // Walks to the hiding place, then stands for five minutes with the screen off: the pocket all along.
        for (s in 0 until 20) assertEquals(Carry.IN_POCKET, second(s, moving = true, std = 0.6).state)
        for (s in 20 until 320) {
            assertEquals(Carry.IN_POCKET, second(s, std = 0.01, orientation = Orientation.TILTED).state, "second $s")
        }
    }

    @Test
    fun aPhoneUprightWithTheScreenOffIsInThePocketWithoutMoving() {
        assertEquals(CarryVerdict(Carry.IN_POCKET, "upright"), second(0, std = 0.01, orientation = Orientation.UPRIGHT))
    }

    @Test
    fun aPhonePutOnATableLeavesThePocketAfterAMinute() {
        for (s in 0 until 10) second(s, moving = true, std = 0.5)
        for (s in 10 until 69) {
            assertEquals(Carry.IN_POCKET, second(s, std = 0.005, orientation = Orientation.FLAT_UP).state)
        }
        assertEquals(CarryVerdict(Carry.UNKNOWN, "table"), second(70, std = 0.005, orientation = Orientation.FLAT_UP))
        // Flat and still with the screen off from the start: never the pocket.
        val fresh = CarryClassifier()
        val verdict = fresh.classify(CarrySignals(0, false, null, Orientation.FLAT_UP, 0.005, false))
        assertEquals(CarryVerdict(Carry.UNKNOWN, "still"), verdict)
    }

    @Test
    fun faceDownAndCoveredIsATable() {
        second(0, moving = true, std = 0.5)
        assertEquals(
            CarryVerdict(Carry.UNKNOWN, "face_down"),
            second(1, near = true, std = 0.01, orientation = Orientation.FLAT_DOWN),
        )
    }

    @Test
    fun aScreenTheProximitySensorTurnedOffIsNotTheHand() {
        // iOS keeps the app active with the screen off by the proximity sensor (ADR 0016): covered, in a pocket.
        assertEquals(Carry.IN_POCKET, second(0, screenOn = true, near = true, moving = true).state)
    }
}
