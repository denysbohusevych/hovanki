package app.hovanki.shared.lab

import kotlin.math.abs

/**
 * A touch found in the logs (docs/adr/0017-radar-techniques-and-big-run.md §3, «Чокнуться телефонами»): two phones
 * knocked together at [t] (server time, between the two impacts). [rssi]: by direction (`from|to`,
 * [Calibration.direction]), the loudest reading around the touch of every direction that peaked there; [peaksG]: by
 * device, the impact's peak in g over gravity; [truthT]: the tester's «чокнулись» mark it matches, null for none.
 * These are the report's rows, not log events: a touch needs both phones' logs.
 */
data class Touch(
    val pairKey: String,
    val t: Long,
    val rssi: Map<String, Int>,
    val peaksG: Map<String, Double>,
    val truthT: Long?,
)

/**
 * Finds the touches without the button (ADR 0017 §3): an impact on each of two phones within [WINDOW_MILLIS] of each
 * other by the server's clock, and at the same moment an RSSI peak of the pair: within [RSSI_WINDOW_MILLIS] of the
 * impacts a direction reads no quieter than [PEAK_SLACK_DB] under its loudest of the [PEAK_LOOKBACK_MILLIS] before,
 * and at least [PEAK_RISE_DB] over their median (the moments around the pair's touches already found are left out
 * of that look back: a run touches three times in a row, and one touch's peak must not hide the next). A phone put
 * down on a table, or two people walking, bump one phone or bump both at different moments; two phones knocked by
 * chance while far apart have no peak, and two people walking in step side by side, heard steadily, have no rise. The
 * button's marks (`mark` with `action` = `touch`, `label` = `touch A|B`) are the truth the detector is checked against,
 * never an input.
 */
object TouchDetector {
    /** The two impacts within ±150 ms by the server's clock (its offsets are measured to some tens of ms). */
    const val WINDOW_MILLIS = 150L

    /** The RSSI peak within ±1 s of the impacts: the phones hear each other at most a few times a second. */
    const val RSSI_WINDOW_MILLIS = 1_000L

    /** The peak: not below the direction's loudest of the previous 20 s minus [PEAK_SLACK_DB]. */
    const val PEAK_LOOKBACK_MILLIS = 20_000L
    const val PEAK_SLACK_DB = 3

    /**
     * The floor of a peak: at least this far over the median of the look back, or a steady signal would pass the rule
     * above and its level be taken for the touch's. Skipped with fewer than [MIN_RISE_READINGS] readings to take a
     * median of (a direction just heard, or heard once in a while): the loudest-of rule alone then.
     */
    const val PEAK_RISE_DB = 6
    const val MIN_RISE_READINGS = 3

    /** A mark this close to a touch of its pair is its truth. */
    const val TRUTH_WINDOW_MILLIS = 2_000L

    /** One touch per pair per second: a phone bounces. */
    const val MIN_GAP_MILLIS = 1_000L

    /** The mark's action of the «чокнулись» button, and its label's prefix before the pair's key. */
    const val TOUCH_ACTION = "touch"
    const val LABEL_PREFIX = "touch "

    private class Impact(val dev: String, val t: Long, val peakG: Double)

    /** One direction's readings, by time. */
    private class Readings(val times: LongArray, val rssi: IntArray)

    /**
     * The touches in [events] (their `impact` and `rx`; an impact's time is its event's less its `ago`), matched to
     * the touch marks among [marks]. [sender]: who a token is.
     */
    fun find(events: List<LabEvent>, sender: (String?) -> String, marks: List<LabEvent>): List<Touch> {
        val impacts = events.asSequence()
            .filter { it.k == "impact" }
            .map { Impact(it.dev, it.t - (it.long("ago") ?: 0L), it.double("peak") ?: 0.0) }
            .sortedBy { it.t }
            .toList()
        if (impacts.size < 2) return emptyList()
        val devices = impacts.mapTo(HashSet()) { it.dev }
        val readings = events.asSequence()
            .filter { it.k == "rx" && it.int("rssi") != null && it.dev in devices }
            .mapNotNull { rx -> sender(rx.string("token")).takeIf { it in devices && it != rx.dev }?.let { it to rx } }
            .groupBy({ (from, rx) -> Calibration.direction(from, rx.dev) }, { it.second })
            .mapValues { (_, list) ->
                val sorted = list.sortedBy { it.t }
                Readings(LongArray(sorted.size) { sorted[it].t }, IntArray(sorted.size) { sorted[it].int("rssi")!! })
            }
        val truths = touchMarks(marks)
        val found = HashMap<String, MutableList<Long>>()
        val result = ArrayList<Touch>()
        for ((index, first) in impacts.withIndex()) {
            for (second in impacts.subList(index + 1, impacts.size)) {
                if (second.t - first.t > WINDOW_MILLIS) break
                if (second.dev == first.dev) continue
                val pair = RunStep.pairKey(first.dev, second.dev)
                val t = (first.t + second.t) / 2
                val earlier = found[pair].orEmpty()
                if (earlier.isNotEmpty() && t - earlier.last() < MIN_GAP_MILLIS) continue
                val rssi = peaks(readings, first.dev, second.dev, t, earlier)
                if (rssi.isEmpty()) continue
                found.getOrPut(pair) { ArrayList() } += t
                result += Touch(
                    pairKey = pair,
                    t = t,
                    rssi = rssi,
                    peaksG = listOf(first.dev to first.peakG, second.dev to second.peakG).sortedBy { it.first }.toMap(),
                    truthT = truths.filter { it.first == pair && abs(it.second - t) <= TRUTH_WINDOW_MILLIS }
                        .minByOrNull { abs(it.second - t) }?.second,
                )
            }
        }
        return result
    }

    /**
     * The touch marks among [marks] nobody detected: the misses. Marks of one pair within [TRUTH_WINDOW_MILLIS] of
     * each other are one touch (both testers pressed the button), the first of them kept.
     */
    fun missed(marks: List<LabEvent>, touches: List<Touch>): List<LabEvent> {
        val kept = ArrayList<Pair<String, LabEvent>>()
        for (mark in marks.filter { it.k == "mark" }.sortedBy { it.t }) {
            val pair = touchPair(mark) ?: continue
            if (kept.any { it.first == pair && mark.t - it.second.t <= TRUTH_WINDOW_MILLIS }) continue
            kept += pair to mark
        }
        return kept.filter { (pair, mark) ->
            touches.none { it.pairKey == pair && abs(it.t - mark.t) <= TRUTH_WINDOW_MILLIS }
        }.map { it.second }
    }

    /** The pair a touch mark names (`touch B|A` → `A|B`); null for another mark. */
    fun touchPair(mark: LabEvent): String? {
        if (mark.k != "mark" || mark.string("action") != TOUCH_ACTION) return null
        val devices = mark.string("label")?.removePrefix(LABEL_PREFIX)?.trim()?.split('|') ?: return null
        if (devices.size != 2 || devices.any { it.isBlank() }) return null
        return RunStep.pairKey(devices[0], devices[1])
    }

    private fun touchMarks(marks: List<LabEvent>): List<Pair<String, Long>> =
        marks.mapNotNull { mark -> touchPair(mark)?.let { it to mark.t } }

    /** The directions of [a] and [b] that peaked around [t], with their loudest reading there. */
    private fun peaks(
        readings: Map<String, Readings>,
        a: String,
        b: String,
        t: Long,
        earlierTouches: List<Long>,
    ): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        for (direction in listOf(Calibration.direction(a, b), Calibration.direction(b, a)).sorted()) {
            val list = readings[direction] ?: continue
            val windowStart = t - RSSI_WINDOW_MILLIS
            val peak = loudest(list, windowStart, t + RSSI_WINDOW_MILLIS) { true } ?: continue
            val before = within(list, windowStart - PEAK_LOOKBACK_MILLIS, windowStart - 1) { at ->
                earlierTouches.none { abs(it - at) <= RSSI_WINDOW_MILLIS }
            }.sorted()
            if (before.isNotEmpty() && peak < before.last() - PEAK_SLACK_DB) continue
            val median = LabMerge.percentile(before, 50)
            if (before.size >= MIN_RISE_READINGS && median != null && peak < median + PEAK_RISE_DB) continue
            result[direction] = peak
        }
        return result
    }

    /** The loudest reading in `[from, to]` whose time passes [counts]; null: none. */
    private fun loudest(list: Readings, from: Long, to: Long, counts: (Long) -> Boolean): Int? =
        within(list, from, to, counts).maxOrNull()

    /** The readings in `[from, to]` whose time passes [counts]. */
    private fun within(list: Readings, from: Long, to: Long, counts: (Long) -> Boolean): List<Int> {
        var index = lastAtOrBefore(list.times, from - 1) + 1
        val result = ArrayList<Int>()
        while (index < list.times.size && list.times[index] <= to) {
            if (counts(list.times[index])) result += list.rssi[index]
            index++
        }
        return result
    }
}

/**
 * The repeatability of a pair's touches in one direction (ADR 0017 §3): [spreadDb] the loudest touch less the
 * quietest, [driftDb] the last touch less the first (the run touches again at its end). `calib.touch` stays when the
 * spread is at most 6 dB.
 */
data class TouchSpread(
    val pairKey: String,
    val direction: String,
    val touches: Int,
    val spreadDb: Int,
    val driftDb: Int,
)

/** Per pair and direction, sorted by both. */
fun List<Touch>.spreads(): List<TouchSpread> = groupBy {
    it.pairKey
}.entries.sortedBy { it.key }.flatMap { (pair, touches) ->
    val byTime = touches.sortedBy { it.t }
    byTime.flatMap { it.rssi.keys }.distinct().sorted().map { direction ->
        val values = byTime.mapNotNull { it.rssi[direction] }
        TouchSpread(
            pairKey = pair,
            direction = direction,
            touches = values.size,
            spreadDb = values.max() - values.min(),
            driftDb = values.last() - values.first(),
        )
    }
}
