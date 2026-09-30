package app.hovanki.shared.rules

import app.hovanki.shared.protocol.RadarBand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The competing smoothings of ADR 0017 §2.3 on made-up series of one direction's readings. */
class SmoothingTest {
    /** [rssi] every [everyMillis] in `[from, to)`. */
    private fun SignalSmoother.feed(rssi: Int, from: Long, to: Long, everyMillis: Long) {
        var t = from
        while (t < to) {
            add(rssi, t)
            t += everyMillis
        }
    }

    @Test
    fun emaIsTheGamesSmoother() {
        val ema = EmaSmoother()
        val game = RadarSmoother()
        val series = listOf(-80, -78, -65, -62, -59, -70, -85, -60, -91, -75)
        for ((index, rssi) in series.withIndex()) {
            val t = index * 300L
            ema.add(rssi, t)
            game.add(rssi, t)
            assertEquals(game.bandAt(t), ema.bandAt(t), "at $t")
            assertEquals(game.levelDbm, ema.levelAt(t))
        }
    }

    @Test
    fun p80IgnoresASingleSpike() {
        val p80 = PercentileSmoother()
        val ema = EmaSmoother()
        for (smoother in listOf(p80, ema)) {
            // An Android scanner's five readings a second, and one reflection off a wall.
            smoother.feed(-80, from = 0, to = 3_000, everyMillis = 200)
            smoother.add(-40, 3_000)
        }
        assertEquals(RadarBand.WARM, p80.bandAt(3_000), "one spike among 13 readings")
        assertEquals(-80.0, p80.levelAt(3_000))
        assertEquals(RadarBand.HOT, ema.bandAt(3_000), "the game's smoothing takes a third of the way at once")
    }

    @Test
    fun p80FollowsASteadyRise() {
        val p80 = PercentileSmoother()
        p80.feed(-80, from = 0, to = 3_000, everyMillis = 100)
        p80.feed(-58, from = 3_000, to = 4_000, everyMillis = 100)
        assertEquals(RadarBand.BURNING, p80.bandAt(4_000))
    }

    @Test
    fun p80KeepsTheHysteresis() {
        val p80 = PercentileSmoother()
        p80.feed(-65, from = 0, to = 3_000, everyMillis = 100)
        assertEquals(RadarBand.HOT, p80.bandAt(3_000))
        p80.feed(-73, from = 3_000, to = 6_000, everyMillis = 100)
        assertEquals(RadarBand.HOT, p80.bandAt(6_000), "under HOT's entry, above its exit")
        p80.feed(-78, from = 6_000, to = 9_000, everyMillis = 100)
        assertEquals(RadarBand.WARM, p80.bandAt(9_000))
    }

    @Test
    fun rateRaisesAFrequentlyHeardPhone() {
        val often = RateSmoother()
        val seldom = RateSmoother()
        val ema = EmaSmoother()
        often.feed(-72, from = 0, to = 5_000, everyMillis = 100)
        ema.feed(-72, from = 0, to = 5_000, everyMillis = 100)
        seldom.feed(-72, from = 0, to = 5_000, everyMillis = 1_000)
        assertEquals(RadarBand.WARM, ema.bandAt(4_900), "-72 dBm alone is under HOT's entry")
        assertEquals(RadarBand.HOT, often.bandAt(4_900), "ten readings a second: at most 6 dB louder")
        assertEquals(-66.0, often.levelAt(4_900))
        assertEquals(RadarBand.WARM, seldom.bandAt(4_000))
        assertEquals(-75.0, seldom.levelAt(4_000), "one a second, half the reference: 3 dB quieter")
    }

    @Test
    fun aYoungSignalIsNotReadFar() {
        val rate = RateSmoother()
        rate.add(-72, 0)
        rate.add(-72, 500)
        assertEquals(-72.0, rate.levelAt(500), "two readings in its first second: the reference's rate")
    }

    @Test
    fun everySmootherFallsSilentAfterTheSignalsLife() {
        for (id in Smoothings.ALL) {
            val smoother = Smoothings.create(id)
            assertEquals(RadarBand.NONE, smoother.bandAt(0), id)
            assertNull(smoother.levelAt(0), id)
            smoother.feed(-58, from = 0, to = 2_000, everyMillis = 200)
            assertEquals(RadarBand.BURNING, smoother.bandAt(2_000), id)
            val silent = 1_800 + ProximityRules.SIGNAL_TTL_MILLIS + 1
            assertEquals(RadarBand.NONE, smoother.bandAt(silent), id)
            assertNull(smoother.levelAt(silent), id)
            // Back after its life: it starts again from the new readings, not from the old band.
            smoother.add(-80, silent + 1_000)
            assertEquals(RadarBand.WARM, smoother.bandAt(silent + 1_000), id)
        }
    }

    @Test
    fun theIdsAreTheCatalogs() {
        assertEquals(listOf("smooth.ema", "smooth.p80", "smooth.rate"), Smoothings.ALL)
        assertFailsWith<IllegalArgumentException> { Smoothings.create("smooth.kalman") }
    }
}
