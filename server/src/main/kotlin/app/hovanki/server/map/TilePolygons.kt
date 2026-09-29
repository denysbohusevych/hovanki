package app.hovanki.server.map

import app.hovanki.shared.protocol.GeoPoint
import org.locationtech.jts.geom.Polygon

/**
 * The polygons of a POLYGON [feature] of [tile] in meters around the projection's origin: an outer ring (positive area
 * in tile coordinates) with the holes that follow it. Rounding to the tile grid can make a ring cross itself; such a
 * polygon is mended (a zero buffer).
 */
fun LocalProjection.polygonsOf(feature: MvtFeature, tile: TileId, extent: Int): List<Polygon> {
    val result = ArrayList<Polygon>()
    var outline: List<GeoPoint>? = null
    val holes = ArrayList<List<GeoPoint>>()
    fun flush() {
        val ring = outline ?: return
        val polygon = polygon(ring, holes.toList())
        result += if (polygon.isValid) listOf(polygon) else polygons(polygon.buffer(0.0))
        outline = null
        holes.clear()
    }
    for (ring in feature.parts) {
        if (ring.size < MIN_RING_POINTS) continue
        val area = Mvt.signedArea(ring)
        val points = ring.map { point -> TileMath.toGeo(tile, extent, point) }
        when {
            area > 0 -> {
                flush()
                outline = points
            }

            area < 0 && outline != null -> holes += points
        }
    }
    flush()
    return result
}

/** A closed ring needs three corners and the first one again. */
private const val MIN_RING_POINTS = 4
