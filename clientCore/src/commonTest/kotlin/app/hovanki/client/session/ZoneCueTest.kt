package app.hovanki.client.session

import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ZoneCircle
import app.hovanki.shared.protocol.ZoneSchedule
import app.hovanki.shared.protocol.ZoneStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZoneCueTest {
    private val center = GeoPoint(50.45, 30.52)
    private fun circle(radius: Double) = ZoneCircle(center, radius)

    // Hold 2 min, shrink 1 min (ends at 3:00); hold 1:30, shrink 0:30 (ends at 5:00).
    private val schedule = ZoneSchedule(
        initial = circle(300.0),
        stages = listOf(
            ZoneStage(holdSeconds = 120, shrinkSeconds = 60, target = circle(200.0)),
            ZoneStage(holdSeconds = 90, shrinkSeconds = 30, target = circle(100.0)),
        ),
    )

    private fun cueAt(millis: Long?) = schedule.momentAt(millis).cue

    @Test
    fun calmBeforeTheZoneStartsAndFarFromShrinking() {
        assertEquals(ZoneMoment.NOT_STARTED, schedule.momentAt(null))
        assertEquals(ZoneCue.CALM, cueAt(0))
        assertEquals(ZoneCue.CALM, cueAt(59_999))
    }

    @Test
    fun soonInTheLastMinuteThenACountdown() {
        assertEquals(ZoneCue.SOON, cueAt(60_000))
        assertEquals(ZoneCue.SOON, cueAt(109_999))
        assertEquals(ZoneCue.COUNTDOWN, cueAt(110_000))
        assertEquals(ZoneCue.COUNTDOWN, cueAt(119_999))
    }

    @Test
    fun shrinkingThenShrunkForAMomentThenCalmAgain() {
        assertEquals(ZoneCue.SHRINKING, cueAt(120_000))
        assertEquals(ZoneCue.SHRINKING, cueAt(179_999))
        assertEquals(ZoneCue.SHRUNK, cueAt(180_000))
        assertEquals(ZoneCue.SHRUNK, cueAt(181_499))
        assertEquals(ZoneCue.CALM, cueAt(181_500))
    }

    @Test
    fun theLastStageEndsInTheFinalZone() {
        assertEquals(ZoneCue.SOON, cueAt(210_000))
        assertEquals(ZoneCue.COUNTDOWN, cueAt(260_000))
        assertEquals(ZoneCue.SHRINKING, cueAt(270_000))
        assertEquals(ZoneCue.SHRUNK, cueAt(300_000))
        assertEquals(ZoneCue.FINAL, cueAt(301_500))
        assertEquals(ZoneCue.FINAL, cueAt(10_000_000))
    }

    @Test
    fun timeLeftAndTheSegmentItBelongsTo() {
        val hold = schedule.momentAt(90_000)
        assertEquals(30_000, hold.millisLeft)
        assertEquals(120_000, hold.segmentMillis)
        assertEquals(0.25f, hold.fractionLeft)

        val shrink = schedule.momentAt(150_000)
        assertEquals(30_000, shrink.millisLeft)
        assertEquals(60_000, shrink.segmentMillis)

        val final = schedule.momentAt(400_000)
        assertNull(final.millisLeft)
        assertNull(final.fractionLeft)
    }

    @Test
    fun aShortHoldIsSoonFromItsStart() {
        val short = ZoneSchedule(circle(300.0), listOf(ZoneStage(holdSeconds = 30, shrinkSeconds = 30, circle(150.0))))
        assertEquals(ZoneCue.SOON, short.momentAt(0).cue)
    }

    @Test
    fun anInstantShrinkIsShrunkRightAway() {
        val instant = ZoneSchedule(circle(300.0), listOf(ZoneStage(holdSeconds = 10, shrinkSeconds = 0, circle(150.0))))
        assertEquals(ZoneCue.SHRUNK, instant.momentAt(10_000).cue)
        assertEquals(ZoneCue.FINAL, instant.momentAt(11_500).cue)
    }

    @Test
    fun noStagesIsTheFinalZoneFromTheStart() {
        assertEquals(ZoneCue.FINAL, ZoneSchedule(circle(300.0)).momentAt(0).cue)
    }
}
