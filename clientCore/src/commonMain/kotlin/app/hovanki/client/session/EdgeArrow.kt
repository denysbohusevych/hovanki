package app.hovanki.client.session

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The «back into the zone» arrow at the edge of the screen (docs/design.md, «Тревоги прячущегося»): at ([x], [y]) in
 * screen coordinates (x to the right, y down), pointing [angleDegrees] clockwise from the top of the screen.
 */
data class EdgeArrow(val x: Float, val y: Float, val angleDegrees: Float)

/**
 * Where the arrow goes: from the middle of the free part of the screen ([left], [top], [right], [bottom]: what the
 * HUD and the controls leave) towards [angleDegrees] (clockwise from the top of the screen), on the border of that
 * part. The way back is always straight to the zone's center, so the angle is the bearing from the player to it minus
 * the map's own rotation.
 */
fun edgeArrow(left: Float, top: Float, right: Float, bottom: Float, angleDegrees: Float): EdgeArrow {
    val centerX = (left + right) / 2
    val centerY = (top + bottom) / 2
    val halfWidth = ((right - left) / 2).coerceAtLeast(0f)
    val halfHeight = ((bottom - top) / 2).coerceAtLeast(0f)
    val radians = angleDegrees * PI / 180
    val dx = sin(radians).toFloat()
    val dy = -cos(radians).toFloat()
    // How far along the direction the ray meets a vertical and a horizontal side; the nearer one is where it leaves.
    val toSide = if (abs(dx) < EPSILON) Float.MAX_VALUE else halfWidth / abs(dx)
    val toTopOrBottom = if (abs(dy) < EPSILON) Float.MAX_VALUE else halfHeight / abs(dy)
    val distance = min(toSide, toTopOrBottom)
    return EdgeArrow(centerX + dx * distance, centerY + dy * distance, angleDegrees.mod(360f))
}

private const val EPSILON = 1e-6f
