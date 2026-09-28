package app.hovanki.shared.rules

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteRecorderTest {
    private val rules = GameRules()
    private val start = GeoPoint(50.4501, 30.5234)

    @Test
    fun walkingAddsUpToTheDistanceWalked() {
        val route = RouteRecorder(rules)
        // 1.4 m/s east for 200 s, a fix every 2 s.
        for (second in 0..200 step 2) route.add(fix(start.moveBy(1.4 * second, 0.0), second))

        // The last step still waits for a fix to confirm it.
        assertNear(280.0, route.distanceMeters, tolerance = 15.0)
        assertNear(200_000.0, route.movingMillis.toDouble(), tolerance = 10_000.0)
        assertNear(1.4, route.maxSpeedMetersPerSecond!!, tolerance = 0.1)
        assertEquals(101, route.fixes)
    }

    @Test
    fun standingStillWithJitterWalksNowhere() {
        val route = RouteRecorder(rules)
        // Ten minutes in one place, each fix off by up to 7 m in a different direction.
        for (second in 0..600 step 2) {
            val angle = second * 1.7
            route.add(fix(start.moveBy(7 * cos(angle), 7 * sin(angle)), second, accuracy = 8.0))
        }

        assertTrue(route.distanceMeters < 20.0, "${route.distanceMeters} m")
        assertEquals(0L, route.movingMillis)
        assertNull(route.maxSpeedMetersPerSecond)
    }

    @Test
    fun aSingleStrayFixIsNotAStep() {
        val route = RouteRecorder(rules)
        route.add(fix(start, 0))
        route.add(fix(start.moveBy(60.0, 0.0), 2))
        route.add(fix(start.moveBy(1.0, 0.0), 4))
        route.add(fix(start.moveBy(0.0, 1.0), 6))

        assertEquals(0.0, route.distanceMeters)
    }

    @Test
    fun inaccurateFixesAreDrawnButNotCounted() {
        val route = RouteRecorder(rules)
        route.add(fix(start, 0))
        route.add(fix(start.moveBy(100.0, 0.0), 30, accuracy = 40.0))
        route.add(fix(start.moveBy(200.0, 0.0), 60, accuracy = 70.0))

        assertEquals(0.0, route.distanceMeters)
        assertEquals(listOf(5.0, 40.0), route.points().map { it.accuracyMeters }, "70 m is too vague to draw")
    }

    @Test
    fun aSlowCreepOverALongPauseIsNotMovingTime() {
        val route = RouteRecorder(rules)
        route.add(fix(start, 0))
        route.add(fix(start.moveBy(20.0, 0.0), 300))
        route.add(fix(start.moveBy(21.0, 0.0), 302))

        assertNear(20.0, route.distanceMeters, tolerance = 0.5)
        assertEquals(0L, route.movingMillis)
    }

    @Test
    fun theTopSpeedIgnoresShortStepsAndImplausibleOnes() {
        val route = RouteRecorder(rules)
        route.add(fix(start, 0))
        // 12 m in 2 s: too short a step to judge a speed by.
        route.add(fix(start.moveBy(12.0, 0.0), 2))
        // A sprint: 30 m in 5 s.
        route.add(fix(start.moveBy(42.0, 0.0), 7))
        // Faster than anybody runs (the track would have dropped it): capped.
        route.add(fix(start.moveBy(242.0, 0.0), 17))
        route.add(fix(start.moveBy(244.0, 0.0), 19))

        assertEquals(rules.maxPlausibleSpeedMetersPerSecond, route.maxSpeedMetersPerSecond)
        val sprintOnly = RouteRecorder(rules).apply {
            add(fix(start, 0))
            add(fix(start.moveBy(12.0, 0.0), 2))
            add(fix(start.moveBy(42.0, 0.0), 7))
            add(fix(start.moveBy(44.0, 0.0), 9))
        }
        assertNear(6.0, sprintOnly.maxSpeedMetersPerSecond!!, tolerance = 0.1)
    }

    @Test
    fun pointsAreThinnedByTimeAndWhileStandingStill() {
        val route = RouteRecorder(rules)
        // Walking, a fix every second: a point every 5 s.
        for (second in 0..20) route.add(fix(start.moveBy(1.4 * second, 0.0), second))
        assertEquals(listOf(0, 5, 10, 15, 20), route.points().map { (it.atMillis / 1000).toInt() })

        // Standing, a fix every second: a point a minute.
        val standing = start.moveBy(28.0, 0.0)
        for (second in 21..150) route.add(fix(standing, second))
        assertEquals(listOf(0, 5, 10, 15, 20, 80, 140), route.points().map { (it.atMillis / 1000).toInt() })
    }

    @Test
    fun aVeryLongRouteKeepsItsWholeLengthLessDensely() {
        val route = RouteRecorder(rules, maxPoints = 10)
        for (second in 0..300 step 5) route.add(fix(start.moveBy(1.4 * second, 0.0), second))

        val seconds = route.points().map { (it.atMillis / 1000).toInt() }
        assertTrue(seconds.size <= 10, "$seconds")
        assertEquals(0, seconds.first())
        assertTrue(seconds.last() >= 280, "the end of the route is there: $seconds")
        assertEquals(seconds.sorted(), seconds)
    }

    @Test
    fun mockFixesAreIgnored() {
        val route = RouteRecorder(rules)
        route.add(fix(start, 0, isMock = true))
        route.add(fix(start.moveBy(100.0, 0.0), 60, isMock = true))

        assertEquals(0, route.fixes)
        assertEquals(emptyList(), route.points())
    }

    @Test
    fun coordinatesAreRoundedToTenCentimeters() {
        val route = RouteRecorder(rules)
        route.add(LocationSample(GeoPoint(50.123456789, 30.987654321), 4.26, timestampMillis = 0))

        val point = route.points().single()
        assertEquals(50.123457, point.lat)
        assertEquals(30.987654, point.lon)
        assertEquals(4.3, point.accuracyMeters)
    }

    private fun fix(point: GeoPoint, atSeconds: Int, accuracy: Double = 5.0, isMock: Boolean = false) =
        LocationSample(point, accuracy, timestampMillis = atSeconds * 1000L, isMock = isMock)

    private fun assertNear(expected: Double, actual: Double, tolerance: Double) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected ± $tolerance, got $actual")
}
