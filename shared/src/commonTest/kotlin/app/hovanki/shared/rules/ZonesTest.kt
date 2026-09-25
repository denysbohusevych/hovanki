package app.hovanki.shared.rules

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZonesTest {
    private val center = GeoPoint(50.4501, 30.5234)
    private val schedule = shrinkingZone(
        center,
        initialRadiusMeters = 500.0,
        finalRadiusMeters = 200.0,
        steps = 3,
        holdSeconds = 60,
        shrinkSeconds = 60,
    )

    @Test
    fun holdsThenShrinksLinearly() {
        assertEquals(500.0, schedule.circleAt(0).radiusMeters)
        assertEquals(500.0, schedule.circleAt(59_999).radiusMeters)
        assertEquals(450.0, schedule.circleAt(90_000).radiusMeters, 1e-9)
        assertEquals(400.0, schedule.circleAt(120_000).radiusMeters)
        assertEquals(200.0, schedule.circleAt(10 * 60_000).radiusMeters)
    }

    @Test
    fun stateDescribesWhatHappensNext() {
        val holding = schedule.stateAt(30_000)
        assertFalse(holding.isShrinking)
        assertEquals(400.0, holding.next?.radiusMeters)
        assertEquals(30_000, holding.millisUntilChange)

        val shrinking = schedule.stateAt(75_000)
        assertTrue(shrinking.isShrinking)
        assertEquals(45_000, shrinking.millisUntilChange)

        val done = schedule.stateAt(60 * 60_000)
        assertNull(done.next)
        assertNull(done.millisUntilChange)
    }

    @Test
    fun outsideOnlyWithSeveralClearFixes() {
        val rules = GameRules()
        val zone = schedule.initial
        val farOutside = fixesAt(center.moveBy(eastMeters = 600.0, northMeters = 0.0), accuracy = 10.0)
        val onTheBorder = fixesAt(center.moveBy(eastMeters = 515.0, northMeters = 0.0), accuracy = 10.0)

        assertTrue(ZoneRules.isConfidentlyOutside(farOutside, zone, rules))
        assertFalse(ZoneRules.isConfidentlyOutside(farOutside.take(2), zone, rules), "a single fix never decides")
        assertFalse(ZoneRules.isConfidentlyOutside(onTheBorder, zone, rules), "GPS error keeps the player inside")
    }

    private fun fixesAt(point: GeoPoint, accuracy: Double) =
        (0 until 3).map { LocationSample(point, accuracy, timestampMillis = it * 5_000L) }
}
