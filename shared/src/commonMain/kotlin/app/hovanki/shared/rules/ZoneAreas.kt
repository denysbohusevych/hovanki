package app.hovanki.shared.rules

import app.hovanki.shared.geo.LocalOffset
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ZoneCircle
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.protocol.ZoneSchedule
import kotlin.math.sqrt

/** The zone at one moment as the rules check it: a circle, or a polygon of the zone by streets. */
sealed interface ZoneArea {
    /** Meters from [point] to the zone's border: negative inside, positive outside. */
    fun signedDistanceMeters(point: GeoPoint): Double

    /** The point of the border nearest to [point]: where the way back into the zone is shortest. */
    fun nearestBorderPoint(point: GeoPoint): GeoPoint

    /** Whether [point] is in the zone (on the border counts); cheaper than [signedDistanceMeters]. */
    fun contains(point: GeoPoint): Boolean

    data class Circle(val circle: ZoneCircle) : ZoneArea {
        override fun contains(point: GeoPoint): Boolean = point.distanceTo(circle.center) <= circle.radiusMeters

        override fun signedDistanceMeters(point: GeoPoint): Double =
            point.distanceTo(circle.center) - circle.radiusMeters

        override fun nearestBorderPoint(point: GeoPoint): GeoPoint {
            val offset = point.offsetFrom(circle.center)
            val length = sqrt(offset.eastMeters * offset.eastMeters + offset.northMeters * offset.northMeters)
            if (length == 0.0) return circle.center.moveBy(eastMeters = 0.0, northMeters = circle.radiusMeters)
            val scale = circle.radiusMeters / length
            return circle.center.moveBy(offset.eastMeters * scale, offset.northMeters * scale)
        }
    }

    /**
     * A zone by streets while a stage shrinks (docs/adr/0009-game-setup-glow-streets.md, «Изменение: сужение
     * кварталами»): the next stage's polygon [inner], and of the current one [outer] whatever is still inside [cut], a
     * circle that closes in from around all of [outer] to within [inner]. The blocks that go are eaten from the outside
     * in, and the map draws exactly this shape.
     */
    class Shrinking(val outer: Polygon, val inner: Polygon, val cut: ZoneCircle) : ZoneArea {
        private val cutArea = Circle(cut)

        override fun contains(point: GeoPoint): Boolean =
            inner.contains(point) || (outer.contains(point) && cutArea.contains(point))

        override fun signedDistanceMeters(point: GeoPoint): Double = minOf(
            inner.signedDistanceMeters(point),
            maxOf(outer.signedDistanceMeters(point), cutArea.signedDistanceMeters(point)),
        )

        override fun nearestBorderPoint(point: GeoPoint): GeoPoint {
            // The way in: to the next stage's zone, or to what is left of the current one, whichever is nearer.
            val candidates = listOfNotNull(
                inner.nearestBorderPoint(point),
                outer.nearestBorderPoint(point).takeIf { cutArea.signedDistanceMeters(it) <= 0 },
                cutArea.nearestBorderPoint(point).takeIf { outer.signedDistanceMeters(it) <= 0 },
            )
            return candidates.minBy { it.distanceTo(point) }
        }
    }

    /** A zone by streets at one stage; projected to meters around its first point. */
    class Polygon(val polygon: ZonePolygon) : ZoneArea {
        private val origin = polygon.outline.first()
        private val xs: DoubleArray
        private val ys: DoubleArray

        init {
            val offsets = polygon.outline.map { it.offsetFrom(origin) }
            xs = DoubleArray(offsets.size) { offsets[it].eastMeters }
            ys = DoubleArray(offsets.size) { offsets[it].northMeters }
        }

        override fun signedDistanceMeters(point: GeoPoint): Double {
            val p = point.offsetFrom(origin)
            val distance = nearest(p).second
            return if (contains(p.eastMeters, p.northMeters)) -distance else distance
        }

        override fun nearestBorderPoint(point: GeoPoint): GeoPoint {
            val (x, y) = nearest(point.offsetFrom(origin)).first
            return origin.moveBy(eastMeters = x, northMeters = y)
        }

        override fun contains(point: GeoPoint): Boolean {
            val p = point.offsetFrom(origin)
            return contains(p.eastMeters, p.northMeters)
        }

        /** The farthest corner from [center]: a circle this big around it holds the whole polygon. */
        fun farthestMetersFrom(center: GeoPoint): Double = polygon.outline.maxOf { it.distanceTo(center) }

        /** How far [center] is from the border when it is inside: a circle this big around it is within. 0 outside. */
        fun clearanceMetersAround(center: GeoPoint): Double = (-signedDistanceMeters(center)).coerceAtLeast(0.0)

        /** Even-odd rule; the closing edge is implied. */
        private fun contains(x: Double, y: Double): Boolean {
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

        /** The nearest point of the border to [p] and the distance to it. */
        private fun nearest(p: LocalOffset): Pair<Pair<Double, Double>, Double> {
            var best = Double.MAX_VALUE
            var bestX = xs[0]
            var bestY = ys[0]
            for (i in xs.indices) {
                val j = (i + 1) % xs.size
                val dx = xs[j] - xs[i]
                val dy = ys[j] - ys[i]
                val lengthSquared = dx * dx + dy * dy
                val t = if (lengthSquared == 0.0) {
                    0.0
                } else {
                    (((p.eastMeters - xs[i]) * dx + (p.northMeters - ys[i]) * dy) / lengthSquared).coerceIn(0.0, 1.0)
                }
                val x = xs[i] + t * dx
                val y = ys[i] + t * dy
                val distance = sqrt((x - p.eastMeters) * (x - p.eastMeters) + (y - p.northMeters) * (y - p.northMeters))
                if (distance < best) {
                    best = distance
                    bestX = x
                    bestY = y
                }
            }
            return (bestX to bestY) to best
        }
    }
}

/** The zone by streets of a game (docs/adr/0009-game-setup-glow-streets.md): one polygon per stage of the schedule. */
class StreetZone(val stages: List<ZonePolygon>) {
    private val areas = stages.map { ZoneArea.Polygon(it) }

    init {
        require(stages.isNotEmpty() && stages.all { it.outline.size >= 4 }) { "A zone by streets needs its polygons" }
    }

    /** The zone after [stage] stages are over (the last one once all are). */
    fun areaAt(stage: Int): ZoneArea.Polygon = areas[stage.coerceIn(0, areas.size - 1)]

    /**
     * The zone [fraction] into the shrink of [stage], from the schedule's circle [from] to [to]: the cut starts around
     * all of the stage's polygon and ends within the next one, its center moving like the schedule's circle, so the
     * zone is exactly the one polygon at the start and the other at the end.
     */
    fun shrinking(stage: Int, from: ZoneCircle, to: ZoneCircle, fraction: Double): ZoneArea.Shrinking {
        val outer = areaAt(stage)
        val inner = areaAt(stage + 1)
        val f = fraction.coerceIn(0.0, 1.0)
        val startRadius = outer.farthestMetersFrom(from.center)
        val endRadius = inner.clearanceMetersAround(to.center)
        val cut = ZoneCircle(interpolate(from.center, to.center, f), startRadius + (endRadius - startRadius) * f)
        return ZoneArea.Shrinking(outer, inner, cut)
    }
}

/**
 * The zone in force [elapsedMillis] into the schedule: the zone by streets when there is one ([streets]), else the
 * (smoothly shrinking) circle. A zone by streets is the polygon of the current stage while it holds and shrinks block
 * by block ([ZoneArea.Shrinking]) while the circle of the schedule shrinks.
 */
fun ZoneSchedule.areaAt(elapsedMillis: Long, streets: StreetZone?): ZoneArea {
    val state = stateAt(elapsedMillis)
    if (streets == null) return ZoneArea.Circle(state.current)
    val from = state.stageStart
    val to = state.next
    if (!state.isShrinking || from == null || to == null) return streets.areaAt(state.stage)
    return streets.shrinking(state.stage, from, to, state.shrinkFraction)
}
