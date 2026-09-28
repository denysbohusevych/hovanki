package app.hovanki.server.map

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ZoneCircle
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** One tile of the web map grid (Web Mercator, "slippy map" numbering). */
data class TileId(val z: Int, val x: Int, val y: Int) {
    override fun toString(): String = "$z/$x/$y"
}

/** Tile numbers and coordinates in Web Mercator, as the map tiles use them. */
object TileMath {
    /** The tiles at zoom [z] that cover the bounding box of [area], row by row. */
    fun tilesCovering(area: ZoneCircle, z: Int): List<TileId> {
        val north = area.center.moveBy(eastMeters = 0.0, northMeters = area.radiusMeters).lat
        val south = area.center.moveBy(eastMeters = 0.0, northMeters = -area.radiusMeters).lat
        val east = area.center.moveBy(eastMeters = area.radiusMeters, northMeters = 0.0).lon
        val west = area.center.moveBy(eastMeters = -area.radiusMeters, northMeters = 0.0).lon
        val count = 1 shl z
        val x0 = floor(tileX(west, z)).toInt().coerceIn(0, count - 1)
        val x1 = floor(tileX(east, z)).toInt().coerceIn(0, count - 1)
        val y0 = floor(tileY(north, z)).toInt().coerceIn(0, count - 1)
        val y1 = floor(tileY(south, z)).toInt().coerceIn(0, count - 1)
        return (y0..y1).flatMap { y -> (x0..x1).map { x -> TileId(z, x, y) } }
    }

    /** [point] of [tile] (tile coordinates 0..[extent], y down) on the globe. */
    fun toGeo(tile: TileId, extent: Int, point: TilePoint): GeoPoint {
        val x = tile.x + point.x.toDouble() / extent
        val y = tile.y + point.y.toDouble() / extent
        return GeoPoint(lat = lat(y, tile.z), lon = lon(x, tile.z))
    }

    fun tileX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z)

    fun tileY(lat: Double, z: Int): Double {
        val radians = lat * PI / 180.0
        return (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0 * (1 shl z)
    }

    private fun lon(x: Double, z: Int): Double = x / (1 shl z) * 360.0 - 180.0

    private fun lat(y: Double, z: Int): Double = atan(sinh(PI * (1.0 - 2.0 * y / (1 shl z)))) * 180.0 / PI
}
