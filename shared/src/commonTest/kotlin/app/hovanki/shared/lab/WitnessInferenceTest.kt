package app.hovanki.shared.lab

import app.hovanki.shared.protocol.RadarBand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `infer.witness` (ADR 0017 §2.3): A and B silent, C hears both. */
class WitnessInferenceTest {
    private val ab = LabPair.of("A", "B")
    private val ac = LabPair.of("A", "C")
    private val bc = LabPair.of("B", "C")

    @Test
    fun twoLoudWitnessesBringThePairClose() {
        val inferred = WitnessInference.infer(listOf("A", "B", "C"), mapOf(ac to RadarBand.BURNING, bc to RadarBand.HOT))

        assertEquals(mapOf(ab to RadarBand.WARM), inferred)
        assertEquals(
            mapOf(ab to RadarBand.HOT),
            WitnessInference.infer(listOf("A", "B", "C"), mapOf(ac to RadarBand.BURNING, bc to RadarBand.BURNING)),
        )
    }

    @Test
    fun aPairThatHearsItselfOrAWeakWitnessSaysNothing() {
        val labels = listOf("A", "B", "C")
        assertTrue(WitnessInference.infer(labels, mapOf(ac to RadarBand.WARM, bc to RadarBand.BURNING)).isEmpty())
        assertTrue(
            WitnessInference.infer(
                labels,
                mapOf(ab to RadarBand.WARM, ac to RadarBand.BURNING, bc to RadarBand.BURNING),
            ).isEmpty(),
        )
        assertTrue(WitnessInference.infer(listOf("A", "B"), emptyMap()).isEmpty())
    }

    @Test
    fun theBestWitnessCounts() {
        val labels = listOf("A", "B", "C", "D")
        val bands = mapOf(
            ac to RadarBand.HOT,
            bc to RadarBand.HOT,
            LabPair.of("A", "D") to RadarBand.BURNING,
            LabPair.of("B", "D") to RadarBand.BURNING,
        )

        assertEquals(RadarBand.HOT, WitnessInference.infer(labels, bands)[ab])
    }
}
