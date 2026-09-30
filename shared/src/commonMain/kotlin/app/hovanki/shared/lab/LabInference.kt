package app.hovanki.shared.lab

import app.hovanki.shared.protocol.RadarBand

/**
 * `infer.witness` over a run (docs/adr/0017-radar-techniques-and-big-run.md §2.3, «Вывод на сервере»): over [seconds]
 * whole seconds, [inferred] pair-seconds where a pair's silence was read through a third phone; the truth of the
 * distances agreed in [right] (the pair at most [LabBandTruth.WARM_M] apart), not in [wrong]; the rest had no distance.
 * [pairs]: the pairs ever inferred, sorted.
 */
data class WitnessResult(val seconds: Int, val inferred: Int, val right: Int, val wrong: Int, val pairs: List<String>)

/**
 * The witness (decision 5 of docs/radar-run.md §4): in a second where A and B don't hear each other (the pair's band is
 * NONE both ways) but a third phone C hears both at HOT or better (a pair's band the louder direction), A and B are
 * both near C and so near each other: the pair A|B is inferred WARM. A pure function over a [BandTrack]; the run's
 * distances say whether it was right.
 */
object WitnessInference {
    /** How loud C must have both: a HOT pair is some ten metres at most, two of them twenty, «warm» at worst. */
    val WITNESS_BAND = RadarBand.HOT

    /** What an inferred pair shows. */
    val INFERRED_BAND = RadarBand.WARM

    /** The pairs of [devices] inferred at [t] under [track]. */
    fun inferredAt(track: BandTrack, devices: List<String>, t: Long): List<String> {
        val sorted = devices.distinct().sorted()
        if (sorted.size < 3) return emptyList()
        val loud = HashSet<String>()
        for ((index, a) in sorted.withIndex()) {
            for (b in sorted.subList(index + 1, sorted.size)) {
                val pair = RunStep.pairKey(a, b)
                if (track.pairBandAt(pair, t) >= WITNESS_BAND) loud += pair
            }
        }
        val result = ArrayList<String>()
        for ((index, a) in sorted.withIndex()) {
            for (b in sorted.subList(index + 1, sorted.size)) {
                val pair = RunStep.pairKey(a, b)
                if (track.pairBandAt(pair, t) != RadarBand.NONE) continue
                val witnessed = sorted.any { c ->
                    c != a && c != b && RunStep.pairKey(a, c) in loud && RunStep.pairKey(b, c) in loud
                }
                if (witnessed) result += pair
            }
        }
        return result
    }

    /** Every whole second of [seconds] (stepped by a second), every pair of [devices]. */
    fun run(track: BandTrack, devices: List<String>, distances: LabDistances, seconds: LongProgression): WitnessResult {
        var count = 0
        var inferred = 0
        var right = 0
        var wrong = 0
        val pairs = HashSet<String>()
        for (t in seconds step BandErrors.SECOND_MILLIS) {
            count++
            for (pair in inferredAt(track, devices, t)) {
                inferred++
                pairs += pair
                val meters = distances.at(pair, t) ?: continue
                if (meters <= LabBandTruth.WARM_M) right++ else wrong++
            }
        }
        return WitnessResult(count, inferred, right, wrong, pairs.sorted())
    }
}
