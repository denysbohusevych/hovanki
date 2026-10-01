package app.hovanki.shared.lab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The touch found without a button (docs/adr/0017-radar-techniques-and-big-run.md §3), on invented journals. */
class TouchDetectorTest {
    /** A and B 2 m apart for [seconds] seconds, each heard by the other once a second at about −64 dBm. */
    private fun apart(from: Long, seconds: Int): List<PairReading> = (0 until seconds).flatMap { second ->
        val t = from + second * 1_000L
        listOf(PairReading("A", "B", t, -64 + second % 2), PairReading("B", "A", t + 300, -65))
    }

    @Test
    fun twoLoneJoltsAndAPeakAreATouch() {
        val readings = apart(0, 20) + listOf(PairReading("A", "B", 20_200, -42), PairReading("B", "A", 20_500, -44))
        val impacts = listOf(TouchImpact("A", 20_000, 2.1), TouchImpact("B", 20_090, 1.7))

        val touches = TouchDetector.detect(impacts, readings)

        val touch = touches.single()
        assertEquals(LabPair.of("B", "A"), touch.pair)
        assertEquals(20_045, touch.t)
        assertEquals(-42, touch.rssiAToB)
        assertEquals(-44, touch.rssiBToA)
        assertEquals(2.1, touch.impactA)
        assertEquals(1.7, touch.impactB)
        assertEquals(90, touch.skewMillis)
    }

    @Test
    fun joltsTooFarApartOrWithoutAPeakAreNoTouch() {
        val readings = apart(0, 30) + PairReading("A", "B", 20_200, -42)
        // 200 ms apart: two people bumping into things, not into each other.
        assertTrue(
            TouchDetector.detect(listOf(TouchImpact("A", 20_000, 2.0), TouchImpact("B", 20_200, 2.0)), readings)
                .isEmpty(),
        )
        // Together, but the pair's signal stays where it was: two phones dropped on two tables.
        assertTrue(
            TouchDetector.detect(listOf(TouchImpact("A", 25_000, 2.0), TouchImpact("B", 25_050, 2.0)), readings)
                .isEmpty(),
        )
        // Too soft.
        assertTrue(
            TouchDetector.detect(listOf(TouchImpact("A", 20_000, 0.3), TouchImpact("B", 20_050, 0.3)), readings)
                .isEmpty(),
        )
    }

    @Test
    fun walkingSideBySideIsNoTouch() {
        // Two people walk a metre apart for a minute: both phones jolt every step, often within 150 ms of each
        // other, and they hear each other loudly the whole time.
        val readings = (0 until 60).flatMap { second ->
            val t = second * 1_000L
            listOf(PairReading("A", "B", t, -55), PairReading("B", "A", t + 400, -56))
        }
        val impacts = (0 until 120).flatMap { step ->
            val t = step * 520L
            listOf(TouchImpact("A", t, 1.4), TouchImpact("B", t + 60, 1.3))
        }

        assertTrue(TouchDetector.detect(impacts, readings).isEmpty())
    }

    @Test
    fun oneTouchIsFoundOnce() {
        val readings = apart(0, 20) + PairReading("A", "B", 20_100, -40)
        // A third phone jolts at the same time too: it is not part of the pair the signal says.
        val impacts = listOf(
            TouchImpact("A", 20_000, 2.0),
            TouchImpact("B", 20_040, 2.0),
            TouchImpact("C", 20_080, 2.0),
        )

        val touches = TouchDetector.detect(impacts, readings)

        assertEquals(listOf(LabPair.of("A", "B")), touches.map { it.pair })
    }

    @Test
    fun withoutAnyBaselineALoudPeakIsEnough() {
        val readings = listOf(PairReading("B", "A", 5_100, -50))
        val impacts = listOf(TouchImpact("A", 5_000, 1.2), TouchImpact("B", 5_020, 1.0))

        val touch = TouchDetector.detect(impacts, readings).single()

        assertEquals(null, touch.rssiAToB)
        assertEquals(-50, touch.rssiBToA)
    }
}
