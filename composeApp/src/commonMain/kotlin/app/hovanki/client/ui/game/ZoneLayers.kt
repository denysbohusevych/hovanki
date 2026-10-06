package app.hovanki.client.ui.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import app.hovanki.client.ui.theme.Motion
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ZoneCircle
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Polygon
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.time.Duration.Companion.milliseconds

/**
 * The zone's fills on every map: outside it darker, and the part about to go in pink when [bandOpacity] is above 0.
 * [shape] is read here, so a moving zone redraws these layers only.
 */
@Composable
internal fun ZoneFills(shape: State<ZoneShape>, bandOpacity: Float = 0f) {
    val zone = shape.value
    val shade = rememberGeoJsonSource(GeoJsonData.Features(FeatureCollection(zone.shade().map { Feature(it, null) })))
    FillLayer(id = "zone-shade", source = shade, color = const(Palette.Ink), opacity = const(SHADE_OPACITY))
    if (bandOpacity > 0f && zone.band.isNotEmpty()) {
        val band = rememberGeoJsonSource(
            GeoJsonData.Features(FeatureCollection(zone.band.map { Feature(it.toPolygon(), null) })),
        )
        FillLayer(id = "zone-band", source = band, color = const(Palette.Pink), opacity = const(bandOpacity))
    }
}

/**
 * The zone's border on every map, an ink casing with a colored core, and the next zone's border dashed when
 * [nextOpacity] is above 0.
 */
@Composable
internal fun ZoneBorder(
    shape: State<ZoneShape>,
    casingWidth: Dp = RING_CASING_WIDTH,
    coreColor: Color = Palette.Green,
    coreWidth: Dp = RING_CORE_WIDTH,
    nextOpacity: Float = 0f,
) {
    val zone = shape.value
    val next = zone.next
    if (next != null && nextOpacity > 0f) {
        val nextSource =
            rememberGeoJsonSource(GeoJsonData.Features(features(LineString(next.toCounterclockwiseRing()))))
        LineLayer(
            id = "zone-next",
            source = nextSource,
            color = const(Palette.Ink),
            width = const(NEXT_WIDTH),
            opacity = const(nextOpacity),
            dasharray = const(listOf<Number>(4, 2.5)),
        )
    }
    val rings = zone.parts.flatMap { listOf(it.outline) + it.holes }
    val ring = rememberGeoJsonSource(
        GeoJsonData.Features(FeatureCollection(rings.map { Feature(LineString(it.toCounterclockwiseRing()), null) })),
    )
    LineLayer(
        id = "zone-casing",
        source = ring,
        color = const(Palette.Ink),
        width = const(casingWidth),
        join = const(LineJoin.Round),
        cap = const(LineCap.Round),
    )
    LineLayer(
        id = "zone-core",
        source = ring,
        color = const(coreColor),
        colorTransition = TransitionOptions(Motion.BASE_MILLIS.milliseconds),
        width = const(coreWidth),
        join = const(LineJoin.Round),
        cap = const(LineCap.Round),
    )
}

/**
 * Keeps the camera on the zone (docs/design.md, «Карта»): zoomed out no farther than the whole zone on the map's
 * shorter side, and its middle within the zone, or out to [alsoShow] (the player outside it, a route that left it).
 * A shrinking zone raises the limit: the map closes in with it. Rounded, so the limits change a few times per shrink
 * rather than every frame.
 */
internal fun zoneCameraConstraints(
    zone: ZoneCircle,
    shortSideDp: Float,
    alsoShow: List<GeoPoint> = emptyList(),
): CameraConstraints {
    val radius = ceil(zone.radiusMeters / RADIUS_STEP_METERS) * RADIUS_STEP_METERS
    val side = if (shortSideDp > 0f) shortSideDp else DEFAULT_MAP_DP
    val minZoom = floor(fitZoom(zone.center, radius * FIT_MARGIN, side) * ZOOM_STEPS) / ZOOM_STEPS
    val southWest = zone.center.moveBy(eastMeters = -radius, northMeters = -radius)
    val northEast = zone.center.moveBy(eastMeters = radius, northMeters = radius)
    var west = southWest.lon
    var south = southWest.lat
    var east = northEast.lon
    var north = northEast.lat
    for (point in alsoShow) {
        west = minOf(west, point.lon)
        south = minOf(south, point.lat)
        east = maxOf(east, point.lon)
        north = maxOf(north, point.lat)
    }
    // Outwards to about 10 m: a zone that moves changes the limits now and then, not every frame.
    val box = BoundingBox(
        floor(west / BOX_STEP_DEGREES) * BOX_STEP_DEGREES,
        floor(south / BOX_STEP_DEGREES) * BOX_STEP_DEGREES,
        ceil(east / BOX_STEP_DEGREES) * BOX_STEP_DEGREES,
        ceil(north / BOX_STEP_DEGREES) * BOX_STEP_DEGREES,
    )
    return CameraConstraints(minZoom = minZoom, maxZoom = MAX_ZOOM, boundingBox = box)
}

/** The zoom at which a circle of [radiusMeters] around [center] just fits [sideDp]. */
internal fun fitZoom(center: GeoPoint, radiusMeters: Double, sideDp: Float): Double {
    val metersPerDp = 2 * radiusMeters / sideDp
    // At zoom 0 a 512 dp tile covers the equator (40 075 km); meters per dp shrink by cos(latitude).
    val metersPerDpAtZoom0 = 40_075_016.7 / 512 * cos(center.lat * PI / 180)
    return (ln(metersPerDpAtZoom0 / metersPerDp) / ln(2.0)).coerceIn(MIN_ZOOM, FIT_MAX_ZOOM)
}

private fun ZonePart.toPolygon() =
    Polygon(listOf(outline.toCounterclockwiseRing()) + holes.map { it.toCounterclockwiseRing().asReversed() })

/** The world with the zone cut out, and whatever lies in the zone's holes. */
private fun ZoneShape.shade(): List<Polygon> =
    listOf(Polygon(listOf(around(extent)) + parts.map { it.outline.toCounterclockwiseRing().asReversed() })) +
        parts.flatMap { it.holes }.map { Polygon(listOf(it.toCounterclockwiseRing())) }

private val NEXT_WIDTH = Dp(2f)

/** A little room around the zone when it fills the map. */
private const val FIT_MARGIN = 1.1
private const val RADIUS_STEP_METERS = 10.0
private const val BOX_STEP_DEGREES = 0.0001
private const val ZOOM_STEPS = 20.0
private const val DEFAULT_MAP_DP = 360f
private const val MIN_ZOOM = 2.0
private const val FIT_MAX_ZOOM = 18.0

/** Close enough for a doorway, not so close that the tiles blur. */
private const val MAX_ZOOM = 20.0
