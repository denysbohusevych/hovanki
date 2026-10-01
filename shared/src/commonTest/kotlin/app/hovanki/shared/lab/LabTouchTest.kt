package app.hovanki.shared.lab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The touch detector (ADR 0017 §3) on made-up logs of two phones that hear each other at −70 dBm for 40 s. */
class LabTouchTest {
    private val a = RadarTestLog("A", "aaaa0001")
    private val b = RadarTestLog("B", "bbbb0002")

    init {
        a.hears(b, -70, 0, 40_000, everyMillis = 500)
        b.hears(a, -70, 0, 40_000, everyMillis = 500)
    }

    /** Both phones hear each other louder just after [t]: A hears B at [heardByA], B hears A at [heardByB]. */
    private fun peak(t: Long, heardByA: Int, heardByB: Int) {
        a.rx(t + 150, b, heardByA)
        b.rx(t + 250, a, heardByB)
    }

    private fun find(): Pair<List<Touch>, List<LabEvent>> {
        val merge = radarMerge(a, b)
        val marks = merge.events.filter { it.k == "mark" }
        val touches = TouchDetector.find(merge.events, merge::sender, marks)
        return touches to TouchDetector.missed(marks, touches)
    }

    @Test
    fun twoImpactsAndAPeakAreATouch() {
        a.impact(20_000, 1.8)
        b.impact(20_100, 2.1)
        peak(20_050, heardByA = -42, heardByB = -45)
        // The tester pressed «чокнулись» a second later, naming the pair the other way round.
        a.mark(21_000, "touch B|A", "by" to "user", "action" to "touch")
        val (touches, missed) = find()
        val touch = touches.single()
        assertEquals("A|B", touch.pairKey)
        assertEquals(20_050, touch.t, "between the two impacts")
        assertEquals(mapOf("A|B" to -45, "B|A" to -42), touch.rssi, "by direction, the sender first")
        assertEquals(mapOf("A" to 1.8, "B" to 2.1), touch.peaksG)
        assertEquals(21_000, touch.truthT)
        assertEquals(emptyList(), missed)
    }

    @Test
    fun anImpactsTimeIsItsEventsLessItsAgo() {
        // The phone writes an impact once the peak is over: `ago` says how long before.
        a.impact(20_150, 1.8, ago = 150)
        b.impact(20_300, 2.1, ago = 200)
        peak(20_050, heardByA = -42, heardByB = -45)
        assertEquals(listOf(20_050L), find().first.map { it.t })
    }

    @Test
    fun impactsOnOnePhoneAreNoTouch() {
        a.impact(20_000, 1.8)
        a.impact(20_100, 2.1)
        peak(20_000, heardByA = -42, heardByB = -45)
        assertEquals(emptyList(), find().first)
    }

    @Test
    fun impactsFarApartAreNoTouch() {
        a.impact(20_000, 1.8)
        b.impact(20_400, 2.1)
        peak(20_200, heardByA = -42, heardByB = -45)
        a.touched(21_000, "B")
        val (touches, missed) = find()
        assertEquals(emptyList(), touches)
        assertEquals(listOf(21_000L), missed.map { it.t }, "the button's touch nobody detected")
    }

    @Test
    fun impactsWithoutAPeakAreNoTouch() {
        // Two people bumping their phones at the same moment 10 m apart: quieter than a moment ago.
        val quiet = RadarTestLog("A", "aaaa0001")
        val loud = RadarTestLog("B", "bbbb0002")
        quiet.hears(loud, -50, 0, 18_000, everyMillis = 500)
        quiet.hears(loud, -60, 18_000, 25_000, everyMillis = 500)
        quiet.impact(20_000, 1.8)
        loud.impact(20_100, 2.1)
        val merge = radarMerge(quiet, loud)
        assertEquals(emptyList(), TouchDetector.find(merge.events, merge::sender, emptyList()))
        // And without any reading around the impacts.
        val silent = radarMerge(RadarTestLog("A", "aaaa0001").apply { impact(20_000, 1.8) }, loud)
        assertEquals(emptyList(), TouchDetector.find(silent.events, silent::sender, emptyList()))
    }

    @Test
    fun impactsWhileHeardSteadilyAreNoTouch() {
        // Two people walking in step side by side: both phones jolt at once, the signal as loud as it has been.
        val left = RadarTestLog("A", "aaaa0001")
        val right = RadarTestLog("B", "bbbb0002")
        left.hears(right, -60, 0, 25_000, everyMillis = 500)
        right.hears(left, -60, 0, 25_000, everyMillis = 500)
        left.impact(20_000, 1.8)
        right.impact(20_050, 2.1)
        val merge = radarMerge(left, right)
        assertEquals(emptyList(), TouchDetector.find(merge.events, merge::sender, emptyList()))
    }

    @Test
    fun aBounceIsOneTouch() {
        a.impact(20_000, 1.8)
        b.impact(20_100, 2.1)
        a.impact(20_200, 0.9)
        peak(20_050, heardByA = -42, heardByB = -45)
        assertEquals(1, find().first.size)
    }

    @Test
    fun touchesInARowAreEachFound() {
        // Three touches 5 s apart: the first one's peak doesn't hide the next, quieter one.
        for ((t, heard) in listOf(10_000L to (-40 to -42), 15_000L to (-44 to -43), 20_000L to (-46 to -41))) {
            a.impact(t, 1.5)
            b.impact(t + 50, 1.6)
            peak(t, heardByA = heard.first, heardByB = heard.second)
        }
        val touches = find().first
        assertEquals(3, touches.size)
        assertTrue(touches.all { it.rssi.keys == setOf("A|B", "B|A") }, "$touches")
        assertNull(touches.first().truthT, "no button pressed")
        assertEquals(
            listOf(
                TouchSpread("A|B", "A|B", touches = 3, spreadDb = 2, driftDb = 1),
                TouchSpread("A|B", "B|A", touches = 3, spreadDb = 6, driftDb = -6),
            ),
            touches.spreads(),
        )
    }

    @Test
    fun bothTestersPressingIsOneTouch() {
        a.touched(21_000, "B")
        b.touched(21_500, "A")
        a.touched(35_000, "B")
        a.mark(36_000, "pocket", "by" to "tester", "place" to LabPlaces.POCKET_FRONT)
        a.impact(20_000, 1.8)
        b.impact(20_100, 2.1)
        peak(20_050, heardByA = -42, heardByB = -45)
        val (touches, missed) = find()
        assertEquals(21_000, touches.single().truthT, "the nearest of the two")
        assertEquals(listOf(35_000L), missed.map { it.t })
    }

    @Test
    fun aTouchMarkNamesItsPair() {
        val merge = radarMerge(a.apply { touched(1_000, "B") })
        val mark = merge.events.single { it.k == "mark" }
        assertEquals("A|B", TouchDetector.touchPair(mark))
        assertNull(TouchDetector.touchPair(merge.events.first { it.k == "rx" }))
    }
}
