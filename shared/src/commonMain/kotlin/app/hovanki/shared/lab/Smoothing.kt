package app.hovanki.shared.lab

import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.rules.ProximityRules
import app.hovanki.shared.rules.RadarSmoother
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A reading of one direction at [t] (server time), [rssi] dBm, a calibration's offset already added. */
data class TimedRssi(val t: Long, val rssi: Double)

/**
 * The smoothings that compete for the radar's band (docs/adr/0017-radar-techniques-and-big-run.md §2.3
 * «Конкурируют»): [EMA] the game's ([RadarSmoother]), [P80] the loudest fifth of the last [Smoothing.P80_WINDOW_MILLIS]
 * (a turned body takes a few dB off most readings, not off the best ones), [RATE] the game's with how often the phone is
 * heard as a second sign (a phone far away loses most of its packets: heard less than [Smoothing.RATE_LOW] a second,
 * its band is one lower). Decided by the band's error against the run's distances, recomputed from the journals.
 */
enum class SmoothingVariant(val id: String) {
    EMA("smooth.ema"),
    P80("smooth.p80"),
    RATE("smooth.rate"),
}

/** The band of one direction second by second, by each [SmoothingVariant]. Pure. */
object Smoothing {
    const val P80_WINDOW_MILLIS = 2_500L
    const val P80_PERCENT = 80
    const val RATE_WINDOW_MILLIS = 5_000L

    /** Readings a second below which [SmoothingVariant.RATE] takes a band off. */
    const val RATE_LOW = 0.4

    /** The band at every one of [times] (ascending) after [readings] (in time order) by [variant]. */
    fun bands(variant: SmoothingVariant, readings: List<TimedRssi>, times: List<Long>): List<RadarBand> = when (variant) {
        SmoothingVariant.EMA -> ema(readings, times)
        SmoothingVariant.P80 -> p80(readings, times)
        SmoothingVariant.RATE -> rate(readings, times)
    }

    private fun ema(readings: List<TimedRssi>, times: List<Long>): List<RadarBand> {
        val smoother = RadarSmoother()
        var next = 0
        return times.map { time ->
            while (next < readings.size && readings[next].t <= time) {
                smoother.add(readings[next].rssi.roundToInt(), readings[next].t)
                next++
            }
            smoother.bandAt(time)
        }
    }

    private fun p80(readings: List<TimedRssi>, times: List<Long>): List<RadarBand> {
        val window = ArrayDeque<TimedRssi>()
        var next = 0
        var band = RadarBand.NONE
        var lastAt: Long? = null
        return times.map { time ->
            while (next < readings.size && readings[next].t <= time) {
                window.addLast(readings[next])
                lastAt = readings[next].t
                next++
            }
            while (window.isNotEmpty() && window.first().t <= time - P80_WINDOW_MILLIS) window.removeFirst()
            val heardAt = lastAt
            band = when {
                window.isNotEmpty() -> {
                    // The weakest of the loudest fifth.
                    val loudest = window.map { it.rssi }.sortedDescending()
                    val rank = ceil((100 - P80_PERCENT) / 100.0 * loudest.size).toInt().coerceIn(1, loudest.size)
                    ProximityRules.bandFor(loudest[rank - 1], band)
                }

                // Nothing new for a moment: the band holds until the signal's life is over, as the game's does.
                heardAt != null && time - heardAt <= ProximityRules.SIGNAL_TTL_MILLIS -> band

                else -> RadarBand.NONE
            }
            band
        }
    }

    private fun rate(readings: List<TimedRssi>, times: List<Long>): List<RadarBand> {
        val bands = ema(readings, times)
        val recent = ArrayDeque<Long>()
        var next = 0
        return times.mapIndexed { index, time ->
            while (next < readings.size && readings[next].t <= time) recent.addLast(readings[next++].t)
            while (recent.isNotEmpty() && recent.first() <= time - RATE_WINDOW_MILLIS) recent.removeFirst()
            val perSecond = recent.size * 1000.0 / RATE_WINDOW_MILLIS
            val band = bands[index]
            if (band != RadarBand.NONE && perSecond < RATE_LOW) RadarBand.entries[band.ordinal - 1] else band
        }
    }
}

/**
 * What a band should be at a distance (the truth of the run's steps, ADR 0017 §2.3): «burning» within
 * [BURNING_METERS] (ADR 0012: about 3 m, a claim), «hot» within [HOT_METERS], «warm» within [WARM_METERS], else
 * none. The plan's meaning of the bands, not a measurement: the run tells how far off the thresholds are.
 */
object BandTruth {
    const val BURNING_METERS = 3.0
    const val HOT_METERS = 8.0
    const val WARM_METERS = 25.0

    fun of(meters: Double): RadarBand = when {
        meters <= BURNING_METERS -> RadarBand.BURNING
        meters <= HOT_METERS -> RadarBand.HOT
        meters <= WARM_METERS -> RadarBand.WARM
        else -> RadarBand.NONE
    }
}
