package app.hovanki.shared.rules

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.ZoneCircle
import app.hovanki.shared.protocol.ZonePolygon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZoneAreasTest {
    private val center = GeoPoint(50.4501, 30.5234)

    /** 200 m east–west, 100 m north–south, around [center]. */
    private val block = ZoneArea.Polygon(
        ZonePolygon(
            listOf(
                center.moveBy(-100.0, -50.0),
                center.moveBy(100.0, -50.0),
                center.moveBy(100.0, 50.0),
                center.moveBy(-100.0, 50.0),
                center.moveBy(-100.0, -50.0),
            ),
        ),
    )

    @Test
    fun negativeInsidePositiveOutside() {
        assertEquals(-50.0, block.signedDistanceMeters(center), 0.1)
        assertEquals(-10.0, block.signedDistanceMeters(center.moveBy(90.0, 0.0)), 0.1)
        assertEquals(30.0, block.signedDistanceMeters(center.moveBy(0.0, 80.0)), 0.1)
        // Off a corner: the distance to the corner.
        assertEquals(50.0, block.signedDistanceMeters(center.moveBy(130.0, 90.0)), 0.1)
    }

    @Test
    fun theWayBackIsToTheNearestBorder() {
        val outside = center.moveBy(0.0, 80.0)

        val border = block.nearestBorderPoint(outside)

        assertEquals(30.0, border.distanceTo(outside), 0.1)
        assertEquals(50.0, border.distanceTo(center), 0.1)
    }

    @Test
    fun aCircleIsTheSameAsBefore() {
        val circle = ZoneArea.Circle(ZoneCircle(center, 100.0))

        assertEquals(-100.0, circle.signedDistanceMeters(center), 1e-9)
        assertEquals(50.0, circle.signedDistanceMeters(center.moveBy(150.0, 0.0)), 0.01)
        assertEquals(100.0, circle.nearestBorderPoint(center.moveBy(150.0, 0.0)).distanceTo(center), 0.01)
    }

    @Test
    fun theRulesJudgeByThePolygon() {
        val rules = GameRules()
        fun fix(east: Double, north: Double, accuracy: Double = 5.0) =
            LocationSample(center.moveBy(east, north), accuracy, 0)

        // 20 m outside: the whole accuracy circle and the margin are out.
        assertTrue(ZoneRules.isClearlyOutside(fix(0.0, 70.0), block, rules))
        // 10 m outside, 5 m accuracy: within the margin, in doubt the player is inside.
        assertFalse(ZoneRules.isClearlyOutside(fix(0.0, 60.0), block, rules))
        // Inside the block, whatever the accuracy.
        assertFalse(ZoneRules.isClearlyOutside(fix(90.0, 0.0, accuracy = 50.0), block, rules))
    }

    @Test
    fun theStreetZoneOfTheStage() {
        val smaller = ZonePolygon(
            listOf(
                center.moveBy(-50.0, -25.0),
                center.moveBy(50.0, -25.0),
                center.moveBy(50.0, 25.0),
                center.moveBy(-50.0, 25.0),
                center.moveBy(-50.0, -25.0),
            ),
        )
        val zone = StreetZone(listOf(block.polygon, smaller))
        val schedule =
            shrinkingZone(
                center,
                initialRadiusMeters = 500.0,
                finalRadiusMeters = 200.0,
                steps = 1,
                holdSeconds = 60,
                shrinkSeconds = 30,
            )

        assertEquals(block.polygon, (schedule.areaAt(0, zone) as ZoneArea.Polygon).polygon)
        // Still the first zone while the next one is announced; the next one once the stage is over.
        assertEquals(0, schedule.stateAt(89_999).stage)
        assertEquals(1, schedule.stateAt(90_000).stage)
        assertEquals(smaller, (schedule.areaAt(90_000, zone) as ZoneArea.Polygon).polygon)
        assertTrue(schedule.areaAt(0, null) is ZoneArea.Circle)
    }

    @Test
    fun aStreetZoneShrinksBlockByBlock() {
        // The first stage is the 200 × 100 m block, the second a 100 × 50 m one in its middle.
        val smaller = ZoneArea.Polygon(
            ZonePolygon(
                listOf(
                    center.moveBy(-50.0, -25.0),
                    center.moveBy(50.0, -25.0),
                    center.moveBy(50.0, 25.0),
                    center.moveBy(-50.0, 25.0),
                    center.moveBy(-50.0, -25.0),
                ),
            ),
        )
        val zone = StreetZone(listOf(block.polygon, smaller.polygon))
        val schedule = shrinkingZone(
            center,
            initialRadiusMeters = 500.0,
            finalRadiusMeters = 200.0,
            steps = 1,
            holdSeconds = 60,
            shrinkSeconds = 30,
        )
        val inTheBlocksThatGo = center.moveBy(90.0, 0.0)
        val nearerIn = center.moveBy(60.0, 0.0)
        val staying = center.moveBy(40.0, 0.0)
        fun inside(point: GeoPoint, atMillis: Long) = schedule.areaAt(atMillis, zone).signedDistanceMeters(point) < 0

        assertTrue(schedule.areaAt(60_000, zone) is ZoneArea.Shrinking, "while the stage shrinks")
        // At the start all of the first stage, at the end only the second: no jump either way.
        val points = listOf(inTheBlocksThatGo, nearerIn, staying, center.moveBy(0.0, 45.0), center.moveBy(99.0, 49.0))
        for (point in points) {
            assertEquals(block.signedDistanceMeters(point) < 0, inside(point, 60_000), "start: $point")
            assertEquals(smaller.signedDistanceMeters(point) < 0, inside(point, 89_999), "end: $point")
        }
        // Halfway the cut is ~68 m around the center (from the far corner, 112 m, to within the next block, 25 m).
        val halfway = schedule.areaAt(75_000, zone) as ZoneArea.Shrinking
        assertEquals((111.8 + 25.0) / 2, halfway.cut.radiusMeters, 0.5)
        assertFalse(inside(inTheBlocksThatGo, 75_000), "the far blocks are gone")
        assertTrue(inside(nearerIn, 75_000), "the nearer ones not yet")
        assertTrue(inside(staying, 75_000), "the next zone stays")
        // The way back from the blocks that went: to what is left, not to the next zone.
        val way = halfway.nearestBorderPoint(center.moveBy(95.0, 0.0))
        assertEquals(68.4, way.distanceTo(center), 0.5)
        // Only shrinks: once out, out for the rest of the stage.
        val samples = (-100..100 step 10).flatMap { east ->
            (-50..50 step 10).map { north -> center.moveBy(east.toDouble(), north.toDouble()) }
        }
        for (point in samples) {
            val insideOverTime = (60_000L..90_000L step 1_000).map { inside(point, it) }
            assertEquals(insideOverTime, insideOverTime.sortedDescending(), "monotonic at $point")
        }
    }
}
