package app.hovanki.shared.rules

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import kotlin.test.Test
import kotlin.test.assertEquals

class LocationTrackTest {
    private val rules = GameRules()
    private val start = GeoPoint(50.4501, 30.5234)

    @Test
    fun acceptsWalkingAndDropsTeleports() {
        val track = LocationTrack(rules)
        assertEquals(LocationTrack.Result.ACCEPTED, track.add(fix(start, atSeconds = 0)))
        assertEquals(LocationTrack.Result.ACCEPTED, track.add(fix(start.moveBy(15.0, 0.0), atSeconds = 5)))
        assertEquals(LocationTrack.Result.IMPLAUSIBLE, track.add(fix(start.moveBy(2_000.0, 0.0), atSeconds = 10)))
        assertEquals(LocationTrack.Result.OUT_OF_ORDER, track.add(fix(start, atSeconds = 4)))
        assertEquals(5_000L, track.latestUsable()?.timestampMillis)
    }

    @Test
    fun mockFixesAreRememberedButNotUsed() {
        val track = LocationTrack(rules)
        assertEquals(LocationTrack.Result.MOCK, track.add(fix(start, atSeconds = 1, isMock = true)))
        assertEquals(1_000L, track.lastMockAtMillis)
        assertEquals(null, track.latest)
    }

    @Test
    fun inaccurateFixesAreKeptButNotUsableForDecisions() {
        val track = LocationTrack(rules)
        track.add(fix(start, atSeconds = 0, accuracy = 8.0))
        track.add(fix(start, atSeconds = 5, accuracy = 35.0))
        track.add(fix(start, atSeconds = 10, accuracy = 12.0))

        assertEquals(12.0, track.latest?.accuracyMeters)
        assertEquals(listOf(8.0, 12.0), track.recentUsableFixes(nowMillis = 10_000).map { it.accuracyMeters })
        assertEquals(listOf(12.0), track.recentUsableFixes(nowMillis = 25_000).map { it.accuracyMeters })
    }

    @Test
    fun theWindowEndsAtNowAndReachesBackItsLength() {
        val track = LocationTrack(rules)
        val times = (0..60 step 5).map { it * 1000L }
        times.forEach { track.add(fix(start, atSeconds = (it / 1000).toInt())) }
        val window = rules.decisionWindowSeconds * 1000L
        // Both ends count; a fix after now (from a phone whose clock runs ahead) is not in the window yet.
        for (now in listOf(-1L, 0L, 12_500L, 30_000L, 60_000L, 64_999L, 200_000L)) {
            val expected = times.filter { it in now - window..now }
            assertEquals(expected, track.recentFixes(now).map { it.timestampMillis }, "at $now")
            assertEquals(expected, track.recentUsableFixes(now).map { it.timestampMillis }, "usable at $now")
        }
    }

    private fun fix(point: GeoPoint, atSeconds: Int, accuracy: Double = 5.0, isMock: Boolean = false) =
        LocationSample(point, accuracy, timestampMillis = atSeconds * 1000L, isMock = isMock)
}
