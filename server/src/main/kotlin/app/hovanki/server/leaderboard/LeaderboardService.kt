package app.hovanki.server.leaderboard

import app.hovanki.server.api.AuthenticatedUser
import app.hovanki.shared.protocol.LeaderboardEntry
import app.hovanki.shared.protocol.LeaderboardResponse
import app.hovanki.shared.protocol.LeaderboardScope
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.rules.LeaderboardRules
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.locks.ReentrantLock

/**
 * The leaderboard, «Рейтинг» (docs/adr/0020-leaderboard.md): the weekly points of players with an account, computed
 * from the history of finished games with [LeaderboardRules]; nothing of it is stored. The world's and the cities'
 * order is computed for everybody at once at most every [LeaderboardProperties.freshFor] and kept in memory
 * ([Standings]); the friends'
 * and the last game's are read on every request. Shows nicknames and points only: no game, place or time of anybody
 * else.
 */
@Service
class LeaderboardService(
    private val repository: LeaderboardRepository,
    private val clock: Clock,
    private val properties: LeaderboardProperties = LeaderboardProperties(),
) {
    @Volatile
    private var standings: Standings? = null
    private val refreshing = ReentrantLock()

    fun leaderboard(user: AuthenticatedUser, scope: LeaderboardScope): LeaderboardResponse {
        val now = clock.instant()
        return when (scope) {
            LeaderboardScope.WORLD -> standing(user.userId, scope, now, city = null)
            LeaderboardScope.CITY -> city(user.userId, now)
            LeaderboardScope.FRIENDS -> friends(user.userId, now)
            LeaderboardScope.LAST_GAME -> lastGame(user.userId, now)
        }
    }

    /**
     * Everybody of the caller's city by this week's points, like the world (docs/adr/0022-city-leaderboard.md); last
     * week counted by today's cities too. Empty while the caller has no city.
     */
    private fun city(me: UserId, now: Instant): LeaderboardResponse {
        val city = repository.cityOf(me)
            ?: return weekOf(now).let { LeaderboardResponse(LeaderboardScope.CITY, it.startMillis, it.endMillis) }
        return standing(me, LeaderboardScope.CITY, now, city).copy(city = city)
    }

    /** The top of the world's ([city] null) or [city]'s order this week and the caller's line, from [standings]. */
    private fun standing(me: UserId, scope: LeaderboardScope, now: Instant, city: String?): LeaderboardResponse {
        val standings = standings(now)
        val board = standings.thisWeek.board(city)
        val mine = board.lineOf(me)
        val mineLastWeek = standings.lastWeek.board(city).lineOf(me)
        return LeaderboardResponse(
            scope = scope,
            weekStartMillis = standings.week.startMillis,
            weekEndMillis = standings.week.endMillis,
            entries = board.lines.take(LeaderboardRules.WORLD_TOP).map { if (it.userId == me) mine!! else it },
            me = mine,
            nextAbove = board.lines.above(mine),
            rankChange = if (mine != null && mineLastWeek != null) mineLastWeek.rank - mine.rank else null,
        )
    }

    /** This week's points of the caller and their friends, with last week's place to compare. */
    private fun friends(me: UserId, now: Instant): LeaderboardResponse {
        val week = weekOf(now)
        val results = repository.results(week.lastWeekStart, week.end, now, repository.friends(me) + me)
        val (thisWeekResults, lastWeekResults) = results.partition { it.finishedAt >= week.start }
        val ranked = LeaderboardRules.rank(scores(thisWeekResults), me)
        val mine = ranked.firstOrNull { it.isMe }
        val mineLastWeek = LeaderboardRules.rank(scores(lastWeekResults), me).firstOrNull { it.isMe }
        return LeaderboardResponse(
            scope = LeaderboardScope.FRIENDS,
            weekStartMillis = week.startMillis,
            weekEndMillis = week.endMillis,
            entries = ranked,
            me = mine,
            nextAbove = ranked.above(mine),
            rankChange = if (mine != null && mineLastWeek != null) mineLastWeek.rank - mine.rank else null,
        )
    }

    /** The caller's most recent game: its account players by that game's points, in the week it ended. */
    private fun lastGame(me: UserId, now: Instant): LeaderboardResponse {
        val (gameId, finishedAt) = repository.lastGame(me)
            ?: return weekOf(now).let { LeaderboardResponse(LeaderboardScope.LAST_GAME, it.startMillis, it.endMillis) }
        val week = weekOf(finishedAt)
        val ranked = LeaderboardRules.rank(scores(repository.gameResults(gameId, me, now)), me)
        val mine = ranked.firstOrNull { it.isMe }
        return LeaderboardResponse(
            scope = LeaderboardScope.LAST_GAME,
            weekStartMillis = week.startMillis,
            weekEndMillis = week.endMillis,
            entries = ranked,
            me = mine,
            nextAbove = ranked.above(mine),
        )
    }

    /**
     * This week's [Standings], computed again once they are [LeaderboardProperties.freshFor] old. One request computes them; the others
     * meanwhile get the week's previous ones and wait only when there are none yet (a new week, a restart).
     */
    private fun standings(now: Instant): Standings {
        val week = weekOf(now)
        standings?.takeIf { it.isFresh(week, now, properties.freshFor) }?.let { return it }
        val previous = standings?.takeIf { it.week == week }
        if (previous != null && !refreshing.tryLock()) return previous
        if (previous == null) refreshing.lock()
        try {
            standings?.takeIf { it.isFresh(week, now, properties.freshFor) }?.let { return it }
            return computeStandings(week, now).also { standings = it }
        } finally {
            refreshing.unlock()
        }
    }

    private fun computeStandings(week: Week, now: Instant): Standings {
        val thisWeek = HashMap<UserId, Tally>()
        val lastWeek = HashMap<UserId, Tally>()
        repository.eachResult(week.lastWeekStart, week.end, now) { result, city ->
            val tallies = if (result.finishedAt >= week.start) thisWeek else lastWeek
            tallies.getOrPut(result.userId) { Tally(result.nickname, city) }.add(result)
        }
        return Standings(week, now, WeekStandings.of(thisWeek), WeekStandings.of(lastWeek))
    }

    private fun scores(results: List<LeaderboardResult>): List<LeaderboardRules.Score> =
        results.groupBy { it.userId }.map { (userId, games) ->
            LeaderboardRules.Score(
                userId = userId,
                nickname = games.first().nickname,
                points = games.sumOf { it.points },
                lastGameAtMillis = games.maxOf { it.finishedAt }.toEpochMilli(),
            )
        }

    private fun List<LeaderboardEntry>.above(entry: LeaderboardEntry?): LeaderboardEntry? =
        entry?.let { getOrNull(it.rank - 2) }

    /** [start, end): Monday 00:00 to the next Monday 00:00 in [LeaderboardRules.WEEK_TIME_ZONE]. */
    data class Week(val start: Instant, val end: Instant) {
        val startMillis get() = start.toEpochMilli()
        val endMillis get() = end.toEpochMilli()

        /** The Monday before [start]: last week's results count for the change of place. */
        val lastWeekStart: Instant get() = start.atZone(ZONE).minusWeeks(1).toInstant()
    }

    /** One player's games of a week so far: their points and when their last game ended. */
    private class Tally(val nickname: String, val city: String?) {
        var points = 0
        var lastGameAtMillis = Long.MIN_VALUE

        fun add(result: LeaderboardResult) {
            points += result.points
            lastGameAtMillis = maxOf(lastGameAtMillis, result.finishedAt.toEpochMilli())
        }
    }

    /** One group's order of a week: [lines] best first, nobody marked as the caller. */
    private class Board(val lines: List<LeaderboardEntry>) {
        private val index = HashMap<UserId, Int>(lines.size * 2).apply {
            lines.forEachIndexed { i, line -> put(line.userId, i) }
        }

        /** [userId]'s line marked as the caller's; null: no game in this group this week. */
        fun lineOf(userId: UserId): LeaderboardEntry? = index[userId]?.let { lines[it].copy(isMe = true) }

        companion object {
            val EMPTY = Board(emptyList())

            fun of(tallies: Collection<Map.Entry<UserId, Tally>>) = Board(
                LeaderboardRules.rank(
                    tallies.map { (userId, tally) ->
                        LeaderboardRules.Score(userId, tally.nickname, tally.points, tally.lastGameAtMillis)
                    },
                    me = null,
                ),
            )
        }
    }

    /** A week's order of the world and of every city, by the players' cities today. */
    private class WeekStandings(private val world: Board, private val cities: Map<String, Board>) {
        fun board(city: String?): Board = if (city == null) world else cities[city] ?: Board.EMPTY

        companion object {
            fun of(tallies: Map<UserId, Tally>) = WeekStandings(
                world = Board.of(tallies.entries),
                cities = tallies.entries.filter { it.value.city != null }
                    .groupBy { it.value.city!! }
                    .mapValues { (_, inCity) -> Board.of(inCity) },
            )
        }
    }

    /** The world's and the cities' order of [week] and the week before, as of [computedAt]. */
    private class Standings(
        val week: Week,
        val computedAt: Instant,
        val thisWeek: WeekStandings,
        val lastWeek: WeekStandings,
    ) {
        fun isFresh(week: Week, now: Instant, freshFor: Duration) =
            week == this.week && Duration.between(computedAt, now) < freshFor
    }

    companion object {
        private val ZONE: ZoneId = ZoneId.of(LeaderboardRules.WEEK_TIME_ZONE)

        /** The week [instant] falls in. */
        fun weekOf(instant: Instant): Week {
            val start = instant.atZone(ZONE).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZONE)
            return Week(start.toInstant(), start.plusWeeks(1).toInstant())
        }
    }
}
