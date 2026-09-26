package app.hovanki.e2e.route

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.rules.BuildingMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Places for scenarios among real buildings (the device layer plays wherever the devices are, with OpenStreetMap
 * buildings): spots in the open, where GPS noise never puts a player inside a building, and points deep inside a
 * building, where the server is sure a player is inside. Uses the same [BuildingMap] the server judges by.
 */
class BuildingSearch(buildings: BuildingsResponse, origin: GeoPoint) {
    private val map = BuildingMap(buildings.buildings, buildings.passages, origin)

    /** A point deep inside a building, and how deep. */
    data class Inside(val point: GeoPoint, val depthMeters: Double)

    fun depthInsideMeters(point: GeoPoint): Double? = map.depthInsideMeters(point)

    /** In the open, with no building within [clearanceMeters]. */
    fun isInTheOpen(point: GeoPoint, clearanceMeters: Double = CLEARANCE_METERS): Boolean =
        map.depthInsideMeters(point) == null &&
            listOf(clearanceMeters / 2, clearanceMeters).all { radius ->
                (0 until RING_POINTS).all { i -> map.depthInsideMeters(point.around(radius, i, RING_POINTS)) == null }
            }

    /** The spot in the open nearest to [wanted], within [searchMeters]; null when there is none. */
    fun openSpotNear(wanted: GeoPoint, searchMeters: Double = 150.0): GeoPoint? =
        candidatesAround(wanted, searchMeters).firstOrNull { isInTheOpen(it) }

    /**
     * The point nearest to [from], within [withinMeters] of [center], that lies more than [minDepthMeters] inside a
     * building; if there is none that deep, the deepest point there is. Null when there are no buildings at all.
     */
    fun insideNear(from: GeoPoint, center: GeoPoint, withinMeters: Double, minDepthMeters: Double): Inside? {
        val inside = candidatesAround(center, withinMeters)
            .mapNotNull { point -> map.depthInsideMeters(point)?.let { Inside(point, it) } }
            .toList()
        return inside.filter { it.depthMeters > minDepthMeters }.minByOrNull { it.point.distanceTo(from) }
            ?: inside.maxByOrNull { it.depthMeters }
    }

    /** [center] first, then rings outwards every [STEP_METERS]: the first match is the nearest one. */
    private fun candidatesAround(center: GeoPoint, radiusMeters: Double): Sequence<GeoPoint> = sequence {
        yield(center)
        var radius = STEP_METERS
        while (radius <= radiusMeters) {
            val points = maxOf(8, (2 * PI * radius / STEP_METERS).toInt())
            for (i in 0 until points) yield(center.around(radius, i, points))
            radius += STEP_METERS
        }
    }

    private fun GeoPoint.around(radius: Double, index: Int, of: Int): GeoPoint {
        val angle = 2 * PI * index / of
        return moveBy(eastMeters = radius * cos(angle), northMeters = radius * sin(angle))
    }

    private companion object {
        /** Open-sky GPS noise of the bots stays well within this; the rule needs accuracy + 5 m inside. */
        const val CLEARANCE_METERS = 12.0
        const val STEP_METERS = 4.0
        const val RING_POINTS = 12
    }
}
