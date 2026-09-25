package app.hovanki.e2e.route

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import kotlin.math.roundToLong

/**
 * Where a player really is over time: straight legs between [waypoints] at a constant [speedMetersPerSecond].
 * Before the start the player is at the first waypoint, after the end at the last one.
 * What the phone reports on top of that (noise, jumps, mock flag) is [GpsNoise]'s job.
 */
class Route(val waypoints: List<GeoPoint>, val speedMetersPerSecond: Double = WALKING) {
    init {
        require(waypoints.isNotEmpty()) { "A route needs at least one point" }
        require(speedMetersPerSecond > 0) { "Speed must be positive" }
    }

    private val legLengths = waypoints.zipWithNext { a, b -> a.distanceTo(b) }

    val lengthMeters: Double = legLengths.sum()

    val durationMillis: Long = (lengthMeters / speedMetersPerSecond * 1000).roundToLong()

    val start: GeoPoint get() = waypoints.first()

    val end: GeoPoint get() = waypoints.last()

    fun positionAt(elapsedMillis: Long): GeoPoint {
        var remaining = elapsedMillis.coerceAtLeast(0) / 1000.0 * speedMetersPerSecond
        for ((index, length) in legLengths.withIndex()) {
            if (remaining < length) {
                val from = waypoints[index]
                val offset = waypoints[index + 1].offsetFrom(from)
                val fraction = remaining / length
                return from.moveBy(offset.eastMeters * fraction, offset.northMeters * fraction)
            }
            remaining -= length
        }
        return end
    }

    /**
     * What a phone on this route reports every [intervalMillis] from [startMillis] until the end of the route
     * (and [holdMillis] longer, standing at the end).
     */
    fun samples(
        startMillis: Long,
        intervalMillis: Long,
        noise: GpsNoise = GpsNoise.NONE,
        holdMillis: Long = 0,
    ): Sequence<LocationSample> {
        require(intervalMillis > 0)
        val total = durationMillis + holdMillis
        return generateSequence(0L) { it + intervalMillis }
            .takeWhile { it <= total }
            .map { elapsed -> noise.fix(positionAt(elapsed), startMillis + elapsed) }
    }

    /** This route followed by walking on through [points] at the same speed. */
    fun then(vararg points: GeoPoint): Route = Route(waypoints + points, speedMetersPerSecond)

    companion object {
        /** Brisk walk, m/s. */
        const val WALKING = 1.5

        /** Jogging, m/s. */
        const val RUNNING = 4.0

        fun stay(at: GeoPoint): Route = Route(listOf(at))

        fun walk(vararg points: GeoPoint, speed: Double = WALKING): Route = Route(points.toList(), speed)
    }
}

/** Point [eastMeters]/[northMeters] away; negative values go west/south. Reads well in scenarios. */
fun GeoPoint.offset(eastMeters: Double = 0.0, northMeters: Double = 0.0): GeoPoint = moveBy(eastMeters, northMeters)
