package app.hovanki.device.lab

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/** Knocks in the acceleration's magnitude (docs/adr/0017-radar-techniques-and-big-run.md §3). */
class ImpactDetectorTest {
    /** Readings at 50 Hz from [fromMillis] for [millis]: [magnitude] of the time since the start. */
    private fun ImpactDetector.feed(fromMillis: Long, millis: Long, magnitude: (Long) -> Double): List<Impact> =
        (fromMillis until fromMillis + millis step 20L).mapNotNull { at -> add(at, magnitude(at)) }

    @Test
    fun aSpikeGivesOneImpactWithItsPeak() {
        val detector = ImpactDetector()
        val spike = mapOf(1_000L to 1.9, 1_020L to 2.4, 1_040L to 1.5)
        val impacts = detector.feed(0, 3_000) { spike[it] ?: 1.0 }
        assertEquals(listOf(Impact(1_020, 1.4)), impacts.map { it.copy(peakG = round(it.peakG)) })
    }

    @Test
    fun walkingGivesNone() {
        val detector = ImpactDetector()
        val impacts = detector.feed(0, 30_000) { 1.0 + 0.3 * sin(2 * PI * 1.8 * it / 1000.0) }
        assertEquals(emptyList(), impacts)
    }

    @Test
    fun twoSpikesCloseTogetherAreOneImpact() {
        val detector = ImpactDetector()
        val spikes = mapOf(1_000L to 1.8, 1_100L to 2.0)
        val impacts = detector.feed(0, 3_000) { spikes[it] ?: 1.0 }
        assertEquals(1, impacts.size, "$impacts")
        assertEquals(1_100L, impacts.single().atMillis, "the stronger of the two")
        assertEquals(1.0, round(impacts.single().peakG))
    }

    @Test
    fun spikesFarApartAreTwoImpacts() {
        val detector = ImpactDetector()
        val spikes = mapOf(1_000L to 1.8, 2_000L to 0.2)
        val impacts = detector.feed(0, 3_000) { spikes[it] ?: 1.0 }
        assertEquals(listOf(1_000L, 2_000L), impacts.map { it.atMillis })
    }

    private fun round(value: Double): Double = kotlin.math.round(value * 100) / 100
}
