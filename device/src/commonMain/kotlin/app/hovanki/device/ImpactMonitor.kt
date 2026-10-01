package app.hovanki.device

import app.hovanki.shared.lab.TouchDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.abs

/**
 * The jolts that may be two phones touching (docs/adr/0017-radar-techniques-and-big-run.md §3, «Чокнуться
 * телефонами»), while collected: the accelerometer read about a hundred times a second, every lone sharp jolt
 * ([ImpactDetector]). Only for the journal's touch candidates (the radio lab, the field build's lobby): the game never
 * uses it. Android: `SensorManager`'s accelerometer; iOS: `CMMotionManager`'s.
 */
interface ImpactMonitor {
    fun impacts(): Flow<Impact>
}

/** A jolt at [atMillis] of the device's clock (when the sensor felt it, not when it was told), [g] beyond gravity. */
data class Impact(val atMillis: Long, val g: Double)

class NoopImpactMonitor : ImpactMonitor {
    override fun impacts(): Flow<Impact> = emptyFlow()
}

/**
 * Finds the lone sharp jolts in the accelerometer's readings (its magnitude in g, gravity included): readings more
 * than [thresholdG] from 1 g, those within [burstMillis] of each other one jolt at its peak; a jolt is lone when no
 * other came within [isolationMillis] before or after it. Walking jolts every step, about twice a second, and is never
 * lone; a touch is one knock. A jolt is told once the quiet after it is over, with its own time. Pure, not
 * thread-safe. The report checks the jolts of two phones against each other and the signal ([TouchDetector]).
 */
class ImpactDetector(
    private val thresholdG: Double = TouchDetector.MIN_G,
    private val burstMillis: Long = BURST_MILLIS,
    private val isolationMillis: Long = TouchDetector.ISOLATION_MILLIS,
) {
    private var lastJoltAt: Long? = null
    private var pending: Pending? = null

    private class Pending(var peakAt: Long, var peak: Double, var lastAt: Long, var lone: Boolean)

    /** A reading at [atMillis] of [magnitudeG]; a lone jolt that is over by now, or null. */
    fun add(atMillis: Long, magnitudeG: Double): Impact? {
        var result: Impact? = null
        val current = pending
        if (current != null && atMillis - current.lastAt > isolationMillis) {
            if (current.lone) result = Impact(current.peakAt, current.peak)
            pending = null
        }
        val jolt = abs(magnitudeG - 1.0)
        if (jolt < thresholdG) return result
        val open = pending
        when {
            open != null && atMillis - open.lastAt <= burstMillis -> {
                if (jolt > open.peak) {
                    open.peak = jolt
                    open.peakAt = atMillis
                }
                open.lastAt = atMillis
            }

            // Another jolt before the quiet: neither is lone, and the quiet starts again after this one.
            open != null -> {
                open.lone = false
                open.lastAt = atMillis
            }

            else -> {
                val before = lastJoltAt
                pending = Pending(atMillis, jolt, atMillis, before == null || atMillis - before > isolationMillis)
            }
        }
        lastJoltAt = atMillis
        return result
    }

    fun clear() {
        lastJoltAt = null
        pending = null
    }

    companion object {
        /** A knock rings for a few readings: one jolt. */
        const val BURST_MILLIS = 60L

        /** How often the platforms read the accelerometer for it: about a hundred times a second. */
        const val SAMPLING_MILLIS = 10L
    }
}
