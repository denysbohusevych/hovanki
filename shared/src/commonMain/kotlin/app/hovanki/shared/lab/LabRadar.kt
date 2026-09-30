package app.hovanki.shared.lab

import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.rules.ProximityRules
import app.hovanki.shared.rules.SignalSmoother
import app.hovanki.shared.rules.Smoothings
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The truth of a pair's band from how far apart the phones are (docs/radar-run.md §4): ADR 0012's numbers («RSSI — не
 * метры»: −60 dBm at a metre, −75 at five, −85 at 15–20) turned into metres, so a band the radar shows can be held
 * against a tape measure. The plan's guesses: the run's data may move them.
 */
object LabBandTruth {
    const val BURNING_M = 1.5
    const val HOT_M = 4.0
    const val WARM_M = 15.0

    fun bandFor(meters: Double): RadarBand = when {
        meters <= BURNING_M -> RadarBand.BURNING
        meters <= HOT_M -> RadarBand.HOT
        meters <= WARM_M -> RadarBand.WARM
        else -> RadarBand.NONE
    }
}

/**
 * A stretch of a run with the script's distances: `[start, end)` on the server's clock, [meters] by the pair's key
 * ([RunStep.pairKey]), as the step's [RunStep.distances] say; empty for a stretch without a step (before the first).
 */
data class LabDistanceStretch(val start: Long, val end: Long, val meters: Map<String, Double>)

/**
 * How far apart a pair was at a moment: from a hand mark with a `distance`, else from the script's stretch. A device's
 * mark with a distance holds for every pair of that device (the phone following a run marks the nearest of its
 * distances, a tester the one they measured) from the mark until the next such mark of either phone of the pair, or
 * until the end of the stretch it was made in (outside every stretch: until the next one starts). The run's own marks
 * (`by` = `run`) are left out: their distance is the smallest of the step's, and [stretches] have them all by pair.
 * Answers by binary search, so a report can ask every second of every pair.
 */
class LabDistances(stretches: List<LabDistanceStretch>, marks: List<LabEvent>) {
    private val stretches = stretches.filter { it.end > it.start }.sortedBy { it.start }
    private val starts = LongArray(this.stretches.size) { this.stretches[it].start }

    /** Device → its hand marks with a distance: times and metres, by time. */
    private val marks: Map<String, Pair<LongArray, DoubleArray>> = marks
        .asSequence()
        .filter { it.k == "mark" && it.string("by") != RUN_MARK }
        .mapNotNull { mark -> mark.double("distance")?.let { Triple(mark.dev, mark.t, it) } }
        .groupBy { it.first }
        .mapValues { (_, list) ->
            val sorted = list.sortedBy { it.second }
            LongArray(sorted.size) { sorted[it].second } to DoubleArray(sorted.size) { sorted[it].third }
        }

    /** [pairKey]'s distance at [t], metres; null: nobody said. */
    fun at(pairKey: String, t: Long): Double? {
        val devices = pairKey.split('|')
        var markAt = Long.MIN_VALUE
        var markMeters: Double? = null
        if (devices.size == 2) {
            for (device in devices) {
                val (times, meters) = marks[device] ?: continue
                val index = lastAtOrBefore(times, t)
                if (index >= 0 && times[index] >= markAt) {
                    markAt = times[index]
                    markMeters = meters[index]
                }
            }
        }
        if (markMeters != null && t < holdsUntil(markAt)) return markMeters
        val stretch = stretchIndex(t).takeIf { it >= 0 }?.let { stretches[it] }?.takeIf { t < it.end }
        return stretch?.meters?.get(pairKey)
    }

    /** The end of the stretch a mark at [markAt] was made in; outside every stretch, the next one's start. */
    private fun holdsUntil(markAt: Long): Long {
        val index = stretchIndex(markAt)
        if (index >= 0 && markAt < stretches[index].end) return stretches[index].end
        return starts.getOrNull(index + 1) ?: Long.MAX_VALUE
    }

    private fun stretchIndex(t: Long): Int = lastAtOrBefore(starts, t)

    private companion object {
        const val RUN_MARK = "run"
    }
}

/** The last index of [sorted] (ascending) at or before [t]; -1: none. */
internal fun lastAtOrBefore(sorted: LongArray, t: Long, size: Int = sorted.size): Int {
    var low = 0
    var high = size - 1
    var found = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (sorted[middle] <= t) {
            found = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return found
}

/**
 * A calibration: what is added to every reading of a direction before it is smoothed, dB, by the direction's key
 * ([direction]: `from|to`, the sender first; unlike a pair's key, not sorted). A direction without an offset gets none.
 */
data class Calibration(val id: String, val offsetsDb: Map<String, Double>) {
    fun offset(from: String, to: String): Double = offsetsDb[direction(from, to)] ?: 0.0

    companion object {
        /** `from|to`: [from] the sender, [to] the listener. */
        fun direction(from: String, to: String): String = "$from|$to"
    }
}

/**
 * The competing calibrations (docs/adr/0017-radar-techniques-and-big-run.md §2.3 and §3), computed from the run's own
 * logs. Both `model` and `touch` are measured on the run they are then scored on: they say how much a calibration can
 * give at best, and the run's second half or the next run says whether it holds.
 */
object Calibrations {
    const val NONE = "calib.none"
    const val MODEL = "calib.model"
    const val TOUCH = "calib.touch"

    val ALL: List<String> = listOf(NONE, MODEL, TOUCH)

    /** Two phones back to back: what a direction should read at a touch (a guess until the run). */
    const val TOUCH_REFERENCE_DBM = -40.0

    /** What a direction should read at a metre (ADR 0012 §1.1), the reference of [model]. */
    const val ONE_METER_REFERENCE_DBM = -60.0

    /** A reading counts for [model] when its pair was this close to a metre apart. */
    const val ONE_METER_TOLERANCE_M = 0.25

    fun none(): Calibration = Calibration(NONE, emptyMap())

    /**
     * `calib.model`: by the model pair (the sender's model → the listener's), the median of the readings while the
     * pair stood a metre apart, and every direction of that model pair gets [ONE_METER_REFERENCE_DBM] − that median.
     * The game would take these numbers from the server's `radio_calibration` table; the report has no database and
     * takes the run's own metre steps instead. [readings]: `rx` events (others are skipped); [models]: device → model.
     */
    fun model(
        readings: List<LabEvent>,
        sender: (String?) -> String,
        distances: LabDistances,
        models: Map<String, String?>,
    ): Calibration {
        val atOneMeter = HashMap<String, MutableList<Int>>()
        val directions = HashMap<String, String>()
        for (rx in readings) {
            if (rx.k != "rx") continue
            val rssi = rx.int("rssi") ?: continue
            val from = sender(rx.string("token"))
            val to = rx.dev
            if (from == to) continue
            val modelPair = modelPair(models[from] ?: continue, models[to] ?: continue)
            directions[Calibration.direction(from, to)] = modelPair
            val meters = distances.at(RunStep.pairKey(from, to), rx.t) ?: continue
            if (abs(meters - 1.0) > ONE_METER_TOLERANCE_M) continue
            atOneMeter.getOrPut(modelPair) { ArrayList() } += rssi
        }
        val medians = atOneMeter.mapValues { (_, values) -> median(values) }
        val offsets = directions.mapNotNull { (direction, modelPair) ->
            medians[modelPair]?.let { direction to ONE_METER_REFERENCE_DBM - it }
        }.toMap()
        return Calibration(MODEL, offsets)
    }

    /**
     * `calib.touch` (ADR 0017 §3): every direction a touch was heard in gets [TOUCH_REFERENCE_DBM] − the median of
     * its RSSI at the touches (a run touches three times at the start and once at the end).
     */
    fun touch(touches: List<Touch>): Calibration {
        val byDirection = HashMap<String, MutableList<Int>>()
        for (touch in touches) {
            for ((direction, rssi) in touch.rssi) byDirection.getOrPut(direction) { ArrayList() } += rssi
        }
        return Calibration(TOUCH, byDirection.mapValues { (_, values) -> TOUCH_REFERENCE_DBM - median(values) })
    }

    private fun modelPair(from: String, to: String) = "$from→$to"

    private fun median(values: List<Int>): Double = LabMerge.percentile(values.sorted(), 50)!!.toDouble()
}

/**
 * The bands of every direction and pair of a run under one smoothing ([Smoothings]) and one [calibration], as the
 * game would have shown them: one smoother per direction over all its channels (as the server keeps them), a pair's
 * band the louder of its two directions. Fed the run's `rx` events once, in time order; then answers any moment from
 * the band each reading left (a smoother's band changes only at a reading, see
 * [SignalSmoother]), by binary search: no pass over the events per question.
 */
class BandTrack(val smoothing: String, val calibration: Calibration) {
    private class History(val smoother: SignalSmoother) {
        var times = LongArray(INITIAL)
        var bands = ByteArray(INITIAL)
        var size = 0

        fun add(t: Long, band: RadarBand) {
            if (size == times.size) {
                times = times.copyOf(size * 2)
                bands = bands.copyOf(size * 2)
            }
            times[size] = t
            bands[size] = band.ordinal.toByte()
            size++
        }
    }

    private val histories = LinkedHashMap<String, History>()

    init {
        require(smoothing in Smoothings.ALL) { "unknown smoothing $smoothing" }
    }

    /** The directions heard (`from|to`, [Calibration.direction]). */
    val directions: Set<String> get() = histories.keys

    /** The pairs heard in either direction ([RunStep.pairKey]). */
    val pairs: Set<String>
        get() = histories.keys.mapTo(LinkedHashSet()) { key ->
            val (from, to) = key.split('|', limit = 2)
            RunStep.pairKey(from, to)
        }

    /** One reading; anything but an `rx` with an RSSI, and a device hearing itself, is skipped. */
    fun feed(rx: LabEvent, sender: (String?) -> String) {
        if (rx.k != "rx") return
        val rssi = rx.int("rssi") ?: return
        val from = sender(rx.string("token"))
        val to = rx.dev
        if (from == to) return
        val history = histories.getOrPut(Calibration.direction(from, to)) { History(Smoothings.create(smoothing)) }
        val at = if (history.size == 0) rx.t else maxOf(rx.t, history.times[history.size - 1])
        history.smoother.add((rssi + calibration.offset(from, to)).roundToInt(), rx.t)
        history.add(at, history.smoother.bandAt(at))
    }

    /** What [to] heard of [from] at [t]. */
    fun directionBandAt(from: String, to: String, t: Long): RadarBand {
        val history = histories[Calibration.direction(from, to)] ?: return RadarBand.NONE
        val index = lastAtOrBefore(history.times, t, history.size)
        if (index < 0 || t - history.times[index] > ProximityRules.SIGNAL_TTL_MILLIS) return RadarBand.NONE
        return RadarBand.entries[history.bands[index].toInt()]
    }

    /** The pair's band at [t]: the louder direction. */
    fun pairBandAt(pairKey: String, t: Long): RadarBand {
        val devices = pairKey.split('|')
        if (devices.size != 2) return RadarBand.NONE
        return maxOf(directionBandAt(devices[0], devices[1], t), directionBandAt(devices[1], devices[0], t))
    }

    private companion object {
        const val INITIAL = 64
    }
}

/**
 * How far a band track was from the truth ([LabBandTruth] of [LabDistances]) over the [seconds] that had one: bands
 * exactly right, one off (HOT for BURNING), wrong by more, and the mean distance in bands. «One off» is counted apart:
 * the criterion of ADR 0017 §3 («на 10 процентных пунктов») is on the share of [exact] seconds.
 */
data class BandError(
    val smoothing: String,
    val calibration: String,
    val seconds: Int,
    val exact: Int,
    val oneOff: Int,
    val wrong: Int,
    val meanError: Double,
) {
    /** The share of exact seconds, 0 without any. */
    val exactShare: Double get() = if (seconds == 0) 0.0 else exact.toDouble() / seconds
}

/** The band errors of the competing smoothings and calibrations (ADR 0017 §2.3), recomputed from the logs. */
object BandErrors {
    const val SECOND_MILLIS = 1_000L

    /** [track] against [distances] for every pair of [pairs] at every second of [seconds] (stepped by a second). */
    fun of(track: BandTrack, distances: LabDistances, pairs: List<String>, seconds: LongProgression): BandError {
        var counted = 0
        var exact = 0
        var oneOff = 0
        var wrong = 0
        var total = 0L
        for (t in seconds step SECOND_MILLIS) {
            for (pair in pairs) {
                val meters = distances.at(pair, t) ?: continue
                val error = abs(track.pairBandAt(pair, t).ordinal - LabBandTruth.bandFor(meters).ordinal)
                counted++
                total += error
                when (error) {
                    0 -> exact++
                    1 -> oneOff++
                    else -> wrong++
                }
            }
        }
        return BandError(
            smoothing = track.smoothing,
            calibration = track.calibration.id,
            seconds = counted,
            exact = exact,
            oneOff = oneOff,
            wrong = wrong,
            meanError = if (counted == 0) 0.0 else total.toDouble() / counted,
        )
    }

    /**
     * Every smoothing × every calibration ([Smoothings.ALL] × [Calibrations.ALL], in that order) over the run's
     * [events] (sorted by time, as [LabMerge] has them; only the `rx` are read). [models]: device → model for
     * `calib.model`; [touches] for `calib.touch` (none: no offsets). [pairs] and [seconds]: by default every pair of
     * the devices that wrote [events], every second from the first event to the last.
     */
    fun all(
        events: List<LabEvent>,
        sender: (String?) -> String,
        distances: LabDistances,
        touches: List<Touch>,
        models: Map<String, String?>,
        pairs: List<String> = pairsOf(events),
        seconds: LongProgression = secondsOf(events),
    ): List<BandError> {
        val readings = events.filter { it.k == "rx" }
        val calibrations = listOf(
            Calibrations.none(),
            Calibrations.model(readings, sender, distances, models),
            Calibrations.touch(touches),
        )
        return Smoothings.ALL.flatMap { smoothing ->
            calibrations.map { calibration ->
                val track = BandTrack(smoothing, calibration)
                for (rx in readings) track.feed(rx, sender)
                of(track, distances, pairs, seconds)
            }
        }
    }

    /** Every pair of the devices that wrote [events], sorted. */
    fun pairsOf(events: List<LabEvent>): List<String> {
        val devices = events.map { it.dev }.distinct().sorted()
        return devices.flatMapIndexed { index, a -> devices.drop(index + 1).map { b -> RunStep.pairKey(a, b) } }
    }

    /** The whole seconds from the first of [events] to the last; empty without events. */
    fun secondsOf(events: List<LabEvent>): LongProgression {
        if (events.isEmpty()) return LongRange.EMPTY
        val first = ceil(events.minOf { it.t } / SECOND_MILLIS.toDouble()).toLong() * SECOND_MILLIS
        return first..events.maxOf { it.t } step SECOND_MILLIS
    }
}
