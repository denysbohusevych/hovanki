package app.hovanki.device

import app.hovanki.device.lab.HapticKind
import app.hovanki.shared.protocol.RadarBand
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The field build's round card on an iPhone (docs/adr/0018-field-test-build.md, wave 4): when it starts and ends, when
 * it is updated, how the pulse beats with it, and what the log is told.
 */
class RoundLiveActivityTest {
    private class FakeHost(override var isAvailable: Boolean = true) : LiveActivityHost {
        var refuse = false
        var refuseAlerts = false
        val calls = ArrayList<String>()

        override fun start(title: String, text: String): Boolean {
            calls += "start $title/$text"
            return !refuse
        }

        override fun update(text: String, band: Int, detail: String, endsAtMillis: Long) {
            calls += "update $text/$band/$detail"
        }

        override fun alert(title: String, text: String, silent: Boolean): Boolean {
            calls += "alert $title/$text/$silent"
            return !refuseAlerts
        }

        override fun end() {
            calls += "end"
        }
    }

    private class FakePlatform(var silentSound: Boolean = true) : LiveActivityPlatform {
        var resign: (() -> Unit)? = null

        override fun onWillResignActive(block: () -> Unit): () -> Unit {
            resign = block
            return { resign = null }
        }

        override fun prepareSilentSound(): Boolean = silentSound
    }

    private class Trace : PocketTrace {
        val lines = ArrayList<String>()

        override fun mode(mode: String, event: String, reason: String?) {
            lines += "$mode $event${reason?.let { " ($it)" }.orEmpty()}"
        }

        override fun haptic(kind: String, result: String, error: String?, reason: String?) {
            lines += "$kind $result${error?.let { ": $it" }.orEmpty()}"
        }
    }

    private val hider = LiveCard("You hide", RadarBand.NONE, "Radar: nothing")

    @Test
    fun startedOnTheScreenAndUpdatedOnlyOnChanges() {
        val host = FakeHost()
        val trace = Trace()
        val round = RoundLiveActivity(host, FakePlatform()).also { it.trace = trace }

        round.want("Hovanki", hider, onScreen = true)
        assertTrue(round.isRunning)
        assertEquals(listOf("start Hovanki/You hide", "update You hide/0/Radar: nothing"), host.calls)
        assertTrue("mode.live_activity live_activity_started (round)" in trace.lines, "${trace.lines}")

        // The same card again (every snapshot asks): iOS budgets the updates, nothing goes out.
        round.want("Hovanki", hider, onScreen = true)
        round.show(hider)
        assertEquals(2, host.calls.size)
        round.show(hider.copy(band = RadarBand.HOT, detail = "Hot!"))
        assertEquals("update You hide/2/Hot!", host.calls.last())
        assertEquals(2, round.updates)

        round.end("round_over")
        assertFalse(round.isRunning)
        assertEquals("end", host.calls.last())
        assertEquals("mode.live_activity live_activity_ended (round_over; updates 2, alerts 0)", trace.lines.last())
        // Ended twice: nothing more.
        round.end("left")
        assertEquals(1, host.calls.count { it == "end" })
    }

    @Test
    fun offTheScreenItWaitsForTheAppToLeaveTheScreenAgain() {
        val host = FakeHost()
        val platform = FakePlatform()
        val trace = Trace()
        val round = RoundLiveActivity(host, platform).also { it.trace = trace }

        // The round began while the app was in the background: iOS would refuse, so nothing is asked.
        round.want("Hovanki", hider, onScreen = false)
        assertTrue(host.calls.isEmpty())
        // Refused at the next resign (Live Activities off in the settings): written, tried again at the next one.
        host.refuse = true
        platform.resign?.invoke()
        assertFalse(round.isRunning)
        assertEquals("mode.live_activity live_activity_refused (will_resign)", trace.lines.last())
        host.refuse = false
        platform.resign?.invoke()
        assertTrue(round.isRunning)
        // One that runs is not started again.
        platform.resign?.invoke()
        assertEquals(2, host.calls.count { it.startsWith("start") }, "the refused one and the one that runs")

        round.end("left")
        assertEquals(null, platform.resign, "the resign is no longer watched")
    }

    @Test
    fun withoutTheSwiftHostNothingStartsAndThePulseKeepsItsNotification() = runTest {
        val host = FakeHost(isAvailable = false)
        val trace = Trace()
        val round = RoundLiveActivity(host, FakePlatform()).also { it.trace = trace }
        round.want("Hovanki", hider, onScreen = true)
        assertFalse(round.isRunning)
        assertTrue(host.calls.isEmpty())
        assertTrue(trace.lines.single().startsWith("mode.live_activity unavailable"), "${trace.lines}")

        val beat = round.pulse("A seeker is near!", "close by")
        assertFalse(beat.played)
        assertEquals("live_activity_alert_double skipped: no live activity running", trace.lines.last())
        assertEquals(PulseWay.NOTIFICATION, PocketPulseRules.way(onScreen = false, round.isRunning))
    }

    @Test
    fun aBeatIsTwoSilentAlerts300MsApart() = runTest {
        val host = FakeHost()
        val trace = Trace()
        val round = RoundLiveActivity(host, FakePlatform()).also { it.trace = trace }
        round.want("Hovanki", hider, onScreen = true)
        host.calls.clear()

        val beat = async { round.pulse("A seeker is near!", "close by") }
        runCurrent()
        assertEquals(listOf("alert A seeker is near!/close by/true"), host.calls)
        advanceTimeBy(RoundLiveActivity.ALERT_GAP_MILLIS - 1)
        runCurrent()
        assertEquals(1, host.calls.size)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(beat.await().played)
        assertEquals(2, host.calls.size)
        assertEquals(2, round.alerts)
        assertEquals("live_activity_alert_double played", trace.lines.last())

        // The host refuses: skipped, and the caller notifies instead.
        host.refuseAlerts = true
        assertFalse(round.pulse("A seeker is near!", "close by").played)
        assertEquals("live_activity_alert_double skipped: the host refused the alert", trace.lines.last())
    }

    @Test
    fun noAlertWithoutTheFileOfSilence() = runTest {
        val host = FakeHost()
        val trace = Trace()
        val round = RoundLiveActivity(host, FakePlatform(silentSound = false)).also { it.trace = trace }
        round.want("Hovanki", hider, onScreen = true)
        assertTrue("mode.live_activity silent_sound_missing (the pulse keeps its notification)" in trace.lines)
        // The default sound would give the hiding place away: not a single alert.
        assertFalse(round.pulse("A seeker is near!", "close by").played)
        assertTrue(host.calls.none { it.startsWith("alert") })
    }

    @Test
    fun thePulsesWayAndPace() {
        assertEquals(PulseWay.TAPS, PocketPulseRules.way(onScreen = true, liveActivityRunning = true))
        assertEquals(PulseWay.LIVE_ACTIVITY, PocketPulseRules.way(onScreen = false, liveActivityRunning = true))
        assertEquals(PulseWay.NOTIFICATION, PocketPulseRules.way(onScreen = false, liveActivityRunning = false))
        // The notification's pace is the one every build had.
        assertEquals(4_000L, PocketPulseRules.gapMillis(PulseWay.NOTIFICATION, RadarBand.WARM))
        assertEquals(4_000L, PocketPulseRules.gapMillis(PulseWay.NOTIFICATION, RadarBand.BURNING))
        // The alerts: the closer, the more often.
        val gaps = listOf(RadarBand.WARM, RadarBand.HOT, RadarBand.BURNING)
            .map { PocketPulseRules.gapMillis(PulseWay.LIVE_ACTIVITY, it) }
        assertEquals(gaps.sortedDescending(), gaps)
        assertTrue(gaps.all { it >= PocketPulseRules.NOTIFICATION_GAP_MILLIS })
        // The log's kind is the lab's.
        assertEquals(HapticKind.LIVE_ACTIVITY_ALERT_DOUBLE.key, RoundLiveActivity.KIND)
    }
}
