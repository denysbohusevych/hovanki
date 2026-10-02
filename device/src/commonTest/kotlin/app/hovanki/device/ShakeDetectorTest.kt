package app.hovanki.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShakeDetectorTest {
    @Test
    fun threeJoltsInASecondAndAHalfAreAShake() {
        val detector = ShakeDetector()
        assertFalse(detector.add(0, 3.0))
        // Within the gap: the same jolt.
        assertFalse(detector.add(50, 3.2))
        // The other way counts too.
        assertFalse(detector.add(400, -1.0))
        assertTrue(detector.add(800, 2.8))
        // One shake is one mark: quiet for a while.
        assertFalse(detector.add(1_200, 3.0))
        assertFalse(detector.add(1_600, 3.0))
        assertFalse(detector.add(2_000, 3.0))
    }

    @Test
    fun walkingRunningAndSlowJoltsAreNone() {
        val detector = ShakeDetector()
        // Running: up to about 2 g, every step.
        val shakes = (0 until 200).count { step -> detector.add(step * 300L, if (step % 2 == 0) 2.2 else 0.3) }
        assertEquals(0, shakes)
        // Hard jolts, but too far apart.
        assertFalse(detector.add(100_000, 3.0))
        assertFalse(detector.add(101_000, 3.0))
        assertFalse(detector.add(102_000, 3.0))
    }
}
