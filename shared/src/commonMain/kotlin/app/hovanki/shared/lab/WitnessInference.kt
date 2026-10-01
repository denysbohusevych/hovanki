package app.hovanki.shared.lab

import app.hovanki.shared.protocol.RadarBand

/**
 * `infer.witness` (docs/adr/0017-radar-techniques-and-big-run.md §2.3, «Вывод на сервере»): A and B don't hear each
 * other, but a third phone C hears both loudly: both are near C, so near each other. Two «burning» witnesses make the
 * pair «hot», two «hot» ones «warm» (each hop is up to a band's distance, and the two may be on opposite sides of C).
 * Could close the pair «two iPhones in pockets» when a third phone is near. Pure; checked on a run's triangles.
 */
object WitnessInference {
    /**
     * The pairs of [labels] that heard each other not at all in [bands] (a pair's band: the louder of its two
     * directions; a pair missing is [RadarBand.NONE]) for which a witness says better, with the band it says.
     */
    fun infer(labels: Collection<String>, bands: Map<LabPair, RadarBand>): Map<LabPair, RadarBand> {
        val result = HashMap<LabPair, RadarBand>()
        val all = labels.distinct().sorted()
        for ((index, a) in all.withIndex()) {
            for (b in all.subList(index + 1, all.size)) {
                val pair = LabPair.of(a, b)
                if ((bands[pair] ?: RadarBand.NONE) != RadarBand.NONE) continue
                val best = all.filter { it != a && it != b }.maxOfOrNull { witness ->
                    through(bands[LabPair.of(a, witness)], bands[LabPair.of(b, witness)])
                } ?: RadarBand.NONE
                if (best != RadarBand.NONE) result[pair] = best
            }
        }
        return result
    }

    /** What two hops of these bands say of the pair at their ends. */
    fun through(first: RadarBand?, second: RadarBand?): RadarBand {
        val weaker = minOf(first ?: RadarBand.NONE, second ?: RadarBand.NONE)
        return when (weaker) {
            RadarBand.BURNING -> RadarBand.HOT
            RadarBand.HOT -> RadarBand.WARM
            else -> RadarBand.NONE
        }
    }
}
