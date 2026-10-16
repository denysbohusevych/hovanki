package app.hovanki.shared.rules

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample

/** A fix good enough for rule checks: not mocked and accurate enough. */
fun LocationSample.isUsable(rules: GameRules): Boolean = !isMock && accuracyMeters <= rules.maxUsableAccuracyMeters

/**
 * Recent fixes of one player, used for every GPS-based decision.
 * Out-of-order fixes and physically impossible jumps are dropped; history older than [retentionMillis] is pruned.
 * Not thread-safe: the owner synchronizes access.
 *
 * Every player of every game has one, so its memory counts (docs/live-v2/plan.md, A3): the fixes of the decision window
 * stay objects, the rules read them many times a second; the older ones, read only for a moment in the past, are packed
 * into primitive arrays, 32 bytes a fix instead of 84, and made objects again when asked for.
 */
class LocationTrack(private val rules: GameRules, private val retentionMillis: Long = 5 * 60_000L) {
    /** The fixes of the decision window that ends at [latest], and every one after them, in time order. */
    private val recent = ArrayDeque<LocationSample>()

    /** The older fixes within [retentionMillis], in time order, all before [recent]'s. */
    private val older = PackedFixes()

    /** [older]'s last usable fix as an object, made once per change of [older]; null while unknown. */
    private var olderUsable: LocationSample? = null
    private var olderUsableKnown = true

    /** Last accepted fix of any accuracy (what we can show on a map). */
    var latest: LocationSample? = null
        private set

    /** Time of the last mocked fix, if any. Mocked fixes are never added to the track. */
    var lastMockAtMillis: Long? = null
        private set

    enum class Result { ACCEPTED, MOCK, OUT_OF_ORDER, IMPLAUSIBLE }

    fun add(fix: LocationSample): Result {
        if (fix.isMock) {
            lastMockAtMillis = fix.timestampMillis
            return Result.MOCK
        }
        val previous = latest
        if (previous != null && fix.timestampMillis <= previous.timestampMillis) return Result.OUT_OF_ORDER
        val lastUsable = latestUsable()
        if (lastUsable != null && fix.isUsable(rules) && !isPlausibleMove(lastUsable, fix)) return Result.IMPLAUSIBLE

        recent.addLast(fix)
        latest = fix
        val keptSince = fix.timestampMillis - retentionMillis
        val windowSince = fix.timestampMillis - rules.decisionWindowSeconds * 1000L
        while (recent.isNotEmpty() && recent.first().timestampMillis < windowSince) {
            val old = recent.removeFirst()
            if (old.timestampMillis >= keptSince) older.addLast(old)
        }
        older.dropBefore(keptSince)
        olderUsableKnown = false
        while (recent.isNotEmpty() && recent.first().timestampMillis < keptSince) recent.removeFirst()
        return Result.ACCEPTED
    }

    fun latestUsable(): LocationSample? = recent.lastOrNull { it.isUsable(rules) } ?: latestOlderUsable()

    /** The last accepted fix taken at or before [atMillis], within the kept history; null when there is none. */
    fun latestAtOrBefore(atMillis: Long): LocationSample? =
        recent.lastOrNull { it.timestampMillis <= atMillis } ?: older.lastAtOrBefore(atMillis)?.let(older::sample)

    /**
     * Accepted fixes of any accuracy within the decision window that ends at [nowMillis]. A view of the track, not a
     * copy (the rules ask for it many times a second): valid until the next [add], so read it, never keep it.
     */
    fun recentFixes(nowMillis: Long): List<LocationSample> {
        val since = nowMillis - rules.decisionWindowSeconds * 1000L
        // The fixes are in time order (add drops the late ones): the window is a run of them, found from the end.
        var end = recent.size
        while (end > 0 && recent[end - 1].timestampMillis > nowMillis) end--
        var start = end
        while (start > 0 && recent[start - 1].timestampMillis >= since) start--
        if (start > 0 || older.size == 0 || older.timestampAt(older.size - 1) < since) return recent.subList(start, end)
        // A window that ends before the latest fix's (a phone whose clock ran ahead) can reach the packed ones.
        var first = older.size
        while (first > 0 && older.timestampAt(first - 1) >= since) first--
        var last = first
        while (last < older.size && older.timestampAt(last) <= nowMillis) last++
        return buildList(last - first + end) {
            for (index in first until last) add(older.sample(index))
            addAll(recent.subList(0, end))
        }
    }

    /** Usable fixes within the decision window that ends at [nowMillis]; like [recentFixes], read it, never keep it. */
    fun recentUsableFixes(nowMillis: Long): List<LocationSample> {
        val recent = recentFixes(nowMillis)
        // Mostly every fix is usable: then the window itself, else a list of the usable ones.
        return if (recent.all { it.isUsable(rules) }) recent else recent.filter { it.isUsable(rules) }
    }

    /** Mocked fixes never reach [older]: a packed fix is usable by its accuracy alone. */
    private fun latestOlderUsable(): LocationSample? {
        if (!olderUsableKnown) {
            var index = older.size - 1
            while (index >= 0 && older.accuracyAt(index) > rules.maxUsableAccuracyMeters) index--
            olderUsable = if (index >= 0) older.sample(index) else null
            olderUsableKnown = true
        }
        return olderUsable
    }

    private fun isPlausibleMove(from: LocationSample, to: LocationSample): Boolean {
        val seconds = (to.timestampMillis - from.timestampMillis) / 1000.0
        val slack = from.accuracyMeters + to.accuracyMeters
        return from.point.distanceTo(to.point) - slack <= rules.maxPlausibleSpeedMetersPerSecond * seconds
    }
}

/**
 * Fixes in time order as a ring of primitive arrays: the point, the accuracy and the time of each, exactly as they came
 * (the mock flag is not kept: mocked fixes never reach a track). Grows by doubling and halves when a quarter is used.
 */
internal class PackedFixes {
    private var lat = DoubleArray(0)
    private var lon = DoubleArray(0)
    private var accuracy = DoubleArray(0)
    private var time = LongArray(0)
    private var head = 0

    var size = 0
        private set

    fun addLast(fix: LocationSample) {
        if (size == time.size) resize(maxOf(MIN_CAPACITY, time.size * 2))
        val at = slot(size)
        lat[at] = fix.point.lat
        lon[at] = fix.point.lon
        accuracy[at] = fix.accuracyMeters
        time[at] = fix.timestampMillis
        size++
    }

    /** Drops the fixes taken before [millis]. */
    fun dropBefore(millis: Long) {
        while (size > 0 && time[head] < millis) {
            head = (head + 1) % time.size
            size--
        }
        if (size == 0) head = 0
        if (time.size > MIN_CAPACITY && size <= time.size / 4) resize(maxOf(MIN_CAPACITY, time.size / 2))
    }

    fun timestampAt(index: Int): Long = time[slot(index)]

    fun accuracyAt(index: Int): Double = accuracy[slot(index)]

    /** The index of the last fix taken at or before [millis], or null. */
    fun lastAtOrBefore(millis: Long): Int? {
        var index = size - 1
        while (index >= 0 && timestampAt(index) > millis) index--
        return index.takeIf { it >= 0 }
    }

    /** The fix at [index] as the object it came as. */
    fun sample(index: Int): LocationSample {
        val at = slot(index)
        return LocationSample(GeoPoint(lat[at], lon[at]), accuracy[at], time[at])
    }

    private fun slot(index: Int): Int = (head + index) % time.size

    private fun resize(capacity: Int) {
        val newLat = DoubleArray(capacity)
        val newLon = DoubleArray(capacity)
        val newAccuracy = DoubleArray(capacity)
        val newTime = LongArray(capacity)
        for (index in 0 until size) {
            val at = slot(index)
            newLat[index] = lat[at]
            newLon[index] = lon[at]
            newAccuracy[index] = accuracy[at]
            newTime[index] = time[at]
        }
        lat = newLat
        lon = newLon
        accuracy = newAccuracy
        time = newTime
        head = 0
    }

    private companion object {
        const val MIN_CAPACITY = 16
    }
}
