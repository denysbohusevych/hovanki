package app.hovanki.client.ui.game

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.PathSegment
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ZoneCircle
import app.hovanki.shared.protocol.ZoneSchedule
import app.hovanki.shared.rules.StreetZone
import app.hovanki.shared.rules.ZoneArea
import app.hovanki.shared.rules.areaAt
import app.hovanki.shared.rules.stateAt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a map draws of the zone at one moment (docs/design.md, «Зона — главная анимация»), made from the very
 * [ZoneArea] the rules check: the zone (outside it darker), the part about to go (pink) and the next zone's border
 * (dashed). While a zone by streets shrinks ([ZoneArea.Shrinking]) its shape is cut out with the platform's path
 * operations: the next stage's polygon and whatever of the current one is still inside the closing circle.
 */
internal class ZoneShape(
    /** The zone, as polygons with holes. */
    val parts: List<ZonePart>,
    /** The part of the zone about to go: from the next zone's announcement until the shrink is over. */
    val band: List<ZonePart>,
    /** The next zone's border; null after the last stage. */
    val next: List<GeoPoint>?,
    /** A circle around the zone as it is now: what the camera fits and stays within. */
    val extent: ZoneCircle,
)

/** One polygon: its border and the holes in it. */
internal class ZonePart(val outline: List<GeoPoint>, val holes: List<List<GeoPoint>> = emptyList())

/**
 * The zone of a game at one moment, for the maps: its [schedule], when it started (null: not yet, the initial zone)
 * and the zone by streets when the game has one.
 */
data class ZoneTimeline(val schedule: ZoneSchedule, val startedAtMillis: Long?, val streets: StreetZone?) {
    internal fun elapsedAt(nowMillis: Long): Long = startedAtMillis?.let { (nowMillis - it).coerceAtLeast(0) } ?: 0L

    internal fun shapeAt(nowMillis: Long): ZoneShape = zoneShapeAt(schedule, streets, elapsedAt(nowMillis))

    /** Whether the zone moves at [nowMillis]: then the maps redraw it many times a second. */
    internal fun isShrinkingAt(nowMillis: Long): Boolean =
        startedAtMillis != null && schedule.stateAt(elapsedAt(nowMillis)).isShrinking
}

/** The zone [elapsedMillis] into [schedule]; [streets] when the game has its zone by streets. */
internal fun zoneShapeAt(schedule: ZoneSchedule, streets: StreetZone?, elapsedMillis: Long): ZoneShape {
    val state = schedule.stateAt(elapsedMillis)
    return when (val area = schedule.areaAt(elapsedMillis, streets)) {
        is ZoneArea.Circle -> {
            val ring = circlePoints(area.circle)
            val next = state.next?.let(::circlePoints)
            ZoneShape(listOf(ZonePart(ring)), bandBetween(ring, next), next, area.circle)
        }

        is ZoneArea.Polygon -> {
            val outline = area.polygon.outline
            val next = streets?.takeIf { state.next != null }?.areaAt(state.stage + 1)?.polygon?.outline
            ZoneShape(listOf(ZonePart(outline)), bandBetween(outline, next), next, extentOf(state.current, outline))
        }

        is ZoneArea.Shrinking -> {
            val plane = Plane(area.cut.center)
            val inner = plane.path(area.inner.polygon.outline)
            val outer = plane.path(area.outer.polygon.outline)
            val left = Path.combine(PathOperation.Intersect, outer, plane.path(circlePoints(area.cut, CUT_SEGMENTS)))
            val parts = plane.parts(Path.combine(PathOperation.Union, inner, left))
                .ifEmpty { listOf(ZonePart(area.inner.polygon.outline)) }
            ZoneShape(
                parts = parts,
                band = plane.parts(Path.combine(PathOperation.Difference, left, inner)),
                next = area.inner.polygon.outline,
                extent = extentOf(state.current, parts.flatMap { it.outline }),
            )
        }
    }
}

private fun bandBetween(outline: List<GeoPoint>, next: List<GeoPoint>?): List<ZonePart> =
    if (next == null) emptyList() else listOf(ZonePart(outline, listOf(next)))

/** Around the schedule's circle now, as far out as the zone reaches. */
private fun extentOf(circle: ZoneCircle, points: List<GeoPoint>): ZoneCircle =
    ZoneCircle(circle.center, points.maxOfOrNull { it.distanceTo(circle.center) } ?: circle.radiusMeters)

/** A closed ring of [segments] points around [circle], counterclockwise. */
internal fun circlePoints(circle: ZoneCircle, segments: Int = CIRCLE_SEGMENTS): List<GeoPoint> =
    (0..segments).map { step ->
        val angle = 2 * PI * (step % segments) / segments
        circle.center.moveBy(
            eastMeters = circle.radiusMeters * cos(angle),
            northMeters =
            circle.radiusMeters * sin(angle),
        )
    }

/** Meters east and north of [origin], what the path operations work in (floats: millimeters at a few kilometers). */
private class Plane(private val origin: GeoPoint) {
    fun path(ring: List<GeoPoint>): Path = Path().apply {
        ring.forEachIndexed { index, point ->
            val offset = point.offsetFrom(origin)
            val x = offset.eastMeters.toFloat()
            val y = offset.northMeters.toFloat()
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    /** The polygons of [path]: every contour, each hole with the smallest border around it. */
    fun parts(path: Path): List<ZonePart> {
        val rings = contours(path).sortedByDescending { abs(it.area) }
        val depth = IntArray(rings.size)
        val parent = IntArray(rings.size) { -1 }
        for (i in rings.indices) {
            val (x, y) = rings[i].probe()
            for (j in 0 until i) {
                // Sorted by size: the last ring that holds this one is the smallest.
                if (rings[j].contains(x, y)) parent[i] = j
            }
            depth[i] = if (parent[i] < 0) 0 else depth[parent[i]] + 1
        }
        return rings.indices.filter { depth[it] % 2 == 0 }.map { outer ->
            ZonePart(
                outline = rings[outer].toGeo(),
                holes = rings.indices.filter { parent[it] == outer && depth[it] % 2 == 1 }.map { rings[it].toGeo() },
            )
        }
    }

    private fun contours(path: Path): List<Ring> {
        val rings = ArrayList<Ring>()
        var xs = ArrayList<Double>()
        var ys = ArrayList<Double>()
        fun finish() {
            if (xs.size >= 3) rings += Ring(xs.toDoubleArray(), ys.toDoubleArray())
            xs = ArrayList()
            ys = ArrayList()
        }
        val points = FloatArray(8)
        val segments = path.iterator()
        while (segments.hasNext()) {
            when (val type = segments.next(points)) {
                PathSegment.Type.Move -> {
                    finish()
                    xs += points[0].toDouble()
                    ys += points[1].toDouble()
                }

                PathSegment.Type.Close -> finish()

                PathSegment.Type.Done -> break

                else -> {
                    // Lines only, the inputs are polygons; a curve would still end at its last point.
                    val end = when (type) {
                        PathSegment.Type.Quadratic, PathSegment.Type.Conic -> 4
                        PathSegment.Type.Cubic -> 6
                        else -> 2
                    }
                    if (xs.isEmpty()) {
                        xs += points[0].toDouble()
                        ys += points[1].toDouble()
                    }
                    xs += points[end].toDouble()
                    ys += points[end + 1].toDouble()
                }
            }
        }
        finish()
        return rings
    }

    private fun Ring.toGeo(): List<GeoPoint> =
        (xs.indices + 0).map { origin.moveBy(eastMeters = xs[it], northMeters = ys[it]) }
}

/** A closed contour in meters; the closing edge is implied. */
private class Ring(val xs: DoubleArray, val ys: DoubleArray) {
    /** Positive counterclockwise. */
    val area: Double = xs.indices.sumOf { i ->
        val j = (i + 1) % xs.size
        xs[i] * ys[j] - xs[j] * ys[i]
    } / 2

    fun contains(x: Double, y: Double): Boolean {
        var inside = false
        var j = xs.size - 1
        for (i in xs.indices) {
            if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** A point just inside, next to the middle of the longest edge: never on another contour's border. */
    fun probe(): Pair<Double, Double> {
        val i = xs.indices.maxBy { i ->
            val j = (i + 1) % xs.size
            (xs[j] - xs[i]) * (xs[j] - xs[i]) + (ys[j] - ys[i]) * (ys[j] - ys[i])
        }
        val j = (i + 1) % xs.size
        val dx = xs[j] - xs[i]
        val dy = ys[j] - ys[i]
        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-9)
        // The inside is on the left of a counterclockwise contour, on the right of a clockwise one.
        val side = if (area >= 0) PROBE_METERS else -PROBE_METERS
        return (xs[i] + dx / 2 - dy / length * side) to (ys[i] + dy / 2 + dx / length * side)
    }
}

private const val CIRCLE_SEGMENTS = 64

/** The closing circle of a shrinking zone by streets: 256 sides are within 8 cm of a 1 km circle. */
private const val CUT_SEGMENTS = 256
private const val PROBE_METERS = 0.01
