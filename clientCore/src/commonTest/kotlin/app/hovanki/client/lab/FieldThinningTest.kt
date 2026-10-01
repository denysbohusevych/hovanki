package app.hovanki.client.lab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The field log's thinning (docs/adr/0018-field-test-build.md §3.2): a reading a second per peer, frames every 10 s. */
class FieldThinningTest {
    private val a = FieldThinning.RxKey("0123abcd", "android_le", "service_data", null)
    private val b = FieldThinning.RxKey("feedbeef", "corebluetooth", "name", "p1")

    @Test
    fun aPeersReadingsOfASecondAreOneSummary() {
        val thinning = FieldThinning(rxEveryMillis = 1_000)
        assertTrue(thinning.rx(a, -70, 1_000).isEmpty())
        assertTrue(thinning.rx(a, -60, 1_300).isEmpty())
        assertTrue(thinning.rx(a, -80, 1_999).isEmpty())
        assertTrue(thinning.rx(b, -50, 1_500).isEmpty())

        // The next second's reading closes the window: the count, the median and the loudest.
        val closed = thinning.rx(a, -65, 2_000)
        assertEquals(listOf(FieldThinning.RxSummary(a, 3, -70, -60, 1_000, 1_999)), closed)
        // The tick writes what is over: b's window, not a's new one.
        val flushed = thinning.flush(2_600)
        assertEquals(listOf(b), flushed.map { it.key })
        assertEquals(Triple(1, -50, -50), flushed.single().let { Triple(it.count, it.median, it.max) })
        assertTrue(thinning.flush(2_900).isEmpty())
        // At the end everything goes.
        assertEquals(listOf(FieldThinning.RxSummary(a, 1, -65, -65, 2_000, 2_000)), thinning.flush(2_900, all = true))
        assertTrue(thinning.flush(10_000, all = true).isEmpty())
    }

    @Test
    fun aBusyAirStaysBounded() {
        val thinning = FieldThinning()
        // More readings than kept for the median: the count goes on.
        repeat(FieldThinning.MAX_READINGS + 50) { thinning.rx(a, -60 - it % 3, 1_000L + it) }
        val summary = thinning.flush(5_000).single()
        assertEquals(FieldThinning.MAX_READINGS + 50, summary.count)
        // More peers than kept: the oldest window is closed for the newest.
        val many = (0 until FieldThinning.MAX_PEERS).map { FieldThinning.RxKey("t$it", "api", "via", null) }
        many.forEach { thinning.rx(it, -70, 6_000) }
        val pushed = thinning.rx(b, -70, 6_001)
        assertEquals(listOf(many.first()), pushed.map { it.key })
    }

    @Test
    fun framesAndTheAirComeEveryTenSecondsAndGpsEverySecond() {
        val thinning = FieldThinning(frameEveryMillis = 10_000, gpsEveryMillis = 1_000)
        assertTrue(thinning.allowThrottled("frame", "p1", 0))
        assertFalse(thinning.allowThrottled("frame", "p1", 9_999))
        // Per kind and peer.
        assertTrue(thinning.allowThrottled("frame", "p2", 5_000))
        assertTrue(thinning.allowThrottled("air", null, 5_000))
        assertTrue(thinning.allowThrottled("frame", "p1", 10_000))

        assertTrue(thinning.allowGps(1_000))
        assertFalse(thinning.allowGps(1_500))
        assertTrue(thinning.allowGps(2_000))
        // A fix from before (the clock went back) is not held back for ever.
        assertTrue(thinning.allowGps(500))
    }
}
