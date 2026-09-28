package app.hovanki.shared.rules

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.RoutePoint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * One player's route through a round (docs/adr/0007-game-history-and-routes.md): the points to draw it, thinned, and
 * what it adds up to: the distance, the moving time and the top speed. Feed it the fixes [LocationTrack] accepted, in
 * time order. Not thread-safe: the owner synchronizes access.
 *
 * GPS jitters by meters even when nobody moves, and adding up every little jump would make a hider standing in a
 * doorway for half an hour walk kilometers. So the distance only grows by steps from the last point counted that are
 * longer than the accuracy radii of both their ends together (and at least [MIN_STEP_METERS]): two fixes of a player
 * standing still overlap. A step counts once the next fix is that far away too: a single stray fix never does. Only
 * usable fixes ([isUsable]) count. A step slower than [MIN_MOVING_SPEED_METERS_PER_SECOND] is drift, or the first
 * step after a long stop: its distance counts, its time is not moving time. The top speed is taken over steps of at
 * least [MIN_SPEED_STEP_MILLIS] and never exceeds the plausible speed of the rules.
 *
 * The points are for drawing: accepted fixes up to [MAX_POINT_ACCURACY_METERS], at most one per
 * [MIN_POINT_INTERVAL_MILLIS], and while the player stands still one per [MAX_POINT_INTERVAL_MILLIS]. A route longer
 * than [maxPoints] loses every other point and keeps half as many from then on: the whole round stays, less densely.
 */
class RouteRecorder(
    private val rules: GameRules,
    private val maxPoints: Int = MAX_POINTS,
    /** False: only the numbers, no points at all (an odometer for a guest, whose positions are never kept). */
    private val keepPoints: Boolean = true,
) {
    private val points = ArrayList<RoutePoint>()
    private var minPointIntervalMillis = MIN_POINT_INTERVAL_MILLIS

    /** The last fix the distance was counted up to. */
    private var anchor: LocationSample? = null

    /** A step away from [anchor], counted once the next fix confirms it. */
    private var candidate: LocationSample? = null

    /** Fixes fed in, of any accuracy: how well the phone reported (analytics). */
    var fixes: Int = 0
        private set

    var distanceMeters: Double = 0.0
        private set

    var movingMillis: Long = 0
        private set

    var maxSpeedMetersPerSecond: Double? = null
        private set

    /** The points to draw, oldest first. */
    fun points(): List<RoutePoint> = points.toList()

    fun add(fix: LocationSample) {
        if (fix.isMock) return
        fixes++
        keepPoint(fix)
        if (fix.isUsable(rules)) count(fix)
    }

    private fun keepPoint(fix: LocationSample) {
        if (!keepPoints || fix.accuracyMeters > MAX_POINT_ACCURACY_METERS) return
        val last = points.lastOrNull()
        if (last != null) {
            val millis = fix.timestampMillis - last.atMillis
            if (millis < minPointIntervalMillis) return
            val still = last.point.distanceTo(fix.point) < MIN_POINT_DISTANCE_METERS
            if (still && millis < MAX_POINT_INTERVAL_MILLIS) return
        }
        if (points.size >= maxPoints) thin()
        points += RoutePoint(
            lat = fix.point.lat.rounded(COORDINATE_DECIMALS),
            lon = fix.point.lon.rounded(COORDINATE_DECIMALS),
            accuracyMeters = fix.accuracyMeters.rounded(1),
            atMillis = fix.timestampMillis,
        )
    }

    /** Every other point goes (the first and the last stay), and points come half as often from now on. */
    private fun thin() {
        val last = points.last()
        val kept = points.filterIndexed { index, _ -> index % 2 == 0 }
        points.clear()
        points += kept
        if (points.last() != last) points += last
        minPointIntervalMillis *= 2
    }

    private fun count(fix: LocationSample) {
        val from = anchor
        if (from == null) {
            anchor = fix
            return
        }
        if (fix.timestampMillis <= from.timestampMillis) return
        if (!isStep(from, fix)) {
            // Back where the player was: the step waiting for confirmation was a stray fix.
            candidate = null
            return
        }
        val step = candidate
        if (step == null) {
            candidate = fix
            return
        }
        commit(from, step)
        candidate = null
        // The fix that confirmed the step may start the next one.
        count(fix)
    }

    private fun isStep(from: LocationSample, to: LocationSample): Boolean =
        from.point.distanceTo(to.point) >= max(MIN_STEP_METERS, from.accuracyMeters + to.accuracyMeters)

    private fun commit(from: LocationSample, to: LocationSample) {
        val meters = from.point.distanceTo(to.point)
        val millis = to.timestampMillis - from.timestampMillis
        val speed = meters / (millis / 1000.0)
        distanceMeters += meters
        if (speed >= MIN_MOVING_SPEED_METERS_PER_SECOND) {
            movingMillis += millis
            if (millis >= MIN_SPEED_STEP_MILLIS) {
                val capped = min(speed, rules.maxPlausibleSpeedMetersPerSecond)
                maxSpeedMetersPerSecond = max(maxSpeedMetersPerSecond ?: 0.0, capped)
            }
        }
        anchor = to
    }

    companion object {
        /** A two-hour round at one point per 5 s fits; longer ones get thinned. */
        const val MAX_POINTS = 1_500
        const val MIN_STEP_METERS = 8.0
        const val MIN_MOVING_SPEED_METERS_PER_SECOND = 0.5
        const val MIN_SPEED_STEP_MILLIS = 5_000L
        const val MAX_POINT_ACCURACY_METERS = 50.0
        const val MIN_POINT_INTERVAL_MILLIS = 5_000L
        const val MAX_POINT_INTERVAL_MILLIS = 60_000L
        const val MIN_POINT_DISTANCE_METERS = 5.0

        /** About 10 cm: far below what GPS can tell, and a third shorter in JSON. */
        private const val COORDINATE_DECIMALS = 6
    }
}

private fun Double.rounded(decimals: Int): Double {
    var scale = 1.0
    repeat(decimals) { scale *= 10 }
    return round(this * scale) / scale
}
