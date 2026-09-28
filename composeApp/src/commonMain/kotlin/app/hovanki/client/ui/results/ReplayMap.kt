package app.hovanki.client.ui.results

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hovanki.client.session.Replay
import app.hovanki.client.session.ReplayLine
import app.hovanki.client.ui.game.MapCredit
import app.hovanki.client.ui.game.MapStyle
import app.hovanki.client.ui.game.SHADE_OPACITY
import app.hovanki.client.ui.game.around
import app.hovanki.client.ui.game.circle
import app.hovanki.client.ui.game.features
import app.hovanki.client.ui.game.toPosition
import app.hovanki.client.ui.game.zoomToFit
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.theme.color
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.ZoneSchedule
import app.hovanki.shared.rules.stateAt
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
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon

/**
 * The replay's map (docs/design.md, «Итоги»): everybody's way until [atMillis] in the color of their role, where each
 * of them was at that moment (a hider who was out already in gray) and the zone as it was. Still: the screen around it
 * scrolls, and the zone is all there is to see.
 */
@Composable
internal fun ReplayMap(
    replay: Replay,
    zone: ZoneSchedule,
    zoneStartedAtMillis: Long?,
    atMillis: Long,
    modifier: Modifier = Modifier,
) {
    var styleFailed by remember { mutableStateOf(false) }
    val circleNow = zone.stateAt(zoneStartedAtMillis?.let { (atMillis - it).coerceAtLeast(0) } ?: 0L).current
    val frame = replay.lines.map { line -> line to line.pathUntil(atMillis) }
    val mapState = rememberMapState(
        baseStyle = if (styleFailed) MapStyle.fallback else BaseStyle.Uri(MapStyle.URL),
        initialCameraPosition = CameraPosition(
            target = zone.initial.center.toPosition(),
            zoom = zoomToFit(zone.initial.copy(radiusMeters = zone.initial.radiusMeters * FIT_MARGIN)),
        ),
    ) {
        val ring = circle(circleNow)
        val shade =
            rememberGeoJsonSource(GeoJsonData.Features(features(Polygon(listOf(around(circleNow), ring.asReversed())))))
        FillLayer(id = "zone-shade", source = shade, color = const(Palette.Ink), opacity = const(SHADE_OPACITY))
        val zoneRing = rememberGeoJsonSource(GeoJsonData.Features(features(LineString(ring))))
        LineLayer(id = "zone-casing", source = zoneRing, color = const(Palette.Ink), width = const(6.dp))
        LineLayer(id = "zone-core", source = zoneRing, color = const(Palette.Lime), width = const(3.dp))

        for (role in Role.entries) {
            val paths = frame.filter { (line, path) -> line.player.role == role && path.size >= 2 }
                .map { (_, path) -> Feature(LineString(path.map { it.toPosition() }), null) }
            val source = rememberGeoJsonSource(GeoJsonData.Features(FeatureCollection(paths)))
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
                strokeColor = const(Palette.Ink),
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
            textColor = const(Palette.Ink),
            textHaloColor = const(Color.White),
            textHaloWidth = const(2.dp),
            textAllowOverlap = const(true),
        )
    }
    LaunchedEffect(mapState) {
        mapState.events.collect { event -> if (event is MapEvent.StyleLoadFailed) styleFailed = true }
    }
    Box(modifier = modifier) {
        MaplibreMap(state = mapState, interactions = MapInteractions.None, overlay = { include(MapOverlay.None) })
        MapCredit(Modifier.align(Alignment.BottomStart))
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
