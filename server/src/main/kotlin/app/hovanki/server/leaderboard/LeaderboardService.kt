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
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * The leaderboard, «Рейтинг» (docs/adr/0020-leaderboard.md): the weekly points of players with an account, computed
 * on every request from the history of finished games with [LeaderboardRules]; nothing of it is stored. Shows
 * nicknames and points only: no game, place or time of anybody else.
 */
@Service
class LeaderboardService(private val repository: LeaderboardRepository, private val clock: Clock) {
    fun leaderboard(user: AuthenticatedUser, scope: LeaderboardScope): LeaderboardResponse {
        val now = clock.instant()
        return when (scope) {
            LeaderboardScope.WORLD -> weekly(user.userId, scope, now, only = null)
            LeaderboardScope.CITY -> city(user.userId, now)
            LeaderboardScope.FRIENDS -> weekly(user.userId, scope, now, only = repository.friends(user.userId))
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
        return weekly(me, LeaderboardScope.CITY, now, only = null, city = city).copy(city = city)
    }

    /**
     * This week's points of everybody ([only] null) or of the caller and [only], of [city]'s players when given; with
     * last week's place to compare.
     */
    private fun weekly(
        me: UserId,
        scope: LeaderboardScope,
        now: Instant,
        only: List<UserId>?,
        city: String? = null,
    ): LeaderboardResponse {
        val week = weekOf(now)
        val lastWeek = Week(week.start.atZone(ZONE).minusWeeks(1).toInstant(), week.start)
        val members = only?.let { it + me }
        val results = repository.results(lastWeek.start, week.end, now, members, city)
        val (thisWeekResults, lastWeekResults) = results.partition { it.finishedAt >= week.start }
        val ranked = LeaderboardRules.rank(scores(thisWeekResults), me)
        val mine = ranked.firstOrNull { it.isMe }
        val mineLastWeek = LeaderboardRules.rank(scores(lastWeekResults), me).firstOrNull { it.isMe }
        return LeaderboardResponse(
            scope = scope,
            weekStartMillis = week.start.toEpochMilli(),
            weekEndMillis = week.end.toEpochMilli(),
            entries = if (only == null) ranked.take(LeaderboardRules.WORLD_TOP) else ranked,
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
