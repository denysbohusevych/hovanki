package app.hovanki.server.map

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.ZoneCircle
import app.hovanki.shared.rules.ZoneArea
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A feature for [MvtWriter]: its geometry type, its tags and its rings. */
private typealias TileFeature = Triple<MvtGeometryType, Map<String, Any>, List<List<TilePoint>>>

/** The ground under a zone from map tiles (docs/adr/0010-big-games.md), with tiles written in the test. */
class TerrainReaderTest {
    // Kyiv; a z14 tile is about 1.56 km wide there, 0.38 m a tile unit. Cells of 10 m are 26 units.
    private val tile = TileId(14, 9581, 5524)
    private val area = ZoneCircle(TileMath.toGeo(tile, 4096, TilePoint(2048, 2048)), 300.0)
    private val reader = TerrainReader()

    private fun area(ring: List<TilePoint>, vararg tags: Pair<String, Any>): TileFeature =
        Triple(MvtGeometryType.POLYGON, mapOf(*tags), listOf(ring))

    private fun read(vararg layers: Pair<String, List<TileFeature>>): TerrainGrid {
        val writer = MvtWriter()
        for ((name, features) in layers) writer.layer(name, features)
        return reader.read(listOf(LoadedTile(tile, Mvt.decode(writer.bytes()))), area)
    }

    private fun TerrainGrid.atTile(x: Int, y: Int): Terrain? = at(TileMath.toGeo(tile, 4096, TilePoint(x, y)))

    @Test
    fun eachKindOfGround() {
        val grid = read(
            "landuse" to listOf(
                area(square(1300, 1300, 2000, 2000), "class" to "residential"),
                area(square(2100, 1300, 2800, 2000), "class" to "school"),
                area(square(2300, 1500, 2600, 1800), "class" to "pitch"),
            ),
            "landcover" to listOf(
                // A lawn among the houses, a park with a wood and a lawn in it, a field.
                area(square(1500, 1500, 1800, 1800), "class" to "grass", "subclass" to "grass"),
                area(square(1300, 2100, 2000, 2800), "class" to "grass", "subclass" to "park"),
                area(square(1400, 2200, 1650, 2450), "class" to "wood", "subclass" to "forest"),
                area(square(1700, 2200, 1950, 2450), "class" to "grass", "subclass" to "grass"),
                area(square(2100, 2100, 2800, 2450), "class" to "farmland", "subclass" to "farmland"),
            ),
            "water" to listOf(area(square(2100, 2550, 2400, 2800), "class" to "lake")),
            "building" to listOf(area(square(1350, 1350, 1450, 1450))),
        )

        assertEquals(Terrain.BLOCKED, grid.atTile(1400, 1400), "a house")
        assertEquals(Terrain.DENSE, grid.atTile(1900, 1400), "a residential block")
        assertEquals(Terrain.DENSE, grid.atTile(1650, 1650), "a lawn among the houses is part of the block")
        assertEquals(Terrain.DENSE, grid.atTile(2200, 1400), "a school")
        assertEquals(Terrain.OPEN, grid.atTile(2450, 1650), "the pitch of the school")
        assertEquals(Terrain.MIXED, grid.atTile(1500, 2650), "a park")
        assertEquals(Terrain.FOREST, grid.atTile(1525, 2325), "a wood in the park")
        assertEquals(Terrain.MIXED, grid.atTile(1825, 2325), "a lawn in the park is part of the park")
        assertEquals(Terrain.OPEN, grid.atTile(2450, 2275), "a field")
        assertEquals(Terrain.BLOCKED, grid.atTile(2250, 2675), "a lake")
    }

    @Test
    fun groundTheMapSaysNothingAboutGoesByTheHousesAround() {
        // Houses of 15 × 15 m every 30 m in the west half, nothing in the east half.
        val houses = (0 until 9).flatMap { i ->
            (0 until 18).map { j ->
                val x = 1300 + i * 80
                val y = 1300 + j * 80
                area(square(x, y, x + 40, y + 40))
            }
        }
        val grid = read("building" to houses)

        assertEquals(Terrain.BLOCKED, grid.atTile(1300 + 2 * 80 + 20, 1300 + 5 * 80 + 20), "a house")
        assertEquals(Terrain.DENSE, grid.atTile(1300 + 2 * 80 + 60, 1300 + 5 * 80 + 60), "a yard between houses")
        assertEquals(Terrain.MIXED, grid.atTile(2600, 2048), "far from any house")
    }

    @Test
    fun raisedHousesAndTunnelsBlockNothing() {
        val grid = read(
            "building" to listOf(area(square(1900, 1900, 2200, 2200), "render_min_height" to 5.0)),
            "water" to listOf(area(square(1300, 1300, 1600, 1600), "class" to "river", "brunnel" to "tunnel")),
            "landuse" to listOf(area(square(1250, 1250, 2850, 2850), "class" to "commercial")),
        )

        assertEquals(Terrain.DENSE, grid.atTile(2048, 2048))
        assertEquals(Terrain.DENSE, grid.atTile(1450, 1450))
    }

    @Test
    fun squaresAndSportsGroundsAreOpenEvenInTown() {
        val grid = read(
            "landuse" to listOf(area(square(1250, 1250, 2850, 2850), "class" to "retail")),
            "transportation" to
                listOf(area(square(1900, 1900, 2200, 2200), "class" to "path", "subclass" to "pedestrian")),
        )

        assertEquals(Terrain.OPEN, grid.atTile(2048, 2048), "a square")
        assertEquals(Terrain.DENSE, grid.atTile(1400, 1400))
    }

    @Test
    fun theGridCoversTheArea() {
        val grid = read()

        val side = grid.columns * grid.cellMeters
        assertTrue(side >= 2 * area.radiusMeters, "$side m")
        assertEquals(10.0, grid.cellMeters)
        assertNull(grid.at(area.center.moveBy(eastMeters = 400.0, northMeters = 0.0)))
    }

    @Test
    fun theAreasOfAZone() {
        val grid = TerrainGrid.uniform(ZoneCircle(area.center, 400.0), Terrain.DENSE)

        val areas = grid.areasWithin(ZoneArea.Circle(area))

        val circle = PI * area.radiusMeters * area.radiusMeters
        assertEquals(circle, areas.denseSquareMeters.toDouble(), circle * 0.02)
        assertEquals(0, areas.openSquareMeters)
        assertEquals(areas.denseSquareMeters, areas.playableSquareMeters)
    }

    @Test
    fun aLargeAreaHasLargerCells() {
        val large = ZoneCircle(area.center, 3_000.0)
        val grid = TerrainGrid.uniform(large, Terrain.OPEN)

        assertEquals(30.0, grid.cellMeters)
        assertTrue(grid.columns * grid.rows <= TerrainGrid.MAX_CELLS)
    }
}
