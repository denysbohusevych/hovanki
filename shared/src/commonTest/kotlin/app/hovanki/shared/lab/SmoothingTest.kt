package app.hovanki.shared.lab

import app.hovanki.shared.protocol.RadarBand
import kotlin.test.Test
import kotlin.test.assertEquals

/** The competing smoothings and the calibrations' numbers (ADR 0017 §2.3, §3), on invented readings. */
class SmoothingTest {
    private val seconds = (1..20).map { it * 1_000L }

    @Test
    fun theGamesSmoothingFollowsASteadySignal() {
        val readings = (0 until 20).map { TimedRssi(it * 1_000L + 500, -65.0) }

        val bands = Smoothing.bands(SmoothingVariant.EMA, readings, seconds)

        assertEquals(RadarBand.HOT, bands.last())
        assertEquals(RadarBand.NONE, Smoothing.bands(SmoothingVariant.EMA, readings, listOf(40_000L)).single())
    }

    @Test
    fun theLoudestFifthHoldsWhenABodyTurns() {
        // Ten readings a second: mostly −85 (a body in the way), one in five at −62 (the line of sight).
        val readings = (0 until 200).map { i -> TimedRssi(i * 100L, if (i % 5 == 4) -62.0 else -85.0) }

        val p80 = Smoothing.bands(SmoothingVariant.P80, readings, seconds)
        val ema = Smoothing.bands(SmoothingVariant.EMA, readings, seconds)

        assertEquals(RadarBand.HOT, p80.last())
        assertEquals(RadarBand.WARM, ema.last())
    }

    @Test
    fun aRarelyHeardPhoneIsOneBandColder() {
        // Loud, but heard once in four seconds: most of its packets are lost on the way.
        val rare = (0 until 5).map { TimedRssi(it * 4_000L, -65.0) }
        val often = (0 until 20).map { TimedRssi(it * 1_000L, -65.0) }

        assertEquals(RadarBand.WARM, Smoothing.bands(SmoothingVariant.RATE, rare, seconds)[17])
        assertEquals(RadarBand.HOT, Smoothing.bands(SmoothingVariant.EMA, rare, seconds)[17])
        assertEquals(RadarBand.HOT, Smoothing.bands(SmoothingVariant.RATE, often, seconds).last())
    }

    @Test
    fun theBandsMeaningByDistance() {
        assertEquals(RadarBand.BURNING, BandTruth.of(0.0))
        assertEquals(RadarBand.BURNING, BandTruth.of(3.0))
        assertEquals(RadarBand.HOT, BandTruth.of(5.0))
        assertEquals(RadarBand.WARM, BandTruth.of(20.0))
        assertEquals(RadarBand.NONE, BandTruth.of(40.0))
    }

    @Test
    fun theTouchOffsetSpreadAndDrift() {
        // What b heard of a at four touches: the first three calibrate, the last one is the drift.
        val touches = listOf(-50, -47, -49, -55)

        assertEquals(4.0, Calibration.touchOffset(touches))
        assertEquals(3.0, Calibration.spread(touches))
        assertEquals(-6.0, Calibration.drift(touches))
        assertEquals(null, Calibration.touchOffset(emptyList()))
        assertEquals(null, Calibration.spread(listOf(-50)))
        assertEquals(null, Calibration.drift(listOf(-50)))
    }

    @Test
    fun modelOffsetsFromTheCatches() {
        val offsets = ModelOffsets.fromCatches(
            listOf(
                CalibrationSample("iPhone15,2", "Pixel 8", -70, 10),
                CalibrationSample("iPhone15,2", "Pixel 8", -68, 20),
                CalibrationSample("iPhone15,2", "Pixel 8", -60, 5),
                // Too few readings to say anything.
                CalibrationSample("Pixel 8", "iPhone15,2", -62, 3),
            ),
        )

        assertEquals(8.0, offsets.of("iPhone15,2", "Pixel 8"))
        assertEquals(null, offsets.of("Pixel 8", "iPhone15,2"))
        assertEquals(null, offsets.of(null, "Pixel 8"))
    }
}
