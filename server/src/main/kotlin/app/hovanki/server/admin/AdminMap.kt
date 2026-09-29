package app.hovanki.server.admin

import app.hovanki.server.game.GameException
import app.hovanki.server.map.MapDataUnavailableException
import app.hovanki.server.map.MvtFeature
import app.hovanki.server.map.MvtGeometryType
import app.hovanki.server.map.MvtTile
import app.hovanki.server.map.TileId
import app.hovanki.server.map.TilePoint
import app.hovanki.server.map.VectorTiles
import app.hovanki.shared.protocol.AdminTile
import app.hovanki.shared.protocol.AdminTileLine
import app.hovanki.shared.protocol.ErrorCode
import org.springframework.stereotype.Service

/**
 * The map of the admin's big games page (docs/adr/0010-big-games.md): the server fetches the tiles of the players' map
 * (OpenFreeMap, through the same cache as the games) and hands the page what it draws, so the page loads nothing from
 * other hosts. Admins only.
 */
@Service
class AdminMap(private val tiles: VectorTiles) {
    fun tile(staff: Staff, z: Int, x: Int, y: Int): AdminTile {
        if (!staff.isAdmin) throw GameException(ErrorCode.FORBIDDEN, "Admins only")
        val tile = try {
            tiles.tile(TileId(z, x, y))
        } catch (e: MapDataUnavailableException) {
            throw GameException(ErrorCode.NOT_FOUND, "The map tile is unavailable")
        }
        return toAdmin(tile)
    }

    fun toAdmin(tile: MvtTile): AdminTile {
        val extent = tile.layers.values.firstOrNull()?.extent ?: DEFAULT_EXTENT
        fun polygons(layer: String, keep: (MvtFeature) -> Boolean = { true }) = tile.layers[layer]?.features.orEmpty()
            .filter { it.type == MvtGeometryType.POLYGON && keep(it) }
            .map { feature -> feature.parts.map(::flat) }
        return AdminTile(
            extent = extent,
            streets = tile.layers[VectorTiles.TRANSPORTATION_LAYER]?.features.orEmpty()
                .filter { it.type == MvtGeometryType.LINESTRING && it.string("brunnel") != "tunnel" }
                .filter { it.string("class") !in NOT_DRAWN }
                .flatMap { feature ->
                    val major = feature.string("class") in MAJOR
                    feature.parts.map { AdminTileLine(major, flat(it)) }
                },
            buildings = polygons(VectorTiles.BUILDING_LAYER),
            water = polygons(VectorTiles.WATER_LAYER) { it.string("brunnel") != "tunnel" },
            green = polygons(VectorTiles.LANDCOVER_LAYER) { it.string("class") in GREEN } +
                polygons(VectorTiles.PARK_LAYER),
        )
    }

    private fun flat(points: List<TilePoint>): List<Int> = points.flatMap { listOf(it.x, it.y) }

    private companion object {
        const val DEFAULT_EXTENT = 4096
        val MAJOR = setOf("motorway", "trunk", "primary", "secondary", "tertiary")
        val NOT_DRAWN = setOf("ferry", "transit", "aerialway")
        val GREEN = setOf("wood", "grass")
    }
}
