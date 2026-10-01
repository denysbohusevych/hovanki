package app.hovanki.device.lab

import kotlin.math.abs

/** A knock the accelerometer felt: when ([atMillis], the sensors' clock) and how hard ([peakG]: |magnitude − 1|, g). */
data class Impact(val atMillis: Long, val peakG: Double)

/**
 * Finds knocks in the acceleration's magnitude (docs/adr/0017-radar-techniques-and-big-run.md §3, the touch
 * calibration): two phones knocked back to back both feel one at the same moment, and the report pairs them by the
 * server's clock. A reading at least [IMPACT_G] away from 1 g starts a candidate; a stronger one within [PEAK_MILLIS]
 * takes its place, and the candidate is an [Impact] once [PEAK_MILLIS] of readings passed without a stronger one. At
 * most one impact every [MIN_GAP_MILLIS]: the bounce after a knock is the same knock. Walking stays well below
 * [IMPACT_G] (about ±0.3 g); a knock on a table or another phone goes above it. The thresholds are the plan's guesses
 * until the run. Fed at the sensors' rate (50 Hz in the lab); pure, not thread-safe.
 */
class ImpactDetector {
    private var candidate: Impact? = null
    private var lastImpactAt: Long? = null

    /** A reading of the magnitude in g (gravity included) at [atMillis]; the impact that just ended, if one did. */
    fun add(atMillis: Long, magnitudeG: Double): Impact? {
        var found: Impact? = null
        candidate?.let {
            if (atMillis - it.atMillis >= PEAK_MILLIS) {
                found = it
                lastImpactAt = it.atMillis
                candidate = null
            }
        }
        val deviation = abs(magnitudeG - 1.0)
        val last = lastImpactAt
        if (deviation >= IMPACT_G && (last == null || atMillis - last >= MIN_GAP_MILLIS)) {
            val current = candidate
            if (current == null || deviation > current.peakG) candidate = Impact(atMillis, deviation)
        }
        return found
    }

    fun clear() {
        candidate = null
        lastImpactAt = null
    }

    companion object {
        /** g away from the resting 1 g. */
        const val IMPACT_G = 0.6
        const val PEAK_MILLIS = 150L
        const val MIN_GAP_MILLIS = 300L
    }
}
