package app.hovanki.e2e.route

import app.hovanki.shared.debug.DebugBuildings
import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildingSearchTest {
    private val center = GeoPoint(50.4476, 30.5396)
    private val quarter = BuildingSearch(DebugBuildings.around(center), center)
    private val insideBlock = center.offset(DebugBuildings.INSIDE_EAST, DebugBuildings.INSIDE_NORTH)

    @Test
    fun aSpotInTheOpenNextToTheBlock() {
        assertFalse(quarter.isInTheOpen(insideBlock))

        val spot = assertNotNull(quarter.openSpotNear(insideBlock))

        assertTrue(quarter.isInTheOpen(spot))
        assertNull(quarter.depthInsideMeters(spot))
        val offset = spot.offsetFrom(center)
        val clearOfTheBlock = offset.eastMeters >= DebugBuildings.EAST + 11 ||
            offset.northMeters >= DebugBuildings.NORTH + 11 || offset.northMeters <= DebugBuildings.SOUTH - 11
        assertTrue(clearOfTheBlock, "at least the clearance away from the walls: $offset")
    }

    @Test
    fun theZoneCenterIsAlreadyInTheOpen() {
        assertEquals(center, quarter.openSpotNear(center))
    }

    @Test
    fun deepEnoughInsideTheBlockNearestToThePlayer() {
        val inside = assertNotNull(quarter.insideNear(from = center, center = center, withinMeters = 200.0, 12.0))

        assertTrue(inside.depthMeters > 12.0, "deeper than asked: ${inside.depthMeters}")
        assertEquals(inside.depthMeters, quarter.depthInsideMeters(inside.point))
        val offset = inside.point.offsetFrom(center)
        assertTrue(offset.eastMeters in DebugBuildings.WEST..DebugBuildings.EAST, "in the block: $offset")
        assertTrue(offset.northMeters in DebugBuildings.SOUTH..DebugBuildings.NORTH, "in the block: $offset")
    }

    @Test
    fun theDeepestPointWhenNothingIsDeepEnough() {
        val inside = assertNotNull(quarter.insideNear(from = center, center = center, withinMeters = 200.0, 50.0))

        // The arch splits the 40 m block: the part east of it is 32 m wide, 16 m deep at most.
        assertTrue(inside.depthMeters in 14.0..16.0, "the deepest point: ${inside.depthMeters}")
    }

    @Test
    fun noBuildingsAtAll() {
        val empty = BuildingSearch(BuildingsResponse(), center)

        assertNull(empty.insideNear(from = center, center = center, withinMeters = 200.0, 12.0))
        assertEquals(insideBlock, empty.openSpotNear(insideBlock))
    }
}
