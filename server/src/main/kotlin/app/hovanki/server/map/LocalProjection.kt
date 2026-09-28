package app.hovanki.server.map

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.GeoPoint
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.LinearRing
import org.locationtech.jts.geom.Polygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Meters east and north of [origin] on a flat plane, for geometry with JTS: the same projection the rules in :shared
 * use (fine for an area of a few kilometers).
 */
class LocalProjection(val origin: GeoPoint) {
    val factory = GeometryFactory()

    fun toMeters(point: GeoPoint): Coordinate {
        val offset = point.offsetFrom(origin)
        return Coordinate(offset.eastMeters, offset.northMeters)
    }

    fun toGeo(coordinate: Coordinate): GeoPoint = origin.moveBy(eastMeters = coordinate.x, northMeters = coordinate.y)

    fun line(points: List<GeoPoint>): LineString = factory.createLineString(points.map(::toMeters).toTypedArray())

    /** A polygon from closed rings of points; may be invalid (self-intersecting): check or fix it. */
    fun polygon(outline: List<GeoPoint>, holes: List<List<GeoPoint>> = emptyList()): Polygon =
        factory.createPolygon(ring(outline), holes.map(::ring).toTypedArray())

    /** A circle of [radiusMeters] around [center] with [segments] corners. */
    fun circle(center: GeoPoint, radiusMeters: Double, segments: Int = 96): Polygon {
        val middle = toMeters(center)
        val corners = (0..segments).map { i ->
            val angle = 2 * PI * (i % segments) / segments
            Coordinate(middle.x + radiusMeters * cos(angle), middle.y + radiusMeters * sin(angle))
        }
        return factory.createPolygon(corners.toTypedArray())
    }

    /** The points of [ring] on the globe, closed like the ring. */
    fun points(ring: LineString): List<GeoPoint> = ring.coordinates.map(::toGeo)

    /** The polygons of [geometry]: itself, the parts of a multipolygon or collection; lines and points dropped. */
    fun polygons(geometry: Geometry): List<Polygon> = (0 until geometry.numGeometries).flatMap { index ->
        when (val part = geometry.getGeometryN(index)) {
            is Polygon -> if (part.isEmpty) emptyList() else listOf(part)
            else -> if (part.numGeometries > 1) polygons(part) else emptyList()
        }
    }

    private fun ring(points: List<GeoPoint>): LinearRing {
        val coordinates = points.map(::toMeters).toMutableList()
        if (coordinates.first() != coordinates.last()) coordinates += Coordinate(coordinates.first())
        return factory.createLinearRing(coordinates.toTypedArray())
    }
}
