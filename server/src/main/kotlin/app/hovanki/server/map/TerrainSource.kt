package app.hovanki.server.map

import app.hovanki.shared.protocol.ZoneCircle
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.prep.PreparedGeometry
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import org.locationtech.jts.index.strtree.STRtree
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Where the capacity of a zone gets the ground under it (docs/adr/0010-big-games.md). Blocking: called off the request
 * threads, once per zone (and by the admin for a drawn zone).
 */
fun interface TerrainSource {
    /** The ground within [area]; throws [MapDataUnavailableException] when it can't be read. */
    fun terrain(area: ZoneCircle): TerrainGrid
}

/** The ground from the players' map tiles: the same data the map shows. */
class TileTerrainSource(private val tiles: VectorTiles, private val reader: TerrainReader = TerrainReader()) :
    TerrainSource {
    override fun terrain(area: ZoneCircle): TerrainGrid = reader.read(tiles.tiles(area), area)
}

/** Built-up blocks everywhere (tests, the `e2e` profile): a zone fits one player per 1 000 m², the default norm. */
class FakeTerrainSource : TerrainSource {
    override fun terrain(area: ZoneCircle): TerrainGrid = TerrainGrid.uniform(area, Terrain.DENSE)
}

/** No map data: no zone gets an estimate. */
class NoTerrainSource : TerrainSource {
    override fun terrain(area: ZoneCircle): TerrainGrid =
        throw MapDataUnavailableException("The terrain source is off", retry = false)
}

/**
 * The ground under an area from map tiles (the OpenMapTiles schema of OpenFreeMap). Each cell takes the first
 * [Category] whose polygon holds its center: houses and water first, then squares, woods, parks and gardens, sports
 * grounds, built-up land use, fields and beaches, protected areas. A lawn among apartment blocks is part of the block;
 * a lawn in a park is part of the park. A cell nothing says anything about counts as built-up when houses cover
 * [DENSE_BUILDING_SHARE] of its surroundings, else as mixed ground. Tested with tiles written in the test.
 */
class TerrainReader(private val maxCells: Int = TerrainGrid.MAX_CELLS) {
    fun read(loaded: List<LoadedTile>, area: ZoneCircle): TerrainGrid {
        val projection = LocalProjection(area.center)
        val cell = maxOf(TerrainGrid.MIN_CELL_METERS, 2 * area.radiusMeters / sqrt(maxCells.toDouble()))
        val side = TerrainGrid.sideFor(area, cell)
        val half = side * cell / 2
        val box = Envelope(-half, half, -half, half)
        val trees = Category.entries.associateWith { STRtree() }
        val prepared = PreparedGeometryFactory()
        for (tile in loaded) {
            for ((name, layer) in tile.tile.layers) {
                for (feature in layer.features) {
                    if (feature.type != MvtGeometryType.POLYGON) continue
                    val category = categoryOf(name, feature) ?: continue
                    for (polygon in projection.polygonsOf(feature, tile.id, layer.extent)) {
                        val envelope = polygon.envelopeInternal
                        if (envelope.intersects(
                                box,
                            )
                        ) {
                            trees.getValue(category).insert(envelope, prepared.create(polygon))
                        }
                    }
                }
            }
        }
        val factory = GeometryFactory()
        val categories = arrayOfNulls<Category>(side * side)
        for (row in 0 until side) {
            for (column in 0 until side) {
                val point = factory.createPoint(Coordinate(-half + (column + 0.5) * cell, -half + (row + 0.5) * cell))
                categories[row * side + column] = Category.entries.firstOrNull { category ->
                    trees.getValue(category).query(point.envelopeInternal).any {
                        (it as PreparedGeometry).covers(point)
                    }
                }
            }
        }
        val cells = ByteArray(side * side)
        val buildings = buildingShare(categories, side, cell)
        for (index in categories.indices) {
            val terrain = categories[index]?.terrain
                ?: if (buildings(index) >= DENSE_BUILDING_SHARE) Terrain.DENSE else Terrain.MIXED
            cells[index] = terrain.ordinal.toByte()
        }
        return TerrainGrid(area.center, cell, side, side, cells)
    }

    /** The share of house cells within [NEIGHBORHOOD_METERS] of each cell (a summed-area table: constant time each). */
    private fun buildingShare(categories: Array<Category?>, side: Int, cell: Double): (Int) -> Double {
        val sums = IntArray((side + 1) * (side + 1))
        for (row in 0 until side) {
            for (column in 0 until side) {
                val house = if (categories[row * side + column] == Category.BUILDING) 1 else 0
                sums[(row + 1) * (side + 1) + column + 1] = house + sums[row * (side + 1) + column + 1] +
                    sums[(row + 1) * (side + 1) + column] - sums[row * (side + 1) + column]
            }
        }
        val reach = ceil(NEIGHBORHOOD_METERS / cell).toInt()
        return { index ->
            val row = index / side
            val column = index % side
            val r0 = maxOf(0, row - reach)
            val r1 = minOf(side, row + reach + 1)
            val c0 = maxOf(0, column - reach)
            val c1 = minOf(side, column + reach + 1)
            val houses = sums[r1 * (side + 1) + c1] - sums[r0 * (side + 1) + c1] - sums[r1 * (side + 1) + c0] +
                sums[r0 * (side + 1) + c0]
            houses.toDouble() / ((r1 - r0) * (c1 - c0))
        }
    }

    /** What a polygon of [layer] says about the ground, or null when nothing that matters here. */
    private fun categoryOf(layer: String, feature: MvtFeature): Category? {
        val kind = feature.string("class")
        val subclass = feature.string("subclass")
        return when (layer) {
            VectorTiles.BUILDING_LAYER ->
                if ((feature.number("render_min_height") ?: 0.0) >= RAISED_METERS) null else Category.BUILDING

            VectorTiles.WATER_LAYER ->
                if (feature.string("brunnel") == "tunnel" ||
                    feature.number("intermittent") == 1.0
                ) {
                    null
                } else {
                    Category.WATER
                }

            // Pedestrian squares and other areas of the street network.
            VectorTiles.TRANSPORTATION_LAYER -> Category.SQUARE

            VectorTiles.LANDCOVER_LAYER -> when {
                kind == "wood" || subclass in THICKETS -> Category.WOOD
                subclass in PARKLAND -> Category.PARKLAND
                subclass in SPORT -> Category.SPORT
                kind in OPEN_LANDCOVER -> Category.OPEN_LAND
                else -> null
            }

            VectorTiles.LANDUSE_LAYER -> when (kind) {
                in PARKLAND -> Category.PARKLAND
                in SPORT -> Category.SPORT
                in BUILT -> Category.BUILT
                in OPEN_LANDUSE -> Category.OPEN_LAND
                else -> null
            }

            VectorTiles.PARK_LAYER -> Category.PROTECTED

            else -> null
        }
    }

    /** What a polygon says about the ground under it, in the order a cell looks: the first that holds it wins. */
    private enum class Category(val terrain: Terrain) {
        BUILDING(Terrain.BLOCKED),
        WATER(Terrain.BLOCKED),
        SQUARE(Terrain.OPEN),
        WOOD(Terrain.FOREST),
        PARKLAND(Terrain.MIXED),
        SPORT(Terrain.OPEN),
        BUILT(Terrain.DENSE),
        OPEN_LAND(Terrain.OPEN),
        PROTECTED(Terrain.MIXED),
    }

    companion object {
        /** Houses on more than this share of the surroundings: ground the map says nothing about is a built-up block. */
        const val DENSE_BUILDING_SHARE = 0.1
        const val NEIGHBORHOOD_METERS = 50.0

        /** On pillars or a bridge between houses: one can stand under it (as the building rule has it). */
        private const val RAISED_METERS = 2.5

        /** Landcover subclasses of dense bushes: as good as a wood for hiding. */
        private val THICKETS = setOf("scrub")

        /** Trees among lawns: landcover subclasses and land use classes. */
        private val PARKLAND = setOf(
            "park",
            "garden",
            "recreation_ground",
            "allotments",
            "village_green",
            "orchard",
            "vineyard",
            "plant_nursery",
            "cemetery",
            "playground",
            "zoo",
            "theme_park",
        )

        /** Sports grounds: open, however built-up around. */
        private val SPORT = setOf("pitch", "stadium", "track", "golf_course")

        private val BUILT = setOf(
            "residential",
            "commercial",
            "industrial",
            "retail",
            "garages",
            "school",
            "university",
            "college",
            "kindergarten",
            "hospital",
            "library",
            "bus_station",
            "railway",
            "military",
            "neighbourhood",
            "quarter",
            "suburb",
        )

        /** Fields, meadows, beaches, rock, ice and marsh: seen from afar. */
        private val OPEN_LANDCOVER = setOf("farmland", "grass", "sand", "rock", "ice", "wetland")
        private val OPEN_LANDUSE = setOf("quarry", "brownfield", "landfill", "construction")
    }
}
