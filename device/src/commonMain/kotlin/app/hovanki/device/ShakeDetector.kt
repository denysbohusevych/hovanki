package app.hovanki.device

import kotlin.math.abs

/**
 * «Something is wrong» by shaking the phone (docs/adr/0018-field-test-build.md §5): [hits] jolts of more than
 * [thresholdG] beyond gravity within [windowMillis], each at least [gapMillis] after the last; then nothing for
 * [cooldownMillis], so one shake is one mark. Walking and running stay well under it; a deliberate shake doesn't.
 * Pure: the readings come in (the accelerometer's magnitude in g, gravity included, as the lab's probes give it).
 */
class ShakeDetector(
    private val thresholdG: Double = THRESHOLD_G,
    private val hits: Int = HITS,
    private val windowMillis: Long = WINDOW_MILLIS,
    private val gapMillis: Long = GAP_MILLIS,
    private val cooldownMillis: Long = COOLDOWN_MILLIS,
) {
    private val jolts = ArrayDeque<Long>()
    private var quietUntil = Long.MIN_VALUE

    /** A reading at [atMillis] of [magnitudeG]; true: this one completes a shake. */
    fun add(atMillis: Long, magnitudeG: Double): Boolean {
        if (atMillis < quietUntil) return false
        if (abs(magnitudeG - 1.0) < thresholdG) return false
        val last = jolts.lastOrNull()
        if (last != null && atMillis - last < gapMillis) return false
        while (jolts.isNotEmpty() && atMillis - jolts.first() > windowMillis) jolts.removeFirst()
        jolts.addLast(atMillis)
        if (jolts.size < hits) return false
        jolts.clear()
        quietUntil = atMillis + cooldownMillis
        return true
    }

    companion object {
        const val THRESHOLD_G = 1.5
        const val HITS = 3
        const val WINDOW_MILLIS = 1_500L
        const val GAP_MILLIS = 120L
        const val COOLDOWN_MILLIS = 3_000L
    }
}
