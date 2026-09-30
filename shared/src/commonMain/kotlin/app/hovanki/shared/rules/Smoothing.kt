package app.hovanki.shared.rules

import app.hovanki.shared.protocol.RadarBand
import kotlin.math.ceil
import kotlin.math.log2

/**
 * A candidate smoothing of one direction's readings (docs/adr/0017-radar-techniques-and-big-run.md §2.3,
 * «Сглаживание»): fed a direction's readings in time order, asked for the band. The radio lab's report runs every
 * candidate over the same logs and scores its bands against the distances (`app.hovanki.shared.lab.BandErrors`); the
 * game keeps [RadarSmoother] until the numbers say otherwise, and nothing here changes it.
 *
 * Every candidate keeps the game's rules around the level: [ProximityRules.bandFor] with its hysteresis, the signal
 * gone [ProximityRules.SIGNAL_TTL_MILLIS] after the last reading, a late reading taken as if it came now. Its band
 * changes only at a reading and falls to [RadarBand.NONE] a signal's life after the last one: the report keeps one band
 * per reading and answers any second from that list. Not thread-safe.
 */
interface SignalSmoother {
    fun add(rssi: Int, atMillis: Long)

    fun bandAt(nowMillis: Long): RadarBand

    /** The level the band is read from, dBm; null: no signal (none yet, or older than its life). */
    fun levelAt(nowMillis: Long): Double?
}

/** The competing smoothings by their technique ids (ADR 0017 §2.3): the report makes one per direction. */
object Smoothings {
    const val EMA = "smooth.ema"
    const val P80 = "smooth.p80"
    const val RATE = "smooth.rate"

    val ALL: List<String> = listOf(EMA, P80, RATE)

    fun create(id: String): SignalSmoother = when (id) {
        EMA -> EmaSmoother()
        P80 -> PercentileSmoother()
        RATE -> RateSmoother()
        else -> throw IllegalArgumentException("unknown smoothing $id")
    }
}

/** `smooth.ema`: the game's own [RadarSmoother], the baseline the others have to beat. */
class EmaSmoother : SignalSmoother {
    private val smoother = RadarSmoother()

    override fun add(rssi: Int, atMillis: Long) = smoother.add(rssi, atMillis)

    override fun bandAt(nowMillis: Long): RadarBand = smoother.bandAt(nowMillis)

    override fun levelAt(nowMillis: Long): Double? {
        val last = smoother.lastAtMillis ?: return null
        return smoother.levelDbm.takeIf { nowMillis - last <= ProximityRules.SIGNAL_TTL_MILLIS }
    }
}

/**
 * `smooth.p80`: the upper percentile of the readings of the last [windowMillis]. A body between the phones takes a few
 * dB off most readings and a wall's reflection adds to one now and then: the loud side of a short window is the
 * direct path, and one spike among a dozen readings doesn't move it (at an iPhone's one reading a second the window
 * holds two or three, and the spike does pass). Nearest rank, like the lab's summaries.
 */
class PercentileSmoother(private val windowMillis: Long = WINDOW_MILLIS, private val percent: Int = PERCENT) :
    SignalSmoother {
    private val times = ArrayDeque<Long>()
    private val values = ArrayDeque<Int>()
    private var lastAtMillis: Long? = null
    private var level: Double? = null
    private var band = RadarBand.NONE

    override fun add(rssi: Int, atMillis: Long) {
        val last = lastAtMillis
        if (last != null && atMillis < last - ProximityRules.SIGNAL_TTL_MILLIS) return
        val at = if (last == null) atMillis else maxOf(atMillis, last)
        val gone = last == null || at - last > ProximityRules.SIGNAL_TTL_MILLIS
        if (gone) {
            times.clear()
            values.clear()
        }
        times.addLast(at)
        values.addLast(rssi)
        while (times.first() <= at - windowMillis) {
            times.removeFirst()
            values.removeFirst()
        }
        val sorted = values.sorted()
        val rank = ceil(percent / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        val newLevel = sorted[rank - 1].toDouble()
        level = newLevel
        lastAtMillis = at
        band = ProximityRules.bandFor(newLevel, if (gone) RadarBand.NONE else band)
    }

    override fun bandAt(nowMillis: Long): RadarBand = if (isAlive(nowMillis)) band else RadarBand.NONE

    override fun levelAt(nowMillis: Long): Double? = level.takeIf { isAlive(nowMillis) }

    private fun isAlive(nowMillis: Long): Boolean =
        lastAtMillis?.let { nowMillis - it <= ProximityRules.SIGNAL_TTL_MILLIS } == true

    companion object {
        /** 2–3 s in the ADR: long enough for a dozen readings of an Android scanner, short enough for a walk. */
        const val WINDOW_MILLIS = 2_500L
        const val PERCENT = 80
    }
}

/**
 * `smooth.rate`: the game's level ([RadarSmoother]) plus how often the phone is heard, as a second sign of distance: a
 * phone far off loses more of its advertisements than one close by, whatever its RSSI says. [RATE_DB] per doubling of
 * the readings per second over the last [RATE_WINDOW_MILLIS] against [RATE_REFERENCE_PER_SECOND], at most
 * [MAX_BOOST_DB] either way; a signal younger than the window counts over its own life (at least a second), or its
 * first readings would read far. All of it the plan's guess: the phones hear at their own rates (an iPhone ranging a
 * beacon once a second, an Android scanner ten times), which this rewards and punishes alike, and only the run says
 * whether the rate tells distance better than it tells the phone.
 */
class RateSmoother : SignalSmoother {
    private val ema = RadarSmoother()
    private val times = ArrayDeque<Long>()
    private var firstAtMillis = 0L
    private var lastAtMillis: Long? = null
    private var level: Double? = null
    private var band = RadarBand.NONE

    override fun add(rssi: Int, atMillis: Long) {
        val last = lastAtMillis
        if (last != null && atMillis < last - ProximityRules.SIGNAL_TTL_MILLIS) return
        val at = if (last == null) atMillis else maxOf(atMillis, last)
        val gone = last == null || at - last > ProximityRules.SIGNAL_TTL_MILLIS
        if (gone) {
            times.clear()
            firstAtMillis = at
        }
        ema.add(rssi, atMillis)
        times.addLast(at)
        while (times.first() <= at - RATE_WINDOW_MILLIS) times.removeFirst()
        val span = (at - firstAtMillis).coerceIn(MIN_SPAN_MILLIS, RATE_WINDOW_MILLIS)
        val perSecond = times.size * 1000.0 / span
        val boost = (RATE_DB * log2(perSecond / RATE_REFERENCE_PER_SECOND)).coerceIn(-MAX_BOOST_DB, MAX_BOOST_DB)
        val newLevel = checkNotNull(ema.levelDbm) + boost
        level = newLevel
        lastAtMillis = at
        band = ProximityRules.bandFor(newLevel, if (gone) RadarBand.NONE else band)
    }

    override fun bandAt(nowMillis: Long): RadarBand = if (isAlive(nowMillis)) band else RadarBand.NONE

    override fun levelAt(nowMillis: Long): Double? = level.takeIf { isAlive(nowMillis) }

    private fun isAlive(nowMillis: Long): Boolean =
        lastAtMillis?.let { nowMillis - it <= ProximityRules.SIGNAL_TTL_MILLIS } == true

    companion object {
        const val RATE_DB = 3.0
        const val RATE_REFERENCE_PER_SECOND = 2.0
        const val RATE_WINDOW_MILLIS = 3_000L
        const val MAX_BOOST_DB = 6.0
        private const val MIN_SPAN_MILLIS = 1_000L
    }
}
