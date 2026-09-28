package app.hovanki.server.buildings

import app.hovanki.server.map.LoadedTile
import app.hovanki.server.map.MapProperties
import app.hovanki.server.map.Mvt
import app.hovanki.server.map.MvtGeometryType
import app.hovanki.server.map.MvtWriter
import app.hovanki.server.map.TileId
import app.hovanki.server.map.TileMath
import app.hovanki.server.map.TilePoint
import app.hovanki.server.map.VectorTiles
import app.hovanki.server.map.square
import app.hovanki.shared.protocol.ZoneCircle
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.BuildingMap
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A feature for [MvtWriter]: its geometry type, its tags and its rings or lines. */
private typealias TileFeature = Triple<MvtGeometryType, Map<String, Any>, List<List<TilePoint>>>

/** Buildings from map tiles (docs/adr/0003-map-and-buildings.md), with tiles written in the test. */
class TileBuildingSourceTest {
    // Kyiv; a z14 tile is about 1.6 km wide there, 0.4 m a tile unit.
    private val tile = TileId(14, 9581, 5524)
    private val east = TileId(14, 9582, 5524)
    private val area = ZoneCircle(TileMath.toGeo(tile, 4096, TilePoint(3000, 2048)), 500.0)
    private val tiles = VectorTiles(MapProperties(), protocolJson, Clock.systemUTC())
    private val source = TileBuildingSource(tiles, BuildingProperties())

    private fun building(vararg rings: List<TilePoint>, raised: Double = 0.0): TileFeature =
        Triple(MvtGeometryType.POLYGON, mapOf<String, Any>("render_min_height" to raised), rings.toList())

    private fun road(kind: String, vararg points: TilePoint, brunnel: String = "tunnel"): TileFeature {
        val tags = mapOf<String, Any>("class" to kind, "brunnel" to brunnel)
        return Triple(MvtGeometryType.LINESTRING, tags, listOf(points.toList()))
    }

    private fun loaded(id: TileId, buildings: List<TileFeature>, roads: List<TileFeature> = emptyList()) =
        LoadedTile(id, Mvt.decode(MvtWriter().layer("building", buildings).layer("transportation", roads).bytes()))

    @Test
    fun touchingHousesAreOneBuilding() {
        val houses = listOf(building(square(3000, 2000, 3100, 2100)), building(square(3100, 2000, 3200, 2100)))
        val tiles = listOf(loaded(tile, houses))

        val buildings = source.read(tiles, area)

        assertEquals(1, buildings.buildings.size)
        val map = BuildingMap(buildings.buildings, buildings.passages, area.center)
        // Where the shared wall was is deep inside now: the way out is through the outer walls.
        val wall = TileMath.toGeo(tile, 4096, TilePoint(3100, 2050))
        assertTrue(checkNotNull(map.depthInsideMeters(wall)) > 15.0)
    }

    @Test
    fun aHouseCutByATileBorderIsWholeAgain() {
        // The tiles overlap a little (their buffer): the east part starts a bit left of the east tile's edge.
        val tiles = listOf(
            loaded(tile, listOf(building(square(4000, 2000, 4100, 2100)))),
            loaded(east, listOf(building(square(-100, 2000, 60, 2100)))),
        )

        val buildings = source.read(tiles, area)

        assertEquals(1, buildings.buildings.size)
    }

    @Test
    fun courtyardsStayOutdoors() {
        val house = building(square(3000, 2000, 3300, 2300), square(3100, 2100, 3200, 2200).reversed())
        val tiles = listOf(loaded(tile, listOf(house)))

        val buildings = source.read(tiles, area)

        assertEquals(1, buildings.buildings.single().holes.size)
        val map = BuildingMap(buildings.buildings, buildings.passages, area.center)
        assertNull(map.depthInsideMeters(TileMath.toGeo(tile, 4096, TilePoint(3150, 2150))))
        assertNotNull(map.depthInsideMeters(TileMath.toGeo(tile, 4096, TilePoint(3050, 2150))))
    }

    @Test
    fun buildingsOnPillarsAndFarAwayAreLeftOut() {
        val onPillars = building(square(3000, 2000, 3100, 2100), raised = 7.0)
        val tiles = listOf(loaded(tile, listOf(onPillars, building(square(100, 100, 200, 200)))))

        assertEquals(emptyList(), source.read(tiles, area).buildings)
    }

    @Test
    fun tunnelsThroughBuildingsArePassages() {
        val tiles = listOf(
            loaded(
                tile,
                listOf(building(square(3000, 2000, 3100, 2100))),
                listOf(
                    road("path", TilePoint(3050, 1950), TilePoint(3050, 2150)),
                    road("rail", TilePoint(3020, 1950), TilePoint(3020, 2150)),
                    road("minor", TilePoint(3500, 1950), TilePoint(3500, 2150)),
                    road("minor", TilePoint(3080, 1950), TilePoint(3080, 2150), brunnel = "bridge"),
                ),
            ),
        )

        val passages = source.read(tiles, area).passages

        assertEquals(1, passages.size)
        assertEquals(4.0, passages.single().widthMeters)
    }

    @Test
    fun tooManyBuildingsTurnTheRuleOff() {
        val many = (0 until 20).map { i -> building(square(2600 + i * 40, 2000, 2620 + i * 40, 2020)) }
        val source = TileBuildingSource(tiles, BuildingProperties(maxBuildings = 10))

        val error = assertFailsWith<BuildingsUnavailableException> { source.read(listOf(loaded(tile, many)), area) }

        assertEquals(false, error.retry)
    }
}
