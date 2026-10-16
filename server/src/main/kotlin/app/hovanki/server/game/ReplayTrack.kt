package app.hovanki.server.game

import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.TrackPoint
import kotlin.math.roundToLong

/**
 * One player's whole way through the round, for the replay after it ([Game.tracks]): unlike the rules' `LocationTrack`
 * (the last minutes), it keeps the round from the start, but thinned: at most one point per [intervalMillis], and when
 * [maxPoints] are reached every second point goes (half the detail, the same length). Lives in the game's memory and
 * is deleted with it. Not thread-safe: the game synchronizes access.
 *
 * Every player of every game has one for the whole round, so the points are packed (docs/live-v2/plan.md, A1): each is
 * its difference from the one before, latitude and longitude in 1e-7 degree (about a centimetre) and the time in
 * milliseconds, as variable-length numbers in one byte array: about 6 bytes a point instead of 44 for an object.
 */
class ReplayTrack(
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val maxPoints: Int = DEFAULT_MAX_POINTS,
) {
    private var bytes = ByteArray(0)
    private var length = 0
    private var interval = intervalMillis

    // The last point, which the next one is written against.
    private var lastLat = 0L
    private var lastLon = 0L
    private var lastAt = 0L

    var size: Int = 0
        private set

    /** The bytes the points take, for the tests. */
    internal val packedBytes: Int get() = length

    /** [fix] was accepted by the rules' track: kept when it is far enough in time from the last kept one. */
    fun add(fix: LocationSample) {
        if (size > 0 && fix.timestampMillis - lastAt < interval) return
        append(e7(fix.point.lat), e7(fix.point.lon), fix.timestampMillis)
        if (size >= maxPoints) thin()
    }

    fun points(): List<TrackPoint> {
        val points = ArrayList<TrackPoint>(size)
        forEachPoint { lat, lon, at ->
            points.add(TrackPoint(lat / E7, lon / E7, at))
            true
        }
        return points
    }

    /**
     * The way as it was at [atMillis], for a spectator who sees the round late: the last point at or before it and the
     * points of the [trailMillis] before it. Only those become objects.
     */
    fun at(atMillis: Long, trailMillis: Long): Seen {
        var location: TrackPoint? = null
        val trail = ArrayList<TrackPoint>()
        var last: Long? = null
        var lastLatitude = 0L
        var lastLongitude = 0L
        forEachPoint { lat, lon, at ->
            if (at > atMillis) return@forEachPoint false
            if (at > atMillis - trailMillis) trail.add(TrackPoint(lat / E7, lon / E7, at))
            last = at
            lastLatitude = lat
            lastLongitude = lon
            true
        }
        last?.let { location = trail.lastOrNull() ?: TrackPoint(lastLatitude / E7, lastLongitude / E7, it) }
        return Seen(location, trail)
    }

    /** What [at] answers: the last point (null before the first) and the trail behind it, both in time order. */
    data class Seen(val location: TrackPoint?, val trail: List<TrackPoint>)

    /** Keeps the first point and every second one after it; from now on, points are twice as far apart. */
    private fun thin() {
        val lats = LongArray(size)
        val lons = LongArray(size)
        val times = LongArray(size)
        var index = 0
        forEachPoint { lat, lon, at ->
            lats[index] = lat
            lons[index] = lon
            times[index] = at
            index++
            true
        }
        val count = size
        length = 0
        size = 0
        lastLat = 0
        lastLon = 0
        lastAt = 0
        for (kept in 0 until count step 2) append(lats[kept], lons[kept], times[kept])
        bytes = bytes.copyOf(length)
        interval *= 2
    }

    private fun append(lat: Long, lon: Long, at: Long) {
        ensure(MAX_POINT_BYTES)
        writeSigned(lat - lastLat)
        writeSigned(lon - lastLon)
        writeSigned(at - lastAt)
        lastLat = lat
        lastLon = lon
        lastAt = at
        size++
    }

    /** Every point in time order until [action] answers false: latitude and longitude in 1e-7 degree, the time. */
    private inline fun forEachPoint(action: (lat: Long, lon: Long, at: Long) -> Boolean) {
        val reader = Reader(bytes)
        var lat = 0L
        var lon = 0L
        var at = 0L
        repeat(size) {
            lat += reader.next()
            lon += reader.next()
            at += reader.next()
            if (!action(lat, lon, at)) return
        }
    }

    private class Reader(private val bytes: ByteArray) {
        private var position = 0

        /** The next number [writeSigned] wrote. */
        fun next(): Long {
            var shift = 0
            var raw = 0L
            while (true) {
                val byte = bytes[position++].toInt()
                raw = raw or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
            }
            return (raw ushr 1) xor -(raw and 1)
        }
    }

    /** Zigzag, then seven bits a byte, the lowest first. */
    private fun writeSigned(value: Long) {
        var raw = (value shl 1) xor (value shr 63)
        while (raw and 0x7FL.inv() != 0L) {
            bytes[length++] = ((raw and 0x7F) or 0x80).toByte()
            raw = raw ushr 7
        }
        bytes[length++] = raw.toByte()
    }

    private fun ensure(extra: Int) {
        if (length + extra <= bytes.size) return
        bytes = bytes.copyOf(maxOf(length + extra, bytes.size + bytes.size / 2, MIN_BYTES))
    }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS = 5_000L

        /** 30 players × 1000 points stay around a megabyte of JSON; 5 s apart, they cover almost an hour and a half. */
        const val DEFAULT_MAX_POINTS = 1_000

        private const val E7 = 1e7
        private const val MIN_BYTES = 64

        /** Three numbers of at most ten bytes each. */
        private const val MAX_POINT_BYTES = 30

        private fun e7(degrees: Double): Long = (degrees * E7).roundToLong()
    }
}
