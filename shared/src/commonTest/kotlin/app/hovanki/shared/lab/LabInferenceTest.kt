package app.hovanki.shared.lab

import app.hovanki.shared.rules.Smoothings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `infer.witness` on made-up logs of a triangle: C hears A and B, A and B don't hear each other. */
class LabInferenceTest {
    private val a = RadarTestLog("A", "aaaa0001")
    private val b = RadarTestLog("B", "bbbb0002")
    private val c = RadarTestLog("C", "cccc0003")

    private fun track(vararg logs: RadarTestLog): BandTrack {
        val merge = radarMerge(*logs)
        val track = BandTrack(Smoothings.EMA, Calibrations.none())
        for (event in merge.events) track.feed(event, merge::sender)
        return track
    }

    private fun distances(ab: Double) = LabDistances(
        listOf(LabDistanceStretch(0, 60_000, mapOf("A|B" to ab, "A|C" to 3.0, "B|C" to 3.0))),
        emptyList(),
    )

    private val seconds = 1_000L..20_000L step 1_000

    @Test
    fun aSilentPairHeardLoudByAThirdIsInferredWarm() {
        c.hears(a, -65, 1_000, 21_000)
        c.hears(b, -64, 1_000, 21_000)
        val track = track(a, b, c)
        assertEquals(listOf("A|B"), WitnessInference.inferredAt(track, listOf("A", "B", "C"), 5_000))

        val result = WitnessInference.run(track, listOf("A", "B", "C"), distances(ab = 8.0), seconds)
        assertEquals(20, result.seconds)
        assertEquals(20, result.inferred, "$result")
        assertEquals(20, result.right, "8 m is within the warm band's 15 m")
        assertEquals(0, result.wrong)
        assertEquals(listOf("A|B"), result.pairs)

        val far = WitnessInference.run(track, listOf("A", "B", "C"), distances(ab = 30.0), seconds)
        assertEquals(20, far.wrong, "the truth says 30 m: the witness was wrong")
        assertEquals(0, far.right)
    }

    @Test
    fun noWitnessWhenThePairHearsItselfOrTheThirdIsFar() {
        // A hears B faintly: the pair isn't silent.
        c.hears(a, -65, 1_000, 21_000)
        c.hears(b, -65, 1_000, 21_000)
        a.hears(b, -80, 1_000, 21_000)
        assertEquals(0, WitnessInference.run(track(a, b, c), listOf("A", "B", "C"), distances(8.0), seconds).inferred)

        // C hears B only warm: B is not near enough C to say anything of A|B.
        val a2 = RadarTestLog("A", "aaaa0001")
        val b2 = RadarTestLog("B", "bbbb0002")
        val c2 = RadarTestLog("C", "cccc0003")
        c2.hears(a2, -65, 1_000, 21_000)
        c2.hears(b2, -80, 1_000, 21_000)
        val warm = WitnessInference.run(track(a2, b2, c2), listOf("A", "B", "C"), distances(8.0), seconds)
        assertEquals(0, warm.inferred)
        assertEquals(emptyList(), warm.pairs)
    }

    @Test
    fun twoPhonesHaveNoWitness() {
        a.hears(b, -65, 1_000, 21_000)
        val result = WitnessInference.run(track(a, b), listOf("A", "B"), distances(8.0), seconds)
        assertEquals(0, result.inferred)
        assertTrue(result.seconds > 0)
    }

    @Test
    fun theWitnessStopsWhenTheSignalGoes() {
        // C hears both for 5 s; the signal lives SIGNAL_TTL_MILLIS after the last reading, then the pair is unknown.
        c.hears(a, -65, 1_000, 6_000)
        c.hears(b, -65, 1_000, 6_000)
        val result = WitnessInference.run(track(a, b, c), listOf("A", "B", "C"), distances(8.0), seconds)
        assertTrue(result.inferred in 10..16, "$result")
    }
}
