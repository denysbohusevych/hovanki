package app.hovanki.device.lab

import app.hovanki.shared.protocol.Activity
import kotlin.math.sqrt

/**
 * Gravity in the phone's frame, in g, the same on both platforms (the platforms convert): x to the right of the screen,
 * y to its top, z out of the screen, and the vector points down to the earth, as iOS's `CMDeviceMotion.gravity` does.
 * Lying screen up: z = −1; upright in portrait: y = −1. Android's `TYPE_GRAVITY` points the other way: negate it and
 * divide by 9.81.
 */
data class Gravity(val x: Double, val y: Double, val z: Double)

/** How the phone lies, from [Gravity] (docs/radio-lab.md §7.3): a pocket is upright or tilted, a table flat. */
enum class Orientation {
    FLAT_UP,
    FLAT_DOWN,
    UPRIGHT,
    UPSIDE_DOWN,
    TILTED,
    ;

    val key: String get() = name.lowercase()

    companion object {
        /** Within about 37° of an axis counts as along it. */
        const val AXIS = 0.8

        fun of(gravity: Gravity): Orientation = when {
            gravity.z <= -AXIS -> FLAT_UP
            gravity.z >= AXIS -> FLAT_DOWN
            gravity.y <= -AXIS -> UPRIGHT
            gravity.y >= AXIS -> UPSIDE_DOWN
            else -> TILTED
        }
    }
}

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
