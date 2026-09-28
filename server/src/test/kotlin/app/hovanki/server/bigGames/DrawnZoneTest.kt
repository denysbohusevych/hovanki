package app.hovanki.server.bigGames

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.BigGameSetup
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.rules.ZoneArea
import app.hovanki.shared.rules.areaSquareMeters
import app.hovanki.shared.rules.settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The zone an admin draws (docs/adr/0010-big-games.md): a figure that shrinks towards its center. */
class DrawnZoneTest {
    private val park = GeoPoint(50.4501, 30.5234)

    private fun polygon(vararg corners: Pair<Double, Double>) =
        ZonePolygon(corners.map { (east, north) -> park.moveBy(east, north) })

    @Test
    fun aSquareShrinksTowardsItsCenter() {
        val zone =
            assertNotNull(DrawnZone.of(polygon(-500.0 to -500.0, 500.0 to -500.0, 500.0 to 500.0, -500.0 to 500.0)))
        val settings = BigGameSetup(seekingMinutes = 60).settings(zone.center, zone.radiusMeters)

        val stages = zone.stages(settings.zone)

        assertEquals(7, stages.size, "the start, three stages and the squeeze at the end")
        assertTrue(zone.center.distanceTo(park) < 1.0)
        assertEquals(707.1, zone.radiusMeters, 0.5)
        val areas = stages.map { it.areaSquareMeters() }
        assertEquals(1_000_000.0, areas.first(), 100.0)
        // A fifth of the size after the three stages: a twenty-fifth of the area; a twentieth at the very end.
        assertEquals(40_000.0, areas[3], 500.0)
        assertEquals(2_500.0, areas.last(), 100.0)
        assertTrue(areas.zipWithNext().all { (a, b) -> b < a })
    }

    @Test
    fun eachStageIsInsideTheOneBefore() {
        // A horseshoe: its centroid is outside it; the stages still stay inside the figure.
        val horseshoe = polygon(
            -300.0 to -300.0,
            300.0 to -300.0,
            300.0 to 300.0,
            150.0 to 300.0,
            150.0 to -150.0,
            -150.0 to -150.0,
            -150.0 to 300.0,
            -300.0 to 300.0,
        )
        val zone = assertNotNull(DrawnZone.of(horseshoe))
        assertTrue(ZoneArea.Polygon(zone.outline).contains(zone.center), "the center is in the figure")
        val settings = BigGameSetup().settings(zone.center, zone.radiusMeters)

        val stages = zone.stages(settings.zone)

        for ((outer, inner) in stages.zipWithNext()) {
            val area = ZoneArea.Polygon(outer)
            assertTrue(inner.outline.all { area.signedDistanceMeters(it) <= 0.5 }, "inside the stage before")
        }
    }

    @Test
    fun noFigureNoZone() {
        assertNull(DrawnZone.of(polygon(0.0 to 0.0, 100.0 to 0.0)))
        // A bow tie crosses itself.
        assertNull(DrawnZone.of(polygon(-100.0 to -100.0, 100.0 to 100.0, 100.0 to -100.0, -100.0 to 100.0)))
    }

    @Test
    fun aZoneThatDoesNotShrinkStaysAsDrawn() {
        val square = polygon(-200.0 to -200.0, 200.0 to -200.0, 200.0 to 200.0, -200.0 to 200.0)
        val zone = assertNotNull(DrawnZone.of(square))
        val settings = BigGameSetup(shrinks = false).settings(zone.center, zone.radiusMeters)

        val stages = zone.stages(settings.zone)

        assertEquals(1, stages.size)
        assertEquals(square.outline + square.outline.first(), stages.single().outline)
    }
}
