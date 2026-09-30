package app.hovanki.device.lab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pocket classifier's features: how the phone lies and how much it moves (docs/radio-lab.md §7.3). */
class CarryFeaturesTest {
    @Test
    fun orientationsFromGravity() {
        assertEquals(Orientation.FLAT_UP, Orientation.of(Gravity(0.0, 0.0, -1.0)))
        assertEquals(Orientation.FLAT_DOWN, Orientation.of(Gravity(0.0, 0.1, 0.99)))
        assertEquals(Orientation.UPRIGHT, Orientation.of(Gravity(0.1, -0.95, -0.2)))
        assertEquals(Orientation.UPSIDE_DOWN, Orientation.of(Gravity(0.0, 0.9, 0.3)))
        assertEquals(Orientation.TILTED, Orientation.of(Gravity(0.6, -0.6, -0.5)))
    }

    @Test
    fun theMotionSpreadTellsATableFromABreath() {
        val window = MotionWindow()
        repeat(5) { window.add(it * 100L, 1.0) }
        assertNull(window.std(), "too few readings")
        repeat(30) { window.add(500L + it * 100L, 1.0) }
        assertEquals(0.0, window.std())
        val breathing = MotionWindow()
        repeat(30) { breathing.add(it * 100L, if (it % 2 == 0) 1.05 else 0.95) }
        val std = breathing.std() ?: 0.0
        assertTrue(std in 0.04..0.06, "$std")
        // Only the last 3 seconds count.
        repeat(40) { breathing.add(3_000L + it * 100L, 1.0) }
        assertEquals(0.0, breathing.std())
    }
}
