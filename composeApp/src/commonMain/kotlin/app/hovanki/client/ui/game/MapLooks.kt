@file:OptIn(ExperimentalTime::class)

package app.hovanki.client.ui.game

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.map.MapSettings
import app.hovanki.client.map.MapTheme
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.GeoPoint
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.koin.compose.koinInject
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.times
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.FillExtrusionLayer
import org.maplibre.compose.layers.HillshadeLayer
import org.maplibre.compose.sources.RasterDemEncoding
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.sources.rememberRasterDemTileSource
import org.maplibre.compose.sources.rememberVectorTileSource
import org.maplibre.compose.style.BaseStyle
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Tiles and their credits: one place to switch providers (docs/adr/0003-map-and-buildings.md,
 * docs/adr/0025-map-styles-and-height.md).
 */
internal object MapStyle {
    /** OpenFreeMap: free, no key, OpenStreetMap data in the OpenMapTiles schema. Light and neutral: the game's
     * colors stand out on it. The light theme; the others are ours ([MapStyles]) over the same [TILES]. */
    const val URL = "https://tiles.openfreemap.org/styles/positron"

    /** OpenFreeMap's vector tiles (TileJSON): every style's streets and houses, and the 3D houses' heights. */
    const val TILES = "https://tiles.openfreemap.org/planet"

    /** The provider's glyph server: the labels of our styles and of the game's layers. */
    const val GLYPHS = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"

    /**
     * The ground's heights for the relief: Mapzen's Terrain Tiles on AWS Open Data, free, no key, PNG in the Terrarium
     * encoding, to zoom 15 (MapLibre stretches them closer).
     */
    const val RELIEF_TILES = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
    const val RELIEF_MAX_ZOOM = 15

    /** The credit the provider and the OpenStreetMap license require, shown on the map at all times. */
    const val ATTRIBUTION = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap"

    /** The heights' credit, next to [ATTRIBUTION] while the relief is drawn. */
    const val RELIEF_ATTRIBUTION = "Terrain Tiles © Mapzen"

    /** Where the credit leads: the OpenStreetMap license and contributors. */
    const val COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"

    /** Fonts the provider's glyph server has. */
    val FONTS = listOf("Noto Sans Regular")
}

/**
 * The colors the game's own layers take on a base map: ink on the light ones; on the dark one darker shade outside the
 * zone and light lines, the ink would disappear there. The markers keep their colors on every map.
 */
@Immutable
internal data class MapPaint(
    /** Outside the zone. */
    val shade: Color,
    val shadeOpacity: Float,
    /** Thin lines over the map: the next zone's dash, the building the host looks at. */
    val line: Color,
    /** The map's ground: passages through the buildings, which are outdoors, and the plain fallback background. */
    val ground: Color,
    /** The names next to the players and the board's items, and the halo that keeps them readable over the streets. */
    val labelText: Color,
    val labelHalo: Color,
    /** The 3D houses' walls and roofs (the light shades the walls). */
    val houses: Color,
    val reliefShadow: Color,
    val reliefHighlight: Color,
    /** How strong the relief's shading is, 0..1. */
    val reliefStrength: Float,
) {
    companion object {
        val Light = MapPaint(
            shade = Palette.Ink,
            shadeOpacity = SHADE_OPACITY,
            line = Palette.Ink,
            ground = Color.White,
            labelText = Palette.Ink,
            labelHalo = Color.White,
            houses = Color(0xFFE4E4DF),
            reliefShadow = Color(0xFF4A4A46),
            reliefHighlight = Color.White,
            reliefStrength = 0.3f,
        )
        val Minimal = Light.copy(ground = Color(0xFFF7F7F9), houses = Color(0xFFE8E8EC))
        val Dark = MapPaint(
            shade = Color.Black,
            shadeOpacity = 0.42f,
            line = Color(0xFFE6E7EA),
            ground = Color(0xFF1B1C1F),
            labelText = Color(0xFFF2F2F4),
            labelHalo = Palette.Ink,
            houses = Color(0xFF3A3D44),
            reliefShadow = Color.Black,
            reliefHighlight = Color(0xFF5C6069),
            reliefStrength = 0.45f,
        )
    }
}

/**
 * What a map draws under the game's layers (docs/adr/0025-map-styles-and-height.md): the base style of [theme] (never
 * [MapTheme.AUTO]: that is decided before), the relief and the 3D houses, and the colors the game takes on it.
 */
@Immutable
internal data class MapBackdrop(val theme: MapTheme, val buildings3d: Boolean, val relief: Boolean) {
    val isDark: Boolean get() = theme == MapTheme.DARK

    val paint: MapPaint
        get() = when (theme) {
            MapTheme.DARK -> MapPaint.Dark
            MapTheme.MINIMAL -> MapPaint.Minimal
            MapTheme.LIGHT, MapTheme.AUTO -> MapPaint.Light
        }

    val baseStyle: BaseStyle
        get() = when (theme) {
            MapTheme.DARK -> darkStyle
            MapTheme.MINIMAL -> minimalStyle
            MapTheme.LIGHT, MapTheme.AUTO -> BaseStyle.Uri(MapStyle.URL)
        }

    /** A plain background in the theme's ground for when the style can't be loaded; the game layers still draw on it. */
    val fallback: BaseStyle
        get() = BaseStyle.Json(
            buildJsonObject {
                put("version", 8)
                put("glyphs", MapStyle.GLYPHS)
                putJsonObject("sources") {}
                putJsonArray("layers") {
                    add(
                        buildJsonObject {
                            put("id", "background")
                            put("type", "background")
                            putJsonObject("paint") {
                                put("background-color", if (isDark) "#1b1c1f" else "#f1f1ee")
                            }
                        },
                    )
                }
            },
        )

    /** The credit under the map: the relief's source too while it is drawn. */
    val attribution: String
        get() = if (relief) "${MapStyle.ATTRIBUTION} · ${MapStyle.RELIEF_ATTRIBUTION}" else MapStyle.ATTRIBUTION
}

private val darkStyle by lazy { BaseStyle.Json(MapStyles.dark()) }
private val minimalStyle by lazy { BaseStyle.Json(MapStyles.minimal()) }

/**
 * The look the player chose for the maps on this phone, for a map around [at]: «Auto» is dark between sunset and
 * sunrise there, checked every minute while it is on.
 */
@Composable
internal fun rememberMapBackdrop(at: GeoPoint): MapBackdrop {
    val look by koinInject<MapSettings>().look.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(deviceNow()) }
    if (look.theme == MapTheme.AUTO) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(AUTO_CHECK_MILLIS)
                now = deviceNow()
            }
        }
    }
    val theme = look.themeAt(at, now)
    return remember(theme, look.buildings3d, look.relief) { MapBackdrop(theme, look.buildings3d, look.relief) }
}

/**
 * The ground's height under the game's layers: the relief's shading under the streets, and the houses rising by their
 * height while the map is [tilted] (from straight above they would only lean over the streets and the game's
 * outlines). Declared first in a map's content: the game's layers stay on top of both.
 */
@Composable
internal fun HeightLayers(backdrop: MapBackdrop, tilted: Boolean) {
    val paint = backdrop.paint
    if (backdrop.relief) {
        val heights = rememberRasterDemTileSource(
            tiles = listOf(MapStyle.RELIEF_TILES),
            options = TileSetOptions(maxZoom = MapStyle.RELIEF_MAX_ZOOM),
            tileSize = RELIEF_TILE_SIZE,
            encoding = RasterDemEncoding.Terrarium,
        )
        // Over the water, parks and houses, under the streets and names.
        Anchor.Below({ it.sourceLayer == "transportation" || it.type == "symbol" }) {
            HillshadeLayer(
                id = "relief",
                source = heights,
                shadowColor = const(paint.reliefShadow),
                highlightColor = const(paint.reliefHighlight),
                accentColor = const(paint.reliefShadow),
                exaggeration = const(paint.reliefStrength),
            )
        }
    }
    if (backdrop.buildings3d) {
        // The houses grow out of the ground as the map tilts, and sink back when it is flat again.
        val rise by animateFloatAsState(
            targetValue = if (tilted) 1f else 0f,
            animationSpec = tween(RISE_MILLIS, easing = FastOutSlowInEasing),
        )
        val tiles = rememberVectorTileSource(MapStyle.TILES)
        Anchor.Below({ it.type == "symbol" }) {
            FillExtrusionLayer(
                id = "houses-3d",
                source = tiles,
                sourceLayer = "building",
                minZoom = HOUSES_MIN_ZOOM,
                visible = rise > 0f,
                color = const(paint.houses),
                opacity = const(HOUSES_OPACITY),
                height = feature["render_height"].asNumber(const(0f)) * const(rise),
                base = feature["render_min_height"].asNumber(const(0f)) * const(rise),
            )
        }
    }
}

/** The phone's own clock: enough for the colors of the map, never for the game. */
private fun deviceNow(): Long = Clock.System.now().toEpochMilliseconds()

/** A map counts as tilted from this angle on: the 3D houses rise. */
internal const val TILTED_DEGREES = 8.0

/** The camera's angle the «3D» button tilts the map to. */
internal const val VIEW_3D_TILT = 55.0

private const val AUTO_CHECK_MILLIS = 60_000L
private const val RELIEF_TILE_SIZE = 256
private const val HOUSES_MIN_ZOOM = 14f
private const val HOUSES_OPACITY = 0.92f
private const val RISE_MILLIS = 450
