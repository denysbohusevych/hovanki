package app.hovanki.server.buildings

import app.hovanki.server.map.LoadedTile
import app.hovanki.server.map.LocalProjection
import app.hovanki.server.map.MapDataUnavailableException
import app.hovanki.server.map.MvtFeature
import app.hovanki.server.map.MvtGeometryType
import app.hovanki.server.map.TileMath
import app.hovanki.server.map.VectorTiles
import app.hovanki.server.map.polygonsOf
import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.Passage
import app.hovanki.shared.protocol.ZoneCircle
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.simplify.TopologyPreservingSimplifier

/**
 * The zone's buildings from the vector tiles of the players' map (docs/adr/0003-map-and-buildings.md, «Изменение»):
 * the `building` layer, merged across tile borders and between touching houses (a wall two houses share is no way
 * out), and the ways through buildings from tunnels of the `transportation` layer. The tiles have fewer tags than
 * OpenStreetMap itself: a roof on pillars can't be told from a house, only buildings raised off the ground
 * (`render_min_height`) are left out.
 */
class TileBuildingSource(private val tiles: VectorTiles, private val properties: BuildingProperties) : BuildingSource {
    override fun load(area: ZoneCircle): Buildings {
        val loaded = try {
            tiles.tiles(area)
        } catch (e: MapDataUnavailableException) {
            throw BuildingsUnavailableException(e.message ?: "Map tiles unavailable", e, e.retry)
        }
        return read(loaded, area)
    }

    /** [loaded] tiles → the buildings within [area]; separate from the download, tested with fixed tiles. */
    fun read(loaded: List<LoadedTile>, area: ZoneCircle): Buildings {
        val projection = LocalProjection(area.center)
        val reach = Envelope(-area.radiusMeters, area.radiusMeters, -area.radiusMeters, area.radiusMeters)
        val footprints = ArrayList<Polygon>()
        val tunnels = ArrayList<LineString>()
        for (tile in loaded) {
            tile.tile.layers[VectorTiles.BUILDING_LAYER]?.let { layer ->
                for (feature in layer.features) {
                    if (feature.type != MvtGeometryType.POLYGON || isRaised(feature)) continue
                    for (polygon in projection.polygonsOf(feature, tile.id, layer.extent)) {
                        if (polygon.envelopeInternal.intersects(reach)) footprints += polygon
                    }
                }
            }
            tile.tile.layers[VectorTiles.TRANSPORTATION_LAYER]?.let { layer ->
                for (feature in layer.features) {
                    if (feature.type != MvtGeometryType.LINESTRING || !isPassage(feature)) continue
                    for (part in feature.parts) {
                        val line = projection.line(part.map { TileMath.toGeo(tile.id, layer.extent, it) })
                        if (line.envelopeInternal.intersects(reach)) tunnels += line
                    }
                }
            }
            if (footprints.size > properties.maxBuildings * TILE_PARTS_PER_BUILDING) {
                throw BuildingsUnavailableException("More than ${properties.maxBuildings} buildings", retry = false)
            }
        }
        if (footprints.isEmpty()) return Buildings(emptyList())
        val merged = merge(footprints)
        val areas = projection.polygons(merged).map { polygon ->
            BuildingArea(
                outline = projection.points(polygon.exteriorRing),
                holes = (0 until polygon.numInteriorRing).map { projection.points(polygon.getInteriorRingN(it)) },
            )
        }
        if (areas.size > properties.maxBuildings) {
            throw BuildingsUnavailableException("More than ${properties.maxBuildings} buildings", retry = false)
        }
        val vertices = areas.sumOf { area -> area.outline.size + area.holes.sumOf { it.size } }
        if (vertices > properties.maxVertices) {
            throw BuildingsUnavailableException("More than ${properties.maxVertices} building vertices", retry = false)
        }
        val passages = tunnels.filter { it.intersects(merged) }.map { line ->
            Passage(line.coordinates.map(projection::toGeo), PASSAGE_WIDTH_METERS)
        }
        return Buildings(areas, passages)
    }

    /**
     * One outline per group of touching buildings: parts cut by tile borders join again, and gaps narrower than
     * [CLOSE_GAP_METERS] (rounding in the tiles) close. Then simplified to what the rule and the map need.
     */
    private fun merge(footprints: List<Polygon>): Geometry {
        val parameters = BufferParameters(1, BufferParameters.CAP_FLAT, BufferParameters.JOIN_MITRE, MITRE_LIMIT)
        val all = footprints.first().factory.buildGeometry(footprints)
        val grown = BufferOp.bufferOp(all, CLOSE_GAP_METERS / 2, parameters)
        val merged = BufferOp.bufferOp(grown, -CLOSE_GAP_METERS / 2, parameters)
        return TopologyPreservingSimplifier.simplify(merged, SIMPLIFY_METERS)
    }

    /** On pillars or a bridge between houses: one can stand under it. */
    private fun isRaised(feature: MvtFeature): Boolean = (feature.number("render_min_height") ?: 0.0) >= RAISED_METERS

    /** A way under or through something (arch, passage, underpass), not a railway or a metro line. */
    private fun isPassage(feature: MvtFeature): Boolean =
        feature.string("brunnel") == "tunnel" && feature.string("class") !in NOT_PASSAGES

    private companion object {
        const val RAISED_METERS = 2.5
        const val PASSAGE_WIDTH_METERS = 4.0
        const val CLOSE_GAP_METERS = 0.5
        const val SIMPLIFY_METERS = 0.3
        const val MITRE_LIMIT = 4.0

        /** A house cut by tile borders comes in several parts before merging. */
        const val TILE_PARTS_PER_BUILDING = 4
        val NOT_PASSAGES = setOf("rail", "transit", "ferry", "motorway", "trunk")
    }
}
