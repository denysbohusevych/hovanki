package app.hovanki.client.ui.game

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.building_open_tag
import app.hovanki.client.resources.item_taken
import app.hovanki.client.resources.reason_fresh_trail
import app.hovanki.client.resources.reason_glow
import app.hovanki.client.resources.reason_glow_mark
import app.hovanki.client.resources.reason_inside_building
import app.hovanki.client.resources.reason_mock_location
import app.hovanki.client.resources.reason_out_of_zone
import app.hovanki.client.resources.reason_radar_off
import app.hovanki.client.resources.reason_spotlight
import app.hovanki.client.resources.reason_stale_signal
import app.hovanki.client.resources.reason_teammate
import app.hovanki.client.session.ZoneCue
import app.hovanki.client.ui.common.formatCountdown
import app.hovanki.client.ui.common.itemKindTitle
import app.hovanki.client.ui.theme.Motion
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.theme.color
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ItemKind
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.Passage
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.protocol.ZoneCircle
import app.hovanki.shared.rules.ZoneState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.textOffset
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.interaction.ClickResult
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
import org.maplibre.compose.style.TransitionOptions
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
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

/**
 * The game map (docs/adr/0003-map-and-buildings.md, docs/design.md «Карта»): OpenStreetMap vector tiles as the
 * background, and on top of them only what the game knows: the zone (outside it darker), the next zone (dashed), the
 * buildings the server judges by (pink, with the passages through them cut out), our own position with its accuracy
 * and the players the server lets us see. The background is decoration; without it (no network, provider down) the
 * game layers are drawn on a plain background.
 *
 * The zone moves with [cue] (docs/design.md, «Зона — главная анимация»): the part about to go blinks pink before a
 * shrink, the ring turns pink while the zone closes in and snaps back to lime when done. The map redraws the zone many
 * times a second while it shrinks, by the server time [serverNow]: a circle shrinks smoothly, a zone by streets
 * ([ZoneTimeline.streets], docs/adr/0009-game-setup-glow-streets.md) loses its outer blocks from the outside in, both
 * exactly as the rules judge. The camera stays on the zone and closes in with it ([zoneCameraConstraints]).
 * [recenterRequests]: each increase moves the camera to our own position. [onCameraBearing]:
 * the map's rotation (degrees clockwise from north) whenever the player turns it, for what points somewhere on screen.
 * The board ([items], docs/adr/0013-quests-sparks-and-sensors.md) is drawn by kind, what is taken in grey; with
 * [onMapClick] a tap on the map gives its point (the host placing an item, a hider placing a decoy) and [pickedPoint]
 * marks the last one.
 */
@Composable
fun GameMap(
    zone: ZoneTimeline,
    serverNow: () -> Long,
    cue: ZoneCue,
    myLocation: LocationSample?,
    myRole: Role,
    markers: List<MapMarker>,
    buildings: BuildingsResponse?,
    modifier: Modifier = Modifier,
    recenterRequests: Int = 0,
    reduceMotion: Boolean = false,
    attributionPadding: PaddingValues = PaddingValues(0.dp),
    onCameraBearing: (Double) -> Unit = {},
    items: List<MapItem> = emptyList(),
    pickedPoint: GeoPoint? = null,
    onMapClick: ((GeoPoint) -> Unit)? = null,
    highlightedBuilding: BuildingArea? = null,
    interactive: Boolean = true,
    fitTo: ZoneCircle? = null,
    cameraArea: ZoneCircle? = null,
    onCameraIdle: ((GeoPoint) -> Unit)? = null,
    animateZone: Boolean = false,
) {
    val reasonLabels = mapOf(
        VisibilityReason.TEAMMATE to stringResource(Res.string.reason_teammate),
        VisibilityReason.STALE_SIGNAL to stringResource(Res.string.reason_stale_signal),
        VisibilityReason.OUT_OF_ZONE to stringResource(Res.string.reason_out_of_zone),
        VisibilityReason.MOCK_LOCATION to stringResource(Res.string.reason_mock_location),
        VisibilityReason.INSIDE_BUILDING to stringResource(Res.string.reason_inside_building),
        VisibilityReason.GLOW to stringResource(Res.string.reason_glow),
        VisibilityReason.RADAR_OFF to stringResource(Res.string.reason_radar_off),
        VisibilityReason.SPOTLIGHT to stringResource(Res.string.reason_spotlight),
        VisibilityReason.FRESH_TRAIL to stringResource(Res.string.reason_fresh_trail),
    )
    val takenLabel = stringResource(Res.string.item_taken)
    val itemLabels = items.associate { item ->
        val title = item.name.ifBlank { itemKindTitle(item.kind) }
        item.id to if (item.isTaken) "$title · $takenLabel" else title
    }
    var styleFailed by remember { mutableStateOf(false) }
    val look = zoneLook(cue, reduceMotion)
    val ping = if (markers.any { it.isRevealed } && !reduceMotion) revealPing() else null
    val smoothMarkers = markers.map { marker -> key(marker.id) { marker.copy(point = smoothPoint(marker.point)) } }
    val labelTexts = markers.associate { marker ->
        val why = marker.markAgeMillis?.let { age ->
            key(marker.id) { stringResource(Res.string.reason_glow_mark, formatCountdown(age)) }
        } ?: reasonLabels[marker.reason].orEmpty()
        marker.id to "${marker.name} · $why"
    }
    val me = myLocation?.let { it.copy(point = smoothPoint(it.point)) }
    val myColor = myRole.color
    val zoneShape = rememberZoneShape(zone, serverNow, animateZone)
    // Where the camera starts: computed once, not read from the shape, so a moving zone redraws its layers only.
    val startZone = remember(zone) { zone.shapeAt(serverNow()).extent }
    val forbidden = remember(buildings) {
        buildings?.let { FeatureCollection(it.buildings.map { building -> Feature(building.toPolygon(), null) }) }
    }
    val openBuildings = remember(buildings) {
        buildings?.let { FeatureCollection(it.open.map { building -> Feature(building.toPolygon(), null) }) }
    }
    val openLabel = stringResource(Res.string.building_open_tag)
    val openLabels = remember(buildings, openLabel) {
        FeatureCollection(
            buildings?.open.orEmpty().map { building ->
                Feature(Point(building.center().toPosition()), buildJsonObject { put("label", openLabel) })
            },
        )
    }
    val highlight = remember(highlightedBuilding) {
        FeatureCollection(listOfNotNull(highlightedBuilding?.let { Feature(it.toPolygon(), null) }))
    }
    val passageAreas = remember(buildings) {
        buildings?.let {
            FeatureCollection(it.passages.flatMap(::corridor).map { area -> Feature(Polygon(area), null) })
        }
    }

    val mapState = rememberMapState(
        baseStyle = if (styleFailed) MapStyle.fallback else BaseStyle.Uri(MapStyle.URL),
        initialCameraPosition = CameraPosition(target = startZone.center.toPosition(), zoom = zoomToFit(startZone)),
    ) {
        // Outside the zone darker; the part about to go blinks before a shrink and stays pink while it goes.
        ZoneFills(zoneShape, bandOpacity = look.bandOpacity)

        // Where hiding is not allowed: the server's own outlines, not the base map's buildings.
        if (forbidden != null && passageAreas != null) {
            val buildingSource = rememberGeoJsonSource(GeoJsonData.Features(forbidden))
            FillLayer(
                id = "buildings-fill",
                source = buildingSource,
                color = const(Palette.Pink),
                opacity = const(0.3f),
            )
            LineLayer(
                id = "buildings-border",
                source = buildingSource,
                color = const(Palette.Pink),
                width = const(1.5.dp),
            )
            // Passages are outdoors: drawn over the buildings in the light color of the base map.
            val passages = rememberGeoJsonSource(GeoJsonData.Features(passageAreas))
            FillLayer(id = "buildings-passages", source = passages, color = const(Color.White), opacity = const(0.9f))
        }
        // The buildings the host opened for hiding (docs/adr/0014-settings-lobby-redesign-open-buildings.md): lime
        // with an ink dash, and «open» on them up close.
        if (openBuildings != null) {
            val openSource = rememberGeoJsonSource(GeoJsonData.Features(openBuildings))
            FillLayer(
                id = "buildings-open-fill",
                source = openSource,
                color = const(Palette.Lime),
                opacity = const(0.85f),
            )
            LineLayer(
                id = "buildings-open-border",
                source = openSource,
                color = const(Palette.Ink),
                width = const(1.5.dp),
                dasharray = const(listOf<Number>(2, 1.5)),
            )
            SymbolLayer(
                id = "buildings-open-labels",
                source = rememberGeoJsonSource(GeoJsonData.Features(openLabels)),
                minZoom = OPEN_LABEL_MIN_ZOOM,
                textField = feature["label"].asString(),
                textFont = const(MapStyle.FONTS),
                textSize = const(11.sp),
                textColor = const(Palette.Ink),
                textHaloColor = const(Palette.Lime),
                textHaloWidth = const(2.dp),
            )
        }
        // The building the host is looking at in the settings, drawn over the rest.
        LineLayer(
            id = "buildings-highlight",
            source = rememberGeoJsonSource(GeoJsonData.Features(highlight)),
            color = const(Palette.Ink),
            width = const(3.dp),
            join = const(LineJoin.Round),
        )

        ZoneBorder(
            zoneShape,
            casingWidth = look.casingWidth,
            coreColor = look.coreColor,
            nextOpacity = look.nextOpacity,
        )

        val playerAccuracy = rememberGeoJsonSource(
            GeoJsonData.Features(
                FeatureCollection(
                    smoothMarkers.filter {
                        it.isRevealed
                    }.map { Feature(Polygon(circle(it.point, it.accuracyMeters)), null) },
                ),
            ),
        )
        FillLayer(
            id = "players-accuracy",
            source = playerAccuracy,
            color = const(Palette.Violet),
            opacity = const(0.14f),
        )
        val revealed = rememberGeoJsonSource(GeoJsonData.Features(points(smoothMarkers.filter { it.isRevealed })))
        if (ping != null) {
            CircleLayer(
                id = "players-ping",
                source = revealed,
                color = const(Color.Transparent),
                radius = const(ping.radius),
                strokeColor = const(Palette.Violet),
                strokeWidth = const(2.5.dp),
                strokeOpacity = const(ping.opacity),
            )
        }
        CircleLayer(
            id = "players-revealed",
            source = revealed,
            color = const(Palette.Violet),
            radius = const(MARKER_RADIUS),
            strokeColor = const(Color.White),
            strokeWidth = const(2.5.dp),
        )
        val stale = rememberGeoJsonSource(GeoJsonData.Features(points(smoothMarkers.filter { it.isStale })))
        CircleLayer(
            id = "players-stale",
            source = stale,
            color = const(Palette.Stale),
            opacity = const(0.75f),
            radius = const(MARKER_RADIUS),
            strokeColor = const(Color.White),
            strokeWidth = const(2.dp),
        )
        val teammates = rememberGeoJsonSource(GeoJsonData.Features(points(smoothMarkers.filter { it.isTeammate })))
        CircleLayer(
            id = "players-teammates",
            source = teammates,
            color = const(myColor),
            radius = const(MARKER_RADIUS),
            strokeColor = const(Palette.Ink),
            strokeWidth = const(2.5.dp),
        )
        val labels = rememberGeoJsonSource(
            GeoJsonData.Features(
                points(smoothMarkers) { marker -> labelTexts[marker.id] },
            ),
        )
        SymbolLayer(
            id = "players-labels",
            source = labels,
            textField = feature["label"].asString(),
            textFont = const(MapStyle.FONTS),
            textSize = const(12.sp),
            textAnchor = const(SymbolAnchor.Left),
            textOffset = textOffset(12.dp, 0.dp),
            textColor = const(Palette.Ink),
            textHaloColor = const(Color.White),
            textHaloWidth = const(2.dp),
            textAllowOverlap = const(true),
        )

        // The board: quest points in lime, checkpoints in orange, perks lying around in pink; taken ones grey.
        val questPoints = rememberGeoJsonSource(
            GeoJsonData.Features(itemPoints(items.filter { !it.isTaken && it.kind == ItemKind.QUEST_POINT })),
        )
        CircleLayer(
            id = "items-quests",
            source = questPoints,
            color = const(Palette.Lime),
            radius = const(ITEM_RADIUS),
            strokeColor = const(Palette.Ink),
            strokeWidth = const(2.dp),
        )
        val checkpoints = rememberGeoJsonSource(
            GeoJsonData.Features(
                itemPoints(
                    items.filter {
                        !it.isTaken && (it.kind == ItemKind.CHECKPOINT_GEO || it.kind == ItemKind.CHECKPOINT_SCAN)
                    },
                ),
            ),
        )
        CircleLayer(
            id = "items-checkpoints",
            source = checkpoints,
            color = const(Palette.Orange),
            radius = const(ITEM_RADIUS),
            strokeColor = const(Palette.Ink),
            strokeWidth = const(2.dp),
        )
        val pickups = rememberGeoJsonSource(
            GeoJsonData.Features(itemPoints(items.filter { !it.isTaken && it.kind == ItemKind.PICKUP })),
        )
        CircleLayer(
            id = "items-pickups",
            source = pickups,
            color = const(Palette.Pink),
            radius = const(ITEM_RADIUS),
            strokeColor = const(Palette.Ink),
            strokeWidth = const(2.dp),
        )
        val taken = rememberGeoJsonSource(GeoJsonData.Features(itemPoints(items.filter { it.isTaken })))
        CircleLayer(
            id = "items-taken",
            source = taken,
            color = const(Palette.Stale),
            opacity = const(0.7f),
            radius = const(ITEM_RADIUS),
            strokeColor = const(Color.White),
            strokeWidth = const(2.dp),
        )
        val itemLabelSource = rememberGeoJsonSource(GeoJsonData.Features(itemPoints(items) { itemLabels[it.id] }))
        SymbolLayer(
            id = "items-labels",
            source = itemLabelSource,
            textField = feature["label"].asString(),
            textFont = const(MapStyle.FONTS),
            textSize = const(11.sp),
            textAnchor = const(SymbolAnchor.Top),
            textOffset = textOffset(0.dp, 10.dp),
            textColor = const(Palette.Ink),
            textHaloColor = const(Color.White),
            textHaloWidth = const(2.dp),
        )

        // Where the player tapped, about to place something.
        if (pickedPoint != null) {
            val picked = rememberGeoJsonSource(GeoJsonData.Features(features(Point(pickedPoint.toPosition()))))
            CircleLayer(
                id = "picked",
                source = picked,
                color = const(Color.White),
                radius = const(11.dp),
                strokeColor = const(Palette.Ink),
                strokeWidth = const(3.dp),
            )
        }

        if (me != null) {
            val meAccuracy = rememberGeoJsonSource(
                GeoJsonData.Features(features(Polygon(circle(me.point, me.accuracyMeters)))),
            )
            FillLayer(id = "me-accuracy", source = meAccuracy, color = const(myColor), opacity = const(0.18f))
            val mePoint = rememberGeoJsonSource(GeoJsonData.Features(features(Point(me.point.toPosition()))))
            CircleLayer(
                id = "me",
                source = mePoint,
                color = const(myColor),
                radius = const(8.dp),
                strokeColor = const(Color.White),
                strokeWidth = const(3.dp),
            )
        }
    }

    LaunchedEffect(mapState) {
        mapState.events.collect { event -> if (event is MapEvent.StyleLoadFailed) styleFailed = true }
    }
    val bearingListener by rememberUpdatedState(onCameraBearing)
    LaunchedEffect(mapState) {
        snapshotFlow { mapState.cameraPosition.bearing }.distinctUntilChanged().collect { bearingListener(it) }
    }
    LaunchedEffect(recenterRequests) {
        val point = myLocation?.point
        if (recenterRequests > 0 && point != null) mapState.animateCamera(CameraUpdate(target = point.toPosition()))
    }
    val idleListener by rememberUpdatedState(onCameraIdle)
    LaunchedEffect(mapState, onCameraIdle != null) {
        if (onCameraIdle == null) return@LaunchedEffect
        snapshotFlow { mapState.isCameraMoving to mapState.cameraPosition.target }
            .filter { (moving, _) -> !moving }
            .map { (_, target) -> GeoPoint(target.latitude, target.longitude) }
            .distinctUntilChanged()
            .collect { idleListener?.invoke(it) }
    }

    // A tap gives its point when somebody is placing something; otherwise the map only pans and zooms.
    val clickListener by rememberUpdatedState(onMapClick)
    val interactions = remember(onMapClick != null, interactive) {
        if (!interactive) {
            MapInteractions.None
        } else if (onMapClick == null) {
            MapInteractions.Standard
        } else {
            MapInteractions(MapInteractions.Standard) {
                callbacks {
                    click {
                        onEvent { event ->
                            // No position for a tap off the map's edge.
                            val position = event.position
                            if (position != null) {
                                clickListener?.invoke(GeoPoint(position.latitude, position.longitude))
                            }
                            ClickResult.Consume
                        }
                    }
                }
            }
        }
    }

    var shortSideDp by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val myPoint = myLocation?.point
    val constraints by remember(myPoint, cameraArea) {
        derivedStateOf {
            val extent = cameraArea ?: zoneShape.value.extent
            // Out of the zone the camera may go as far as the player, to show the way back.
            val outside = myPoint?.takeIf { it.distanceTo(extent.center) > extent.radiusMeters }
            zoneCameraConstraints(extent, shortSideDp, listOfNotNull(outside))
        }
    }

    // The settings' draft: the camera follows the zone the host is choosing.
    LaunchedEffect(fitTo) {
        val area = fitTo ?: return@LaunchedEffect
        val side = if (shortSideDp > 0f) shortSideDp else FIT_DEFAULT_SIDE_DP
        mapState.animateCamera(
            CameraUpdate(
                target = area.center.toPosition(),
                zoom = fitZoom(area.center, area.radiusMeters * 1.15, side),
            ),
        )
    }

    Box(
        modifier = modifier
            .testTag(TestTags.GAME_MAP)
            .onSizeChanged { size -> shortSideDp = with(density) { minOf(size.width, size.height).toDp().value } },
    ) {
        MaplibreMap(
            state = mapState,
            interactions = interactions,
            cameraConstraints = constraints,
            // The credit is our own line: always visible, also over the plain fallback background and for the
            // building outlines (OpenStreetMap too). MapLibre's expanding one would repeat it.
            overlay = { include(MapOverlay.None) },
        )
        MapCredit(Modifier.align(Alignment.BottomStart).padding(attributionPadding))
    }
}

/**
 * The zone as the map draws it, by the server time: redrawn many times a second while it shrinks, once a second
 * otherwise. The path operations of a zone by streets run off the main thread.
 */
@Composable
private fun rememberZoneShape(zone: ZoneTimeline, serverNow: () -> Long, animate: Boolean): State<ZoneShape> {
    val clock by rememberUpdatedState(serverNow)
    return produceState(initialValue = remember(zone) { zone.shapeAt(serverNow()) }, zone, animate) {
        while (true) {
            val now = clock()
            value = withContext(Dispatchers.Default) { zone.shapeAt(now) }
            // A preview plays the schedule sped up: every frame counts.
            delay(if (animate || zone.isShrinkingAt(now)) SHRINK_FRAME_MILLIS else CALM_FRAME_MILLIS)
        }
    }
}

/** The credit the tile provider and the OpenStreetMap license require, on every map; leads to the license. */
@Composable
internal fun MapCredit(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Text(
        text = MapStyle.ATTRIBUTION,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
        color = Palette.Ink2,
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.8f))
            .clickable { uriHandler.openUri(MapStyle.COPYRIGHT_URL) }
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .testTag(TestTags.MAP_ATTRIBUTION),
    )
}

/** How the zone looks at the moment: what blinks and pops. */
private class ZoneLook(val bandOpacity: Float, val nextOpacity: Float, val casingWidth: Dp, val coreColor: Color)

@Composable
private fun zoneLook(cue: ZoneCue, reduceMotion: Boolean): ZoneLook {
    val blink = when {
        cue != ZoneCue.SOON && cue != ZoneCue.COUNTDOWN -> 0f

        reduceMotion -> 0.5f

        // Faster in the last seconds, in time with the countdown.
        else -> blinking(periodMillis = if (cue == ZoneCue.COUNTDOWN) 1_000 else 2_000)
    }
    val band = when (cue) {
        ZoneCue.SOON, ZoneCue.COUNTDOWN -> BAND_MIN + (BAND_MAX - BAND_MIN) * blink
        ZoneCue.SHRINKING -> BAND_SHRINKING
        else -> 0f
    }
    val next = when (cue) {
        ZoneCue.SOON, ZoneCue.COUNTDOWN -> 0.45f + 0.55f * blink
        ZoneCue.SHRUNK, ZoneCue.FINAL -> 0f
        else -> 1f
    }
    // The ring «snaps» when a shrink is done: the outline swells and springs back.
    val casing by animateDpAsState(if (cue == ZoneCue.SHRUNK) 20.dp else RING_CASING_WIDTH, Motion.pop())
    val core by animateColorAsState(if (cue == ZoneCue.SHRINKING) Palette.Pink else Palette.Lime, Motion.base())
    return ZoneLook(bandOpacity = band, nextOpacity = next, casingWidth = casing, coreColor = core)
}

/** 0 → 1 → 0 over [periodMillis], for as long as it is composed. */
@Composable
private fun blinking(periodMillis: Int): Float {
    val transition = rememberInfiniteTransition()
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMillis / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    )
    return value
}

private class Ping(val radius: Dp, val opacity: Float)

/** A ring growing out of the revealed players and fading, then a pause. */
@Composable
private fun revealPing(): Ping {
    val transition = rememberInfiniteTransition()
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PING_MILLIS, easing = LinearEasing), RepeatMode.Restart),
    )
    val grow = min(t * 2f, 1f)
    return Ping(radius = MARKER_RADIUS + 18.dp * grow, opacity = if (t < 0.5f) 0.7f * (1f - grow) else 0f)
}

/**
 * [target], reached smoothly: points come every few seconds, and a marker glides to the new one instead of jumping.
 * A jump further than [SNAP_METERS] (a reconnect) is not animated.
 */
@Composable
private fun smoothPoint(target: GeoPoint): GeoPoint {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == to) return@LaunchedEffect
        val shown = interpolate(from, to, progress.value)
        if (shown.distanceTo(target) > SNAP_METERS) {
            from = target
            to = target
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        from = shown
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, tween(MARKER_GLIDE_MILLIS, easing = FastOutSlowInEasing))
    }
    return interpolate(from, to, progress.value)
}

private fun interpolate(from: GeoPoint, to: GeoPoint, fraction: Float): GeoPoint = if (fraction >= 1f) {
    to
} else {
    GeoPoint(from.lat + (to.lat - from.lat) * fraction, from.lon + (to.lon - from.lon) * fraction)
}

internal const val SHADE_OPACITY = 0.18f
private const val BAND_MIN = 0.14f
private const val BAND_MAX = 0.5f
private const val BAND_SHRINKING = 0.3f

/** How often a map redraws the zone: while it shrinks, and while it holds (for the next second's state). */
private const val SHRINK_FRAME_MILLIS = 100L
private const val CALM_FRAME_MILLIS = 1_000L
private const val PING_MILLIS = 2_400
private const val MARKER_GLIDE_MILLIS = 800
private const val SNAP_METERS = 150.0
internal val RING_CASING_WIDTH = 8.dp
internal val RING_CORE_WIDTH = 4.dp
private val MARKER_RADIUS = 9.dp
private val ITEM_RADIUS = 8.dp

/** The map's short side before it is measured, for fitting the camera. */
private const val FIT_DEFAULT_SIDE_DP = 360f

/** From this zoom on an open building says so: houses are big enough there. */
private const val OPEN_LABEL_MIN_ZOOM = 15.5f

/** Tiles and their credits: one place to switch providers (docs/adr/0003-map-and-buildings.md). */
internal object MapStyle {
    /** OpenFreeMap: free, no key, OpenStreetMap data in the OpenMapTiles schema. Light and neutral: the game's
     * colors stand out on it. */
    const val URL = "https://tiles.openfreemap.org/styles/positron"

    /** The credit the provider and the OpenStreetMap license require, shown on the map at all times. */
    const val ATTRIBUTION = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap"

    /** Where the credit leads: the OpenStreetMap license and contributors. */
    const val COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"

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
                        putJsonObject("paint") { put("background-color", "#f1f1ee") }
                    },
                )
            }
        },
    )
}

private val MapMarker.isTeammate: Boolean get() = reason == VisibilityReason.TEAMMATE

/** Where a player was, not where they are: an old fix, or the spot the last glow left. */
private val MapMarker.isStale: Boolean get() = reason == VisibilityReason.STALE_SIGNAL || markAgeMillis != null
private val MapMarker.isRevealed: Boolean get() = !isTeammate && !isStale

private fun List<GeoPoint>.toRing(): List<Position> = map { it.toPosition() }.let { ring ->
    if (ring.first() == ring.last()) ring else ring + listOf(ring.first())
}

/** The middle of a building's outline, where its label goes. */
private fun BuildingArea.center(): GeoPoint {
    val ring = if (outline.size > 1 && outline.first() == outline.last()) outline.dropLast(1) else outline
    return GeoPoint(ring.sumOf { it.lat } / ring.size, ring.sumOf { it.lon } / ring.size)
}

private fun BuildingArea.toPolygon() = Polygon(
    listOf(outline.toRing()) + holes.filter {
        it.size >= 3
    }.map { it.toRing() },
)

/** A passage as rectangles along its segments, [Passage.widthMeters] wide. */
private fun corridor(passage: Passage): List<List<Position>> = passage.path.zipWithNext { from, to ->
    val along = to.offsetFrom(from)
    val length = sqrt(along.eastMeters * along.eastMeters + along.northMeters * along.northMeters)
    if (length == 0.0) return@zipWithNext null
    val half = passage.widthMeters / 2
    val east = -along.northMeters / length * half
    val north = along.eastMeters / length * half
    val corners =
        listOf(from.moveBy(east, north), to.moveBy(east, north), to.moveBy(-east, -north), from.moveBy(-east, -north))
    (corners + corners.first()).map { it.toPosition() }
}.filterNotNull()

internal fun GeoPoint.toPosition() = Position(longitude = lon, latitude = lat)

internal fun features(geometry: Geometry): FeatureCollection<Geometry, JsonObject?> =
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

private fun itemPoints(
    items: List<MapItem>,
    label: (MapItem) -> String? = { null },
): FeatureCollection<Point, JsonObject?> = FeatureCollection(
    items.map { item ->
        val text = label(item)
        Feature(Point(item.point.toPosition()), text?.let { buildJsonObject { put("label", it) } })
    },
)

internal fun circle(zone: ZoneCircle): List<Position> = circle(zone.center, zone.radiusMeters)

/**
 * A polygon's border as a closed ring turning counterclockwise, like [circle]: the map cuts a ring out of another only
 * when the two turn opposite ways, whichever way the server sent it.
 */
internal fun List<GeoPoint>.toCounterclockwiseRing(): List<Position> {
    val ring = toRing()
    // The shoelace sum in degrees: positive turns counterclockwise (east is x, north is y).
    val twiceArea = ring.zipWithNext { a, b -> a.longitude * b.latitude - b.longitude * a.latitude }.sum()
    return if (twiceArea < 0) ring.asReversed() else ring
}

/** A closed ring approximating a circle of [radiusMeters] around [center], counterclockwise. */
internal fun circle(center: GeoPoint, radiusMeters: Double, segments: Int = 64): List<Position> =
    (0..segments).map { step ->
        val angle = 2 * PI * (step % segments) / segments
        center.moveBy(eastMeters = radiusMeters * cos(angle), northMeters = radiusMeters * sin(angle)).toPosition()
    }

/** A counterclockwise square far around [zone]: «the world» that the shade outside the zone covers. */
internal fun around(zone: ZoneCircle): List<Position> {
    val c = zone.center
    return listOf(
        GeoPoint(c.lat - WORLD_DEGREES, c.lon - WORLD_DEGREES),
        GeoPoint(c.lat - WORLD_DEGREES, c.lon + WORLD_DEGREES),
        GeoPoint(c.lat + WORLD_DEGREES, c.lon + WORLD_DEGREES),
        GeoPoint(c.lat + WORLD_DEGREES, c.lon - WORLD_DEGREES),
        GeoPoint(c.lat - WORLD_DEGREES, c.lon - WORLD_DEGREES),
    ).map { it.toPosition() }
}

private const val WORLD_DEGREES = 0.5

/** Zoom at which [zone] fills a phone-sized map (~360 dp wide) with a small margin. */
internal fun zoomToFit(zone: ZoneCircle): Double = fitZoom(zone.center, 1.2 * zone.radiusMeters, 360f)
