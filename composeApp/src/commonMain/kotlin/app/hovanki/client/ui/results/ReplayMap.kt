package app.hovanki.client.ui.results

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hovanki.client.session.Replay
import app.hovanki.client.session.ReplayLine
import app.hovanki.client.ui.game.HeightLayers
import app.hovanki.client.ui.game.MapCredit
import app.hovanki.client.ui.game.MapStyle
import app.hovanki.client.ui.game.ZoneBorder
import app.hovanki.client.ui.game.ZoneFills
import app.hovanki.client.ui.game.ZoneTimeline
import app.hovanki.client.ui.game.rememberMapBackdrop
import app.hovanki.client.ui.game.toPosition
import app.hovanki.client.ui.game.zoneCameraConstraints
import app.hovanki.client.ui.game.zoneShapeAt
import app.hovanki.client.ui.game.zoomToFit
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.theme.color
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.textOffset
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.interaction.MapInteractions
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
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon

/**
 * The replay's map (docs/design.md, «Итоги»): everybody's way until [atMillis] in the color of their role, where each
 * of them was at that moment (a hider who was out already in gray) and the zone as it was, by streets too, as it
 * closed in. Pinch and drag to look closer; the camera stays on the zone of the start.
 */
@Composable
internal fun ReplayMap(replay: Replay, zone: ZoneTimeline, atMillis: Long, modifier: Modifier = Modifier) {
    val shape = rememberUpdatedState(remember(zone, atMillis) { zone.shapeAt(atMillis) })
    val start = remember(zone) { zoneShapeAt(zone.schedule, zone.streets, 0L).extent }
    // The replay is a picture from above: the player's theme and relief, never tilted, so no 3D houses.
    val backdrop = rememberMapBackdrop(at = start.center)
    var styleFailed by remember(backdrop.theme) { mutableStateOf(false) }
    val frame = replay.lines.map { line -> line to line.pathUntil(atMillis) }
    val mapState = rememberMapState(
        baseStyle = if (styleFailed) backdrop.fallback else backdrop.baseStyle,
        initialCameraPosition = CameraPosition(
            target = start.center.toPosition(),
            zoom = zoomToFit(start.copy(radiusMeters = start.radiusMeters * FIT_MARGIN)),
        ),
    ) {
        HeightLayers(backdrop, tilted = false)
        ZoneFills(shape, paint = backdrop.paint)
        ZoneBorder(shape, casingWidth = 6.dp, coreWidth = 3.dp, paint = backdrop.paint)

        for (role in Role.entries) {
            val paths = frame.filter { (line, path) -> line.player.role == role && path.size >= 2 }
                .map { (_, path) -> Feature(LineString(path.map { it.toPosition() }), null) }
            val source = rememberGeoJsonSource(GeoJsonData.Features(FeatureCollection(paths)))
            // The seekers' ink ways would sink into the dark map: a light casing keeps them in sight.
            if (backdrop.isDark && role == Role.SEEKER) {
                LineLayer(
                    id = "paths-${role.name.lowercase()}-casing",
                    source = source,
                    color = const(backdrop.paint.line),
                    width = const(6.dp),
                    join = const(LineJoin.Round),
                    cap = const(LineCap.Round),
                )
            }
            LineLayer(
                id = "paths-${role.name.lowercase()}",
                source = source,
                color = const(role.color),
                width = const(3.5.dp),
                opacity = const(0.9f),
                join = const(LineJoin.Round),
                cap = const(LineCap.Round),
            )
        }

        val here = frame.mapNotNull { (line, path) -> path.lastOrNull()?.let { line to it } }
        for (role in Role.entries) {
            val players = here.filter { (line, _) -> line.player.role == role && !line.isOutAt(atMillis) }
            val source = rememberGeoJsonSource(GeoJsonData.Features(points(players)))
            CircleLayer(
                id = "players-${role.name.lowercase()}",
                source = source,
                color = const(role.color),
                radius = const(7.dp),
                strokeColor = const(if (role == Role.SEEKER) Color.White else Palette.Ink),
                strokeWidth = const(2.dp),
            )
        }
        val outs = here.filter { (line, _) -> line.isOutAt(atMillis) }
        val out = rememberGeoJsonSource(GeoJsonData.Features(points(outs)))
        CircleLayer(
            id = "players-out",
            source = out,
            color = const(Palette.Stale),
            radius = const(6.dp),
            strokeColor = const(Color.White),
            strokeWidth = const(2.dp),
        )
        val labels = rememberGeoJsonSource(GeoJsonData.Features(points(here, withNames = true)))
        SymbolLayer(
            id = "players-labels",
            source = labels,
            textField = feature["label"].asString(),
            textFont = const(MapStyle.FONTS),
            textSize = const(11.sp),
            textAnchor = const(SymbolAnchor.Left),
            textOffset = textOffset(10.dp, 0.dp),
            textColor = const(backdrop.paint.labelText),
            textHaloColor = const(backdrop.paint.labelHalo),
            textHaloWidth = const(2.dp),
            textAllowOverlap = const(true),
        )
    }
    LaunchedEffect(mapState) {
        mapState.events.collect { event -> if (event is MapEvent.StyleLoadFailed) styleFailed = true }
    }
    var shortSideDp by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    Box(
        modifier = modifier.onSizeChanged { size ->
            shortSideDp = with(density) { minOf(size.width, size.height).toDp().value }
        },
    ) {
        MaplibreMap(
            state = mapState,
            cameraConstraints = zoneCameraConstraints(start, shortSideDp),
            // Pinch and drag; no turning or tilting: a picture of the round, north up.
            interactions = MapInteractions(MapInteractions.Standard) {
                camera {
                    rotate { enabled = false }
                    tilt { enabled = false }
                }
            },
            overlay = { include(MapOverlay.None) },
        )
        MapCredit(Modifier.align(Alignment.BottomStart), text = backdrop.attribution)
    }
}

/** A hider caught or eliminated by [atMillis]: their way ends there. */
private fun ReplayLine.isOutAt(atMillis: Long): Boolean =
    player.status != PlayerStatus.ACTIVE && player.outAtMillis?.let { atMillis >= it } == true

private fun points(
    players: List<Pair<ReplayLine, GeoPoint>>,
    withNames: Boolean = false,
): FeatureCollection<Point, JsonObject?> = FeatureCollection(
    players.map { (line, point) ->
        Feature(Point(point.toPosition()), if (withNames) buildJsonObject { put("label", line.player.name) } else null)
    },
)

/** The zone fills the map with a little room around it. */
private const val FIT_MARGIN = 1.1
