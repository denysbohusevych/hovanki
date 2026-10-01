package app.hovanki.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The lone jolt of a touch against the jolts of walking (ADR 0017 §3), on invented accelerometers at 100 Hz. */
class ImpactDetectorTest {
    /** 1 g at rest with [jolts] (time → magnitude) for [millis], read every 10 ms; what the detector told. */
    private fun feed(millis: Long, jolts: Map<Long, Double>, detector: ImpactDetector = ImpactDetector()) =
        (0..millis step ImpactDetector.SAMPLING_MILLIS).mapNotNull { t -> detector.add(t, jolts[t] ?: 1.0) }

    @Test
    fun aKnockIsOneImpactAtItsPeak() {
        // A knock rings for 30 ms: one impact, its time the peak's, told once the quiet after it is over.
        val impacts = feed(3_000, mapOf(1_000L to 2.4, 1_010L to 3.1, 1_020L to 1.9))

        assertEquals(listOf(Impact(1_010, 2.1)), impacts.map { it.copy(g = (it.g * 10).toInt() / 10.0) })
    }

    @Test
    fun walkingIsNoImpact() {
        // A step every 520 ms for a minute, each a jolt of 1.4 g beyond gravity.
        val steps = (0 until 115).associate { (500L + it * 520L) / 10 * 10 to 2.4 }

        assertTrue(feed(61_000, steps).isEmpty())
    }

    @Test
    fun softMovesAreNoImpact() {
        assertTrue(feed(3_000, mapOf(1_000L to 1.5, 1_500L to 0.6)).isEmpty())
    }

    @Test
    fun twoKnocksFarApartAreTwoImpacts() {
        val impacts = feed(6_000, mapOf(1_000L to 2.5, 4_000L to -0.2))

        assertEquals(listOf(1_000L, 4_000L), impacts.map { it.atMillis })
    }

    @Test
    fun aKnockRightAfterAnotherIsNotLone() {
        // Two knocks 400 ms apart: neither is lone (the phone was shaken, or dropped and bounced).
        assertTrue(feed(4_000, mapOf(1_000L to 2.5, 1_400L to 2.5)).isEmpty())
    }
}
