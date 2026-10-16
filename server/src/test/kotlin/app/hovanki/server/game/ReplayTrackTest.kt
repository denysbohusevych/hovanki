package app.hovanki.server.game

import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.TrackPoint
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayTrackTest {
    private fun fix(atSeconds: Int) = LocationSample(GeoPoint(50.45, 30.52 + atSeconds * 1e-5), 5.0, atSeconds * 1000L)

    @Test
    fun onePointPerInterval() {
        val track = ReplayTrack(intervalMillis = 5_000, maxPoints = 100)
        (0..20).forEach { track.add(fix(it)) }

        assertEquals(listOf(0L, 5_000, 10_000, 15_000, 20_000), track.points().map { it.atMillis })
        assertEquals(5, track.size)
    }

    @Test
    fun aFullTrackKeepsItsLengthWithHalfTheDetail() {
        val track = ReplayTrack(intervalMillis = 1_000, maxPoints = 8)
        (0..7).forEach { track.add(fix(it)) }

        assertEquals(listOf(0L, 2_000, 4_000, 6_000), track.points().map { it.atMillis })

        // From now on points are 2 s apart.
        (8..13).forEach { track.add(fix(it)) }
        assertEquals(listOf(0L, 2_000, 4_000, 6_000, 8_000, 10_000, 12_000), track.points().map { it.atMillis })
        assertEquals(GeoPoint(50.45, 30.52012), track.points().last().point)
    }

    /** Packed (A1), the way is the one the objects kept, to 1e-7 degree. */
    @Test
    fun keepsTheWayOfTheUnpackedTrack() {
        val random = Random(5)
        repeat(10) { round ->
            val maxPoints = 50 + round * 10
            val packed = ReplayTrack(intervalMillis = 5_000, maxPoints = maxPoints)
            val unpacked = UnpackedReplay(intervalMillis = 5_000, maxPoints = maxPoints)
            var lat = random.nextDouble(-89.0, 89.0)
            var lon = random.nextDouble(-179.0, 179.0)
            var at = random.nextLong(0, 2_000_000_000_000)
            repeat(2_000) {
                at += 1 + random.nextLong(9_000)
                lat += random.nextDouble(-3e-4, 3e-4)
                lon += random.nextDouble(-3e-4, 3e-4)
                if (random.nextInt(200) == 0) lon += 0.5
                val sample = LocationSample(GeoPoint(lat, lon), 5.0, at)
                packed.add(sample)
                unpacked.add(sample)
            }
            assertEquals(unpacked.points.size, packed.size, "round $round")
            assertSameWay(unpacked.points, packed.points(), "round $round")
            repeat(20) {
                val atMillis = unpacked.points.random(random).atMillis + random.nextLong(-20_000, 20_000)
                val trailMillis = listOf(0L, 15_000L, 60_000L, 600_000L).random(random)
                val until = unpacked.points.filter { it.atMillis <= atMillis }
                val seen = packed.at(atMillis, trailMillis)
                assertSameWay(
                    listOfNotNull(until.lastOrNull()),
                    listOfNotNull(seen.location),
                    "round $round at $atMillis",
                )
                assertSameWay(until.filter { it.atMillis > atMillis - trailMillis }, seen.trail, "round $round trail")
            }
        }
    }

    @Test
    fun aMomentBeforeTheFirstPointHasNoLocation() {
        val track = ReplayTrack()
        track.add(fix(10))
        assertEquals(ReplayTrack.Seen(null, emptyList()), track.at(9_999, 60_000))
        assertEquals(10_000L, track.at(10_000, 0).location?.atMillis)
        assertEquals(emptyList<TrackPoint>(), track.at(10_000, 0).trail)
    }

    @Test
    fun aPointTakesAFewBytes() {
        val track = ReplayTrack(intervalMillis = 5_000, maxPoints = 1_000)
        var point = GeoPoint(50.45, 30.52)
        (0 until 720).forEach {
            // Running, 3 m/s: 15 m between points.
            point = GeoPoint(point.lat + 1e-4, point.lon + 1e-4)
            track.add(LocationSample(point, 5.0, it * 5_004L))
        }
        assertEquals(720, track.size)
        assertTrue(track.packedBytes <= 720 * 7, "${track.packedBytes} bytes")
    }

    private fun assertSameWay(expected: List<TrackPoint>, actual: List<TrackPoint>, message: String) {
        assertEquals(expected.map { it.atMillis }, actual.map { it.atMillis }, message)
        expected.zip(actual).forEach { (want, got) ->
            assertTrue(abs(want.lat - got.lat) <= 0.5e-7 + 1e-12, "$message: ${want.lat} ≠ ${got.lat}")
            assertTrue(abs(want.lon - got.lon) <= 0.5e-7 + 1e-12, "$message: ${want.lon} ≠ ${got.lon}")
        }
    }
}

/** [ReplayTrack] as it was before the packing: a list of points. */
private class UnpackedReplay(private val intervalMillis: Long, private val maxPoints: Int) {
    val points = ArrayList<TrackPoint>()
    private var interval = intervalMillis

    fun add(fix: LocationSample) {
        val last = points.lastOrNull()
        if (last != null && fix.timestampMillis - last.atMillis < interval) return
        points.add(TrackPoint(fix.point.lat, fix.point.lon, fix.timestampMillis))
        if (points.size >= maxPoints) {
            val thinned = points.filterIndexed { index, _ -> index % 2 == 0 }
            points.clear()
            points.addAll(thinned)
            interval *= 2
        }
    }
}
