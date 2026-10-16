package app.hovanki.shared.rules

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import kotlin.random.Random
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

    @Test
    fun theOlderFixesAreKeptTheirRetentionAndComeBackAsTheyCame() {
        val track = LocationTrack(rules, retentionMillis = 60_000)
        (0..120 step 3).forEach {
            track.add(fix(start.moveBy(it.toDouble(), 0.0), atSeconds = it, accuracy = 5.0 + it))
        }

        // Packed, then made objects again: equal to what came.
        assertEquals(fix(start.moveBy(90.0, 0.0), atSeconds = 90, accuracy = 95.0), track.latestAtOrBefore(91_000))
        assertEquals(60_000L, track.latestAtOrBefore(61_000)?.timestampMillis)
        // 59 s is past the retention of the last fix (120 s).
        assertEquals(null, track.latestAtOrBefore(59_000))
        // Every fix over 20 m is unusable: the last usable one is long out of the decision window.
        assertEquals(null, track.latestUsable())
        track.add(fix(start.moveBy(130.0, 0.0), atSeconds = 123, accuracy = 4.0))
        assertEquals(123_000L, track.latestUsable()?.timestampMillis)
    }

    @Test
    fun anInaccurateWindowFallsBackToTheLastUsableOlderFix() {
        val track = LocationTrack(rules)
        track.add(fix(start, atSeconds = 0, accuracy = 6.0))
        (30..90 step 5).forEach { track.add(fix(start, atSeconds = it, accuracy = 60.0)) }

        assertEquals(fix(start, atSeconds = 0, accuracy = 6.0), track.latestUsable())
        assertEquals(emptyList<LocationSample>(), track.recentUsableFixes(nowMillis = 90_000))
    }

    /** The track keeps the answers it gave when every fix was an object (before the packing, A3). */
    @Test
    fun answersAsTheUnpackedTrack() {
        val random = Random(17)
        repeat(20) { round ->
            val window = (5 + random.nextInt(30))
            val gameRules = GameRules(decisionWindowSeconds = window)
            val retention = listOf(5 * 60_000L, 60_000L, 10_000L).random(random)
            val track = LocationTrack(gameRules, retention)
            val reference = UnpackedTrack(gameRules, retention)
            var at = 0L
            var here = start
            repeat(600) { step ->
                at += listOf(-2_000L, 0L, 400L, 1_000L, 3_000L, 7_000L, 40_000L).random(random)
                here = here.moveBy(random.nextDouble(-20.0, 20.0), random.nextDouble(-20.0, 20.0))
                if (random.nextInt(50) == 0) here = here.moveBy(3_000.0, 0.0)
                val sample = LocationSample(
                    point = here,
                    accuracyMeters = listOf(3.0, 8.5, 19.9, 20.0, 20.1, 45.0).random(random),
                    timestampMillis = at,
                    isMock = random.nextInt(40) == 0,
                )
                val where = "round $round, step $step"
                assertEquals(reference.add(sample), track.add(sample), where)
                assertEquals(reference.latest, track.latest, where)
                assertEquals(reference.latestUsable(), track.latestUsable(), where)
                repeat(3) {
                    val now = at + random.nextLong(-90_000L, 30_000L)
                    assertEquals(reference.latestAtOrBefore(now), track.latestAtOrBefore(now), "$where at $now")
                    assertEquals(reference.recentFixes(now), track.recentFixes(now), "$where at $now")
                    assertEquals(reference.recentUsableFixes(now), track.recentUsableFixes(now), "$where at $now")
                }
            }
        }
    }

    private fun fix(point: GeoPoint, atSeconds: Int, accuracy: Double = 5.0, isMock: Boolean = false) =
        LocationSample(point, accuracy, timestampMillis = atSeconds * 1000L, isMock = isMock)
}

/** [LocationTrack] as it was before the packing: every kept fix an object. */
private class UnpackedTrack(private val rules: GameRules, private val retentionMillis: Long) {
    private val fixes = ArrayDeque<LocationSample>()
    var latest: LocationSample? = null

    fun add(fix: LocationSample): LocationTrack.Result {
        if (fix.isMock) return LocationTrack.Result.MOCK
        val previous = latest
        if (previous != null &&
            fix.timestampMillis <= previous.timestampMillis
        ) {
            return LocationTrack.Result.OUT_OF_ORDER
        }
        val lastUsable = latestUsable()
        if (lastUsable != null && fix.isUsable(rules)) {
            val seconds = (fix.timestampMillis - lastUsable.timestampMillis) / 1000.0
            val slack = lastUsable.accuracyMeters + fix.accuracyMeters
            if (lastUsable.point.distanceTo(fix.point) - slack > rules.maxPlausibleSpeedMetersPerSecond * seconds) {
                return LocationTrack.Result.IMPLAUSIBLE
            }
        }
        fixes.addLast(fix)
        latest = fix
        while (fixes.isNotEmpty() && fixes.first().timestampMillis < fix.timestampMillis - retentionMillis) {
            fixes.removeFirst()
        }
        return LocationTrack.Result.ACCEPTED
    }

    fun latestUsable(): LocationSample? = fixes.lastOrNull { it.isUsable(rules) }

    fun latestAtOrBefore(atMillis: Long): LocationSample? = fixes.lastOrNull { it.timestampMillis <= atMillis }

    fun recentFixes(nowMillis: Long): List<LocationSample> =
        fixes.filter { it.timestampMillis in nowMillis - rules.decisionWindowSeconds * 1000L..nowMillis }

    fun recentUsableFixes(nowMillis: Long): List<LocationSample> = recentFixes(nowMillis).filter { it.isUsable(rules) }
}
