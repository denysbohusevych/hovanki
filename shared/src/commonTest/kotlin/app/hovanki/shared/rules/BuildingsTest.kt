package app.hovanki.shared.rules

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.BuildingsState
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
    fun aFixCountsByItsDotWithAMarginAtTheWalls() {
        val buildings = map(listOf(house))
        fun spot(east: Double, accuracy: Double = 5.0, isMock: Boolean = false) =
            BuildingRules.spotOf(fix(east, 20.0, accuracy, isMock = isMock), buildings, rules)

        assertEquals(BuildingRules.Spot.INSIDE, spot(20.0))
        assertEquals(
            BuildingRules.Spot.INSIDE,
            spot(20.0, accuracy = 25.0),
            "a circle wider than the house: by the dot",
        )
        assertEquals(BuildingRules.Spot.INSIDE, spot(36.0), "4 m from the wall")
        assertEquals(BuildingRules.Spot.AT_WALL, spot(38.0), "2 m from the wall: GPS can't tell in from out")
        assertEquals(BuildingRules.Spot.OUTSIDE, spot(45.0))
        assertNull(spot(20.0, accuracy = 45.0), "too coarse even for the building rule")
        assertNull(spot(20.0, isMock = true), "mock")
    }

    /**
     * An ordinary apartment block, shaped like a C open to the east around a yard: 35 × 45 m, wings 16 m deep, no
     * point more than 8 m from a wall. Before the rule went by the dot, a fix counted only deeper than its accuracy plus
     * 5 m, so a player inside such a house was never warned, whatever the phone reported.
     */
    @Test
    fun aPlayerInAnOrdinaryHouseWithIndoorGpsIsInside() {
        val block = BuildingArea(
            listOf(
                at(0.0, 0.0), at(35.0, 0.0), at(35.0, 16.0), at(16.0, 16.0), at(16.0, 29.0), at(35.0, 29.0),
                at(35.0, 45.0), at(0.0, 45.0), at(0.0, 0.0),
            ),
        )
        val buildings = map(listOf(block))
        // In the back wing, 5 m from the yard's wall, with the 20-odd meters phones report indoors.
        val byTheYard = List(7) { fix(11.0 + (it % 3 - 1) * 0.8, 22.5, accuracy = 21.0, time = it * 3_000L) }

        assertEquals(5.0, assertNotNull(buildings.depthInsideMeters(at(11.0, 22.5))), 0.1)
        assertTrue(BuildingRules.isConfidentlyInside(byTheYard, buildings, rules))
        assertFalse(BuildingRules.hasLeft(byTheYard, buildings, rules))
    }

    @Test
    fun severalFixesInsideAreNeeded() {
        val buildings = map(listOf(house))
        val inside = List(3) { fix(20.0, 20.0, time = it * 1_000L) }

        assertTrue(BuildingRules.isConfidentlyInside(inside, buildings, rules))
        assertFalse(BuildingRules.isConfidentlyInside(inside.take(2), buildings, rules), "two fixes decide nothing")
    }

    @Test
    fun mostOfTheFixesInsideAreEnough() {
        val buildings = map(listOf(house))
        fun fixes(inside: Int, outside: Int) = (List(inside) { fix(20.0, 20.0) } + List(outside) { fix(20.0, -10.0) })
            .mapIndexed { n, it -> it.copy(timestampMillis = n * 1_000L) }

        assertTrue(BuildingRules.isConfidentlyInside(fixes(inside = 8, outside = 2), buildings, rules), "8 of 10")
        assertFalse(BuildingRules.isConfidentlyInside(fixes(inside = 7, outside = 3), buildings, rules), "7 of 10")
    }

    @Test
    fun oneFixThatJumpsIntoTheBuildingDecidesNothing() {
        val buildings = map(listOf(house))
        val standingOutside =
            listOf(fix(20.0, -20.0, time = 0), fix(20.0, 20.0, time = 1_000), fix(20.0, -20.0, time = 2_000))

        assertFalse(BuildingRules.isConfidentlyInside(standingOutside, buildings, rules))
    }

    @Test
    fun aDotAtTheWallDecidesNothing() {
        val buildings = map(listOf(house))
        // 1 m outside the wall, the dot jittering into the house by up to 2 m.
        val atTheWall = List(10) { fix(20.0, if (it % 2 == 0) -1.0 else 2.0, accuracy = 8.0, time = it * 1_000L) }

        assertFalse(BuildingRules.isConfidentlyInside(atTheWall, buildings, rules))
    }

    @Test
    fun coarseFixesDecideNothing() {
        val buildings = map(listOf(house))
        val coarse = List(5) { fix(20.0, 20.0, accuracy = 65.0, time = it * 1_000L) }
        val coarseOutside = List(5) { fix(20.0, -20.0, accuracy = 65.0, time = it * 1_000L) }

        assertFalse(BuildingRules.isConfidentlyInside(coarse, buildings, rules))
        assertFalse(BuildingRules.hasLeft(coarseOutside, buildings, rules), "nor the way out")
    }

    @Test
    fun leavingIsJudgedOnSeveralFixesToo() {
        val buildings = map(listOf(house))
        val oneOut = listOf(fix(20.0, 20.0, time = 0), fix(20.0, 20.0, time = 1_000), fix(20.0, -20.0, time = 2_000))
        val out = listOf(
            fix(20.0, 20.0, time = 0),
            fix(20.0, -10.0, time = 1_000),
            fix(20.0, -20.0, time = 2_000),
            fix(20.0, -3.0, time = 3_000),
        )
        val byTheWindow = out.dropLast(1) + fix(20.0, 1.0, time = 3_000)

        assertFalse(BuildingRules.hasLeft(oneOut, buildings, rules), "one fix outside resets nothing")
        assertTrue(BuildingRules.hasLeft(out, buildings, rules), "three fixes outside")
        assertFalse(BuildingRules.hasLeft(byTheWindow, buildings, rules), "a dot at the wall is not out yet")
        assertFalse(BuildingRules.hasLeft(out.take(2), buildings, rules), "too few fixes")
    }

    @Test
    fun aPointOpensTheBuildingItIsInNotItsCourtyard() {
        val block = BuildingArea(rect(0.0, 0.0, 60.0, 60.0), holes = listOf(rect(20.0, 20.0, 40.0, 40.0)))

        assertTrue(house.contains(at(20.0, 20.0)))
        assertFalse(house.contains(at(45.0, 20.0)))
        assertTrue(block.contains(at(10.0, 30.0)))
        assertFalse(block.contains(at(30.0, 30.0)), "the courtyard")
    }

    @Test
    fun theHostsPointsSplitTheBuildingsIntoOpenAndForbidden() {
        // Two houses stand wall to wall: the tiles give them as one outline, a point opens both.
        val row = BuildingArea(rect(100.0, 0.0, 160.0, 20.0))
        val shed = BuildingArea(rect(0.0, 100.0, 10.0, 110.0))

        val split = OpenBuildings.split(listOf(house, row, shed), listOf(at(150.0, 10.0), at(500.0, 500.0)))

        assertEquals(listOf(row), split.open)
        assertEquals(listOf(house, shed), split.forbidden)
        assertEquals(listOf(house), OpenBuildings.split(listOf(house), emptyList()).forbidden)
        assertEquals(1, OpenBuildings.openCount(listOf(at(150.0, 10.0), at(120.0, 5.0)), listOf(house, row)))
    }

    @Test
    fun aTapOpensAForbiddenBuildingAndClosesAnOpenOne() {
        val buildings = listOf(house, BuildingArea(rect(100.0, 0.0, 160.0, 20.0)))

        val opened = OpenBuildings.toggle(emptyList(), buildings, at(20.0, 20.0))
        assertEquals(listOf(at(20.0, 20.0)), opened)
        // Another tap anywhere in the same building closes it, with every point in it.
        assertEquals(emptyList(), OpenBuildings.toggle(opened + at(30.0, 30.0), buildings, at(5.0, 5.0)))
        assertEquals(opened, OpenBuildings.toggle(opened, buildings, at(70.0, 70.0)), "no building there")
    }

    @Test
    fun noMoreThanTheLimitOpen() {
        val houses = (0 until SettingsLimits.MAX_OPEN_BUILDINGS + 1).map { n ->
            BuildingArea(rect(n * 20.0, 0.0, n * 20.0 + 10.0, 10.0))
        }
        val points = (0 until SettingsLimits.MAX_OPEN_BUILDINGS).map { n -> at(n * 20.0 + 5.0, 5.0) }

        val tap = at(SettingsLimits.MAX_OPEN_BUILDINGS * 20.0 + 5.0, 5.0)
        assertEquals(points, OpenBuildings.toggle(points, houses, tap), "the limit is reached")
        assertEquals(points.drop(1), OpenBuildings.toggle(points, houses, at(5.0, 5.0)), "closing still works")
    }

    @Test
    fun thePhoneSplitsTheBuildingsItHasByTheSettingsOfNow() {
        val row = BuildingArea(rect(100.0, 0.0, 160.0, 20.0))
        val served = BuildingsResponse(BuildingsState.READY, buildings = listOf(house), open = listOf(row))

        assertEquals(served, served.withOpenBuildings(null), "an older server says nothing")
        val closed = served.withOpenBuildings(emptyList())
        assertEquals(listOf(house, row), closed.buildings)
        assertEquals(emptyList(), closed.open)
        val swapped = served.withOpenBuildings(listOf(at(20.0, 20.0)))
        assertEquals(listOf(row), swapped.buildings)
        assertEquals(listOf(house), swapped.open)
    }
}
