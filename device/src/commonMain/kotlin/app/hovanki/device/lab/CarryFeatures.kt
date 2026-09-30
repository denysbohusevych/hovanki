package app.hovanki.device.lab

import app.hovanki.shared.protocol.Activity
import kotlin.math.sqrt

/** The pocket's features moved to the root package for [app.hovanki.device.CarryClassifier]: the lab's names stay. */
typealias Gravity = app.hovanki.device.Gravity

typealias Orientation = app.hovanki.device.Orientation

/** What the lab writes once a second about the phone's motion (`motion`, docs/radio-lab.md §4.1). */
data class MotionFeatures(
    /** The spread of the acceleration's magnitude over the window, in g; null: too few readings. */
    val std: Double?,
    val gravity: Gravity?,
    val orientation: Orientation?,
    val activity: Activity?,
)

/**
 * The spread of the acceleration's magnitude (gravity included, in g) over the last [windowMillis]: a table gives
 * about none, a pocket of somebody standing a little (breath, weight), walking a lot. The thresholds between them are
 * what the lab measures (docs/radio-lab.md §7.3). Not thread-safe.
 */
class MotionWindow(private val windowMillis: Long = WINDOW_MILLIS) {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    fun add(atMillis: Long, magnitudeG: Double) {
        samples.addLast(atMillis to magnitudeG)
        while (samples.first().first < atMillis - windowMillis) samples.removeFirst()
    }

    /** The standard deviation so far; null: fewer than [MIN_SAMPLES] readings in the window. */
    fun std(): Double? {
        if (samples.size < MIN_SAMPLES) return null
        val mean = samples.sumOf { it.second } / samples.size
        return sqrt(samples.sumOf { (it.second - mean) * (it.second - mean) } / samples.size)
    }

    fun clear() = samples.clear()

    companion object {
        const val WINDOW_MILLIS = 3_000L
        const val MIN_SAMPLES = 10

        fun magnitude(x: Double, y: Double, z: Double): Double = sqrt(x * x + y * y + z * z)
    }
}
