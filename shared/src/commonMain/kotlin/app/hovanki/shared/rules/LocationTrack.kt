package app.hovanki.shared.rules

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.LocationSample

/** A fix good enough for rule checks: not mocked and accurate enough. */
fun LocationSample.isUsable(rules: GameRules): Boolean = !isMock && accuracyMeters <= rules.maxUsableAccuracyMeters

/**
 * Recent fixes of one player, used for every GPS-based decision.
 * Out-of-order fixes and physically impossible jumps are dropped; history older than [retentionMillis] is pruned.
 * Not thread-safe: the owner synchronizes access.
 */
class LocationTrack(private val rules: GameRules, private val retentionMillis: Long = 5 * 60_000L) {
    private val fixes = ArrayDeque<LocationSample>()

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

        fixes.addLast(fix)
        latest = fix
        while (fixes.isNotEmpty() && fixes.first().timestampMillis < fix.timestampMillis - retentionMillis) {
            fixes.removeFirst()
        }
        return Result.ACCEPTED
    }

    fun latestUsable(): LocationSample? = fixes.lastOrNull { it.isUsable(rules) }

    /** The last accepted fix taken at or before [atMillis], within the kept history; null when there is none. */
    fun latestAtOrBefore(atMillis: Long): LocationSample? = fixes.lastOrNull { it.timestampMillis <= atMillis }

    /**
     * Accepted fixes of any accuracy within the decision window that ends at [nowMillis]. A view of the track, not a
     * copy (the rules ask for it many times a second): valid until the next [add], so read it, never keep it.
     */
    fun recentFixes(nowMillis: Long): List<LocationSample> {
        val since = nowMillis - rules.decisionWindowSeconds * 1000L
        // The fixes are in time order (add drops the late ones): the window is a run of them, found from the end.
        var end = fixes.size
        while (end > 0 && fixes[end - 1].timestampMillis > nowMillis) end--
        var start = end
        while (start > 0 && fixes[start - 1].timestampMillis >= since) start--
        return fixes.subList(start, end)
    }

    /** Usable fixes within the decision window that ends at [nowMillis]; like [recentFixes], read it, never keep it. */
    fun recentUsableFixes(nowMillis: Long): List<LocationSample> {
        val recent = recentFixes(nowMillis)
        // Mostly every fix is usable: then the window itself, else a list of the usable ones.
        return if (recent.all { it.isUsable(rules) }) recent else recent.filter { it.isUsable(rules) }
    }

    private fun isPlausibleMove(from: LocationSample, to: LocationSample): Boolean {
        val seconds = (to.timestampMillis - from.timestampMillis) / 1000.0
        val slack = from.accuracyMeters + to.accuracyMeters
        return from.point.distanceTo(to.point) - slack <= rules.maxPlausibleSpeedMetersPerSecond * seconds
    }
}
