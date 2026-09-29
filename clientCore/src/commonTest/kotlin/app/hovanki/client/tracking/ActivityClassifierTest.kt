package app.hovanki.client.tracking

import app.hovanki.shared.protocol.Activity
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/** Running, walking and standing by the accelerometer alone (docs/adr/0013-quests-sparks-and-sensors.md). */
class ActivityClassifierTest {
    private val gravity = 9.81

    /** [seconds] of readings at 50 Hz: [amplitude] m/s² around gravity at [hz] steps a second, plus a little noise. */
    private fun ActivityClassifier.feed(seconds: Double, hz: Double, amplitude: Double): Activity {
        var activity = Activity.UNKNOWN
        val samples = (seconds * 50).toInt()
        for (i in 0 until samples) {
            val t = i / 50.0
            val noise = 0.05 * sin(t * 37.0)
            activity = add((t * 1000).toLong(), gravity + amplitude * sin(2 * PI * hz * t) + noise)
        }
        return activity
    }

    @Test
    fun standingStill() {
        assertEquals(Activity.STILL, ActivityClassifier().feed(seconds = 4.0, hz = 0.0, amplitude = 0.0))
    }

    @Test
    fun walking() {
        assertEquals(Activity.WALKING, ActivityClassifier().feed(seconds = 4.0, hz = 1.8, amplitude = 2.0))
    }

    @Test
    fun running() {
        assertEquals(Activity.RUNNING, ActivityClassifier().feed(seconds = 4.0, hz = 2.7, amplitude = 6.0))
    }

    @Test
    fun aFewReadingsSayNothing() {
        assertEquals(Activity.UNKNOWN, ActivityClassifier().feed(seconds = 0.2, hz = 2.7, amplitude = 6.0))
    }

    @Test
    fun theWindowForgetsTheRun() {
        val classifier = ActivityClassifier()
        classifier.feed(seconds = 4.0, hz = 2.7, amplitude = 6.0)
        var activity = Activity.UNKNOWN
        for (i in 0 until 200) activity = classifier.add(4_000L + i * 20L, gravity)
        assertEquals(Activity.STILL, activity)
    }
}
