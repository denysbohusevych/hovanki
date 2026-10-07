package app.hovanki.client.ui.game

import app.hovanki.client.map.MapTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.style.BaseStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapStylesTest {
    @Test
    fun ourStylesAreWholeStylesOverOpenFreeMap() {
        for (style in listOf(MapStyles.dark(), MapStyles.minimal())) {
            assertEquals(8, style["version"]!!.jsonPrimitive.content.toInt())
            assertEquals(MapStyle.GLYPHS, style["glyphs"]!!.jsonPrimitive.content, "the game's labels need the glyphs")
            val sources = style["sources"]!!.jsonObject
            assertEquals(setOf(MapStyles.SOURCE), sources.keys)
            assertEquals(MapStyle.TILES, sources[MapStyles.SOURCE]!!.jsonObject["url"]!!.jsonPrimitive.content)
            val layers = style.layers()
            assertEquals(layers.size, layers.map { it.id }.toSet().size, "layer ids are unique")
            assertEquals("background", layers.first().id)
            for (layer in layers.drop(1)) {
                assertEquals(MapStyles.SOURCE, layer["source"]!!.jsonPrimitive.content, layer.id)
            }
            // The game's own ids stay free: they are added on top of these.
            assertTrue(layers.none { it.id.startsWith("zone-") || it.id.startsWith("players-") })
        }
    }

    @Test
    fun theDarkMapHasStreetNamesTheMinimalOneNoText() {
        assertTrue(MapStyles.dark().layers().any { it.id == "street-names" })
        assertTrue(MapStyles.minimal().layers().none { it["type"]!!.jsonPrimitive.content == "symbol" })
    }

    @Test
    fun streetsLeaveTunnelsOut() {
        val roads = MapStyles.minimal().layers().filter {
            it["source-layer"]?.jsonPrimitive?.content == "transportation"
        }
        assertTrue(roads.isNotEmpty())
        for (road in roads) assertTrue("tunnel" in road["filter"].toString(), road.id)
    }

    @Test
    fun eachThemeHasItsBaseStyleAndColors() {
        val light = MapBackdrop(MapTheme.LIGHT, buildings3d = true, relief = false)
        assertEquals(BaseStyle.Uri(MapStyle.URL), light.baseStyle, "the light map is Positron, as before")
        assertEquals(MapPaint.Light, light.paint)
        assertEquals(MapStyle.ATTRIBUTION, light.attribution)

        val dark = MapBackdrop(MapTheme.DARK, buildings3d = true, relief = true)
        assertTrue(dark.isDark)
        assertEquals(MapPaint.Dark, dark.paint)
        assertTrue(dark.baseStyle is BaseStyle.Json)
        assertTrue(MapStyle.RELIEF_ATTRIBUTION in dark.attribution, "the relief's source is credited while drawn")

        val minimal = MapBackdrop(MapTheme.MINIMAL, buildings3d = false, relief = false)
        assertFalse(minimal.isDark)
        assertEquals(MapPaint.Minimal, minimal.paint)
        assertEquals(BaseStyle.Json(MapStyles.minimal()), minimal.baseStyle)
    }

    private fun JsonObject.layers(): List<JsonObject> = this["layers"]!!.jsonArray.map { it.jsonObject }

    private val JsonObject.id: String get() = this["id"]!!.jsonPrimitive.content
}
