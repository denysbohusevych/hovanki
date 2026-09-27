package app.hovanki.server.game

import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplayTrackTest {
    private fun fix(atSeconds: Int) = LocationSample(GeoPoint(50.45, 30.52 + atSeconds * 1e-5), 5.0, atSeconds * 1000L)

    @Test
    fun onePointPerInterval() {
        val track = ReplayTrack(intervalMillis = 5_000, maxPoints = 100)
        (0..20).forEach { track.add(fix(it)) }

        assertEquals(listOf(0L, 5_000, 10_000, 15_000, 20_000), track.points().map { it.atMillis })
    }

    @Test
    fun aFullTrackKeepsItsLengthWithHalfTheDetail() {
        val track = ReplayTrack(intervalMillis = 1_000, maxPoints = 8)
        (0..7).forEach { track.add(fix(it)) }

        assertEquals(listOf(0L, 2_000, 4_000, 6_000), track.points().map { it.atMillis })

        // From now on points are 2 s apart.
        (8..13).forEach { track.add(fix(it)) }
        assertEquals(listOf(0L, 2_000, 4_000, 6_000, 8_000, 10_000, 12_000), track.points().map { it.atMillis })
        assertEquals(GeoPoint(50.45, 30.52 + 12 * 1e-5), track.points().last().point)
    }
}
