package app.hovanki.server.map

import app.hovanki.shared.debug.DebugStreets
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.rules.GameSetup
import app.hovanki.shared.rules.ZoneArea
import app.hovanki.shared.rules.shrinkingZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The zone by streets on the test grid of [DebugStreets]: 100 m blocks, streets at ±50, ±150… m. */
class StreetZoneBuilderTest {
    private val center = GeoPoint(50.4476, 30.5396)
    private val builder = StreetZoneBuilder()
    private val projection = LocalProjection(center)

    private fun area(polygon: ZonePolygon): Double = projection.polygon(polygon.outline).area

    @Test
    fun wholeBlocksAsLargeAsTheCircle() {
        val schedule = shrinkingZone(center, initialRadiusMeters = 300.0, steps = 0)

        val zone = builder.build(schedule, DebugStreets.around(center, 450.0)).single()

        val circle = PI * 300.0 * 300.0
        assertTrue(abs(area(zone) - circle) < circle * 0.25, "area ${area(zone)} vs $circle")
        assertTrue(ZoneArea.Polygon(zone).signedDistanceMeters(center) < -40, "the center is well inside")
        // The border runs along the far side of a street: 8 m beyond a street line (x or y = ±50, ±150, ±250…).
        for (point in zone.outline) {
            val offset = point.offsetFrom(center)
            val onStreetSide = listOf(offset.eastMeters, offset.northMeters).any { v ->
                val fromStreet = abs(((abs(v) - 50.0) % 100.0 + 100.0) % 100.0)
                fromStreet in 6.0..10.0 || fromStreet in 90.0..94.0
            }
            assertTrue(onStreetSide, "corner $offset is on a street's far side")
        }
    }

    @Test
    fun everyStageInsideTheOneBefore() {
        val schedule = GameSetup(radiusMeters = 400).settings(center).zone

        val stages = builder.build(schedule, DebugStreets.around(center, 600.0))

        assertEquals(schedule.stages.size + 1, stages.size)
        for ((before, after) in stages.zipWithNext()) {
            val outer = projection.polygon(before.outline)
            val inner = projection.polygon(after.outline)
            assertTrue(inner.difference(outer).area < 1.0, "a stage stays inside the zone before it")
            assertTrue(inner.area < outer.area)
        }
        assertTrue(stages.all { ZoneArea.Polygon(it).signedDistanceMeters(center) < 0 }, "every stage keeps the center")
    }

    @Test
    fun noStreetsNoZone() {
        val schedule = shrinkingZone(center, initialRadiusMeters = 300.0, steps = 0)

        assertFailsWith<StreetZoneException> { builder.build(schedule, emptyList()) }
    }

    @Test
    fun aParkWithoutStreetsEndsAtTheCircle() {
        // One street far off: the whole zone is one block, cut by a circle a little larger than the zone's.
        val schedule = shrinkingZone(center, initialRadiusMeters = 200.0, steps = 0)
        val far = listOf(
            listOf(
                GeoPoint(center.lat + 0.0024, center.lon - 0.01),
                GeoPoint(
                    center.lat + 0.0024,
                    center.lon + 0.01,
                ),
            ),
        )

        val zone = builder.build(schedule, far).single()

        val distances = zone.outline.map { it.distanceTo(center) }
        assertTrue(distances.all { it in 240.0..252.0 }, "the border is a circle of 250 m: $distances")
    }
}
