package app.hovanki.server.bigGames

import app.hovanki.server.map.LocalProjection
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ZoneCircle
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.protocol.ZoneSchedule
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.util.AffineTransformation
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier

/**
 * A zone an admin drew for a big game (docs/adr/0010-big-games.md): a closed ring of corners, without holes and without
 * crossing itself. It shrinks towards its [center] as the schedule's circles do, each stage inside the one before.
 */
class DrawnZone private constructor(val outline: ZonePolygon, val center: GeoPoint) {
    /** How far the farthest corner is from [center]: the schedule's first circle holds the whole figure. */
    val radiusMeters: Double = outline.outline.maxOf { it.distanceTo(center) }

    /** The circle holding the figure: its buildings and ground are loaded within it. */
    val bounds: ZoneCircle get() = ZoneCircle(center, radiusMeters)

    /**
     * The figure at the start and after each stage of [schedule]: scaled towards [center] as the stage's circle is to
     * the first one, and cut to the stage before (a figure that is not a star around its center could stick out).
     */
    fun stages(schedule: ZoneSchedule): List<ZonePolygon> {
        val projection = LocalProjection(center)
        val start = projection.polygon(outline.outline)
        val origin = projection.toMeters(center)
        val stages = ArrayList<ZonePolygon>()
        stages += outline
        var previous: Geometry = start
        for (stage in schedule.stages) {
            val scale = stage.target.radiusMeters / schedule.initial.radiusMeters
            val scaled = AffineTransformation.scaleInstance(scale, scale, origin.x, origin.y).transform(start)
            val cut = largest(scaled.intersection(previous))
            val simple = DouglasPeuckerSimplifier.simplify(cut, SIMPLIFY_METERS) as? Polygon ?: cut
            stages += ZonePolygon(projection.points(simple.exteriorRing))
            previous = simple
        }
        return stages
    }

    companion object {
        private const val SIMPLIFY_METERS = 1.0

        /** [zone] as a closed ring; null when it is no figure (too few corners, crossing itself, a hole). */
        fun of(zone: ZonePolygon): DrawnZone? {
            val corners = zone.outline.let { if (it.size > 1 && it.first() == it.last()) it.dropLast(1) else it }
            if (corners.size < 3 || corners.toSet().size < 3) return null
            val closed = ZonePolygon(corners + corners.first())
            val projection = LocalProjection(corners.first())
            val polygon = projection.polygon(closed.outline)
            if (!polygon.isValid || polygon.area <= 0.0) return null
            // The centroid, or a point surely inside for a figure that bends around it (a horseshoe).
            val centroid = polygon.centroid
            val inside = if (polygon.contains(centroid)) centroid.coordinate else polygon.interiorPoint.coordinate
            return DrawnZone(closed, projection.toGeo(Coordinate(inside.x, inside.y)))
        }

        private fun largest(geometry: Geometry): Polygon =
            (0 until geometry.numGeometries).mapNotNull { geometry.getGeometryN(it) as? Polygon }
                .filter { !it.isEmpty }.maxBy { it.area }
    }
}
