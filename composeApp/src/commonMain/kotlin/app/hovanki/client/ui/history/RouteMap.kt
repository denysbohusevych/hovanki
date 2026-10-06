package app.hovanki.client.ui.history

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.ui.game.MapCredit
import app.hovanki.client.ui.game.MapStyle
import app.hovanki.client.ui.game.ZoneBorder
import app.hovanki.client.ui.game.ZoneFills
import app.hovanki.client.ui.game.features
import app.hovanki.client.ui.game.toPosition
import app.hovanki.client.ui.game.zoneCameraConstraints
import app.hovanki.client.ui.game.zoneShapeAt
import app.hovanki.client.ui.game.zoomToFit
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.theme.color
import app.hovanki.shared.protocol.GameRoute
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.rules.StreetZone
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon

/**
 * The player's own saved route of a game (docs/adr/0007-game-history-and-routes.md) on the same map as the game
 * (docs/design.md, «Карта»): the zone as it started (by streets when the game had them), the path in the color of the
 * player's role with an ink outline, a white dot where it starts and a role-colored one where it ends. Nothing moves:
 * a picture to look at, and to zoom into; the camera stays on the zone and the route.
 */
@Composable
fun RouteMap(route: GameRoute, modifier: Modifier = Modifier) {
    var styleFailed by remember { mutableStateOf(false) }
    val color = route.role.color
    val path = route.points.map { it.point.toPosition() }
    val streets = remember(route) {
        route.streetZone
            ?.takeIf { stages -> stages.size == route.zone.stages.size + 1 && stages.all { it.outline.size >= 4 } }
            ?.let(::StreetZone)
    }
    val shape = rememberUpdatedState(remember(route, streets) { zoneShapeAt(route.zone, streets, 0L) })
    val zone = shape.value.extent

    val mapState = rememberMapState(
        baseStyle = if (styleFailed) MapStyle.fallback else BaseStyle.Uri(MapStyle.URL),
        initialCameraPosition = CameraPosition(target = zone.center.toPosition(), zoom = zoomToFit(zone)),
    ) {
        ZoneFills(shape)
        ZoneBorder(shape)

        if (path.size >= 2) {
            val line = rememberGeoJsonSource(GeoJsonData.Features(features(LineString(path))))
            LineLayer(
                id = "route-casing",
                source = line,
                // A seeker's path is ink itself: its casing is white.
                color = const(if (route.role == Role.SEEKER) Color.White else Palette.Ink),
                width = const(7.dp),
                join = const(LineJoin.Round),
                cap = const(LineCap.Round),
            )
            LineLayer(
                id = "route-core",
                source = line,
                color = const(color),
                width = const(4.dp),
                join = const(LineJoin.Round),
                cap = const(LineCap.Round),
            )
        }
        if (path.isNotEmpty()) {
            val start = rememberGeoJsonSource(GeoJsonData.Features(features(Point(path.first()))))
            CircleLayer(
                id = "route-start",
                source = start,
                color = const(Color.White),
                radius = const(6.dp),
                strokeColor = const(Palette.Ink),
                strokeWidth = const(2.5.dp),
            )
            val end = rememberGeoJsonSource(GeoJsonData.Features(features(Point(path.last()))))
            CircleLayer(
                id = "route-end",
                source = end,
                color = const(color),
                radius = const(8.dp),
                strokeColor = const(Color.White),
                strokeWidth = const(3.dp),
            )
        }
    }

    LaunchedEffect(mapState) {
        mapState.events.collect { event -> if (event is MapEvent.StyleLoadFailed) styleFailed = true }
    }

    var shortSideDp by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val routePoints = remember(route) { route.points.map { it.point } }
    Box(
        modifier = modifier
            .testTag(TestTags.ROUTE_MAP)
            .onSizeChanged { size -> shortSideDp = with(density) { minOf(size.width, size.height).toDp().value } },
    ) {
        MaplibreMap(
            state = mapState,
            cameraConstraints = zoneCameraConstraints(zone, shortSideDp, routePoints),
            overlay = { include(MapOverlay.None) },
        )
        MapCredit(Modifier.align(Alignment.BottomStart))
    }
}
