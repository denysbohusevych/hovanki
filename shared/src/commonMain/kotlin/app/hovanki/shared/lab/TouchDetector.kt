package app.hovanki.shared.lab

/** Two devices of a run, whichever heard whom: [a] sorts before [b]. */
data class LabPair private constructor(val a: String, val b: String) {
    /** `A|B`, as [RunStep.pairKey]. */
    val key: String get() = "$a|$b"

    fun has(label: String): Boolean = label == a || label == b

    /** The other one of the pair. */
    fun other(label: String): String = if (label == a) b else a

    override fun toString(): String = key

    companion object {
        fun of(x: String, y: String): LabPair = if (x <= y) LabPair(x, y) else LabPair(y, x)
    }
}

/** A lone sharp jolt one phone felt ([TouchFields.IMPACT]) at [t] (server time), [g] beyond gravity. */
data class TouchImpact(val dev: String, val t: Long, val g: Double)

/** [to] heard [from] at [t] (server time) at [rssi] dBm, read by the channel [tech]. */
data class PairReading(val from: String, val to: String, val t: Long, val rssi: Int, val tech: String? = null)

/**
 * A touch of the [pair] at [t] (server time): the loudest each heard of the other around it ([rssiAToB]: what `b`
 * heard of `a`; null: nothing), the two jolts ([impactA], [impactB], g) and how far apart they were ([skewMillis]).
 */
data class DetectedTouch(
    val pair: LabPair,
    val t: Long,
    val rssiAToB: Int?,
    val rssiBToA: Int?,
    val impactA: Double?,
    val impactB: Double?,
    val skewMillis: Long?,
)

/**
 * Finds the moments two phones touched (docs/adr/0017-radar-techniques-and-big-run.md §3) without a button: a jolt in
 * both phones' accelerometers within [WINDOW_MILLIS] of each other by the server's clock, each a lone one (no other
 * jolt of that phone within [ISOLATION_MILLIS]: walking jolts every step), and the pair's signal peaking in the same
 * second ([PEAK_WINDOW_MILLIS] around it): at least [TOUCH_MIN_DBM], and [RISE_DB] above the pair's median of the
 * seconds before ([BASELINE_FROM_MILLIS]…[BASELINE_TO_MILLIS] before), when there were any. Pure: the journal's
 * events in, the touches out. The numbers are guesses until the big run's touches with the button say better.
 */
object TouchDetector {
    const val WINDOW_MILLIS = 150L
    const val ISOLATION_MILLIS = 700L
    const val MIN_G = 0.8
    const val PEAK_WINDOW_MILLIS = 1_500L
    const val TOUCH_MIN_DBM = -60
    const val RISE_DB = 6
    const val BASELINE_FROM_MILLIS = 15_000L
    const val BASELINE_TO_MILLIS = 2_000L

    /** Jolts of one pair closer than this are one touch. */
    const val SAME_TOUCH_MILLIS = 2_000L

    fun detect(impacts: List<TouchImpact>, readings: List<PairReading>): List<DetectedTouch> {
        val lone = impacts.filter { it.g >= MIN_G }.groupBy { it.dev }.flatMap { (_, own) ->
            val sorted = own.sortedBy { it.t }
            sorted.filterIndexed { index, impact ->
                val before = sorted.getOrNull(index - 1)
                val after = sorted.getOrNull(index + 1)
                (before == null || impact.t - before.t > ISOLATION_MILLIS) &&
                    (after == null || after.t - impact.t > ISOLATION_MILLIS)
            }
        }.sortedBy { it.t }
        val byPair = PairReadings(readings)
        val found = ArrayList<DetectedTouch>()
        for ((index, first) in lone.withIndex()) {
            for (second in lone.subList(index + 1, lone.size)) {
                if (second.t - first.t > WINDOW_MILLIS) break
                if (second.dev == first.dev) continue
                val pair = LabPair.of(first.dev, second.dev)
                val t = (first.t + second.t) / 2
                val last = found.lastOrNull { it.pair == pair }
                if (last != null && t - last.t < SAME_TOUCH_MILLIS) continue
                val peak = byPair.peak(pair, t) ?: continue
                val (impactA, impactB) = if (first.dev == pair.a) first.g to second.g else second.g to first.g
                found += DetectedTouch(pair, t, peak.first, peak.second, impactA, impactB, second.t - first.t)
            }
        }
        return found
    }

    /** The readings by pair, in time order. */
    internal class PairReadings(readings: List<PairReading>) {
        private val byPair: Map<LabPair, List<PairReading>> =
            readings.groupBy { LabPair.of(it.from, it.to) }.mapValues { (_, list) -> list.sortedBy { it.t } }

        /** The loudest each of the [pair] heard of the other within [PEAK_WINDOW_MILLIS] of [t]: a's by b, b's by a. */
        fun loudest(pair: LabPair, t: Long): Pair<Int?, Int?> {
            val near = between(pair, t - PEAK_WINDOW_MILLIS, t + PEAK_WINDOW_MILLIS)
            val aToB = near.filter { it.from == pair.a }.maxOfOrNull { it.rssi }
            val bToA = near.filter { it.from == pair.b }.maxOfOrNull { it.rssi }
            return aToB to bToA
        }

        /** The loudest each way when the pair's signal peaks at [t]; null: it doesn't. */
        fun peak(pair: LabPair, t: Long): Pair<Int?, Int?>? {
            val (aToB, bToA) = loudest(pair, t)
            val loudest = listOfNotNull(aToB, bToA).maxOrNull() ?: return null
            if (loudest < TOUCH_MIN_DBM) return null
            val before = between(pair, t - BASELINE_FROM_MILLIS, t - BASELINE_TO_MILLIS).map { it.rssi }.sorted()
            if (before.isNotEmpty() && loudest < before[before.size / 2] + RISE_DB) return null
            return aToB to bToA
        }

        fun between(pair: LabPair, from: Long, to: Long): List<PairReading> {
            val list = byPair[pair] ?: return emptyList()
            var low = 0
            var high = list.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (list[middle].t < from) low = middle + 1 else high = middle
            }
            val result = ArrayList<PairReading>()
            for (index in low until list.size) {
                if (list[index].t > to) break
                result += list[index]
            }
            return result
        }
    }
}
