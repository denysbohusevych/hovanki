package app.hovanki.client.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.reason_mock_location
import app.hovanki.client.resources.reason_out_of_zone
import app.hovanki.client.resources.reason_stale_signal
import app.hovanki.client.resources.reason_teammate
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.rules.ZoneState
import org.jetbrains.compose.resources.stringResource

/**
 * Placeholder for the real map (MapLibre, once a tile provider is chosen): the zone, the next zone (dashed),
 * our own position with its accuracy and the players the server lets us see, on a local flat projection
 * centered on the zone. North is up.
 */
@Composable
fun ZoneRadar(
    zone: ZoneState,
    myLocation: LocationSample?,
    markers: List<RadarMarker>,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurface)
    val reasonLabels = mapOf(
        VisibilityReason.TEAMMATE to stringResource(Res.string.reason_teammate),
        VisibilityReason.STALE_SIGNAL to stringResource(Res.string.reason_stale_signal),
        VisibilityReason.OUT_OF_ZONE to stringResource(Res.string.reason_out_of_zone),
        VisibilityReason.MOCK_LOCATION to stringResource(Res.string.reason_mock_location),
    )

    Canvas(modifier = modifier.clip(MaterialTheme.shapes.medium).background(colors.surfaceVariant)) {
        val zoneCenter = zone.current.center
        // Fit the zone and everything drawn on top of it, with a small margin.
        val extentMeters = 1.1 * maxOf(
            zone.current.radiusMeters,
            myLocation?.let { it.point.distanceTo(zoneCenter) + it.accuracyMeters } ?: 0.0,
            markers.maxOfOrNull { it.point.distanceTo(zoneCenter) } ?: 0.0,
        )
        val pixelsPerMeter = (size.minDimension / 2 / extentMeters).toFloat()

        fun position(point: GeoPoint): Offset {
            val offset = point.offsetFrom(zoneCenter)
            return Offset(
                x = center.x + offset.eastMeters.toFloat() * pixelsPerMeter,
                y = center.y - offset.northMeters.toFloat() * pixelsPerMeter,
            )
        }

        fun radius(meters: Double): Float = meters.toFloat() * pixelsPerMeter

        drawCircle(colors.primary.copy(alpha = 0.12f), radius(zone.current.radiusMeters), position(zoneCenter))
        drawCircle(
            color = colors.primary,
            radius = radius(zone.current.radiusMeters),
            center = position(zoneCenter),
            style = Stroke(width = 3.dp.toPx()),
        )
        zone.next?.let { next ->
            drawCircle(
                color = colors.primary,
                radius = radius(next.radiusMeters),
                center = position(next.center),
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())),
                ),
            )
        }

        for (marker in markers) {
            val at = position(marker.point)
            val color = if (marker.reason == VisibilityReason.TEAMMATE) colors.secondary else colors.error
            drawCircle(color.copy(alpha = 0.15f), radius(marker.accuracyMeters), at)
            drawCircle(color, 5.dp.toPx(), at)
            val label = textMeasurer.measure(
                AnnotatedString("${marker.name} · ${reasonLabels[marker.reason].orEmpty()}"),
                style = labelStyle,
            )
            // Right of the dot, or left of it when the label would not fit.
            val gap = 8.dp.toPx()
            val labelX = if (at.x + gap + label.size.width <= size.width) at.x + gap else at.x - gap - label.size.width
            drawText(label, topLeft = Offset(labelX, at.y - label.size.height / 2))
        }

        myLocation?.let { me ->
            val at = position(me.point)
            drawCircle(colors.tertiary.copy(alpha = 0.2f), radius(me.accuracyMeters), at)
            drawCircle(Color.White, 8.dp.toPx(), at)
            drawCircle(colors.tertiary, 6.dp.toPx(), at)
        }
    }
}
