package app.hovanki.e2e.route

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteTest {
    private val start = GeoPoint(50.4476, 30.5396)

    @Test
    fun walksTheLegsAtConstantSpeed() {
        val corner = start.offset(eastMeters = 100.0)
        val end = corner.offset(northMeters = 50.0)
        val route = Route.walk(start, corner, end, speed = 2.0)

        assertEquals(150.0, route.lengthMeters, 0.5)
        assertEquals(75_000.0, route.durationMillis.toDouble(), 300.0)
        assertEquals(0.0, route.positionAt(0).distanceTo(start), 0.01)
        assertEquals(50.0, route.positionAt(25_000).distanceTo(start), 0.5)
        assertEquals(25.0, route.positionAt(62_500).distanceTo(corner), 0.5)
        // Before the start and after the end the player stands still.
        assertEquals(0.0, route.positionAt(-5_000).distanceTo(start), 0.01)
        assertEquals(0.0, route.positionAt(999_000).distanceTo(end), 0.01)
    }

    @Test
    fun samplesEveryIntervalUntilTheEnd() {
        val route = Route.walk(start, start.offset(eastMeters = 10.0), speed = 1.0)
        val samples = route.samples(startMillis = 1_000, intervalMillis = 2_000, holdMillis = 4_000).toList()

        assertEquals((0..7).map { 1_000L + it * 2_000 }, samples.map { it.timestampMillis })
        assertTrue(samples.all { it.accuracyMeters == GpsNoise.NONE.accuracyMeters && !it.isMock })
        assertEquals(10.0, samples.last().point.distanceTo(start), 0.1)
    }
}
