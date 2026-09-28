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
    fun containsAgreesWithTheDistance() {
        val circle = ZoneArea.Circle(ZoneCircle(center, 100.0))
        for (east in listOf(-150.0, -99.0, -20.0, 0.0, 60.0, 99.0, 101.0, 140.0)) {
            for (north in listOf(-80.0, -49.0, 0.0, 30.0, 51.0)) {
                val point = center.moveBy(east, north)
                assertEquals(block.signedDistanceMeters(point) <= 0, block.contains(point), "$east, $north")
                assertEquals(circle.signedDistanceMeters(point) <= 0, circle.contains(point), "$east, $north")
            }
        }
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
}
