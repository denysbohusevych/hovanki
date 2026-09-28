package app.hovanki.server.map

import app.hovanki.shared.debug.DebugStreets
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ZoneCircle

/**
 * Where the zone by streets gets its streets (docs/adr/0009-game-setup-glow-streets.md): the lines that may be a
 * border of the zone. Blocking: called off the request threads, once per zone.
 */
fun interface StreetSource {
    /** The streets within [area]; throws [MapDataUnavailableException] when they can't be loaded. */
    fun streets(area: ZoneCircle): List<List<GeoPoint>>
}

/**
 * The streets of the players' map tiles (the `transportation` layer): roads a person walks along or can't cross,
 * with the railway; not footpaths, service roads or tunnels, which don't make a block's border.
 */
class TileStreetSource(private val tiles: VectorTiles) : StreetSource {
    override fun streets(area: ZoneCircle): List<List<GeoPoint>> = tiles.tiles(area).flatMap { tile ->
        val layer = tile.tile.layers[VectorTiles.TRANSPORTATION_LAYER] ?: return@flatMap emptyList()
        layer.features.filter { it.type == MvtGeometryType.LINESTRING && isBorder(it) }.flatMap { feature ->
            feature.parts.map { part -> part.map { TileMath.toGeo(tile.id, layer.extent, it) } }
        }
    }

    private fun isBorder(feature: MvtFeature): Boolean {
        if (feature.string("brunnel") == "tunnel") return false
        val kind = feature.string("class") ?: return false
        return kind in BORDER_CLASSES || (kind == "path" && feature.string("subclass") == "pedestrian")
    }

    private companion object {
        /** OpenMapTiles classes of streets, and the railway (nobody crosses it anywhere). */
        val BORDER_CLASSES = setOf(
            "motorway",
            "trunk",
            "primary",
            "secondary",
            "tertiary",
            "minor",
            "busway",
            "rail",
        )
    }
}

/** The fixed street grid of [DebugStreets] around the zone center: tests and the `e2e` profile. */
class FakeStreetSource : StreetSource {
    override fun streets(area: ZoneCircle): List<List<GeoPoint>> = DebugStreets.around(area.center, area.radiusMeters)
}

/** No streets: every zone by streets falls back to the circle. */
class NoStreetSource : StreetSource {
    override fun streets(area: ZoneCircle): List<List<GeoPoint>> =
        throw MapDataUnavailableException("The street source is off", retry = false)
}
