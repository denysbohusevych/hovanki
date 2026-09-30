package app.hovanki.device

import app.hovanki.shared.protocol.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.sqrt

/**
 * What the player is doing by the phone's motion sensors (docs/adr/0013-quests-sparks-and-sensors.md, section 4),
 * while collected. The platforms feed the accelerometer into an [ActivityClassifier]; no permission is needed for
 * that, unlike the activity recognition services.
 */
interface ActivityMonitor {
    fun activity(): Flow<Activity>
}

class NoopActivityMonitor : ActivityMonitor {
    override fun activity(): Flow<Activity> = emptyFlow()
}

/**
 * Tells running from walking from standing still by the accelerometer alone: the spread of the acceleration's
 * magnitude over the last [WINDOW_MILLIS] (still: hardly any; walking: a little; running: a lot) and the cadence of
 * its peaks (steps; running is above [RUNNING_CADENCE_HZ]). Feed it every reading with [add]; not thread-safe.
 * Riding in a vehicle is never told apart here ([Activity.IN_VEHICLE] stays for the platforms' own detectors).
 */
class ActivityClassifier {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    /** A reading of the acceleration's magnitude, in m/s² (gravity included), at [atMillis]; the activity so far. */
    fun add(atMillis: Long, magnitude: Double): Activity {
        samples.addLast(atMillis to magnitude)
        while (samples.first().first < atMillis - WINDOW_MILLIS) samples.removeFirst()
        return classify()
    }

    fun classify(): Activity {
        if (samples.size < MIN_SAMPLES) return Activity.UNKNOWN
        val mean = samples.sumOf { it.second } / samples.size
        val std = sqrt(samples.sumOf { (it.second - mean) * (it.second - mean) } / samples.size)
        if (std < STILL_STD) return Activity.STILL
        var peaks = 0
        var lastPeakAt: Long? = null
        for (i in 1 until samples.size - 1) {
            val (at, value) = samples[i]
            val peak = value > samples[i - 1].second && value >= samples[i + 1].second && value > mean + std / 2
            if (peak && (lastPeakAt == null || at - lastPeakAt >= MIN_PEAK_GAP_MILLIS)) {
                peaks++
                lastPeakAt = at
            }
        }
        val seconds = (samples.last().first - samples.first().first) / 1000.0
        val cadence = if (seconds > 0) peaks / seconds else 0.0
        return if (cadence >= RUNNING_CADENCE_HZ && std >= RUNNING_STD) Activity.RUNNING else Activity.WALKING
    }

    companion object {
        const val WINDOW_MILLIS = 3_000L
        const val MIN_SAMPLES = 20

        /** m/s²: below it the phone lies still or is carried by somebody standing. */
        const val STILL_STD = 0.4
        const val RUNNING_STD = 3.0

        /** Steps per second: a jog is about 2.5, a walk below 2. */
        const val RUNNING_CADENCE_HZ = 2.3
        const val MIN_PEAK_GAP_MILLIS = 200L
    }
}
