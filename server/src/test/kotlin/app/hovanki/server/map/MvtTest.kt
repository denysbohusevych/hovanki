package app.hovanki.server.map

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MvtTest {
    private val house = square(100, 100, 200, 200)
    private val courtyard = square(120, 120, 140, 140).reversed()

    private val tile = MvtWriter()
        .layer(
            "building",
            listOf(
                Triple(MvtGeometryType.POLYGON, mapOf("render_min_height" to 0.0), listOf(house, courtyard)),
                Triple(
                    MvtGeometryType.POLYGON,
                    mapOf("render_min_height" to 7.0, "hide_3d" to true),
                    listOf(square(0, 0, 10, 10)),
                ),
            ),
        )
        .layer(
            "transportation",
            listOf(
                Triple(
                    MvtGeometryType.LINESTRING,
                    mapOf("class" to "minor", "brunnel" to "tunnel"),
                    listOf(listOf(TilePoint(0, 150), TilePoint(300, 150)), listOf(TilePoint(5, 5), TilePoint(6, 900))),
                ),
            ),
        )
        .layer("poi", listOf(Triple(MvtGeometryType.POINT, mapOf("name" to "Café"), listOf(listOf(TilePoint(1, 2))))))
        .bytes()

    @Test
    fun readsLayersFeaturesAndProperties() {
        val decoded = Mvt.decode(tile)

        assertEquals(setOf("building", "transportation", "poi"), decoded.layers.keys)
        val buildings = decoded.layers.getValue("building")
        assertEquals(4096, buildings.extent)
        assertEquals(2, buildings.features.size)
        assertEquals(0.0, buildings.features[0].number("render_min_height"))
        assertEquals(7.0, buildings.features[1].number("render_min_height"))
        assertEquals(true, buildings.features[1].properties["hide_3d"])
        val road = decoded.layers.getValue("transportation").features.single()
        assertEquals("tunnel", road.string("brunnel"))
        assertEquals("Café", decoded.layers.getValue("poi").features.single().string("name"))
    }

    @Test
    fun ringsAreClosedAndHolesWindTheOtherWay() {
        val building = Mvt.decode(tile).layers.getValue("building").features[0]

        assertEquals(MvtGeometryType.POLYGON, building.type)
        assertEquals(listOf(house, courtyard), building.parts)
        assertTrue(Mvt.signedArea(building.parts[0]) > 0, "outer ring")
        assertTrue(Mvt.signedArea(building.parts[1]) < 0, "hole")
    }

    @Test
    fun linesAndPoints() {
        val decoded = Mvt.decode(tile)

        val road = decoded.layers.getValue("transportation").features.single()
        assertEquals(
            listOf(listOf(TilePoint(0, 150), TilePoint(300, 150)), listOf(TilePoint(5, 5), TilePoint(6, 900))),
            road.parts,
        )
        assertEquals(listOf(listOf(TilePoint(1, 2))), decoded.layers.getValue("poi").features.single().parts)
    }

    @Test
    fun onlyTheWantedLayers() {
        assertEquals(setOf("building"), Mvt.decode(tile, setOf("building")).layers.keys)
    }

    @Test
    fun gzippedTiles() {
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(tile) } }.toByteArray()

        assertEquals(Mvt.decode(tile).layers.keys, Mvt.decode(zipped).layers.keys)
    }

    @Test
    fun brokenTilesFailAsIllegalArguments() {
        assertFailsWith<IllegalArgumentException> { Mvt.decode(tile.copyOf(tile.size - 7)) }
    }
}
