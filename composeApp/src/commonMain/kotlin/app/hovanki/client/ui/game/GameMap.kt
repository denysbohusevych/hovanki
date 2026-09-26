package app.hovanki.client.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.reason_mock_location
import app.hovanki.client.resources.reason_out_of_zone
import app.hovanki.client.resources.reason_stale_signal
import app.hovanki.client.resources.reason_teammate
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.protocol.ZoneCircle
import app.hovanki.shared.rules.ZoneState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.textOffset
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * The game map (docs/adr/0003-map-and-buildings.md): OpenStreetMap vector tiles as the background, and on top of
 * them only what the game knows: the zone, the next zone (dashed), our own position with its accuracy and the players
 * the server lets us see. The background is decoration; without it (no network, provider down) the game layers are
 * drawn on a plain background.
 */
@Composable
fun GameMap(zone: ZoneState, myLocation: LocationSample?, markers: List<MapMarker>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val reasonLabels = mapOf(
        VisibilityReason.TEAMMATE to stringResource(Res.string.reason_teammate),
        VisibilityReason.STALE_SIGNAL to stringResource(Res.string.reason_stale_signal),
        VisibilityReason.OUT_OF_ZONE to stringResource(Res.string.reason_out_of_zone),
        VisibilityReason.MOCK_LOCATION to stringResource(Res.string.reason_mock_location),
    )
    var styleFailed by remember { mutableStateOf(false) }

    val mapState = rememberMapState(
        baseStyle = if (styleFailed) MapStyle.fallback else BaseStyle.Uri(MapStyle.URL),
        initialCameraPosition = CameraPosition(
            target = zone.current.center.toPosition(),
            zoom = zoomToFit(zone.current),
        ),
    ) {
        val zoneSource = rememberGeoJsonSource(GeoJsonData.Features(features(Polygon(circle(zone.current)))))
        FillLayer(id = "zone-fill", source = zoneSource, color = const(colors.primary), opacity = const(0.10f))
        LineLayer(id = "zone-border", source = zoneSource, color = const(colors.primary), width = const(3.dp))

        val next = zone.next
        if (next != null) {
            val nextSource = rememberGeoJsonSource(GeoJsonData.Features(features(LineString(circle(next)))))
            LineLayer(
                id = "zone-next",
                source = nextSource,
                color = const(colors.primary),
                width = const(2.dp),
                dasharray = const(listOf(3, 2)),
            )
        }

        val playerAccuracy = rememberGeoJsonSource(
            GeoJsonData.Features(
                FeatureCollection(markers.map { Feature(Polygon(circle(it.point, it.accuracyMeters)), null) }),
            ),
        )
        FillLayer(id = "players-accuracy", source = playerAccuracy, color = const(colors.error), opacity = const(0.12f))
        val teammates = rememberGeoJsonSource(GeoJsonData.Features(points(markers.filter { it.isTeammate })))
        val revealed = rememberGeoJsonSource(GeoJsonData.Features(points(markers.filterNot { it.isTeammate })))
        CircleLayer(
            id = "players-teammates",
            source = teammates,
            color = const(colors.secondary),
            radius = const(5.dp),
            strokeColor = const(Color.White),
            strokeWidth = const(1.5.dp),
        )
        CircleLayer(
            id = "players-revealed",
            source = revealed,
            color = const(colors.error),
            radius = const(5.dp),
            strokeColor = const(Color.White),
            strokeWidth = const(1.5.dp),
        )
        val labels = rememberGeoJsonSource(
            GeoJsonData.Features(
                points(markers) { marker -> "${marker.name} · ${reasonLabels[marker.reason].orEmpty()}" },
            ),
        )
        SymbolLayer(
            id = "players-labels",
            source = labels,
            textField = feature["label"].asString(),
            textFont = const(MapStyle.FONTS),
            textSize = const(12.sp),
            textAnchor = const(SymbolAnchor.Left),
            textOffset = textOffset(8.dp, 0.dp),
            // The base map is light in both themes.
            textColor = const(Color.Black.copy(alpha = 0.87f)),
            textHaloColor = const(Color.White),
            textHaloWidth = const(1.5.dp),
            textAllowOverlap = const(true),
        )

        if (myLocation != null) {
            val meAccuracy = rememberGeoJsonSource(
                GeoJsonData.Features(features(Polygon(circle(myLocation.point, myLocation.accuracyMeters)))),
            )
            FillLayer(id = "me-accuracy", source = meAccuracy, color = const(colors.tertiary), opacity = const(0.2f))
            val me = rememberGeoJsonSource(GeoJsonData.Features(features(Point(myLocation.point.toPosition()))))
            CircleLayer(
                id = "me",
                source = me,
                color = const(colors.tertiary),
                radius = const(6.dp),
                strokeColor = const(Color.White),
                strokeWidth = const(2.dp),
            )
        }
    }

    LaunchedEffect(mapState) {
        mapState.events.collect { event -> if (event is MapEvent.StyleLoadFailed) styleFailed = true }
    }

    Box(modifier = modifier.clip(MaterialTheme.shapes.medium).testTag(TestTags.GAME_MAP)) {
        MaplibreMap(
            state = mapState,
            // Our own attribution line is always visible below; MapLibre's logo and button stay as well.
            overlay = { include(MapOverlay.AttributionOnly) },
        )
        Text(
            text = MapStyle.ATTRIBUTION,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = Color.Black.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(Color.White.copy(alpha = 0.7f))
                .padding(horizontal = 4.dp, vertical = 1.dp)
                .testTag(TestTags.MAP_ATTRIBUTION),
        )
    }
}

/** Tiles and their credits: one place to switch providers (docs/adr/0003-map-and-buildings.md). */
internal object MapStyle {
    /** OpenFreeMap: free, no key, OpenStreetMap data in the OpenMapTiles schema. */
    const val URL = "https://tiles.openfreemap.org/styles/positron"

    /** The credit the provider and the OpenStreetMap license require, shown on the map at all times. */
    const val ATTRIBUTION = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap"

    /** Fonts the provider's glyph server has. */
    val FONTS = listOf("Noto Sans Regular")

    /** A plain background for when the style can't be loaded; the game layers still draw on it. */
    val fallback: BaseStyle = BaseStyle.Json(
        buildJsonObject {
            put("version", 8)
            put("glyphs", "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf")
            putJsonObject("sources") {}
            putJsonArray("layers") {
                add(
                    buildJsonObject {
                        put("id", "background")
                        put("type", "background")
                        putJsonObject("paint") { put("background-color", "#eeeeee") }
                    },
                )
            }
        },
    )
}

private val MapMarker.isTeammate: Boolean get() = reason == VisibilityReason.TEAMMATE

private fun GeoPoint.toPosition() = Position(longitude = lon, latitude = lat)

private fun features(geometry: Geometry): FeatureCollection<Geometry, JsonObject?> =
    FeatureCollection(listOf(Feature(geometry, null)))

private fun points(
    markers: List<MapMarker>,
    label: (MapMarker) -> String? = { null },
): FeatureCollection<Point, JsonObject?> = FeatureCollection(
    markers.map { marker ->
        val text = label(marker)
        Feature(Point(marker.point.toPosition()), text?.let { buildJsonObject { put("label", it) } })
    },
)

private fun circle(zone: ZoneCircle): List<Position> = circle(zone.center, zone.radiusMeters)

/** A closed ring approximating a circle of [radiusMeters] around [center]. */
private fun circle(center: GeoPoint, radiusMeters: Double, segments: Int = 64): List<Position> =
    (0..segments).map { step ->
        val angle = 2 * PI * (step % segments) / segments
        center.moveBy(eastMeters = radiusMeters * cos(angle), northMeters = radiusMeters * sin(angle)).toPosition()
    }

/** Zoom at which [zone] fills a phone-sized map (~360 dp wide) with a small margin. */
private fun zoomToFit(zone: ZoneCircle): Double {
    val metersPerDp = 2.4 * zone.radiusMeters / 360
    // At zoom 0 a 512 dp tile covers the equator (40 075 km); meters per dp shrink by cos(latitude).
    val metersPerDpAtZoom0 = 40_075_016.7 / 512 * cos(zone.center.lat * PI / 180)
    return (ln(metersPerDpAtZoom0 / metersPerDp) / ln(2.0)).coerceIn(2.0, 18.0)
}
