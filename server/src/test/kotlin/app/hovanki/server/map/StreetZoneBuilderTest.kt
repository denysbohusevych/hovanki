package app.hovanki.server.map

import app.hovanki.shared.debug.DebugStreets
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.rules.GameSetup
import app.hovanki.shared.rules.ZoneArea
import app.hovanki.shared.rules.shrinkingZone
import org.locationtech.jts.algorithm.Angle
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random
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
    fun noSlitsNeedlesOrBaysInAnUnevenCity() {
        for (seed in 1..6) {
            for (radius in listOf(300, 500, 800)) {
                val schedule = GameSetup(radiusMeters = radius).settings(center).zone

                val stages = builder.build(schedule, unevenCity(seed))

                for ((index, stage) in stages.withIndex()) {
                    val polygon = projection.polygon(stage.outline)
                    val what = "seed $seed, $radius m, stage $index"
                    // A closing of 6 m fills slits and narrow bays: there are none to fill.
                    val parameters = BufferParameters(2, BufferParameters.CAP_ROUND, BufferParameters.JOIN_MITRE, 5.0)
                    val closed = BufferOp.bufferOp(BufferOp.bufferOp(polygon, 6.0, parameters), -6.0, parameters)
                    assertTrue(closed.area - polygon.area < 5.0, "$what: a slit of ${closed.area - polygon.area} m²")
                    assertTrue(sharpestCorner(polygon) > 30.0, "$what: a needle of ${sharpestCorner(polygon)}°")
                }
                for ((before, after) in stages.zipWithNext()) {
                    val outer = projection.polygon(before.outline)
                    assertTrue(projection.polygon(after.outline).difference(outer).area < 1.0, "nested")
                }
            }
        }
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

    /** The sharpest corner of [polygon] between edges of 3 m or longer, in degrees (180: straight). */
    private fun sharpestCorner(polygon: Polygon): Double {
        val points = polygon.exteriorRing.coordinates.dropLast(1)
        return points.indices.minOf { i ->
            val corner = points[i]
            val before = points[(i - 1 + points.size) % points.size]
            val after = points[(i + 1) % points.size]
            if (corner.distance(before) < 3 || corner.distance(after) < 3) return@minOf 180.0
            Angle.toDegrees(Angle.angleBetween(before, corner, after))
        }
    }

    /**
     * Streets of a city that is not a test grid: blocks of 40–160 m, streets a little crooked, two diagonal avenues
     * and short dead ends. The same [seed], the same city.
     */
    private fun unevenCity(seed: Int): List<List<GeoPoint>> {
        val random = Random(seed)
        fun lines() = generateSequence(-900.0) {
            it + 40.0 + random.nextDouble() * 120.0
        }.takeWhile { it < 900 }.toList()
        val xs = lines()
        val ys = lines()
        fun at(east: Double, north: Double) = center.moveBy(east, north)
        val streets = ArrayList<List<GeoPoint>>()
        for (x in xs) streets += ys.map { y -> at(x + random.nextDouble() * 12 - 6, y) }
        for (y in ys) streets += xs.map { x -> at(x, y + random.nextDouble() * 12 - 6) }
        streets += listOf(at(-900.0, -700.0), at(800.0, 900.0))
        streets += listOf(at(-900.0, 400.0), at(900.0, -300.0))
        repeat(30) {
            val x = random.nextDouble() * 1600 - 800
            val y = random.nextDouble() * 1600 - 800
            streets += listOf(at(x, y), at(x + random.nextDouble() * 60, y + random.nextDouble() * 60))
        }
        return streets
    }
}
