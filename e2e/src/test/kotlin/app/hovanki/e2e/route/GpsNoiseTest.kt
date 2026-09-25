package app.hovanki.e2e.route

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpsNoiseTest {
    private val truth = GeoPoint(50.4476, 30.5396)

    @Test
    fun errorStaysWithinTheReportedAccuracy() {
        val noise = GpsNoise.openSky(seed = 7)
        val fixes = List(2_000) { noise.fix(truth, it.toLong()) }

        assertTrue(fixes.all { it.point.distanceTo(truth) <= it.accuracyMeters + 0.01 })
        assertTrue(fixes.all { it.accuracyMeters in 4.0..8.0 })
        // Gaussian, not a constant offset: the error spreads over the accuracy circle.
        val errors = fixes.map { it.point.distanceTo(truth) }
        assertTrue(errors.average() in 1.0..5.0, "mean error ${errors.average()}")
    }

    @Test
    fun cityHasRareSpikesAndJumps() {
        val noise = GpsNoise.city(seed = 7)
        val fixes = List(5_000) { noise.fix(truth, it.toLong()) }

        val spikes = fixes.count { it.accuracyMeters >= 30.0 }
        val jumps = fixes.count { it.point.distanceTo(truth) > it.accuracyMeters + 1 }
        assertTrue(spikes in 250..550, "spikes: $spikes")
        assertTrue(jumps in 80..220, "jumps: $jumps")
        assertTrue(
            fixes.filter { it.accuracyMeters >= 30.0 }.all {
                it.point.distanceTo(truth) <=
                    it.accuracyMeters + 0.01
            },
        )
    }

    @Test
    fun sameSeedSameFixes() {
        val a = List(50) { GpsNoise.city(seed = 3).fix(truth, 0) }
        val b = List(50) { GpsNoise.city(seed = 3).fix(truth, 0) }
        assertEquals(a, b)
        val mocked = GpsNoise.NONE.fix(truth, 5, isMock = true)
        assertEquals(truth, mocked.point)
        assertTrue(mocked.isMock)
    }
}
