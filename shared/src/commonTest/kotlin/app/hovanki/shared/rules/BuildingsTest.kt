package app.hovanki.shared.rules

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.Passage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildingsTest {
    private val origin = GeoPoint(50.4501, 30.5234)
    private val rules = GameRules()

    private fun at(east: Double, north: Double) = origin.moveBy(east, north)

    /** Axis-aligned rectangle in meters from [origin]. */
    private fun rect(west: Double, south: Double, east: Double, north: Double) =
        listOf(at(west, south), at(east, south), at(east, north), at(west, north), at(west, south))

    private fun map(buildings: List<BuildingArea>, passages: List<Passage> = emptyList()) =
        BuildingMap(buildings, passages, origin)

    private fun fix(east: Double, north: Double, accuracy: Double = 5.0, time: Long = 0, isMock: Boolean = false) =
        LocationSample(at(east, north), accuracy, time, isMock)

    /** A 40 × 40 m house with its south-west corner at the origin. */
    private val house = BuildingArea(rect(0.0, 0.0, 40.0, 40.0))

    @Test
    fun depthIsTheDistanceToTheNearestWall() {
        val buildings = map(listOf(house))

        assertEquals(20.0, assertNotNull(buildings.depthInsideMeters(at(20.0, 20.0))), 0.1)
        assertEquals(3.0, assertNotNull(buildings.depthInsideMeters(at(37.0, 20.0))), 0.1)
        assertNull(buildings.depthInsideMeters(at(45.0, 20.0)), "outside")
        assertNull(buildings.depthInsideMeters(at(2_000.0, 20.0)), "far away")
        assertFalse(buildings.isEmpty)
        assertTrue(map(emptyList()).isEmpty)
    }

    @Test
    fun courtyardIsOutdoors() {
        val block = BuildingArea(rect(0.0, 0.0, 60.0, 60.0), holes = listOf(rect(20.0, 20.0, 40.0, 40.0)))
        val buildings = map(listOf(block))

        assertNull(buildings.depthInsideMeters(at(30.0, 30.0)), "in the courtyard")
        assertEquals(5.0, assertNotNull(buildings.depthInsideMeters(at(15.0, 30.0))), 0.1, "5 m from the courtyard")
    }

    @Test
    fun archAndPassageAreOutdoors() {
        // A 4 m wide passage through the house from south to north, 10 m from its west wall.
        val arch = Passage(listOf(at(10.0, -5.0), at(10.0, 45.0)), widthMeters = 4.0)
        val buildings = map(listOf(house), listOf(arch))

        assertNull(buildings.depthInsideMeters(at(10.0, 20.0)), "in the passage")
        assertNull(buildings.depthInsideMeters(at(11.5, 20.0)), "at its side")
        assertEquals(6.0, assertNotNull(buildings.depthInsideMeters(at(18.0, 20.0))), 0.1, "the way out is the arch")
        assertEquals(8.0, assertNotNull(buildings.depthInsideMeters(at(32.0, 20.0))), 0.1, "the wall is nearer")
    }

    @Test
    fun deepestOfOverlappingBuildingsCounts() {
        val part = BuildingArea(rect(15.0, 15.0, 25.0, 25.0))
        val buildings = map(listOf(part, house))

        assertEquals(20.0, assertNotNull(buildings.depthInsideMeters(at(20.0, 20.0))), 0.1)
    }

    @Test
    fun aFixIsClearlyInsideOnlyWithItsAccuracyAndAMarginWithinTheWalls() {
        val buildings = map(listOf(house))

        assertTrue(BuildingRules.isClearlyInside(fix(20.0, 20.0, accuracy = 5.0), buildings, rules))
        // 12 m from the wall: fine at 5 m accuracy (5 + 5 margin), not at 8 m.
        assertTrue(BuildingRules.isClearlyInside(fix(12.0, 20.0, accuracy = 5.0), buildings, rules))
        assertFalse(BuildingRules.isClearlyInside(fix(12.0, 20.0, accuracy = 8.0), buildings, rules))
        assertFalse(BuildingRules.isClearlyInside(fix(20.0, 20.0, accuracy = 25.0), buildings, rules), "unusable")
        assertFalse(BuildingRules.isClearlyInside(fix(20.0, 20.0, isMock = true), buildings, rules), "mock")
        assertFalse(BuildingRules.isClearlyInside(fix(-5.0, 20.0), buildings, rules), "outside")
    }

    @Test
    fun severalFixesInsideAreNeeded() {
        val buildings = map(listOf(house))
        val inside = List(3) { fix(20.0, 20.0, time = it * 1_000L) }

        assertTrue(BuildingRules.isConfidentlyInside(inside, buildings, rules))
        assertFalse(BuildingRules.isConfidentlyInside(inside.take(2), buildings, rules), "two fixes decide nothing")
    }

    @Test
    fun oneFixThatJumpsIntoTheBuildingDecidesNothing() {
        val buildings = map(listOf(house))
        val standingOutside =
            listOf(fix(20.0, -20.0, time = 0), fix(20.0, 20.0, time = 1_000), fix(20.0, -20.0, time = 2_000))

        assertFalse(BuildingRules.isConfidentlyInside(standingOutside, buildings, rules))
    }

    @Test
    fun leavingIsJudgedOnSeveralFixesToo() {
        val buildings = map(listOf(house))
        val oneOut = listOf(fix(20.0, 20.0, time = 0), fix(20.0, 20.0, time = 1_000), fix(20.0, -20.0, time = 2_000))
        val out = listOf(
            fix(20.0, 20.0, time = 0),
            fix(20.0, -10.0, time = 1_000),
            fix(20.0, -20.0, time = 2_000),
            fix(20.0, 3.0, time = 3_000),
        )

        assertFalse(BuildingRules.hasLeft(oneOut, buildings, rules), "one fix outside resets nothing")
        assertTrue(BuildingRules.hasLeft(out, buildings, rules), "outside, and one at the wall counts as out")
        assertFalse(BuildingRules.hasLeft(out.take(2), buildings, rules), "too few fixes")
    }
}
