package app.hovanki.client.session

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.TrackPoint
import app.hovanki.shared.protocol.TracksResponse

/*
 * The results screen (docs/design.md, «Итоги»): when each hider was out, badges, and the replay of the round. Computed
 * by the app from the final snapshot and the tracks: a hint for the interface, not a rule (the server decided who was
 * caught and when).
 */

/** A badge on the results screen. */
enum class AwardKind {
    /** The seeker who found somebody first; [Award.value]: when, in millis into the search. */
    FIRST_CATCH,

    /** The seeker with the most catches, at least [HUNTER_MIN_CATCHES]; [Award.value]: how many. */
    HUNTER,

    /** A hider nobody found; [Award.value]: how long the search lasted, millis. */
    SURVIVOR,

    /** Everybody was found: the one found last; [Award.value]: when, in millis into the search. */
    LAST_STANDING,

    /** The longest way through the round, from the tracks; [Award.value]: meters. */
    MARATHON,

    /** The most sparks (docs/adr/0013-quests-sparks-and-sensors.md), at least one; [Award.value]: how many. */
    SPARKS,
}

data class Award(val kind: AwardKind, val playerId: PlayerId, val value: Long)

/** How long into the search [atMillis] was (the zone starts with the search); null before it started. */
fun GameSnapshot.searchMillisAt(atMillis: Long): Long? = zoneStartedAtMillis?.let { (atMillis - it).coerceAtLeast(0) }

/** How many hiders [seekerId] caught. */
fun GameSnapshot.catchesBy(seekerId: PlayerId): Int = players.count { it.caughtBy == seekerId }

/**
 * The badges of a finished game, in the order the results show them; ties give nobody the badge. [tracks]: the replay
 * once loaded; badges that need it come with it.
 */
fun GameSnapshot.awards(tracks: TracksResponse? = null): List<Award> {
    if (phase != GamePhase.FINISHED) return emptyList()
    val awards = mutableListOf<Award>()
    val hiders = players.filter { it.role == Role.HIDER }
    val caught = hiders.filter { it.status == PlayerStatus.CAUGHT && it.outAtMillis != null && it.caughtBy != null }

    caught.minByOrNull { it.outAtMillis!! }?.let { first ->
        searchMillisAt(first.outAtMillis!!)?.let { awards += Award(AwardKind.FIRST_CATCH, first.caughtBy!!, it) }
    }
    val counts = caught.groupingBy { it.caughtBy!! }.eachCount()
    counts.entries.uniqueMaxBy { it.value }?.takeIf { it.value >= HUNTER_MIN_CATCHES }?.let { (seeker, count) ->
        awards += Award(AwardKind.HUNTER, seeker, count.toLong())
    }

    val survivors = hiders.filter { it.status == PlayerStatus.ACTIVE }
    val searchMillis = finishedAtMillis?.let(::searchMillisAt)
    if (searchMillis != null) survivors.forEach { awards += Award(AwardKind.SURVIVOR, it.id, searchMillis) }
    if (survivors.isEmpty()) {
        hiders.filter { it.outAtMillis != null }.uniqueMaxBy { it.outAtMillis!! }?.let { last ->
            searchMillisAt(last.outAtMillis!!)?.let { awards += Award(AwardKind.LAST_STANDING, last.id, it) }
        }
    }

    if (tracks != null) {
        val lengths = tracks.tracks.associate { it.playerId to it.points.lengthMeters() }
        lengths.entries.uniqueMaxBy { it.value }?.takeIf { it.value >= MARATHON_MIN_METERS }?.let { (player, meters) ->
            awards += Award(AwardKind.MARATHON, player, meters.toLong())
        }
    }
    players.filter { (it.sparks ?: 0) > 0 }.uniqueMaxBy { it.sparks ?: 0 }?.let { richest ->
        awards += Award(AwardKind.SPARKS, richest.id, (richest.sparks ?: 0).toLong())
    }
    return awards
}

/** How far the points of a track are apart, altogether. */
fun List<TrackPoint>.lengthMeters(): Double = zipWithNext { a, b -> a.point.distanceTo(b.point) }.sum()

/** The one element with the largest [selector], or null when there is none or several share it. */
private fun <T, R : Comparable<R>> Iterable<T>.uniqueMaxBy(selector: (T) -> R): T? {
    val max = maxOfOrNull(selector) ?: return null
    return filter { selector(it) == max }.singleOrNull()
}

const val HUNTER_MIN_CATCHES = 2

/** Shorter than this, nobody «ran a marathon»: the phones mostly lay still. */
const val MARATHON_MIN_METERS = 200.0

/** One player's line in the replay. */
data class ReplayLine(val player: PlayerView, val points: List<TrackPoint>) {
    /** The way until [atMillis], ending where the player was then. */
    fun pathUntil(atMillis: Long): List<GeoPoint> {
        val before = points.takeWhile { it.atMillis <= atMillis }.map { it.point }
        val now = positionAt(atMillis) ?: return before
        return if (before.lastOrNull() == now) before else before + now
    }

    /**
     * Where the player was at [atMillis]: between two points, in a straight line; after the last one, there. Null
     * before the first point (no fix yet).
     */
    fun positionAt(atMillis: Long): GeoPoint? {
        val first = points.firstOrNull() ?: return null
        if (atMillis < first.atMillis) return null
        val next = points.indexOfFirst { it.atMillis > atMillis }
        if (next == -1) return points.last().point
        val a = points[next - 1]
        val b = points[next]
        val fraction = (atMillis - a.atMillis).toDouble() / (b.atMillis - a.atMillis)
        return GeoPoint(a.lat + (b.lat - a.lat) * fraction, a.lon + (b.lon - a.lon) * fraction)
    }
}

/**
 * The replay of a finished round: [lines] of the players who have a track, from the start of hiding ([startMillis])
 * to the end ([endMillis]). Null until the game is over and its tracks are loaded, or when nobody has a track.
 */
class Replay(val lines: List<ReplayLine>, val startMillis: Long, val endMillis: Long) {
    val durationMillis: Long get() = endMillis - startMillis

    companion object {
        fun of(snapshot: GameSnapshot, tracks: TracksResponse?): Replay? {
            if (snapshot.phase != GamePhase.FINISHED || tracks == null) return null
            val players = snapshot.players.associateBy { it.id }
            val lines = tracks.tracks.mapNotNull { track ->
                players[track.playerId]?.takeIf { track.points.isNotEmpty() }?.let { ReplayLine(it, track.points) }
            }
            if (lines.isEmpty()) return null
            val firstPoint = lines.minOf { it.points.first().atMillis }
            val lastPoint = lines.maxOf { it.points.last().atMillis }
            val hidingStart = snapshot.zoneStartedAtMillis?.let { it - snapshot.settings.hidingSeconds * 1000L }
            val start = minOf(hidingStart ?: firstPoint, firstPoint)
            val end = maxOf(snapshot.finishedAtMillis ?: lastPoint, lastPoint)
            return Replay(lines, start, end)
        }
    }
}
