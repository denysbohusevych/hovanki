package app.hovanki.device

import app.hovanki.shared.protocol.Activity
import app.hovanki.shared.protocol.Carry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `carry.v2` (docs/radio-lab.md §7.3): the pocket by the screen, the proximity sensor and the motion. */
class CarryClassifierTest {
    private var now = 0L

    /** [seconds] of the same second's inputs, one a second; the last verdict. */
    private fun CarryClassifier.feed(seconds: Int, inputs: (Long) -> CarryInputs): List<CarryVerdict> = List(seconds) {
        now += 1_000
        add(inputs(now))
    }

    private fun walkingDark(at: Long) = CarryInputs(
        atMillis = at,
        screenOn = false,
        orientation = Orientation.UPRIGHT,
        std = 0.25,
        activity = Activity.WALKING,
    )

    @Test
    fun walkingWithTheScreenOffEntersThePocket() {
        val classifier = CarryClassifier()
        val verdict = classifier.feed(1, ::walkingDark).last()
        assertEquals(CarryVerdict(Carry.IN_POCKET, "dark+moving"), verdict)
    }

    @Test
    fun standingStillInThePocketStaysThere() {
        val classifier = CarryClassifier()
        classifier.feed(20, ::walkingDark)
        val standing = classifier.feed(600) {
            CarryInputs(it, screenOn = false, orientation = Orientation.UPRIGHT, std = 0.01, activity = Activity.STILL)
        }
        assertTrue(standing.all { it.carry == Carry.IN_POCKET }, "${standing.filter { it.carry != Carry.IN_POCKET }}")
    }

    @Test
    fun putOnATableFaceUpIsUnknownAfterAMinute() {
        val classifier = CarryClassifier()
        classifier.feed(20, ::walkingDark)
        val table = classifier.feed(70) {
            CarryInputs(it, screenOn = false, orientation = Orientation.FLAT_UP, std = 0.005, activity = Activity.STILL)
        }
        val firstUnknown = table.indexOfFirst { it.carry == Carry.UNKNOWN }
        // The first flat second starts the minute; 60 s later it is a table.
        assertEquals(60, firstUnknown, "$table")
        assertTrue(table.take(firstUnknown).all { it.carry == Carry.IN_POCKET })
        assertEquals(CarryVerdict(Carry.UNKNOWN, "flat_still_60s"), table.last())
    }

    @Test
    fun faceDownWithTheSensorCoveredIsATableAtOnce() {
        val classifier = CarryClassifier()
        classifier.feed(20, ::walkingDark)
        val verdict = classifier.feed(1) {
            CarryInputs(it, screenOn = false, near = true, orientation = Orientation.FLAT_DOWN, std = 0.2)
        }.single()
        assertEquals(CarryVerdict(Carry.UNKNOWN, "flat_down+near"), verdict)
    }

    @Test
    fun theScreenOnIsInTheHand() {
        val classifier = CarryClassifier()
        classifier.feed(20, ::walkingDark)
        val verdict = classifier.feed(1) { walkingDark(it).copy(screenOn = true) }.single()
        assertEquals(CarryVerdict(Carry.IN_HAND, "screen_on"), verdict)
    }

    @Test
    fun theScreenOffByProximityIsDarkOnlyWithSomethingNear() {
        val classifier = CarryClassifier()
        val lit = classifier.feed(1) {
            walkingDark(it).copy(screenOn = true, screenOffByProximity = true, near = false)
        }
        assertEquals(Carry.IN_HAND, lit.single().carry)
        val covered = classifier.feed(1) {
            walkingDark(it).copy(screenOn = true, screenOffByProximity = true, near = true)
        }
        assertEquals(CarryVerdict(Carry.IN_POCKET, "dark+moving"), covered.single())
    }

    @Test
    fun aSecondWithoutInputsKeepsTheLastState() {
        val classifier = CarryClassifier()
        classifier.feed(5, ::walkingDark)
        val noMotion = classifier.feed(1) { CarryInputs(it, screenOn = false) }.single()
        assertEquals(CarryVerdict(Carry.IN_POCKET, "no_motion"), noMotion)
        val noScreen = classifier.feed(1) { CarryInputs(it, screenOn = null, std = 0.0) }.single()
        assertEquals(CarryVerdict(Carry.IN_POCKET, "no_screen"), noScreen)
    }

    @Test
    fun aStillFlatPhoneInTheDarkIsNotInAPocket() {
        val classifier = CarryClassifier()
        val verdict = classifier.feed(1) {
            CarryInputs(it, screenOn = false, orientation = Orientation.FLAT_UP, std = 0.005)
        }.single()
        assertEquals(CarryVerdict(Carry.UNKNOWN, "dark+still"), verdict)
    }
}
