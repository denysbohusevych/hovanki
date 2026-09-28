package app.hovanki.server.game

import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.TrackPoint

/**
 * One player's whole way through the round, for the replay after it ([Game.tracks]): unlike the rules' `LocationTrack`
 * (the last minutes), it keeps the round from the start, but thinned: at most one point per [intervalMillis], and when
 * [maxPoints] are reached every second point goes (half the detail, the same length). Lives in the game's memory and
 * is deleted with it. Not thread-safe: the game synchronizes access.
 */
class ReplayTrack(
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val maxPoints: Int = DEFAULT_MAX_POINTS,
) {
    private val points = ArrayList<TrackPoint>()
    private var interval = intervalMillis

    val size: Int get() = points.size

    /** [fix] was accepted by the rules' track: kept when it is far enough in time from the last kept one. */
    fun add(fix: LocationSample) {
        val last = points.lastOrNull()
        if (last != null && fix.timestampMillis - last.atMillis < interval) return
        points.add(TrackPoint(fix.point.lat, fix.point.lon, fix.timestampMillis))
        if (points.size >= maxPoints) {
            // Keep the first point and every second one after it; from now on, points are twice as far apart.
            val thinned = points.filterIndexed { index, _ -> index % 2 == 0 }
            points.clear()
            points.addAll(thinned)
            interval *= 2
        }
    }

    fun points(): List<TrackPoint> = points.toList()

    companion object {
        const val DEFAULT_INTERVAL_MILLIS = 5_000L

        /** 30 players × 1000 points stay around a megabyte of JSON; 5 s apart, they cover almost an hour and a half. */
        const val DEFAULT_MAX_POINTS = 1_000
    }
}
