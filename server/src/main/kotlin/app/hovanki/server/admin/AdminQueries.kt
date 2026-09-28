package app.hovanki.server.admin

import app.hovanki.server.db.getInstantOrNull
import app.hovanki.server.db.toTimestamptz
import app.hovanki.shared.protocol.UserId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

/** Counts about one account for its card in the admin (docs/adr/0008-admin.md): numbers only. */
data class AccountCounts(
    val games: Int,
    val lastGameAt: Instant?,
    val devices: Int,
    val lastSeenAt: Instant?,
    val friends: Int,
    val blockedBy: Int,
)

/** Aggregates over the history for the dashboard (docs/metrics.md): no single player behind any of them. */
data class HistoryNumbers(
    val users: Int,
    val usersNew7d: Int,
    val usersNew30d: Int,
    val activePlayers1d: Int,
    val activePlayers7d: Int,
    val games1d: Int,
    val games7d: Int,
    val games30d: Int,
    val avgPlayers30d: Double?,
    val avgSearchMinutes30d: Double?,
    val seekersWinRate30d: Double?,
    val disputes7d: Int,
)

/** The admin's read-only queries across tables. */
@Repository
class AdminQueries(private val jdbc: JdbcClient) {
    fun accountCounts(userId: UserId): AccountCounts = jdbc.sql(
        """
        SELECT
            (SELECT count(*) FROM game_results WHERE user_id = :u) AS games,
            (SELECT max(finished_at) FROM game_results WHERE user_id = :u) AS last_game_at,
            (SELECT count(*) FROM account_sessions WHERE user_id = :u) AS devices,
            (SELECT max(last_used_at) FROM account_sessions WHERE user_id = :u) AS last_seen_at,
            (SELECT count(*) FROM friendships WHERE user_id = :u) AS friends,
            (SELECT count(*) FROM blocks WHERE blocked_id = :u) AS blocked_by
        """.trimIndent(),
    )
        .param("u", userId.value)
        .query { rs, _ ->
            AccountCounts(
                games = rs.getInt("games"),
                lastGameAt = rs.getInstantOrNull("last_game_at"),
                devices = rs.getInt("devices"),
                lastSeenAt = rs.getInstantOrNull("last_seen_at"),
                friends = rs.getInt("friends"),
                blockedBy = rs.getInt("blocked_by"),
            )
        }
        .single()

    /**
     * The dashboard's numbers at [now]. Averages need at least [MIN_GROUP] games, else null: a small group gives away
     * the people in it (docs/metrics.md).
     */
    fun historyNumbers(now: Instant): HistoryNumbers = jdbc.sql(
        """
        WITH recent AS (
            SELECT * FROM played_games WHERE finished_at >= :d30
        )
        SELECT
            (SELECT count(*) FROM users) AS users,
            (SELECT count(*) FROM users WHERE created_at >= :d7) AS users_new_7d,
            (SELECT count(*) FROM users WHERE created_at >= :d30) AS users_new_30d,
            (SELECT count(DISTINCT user_id) FROM game_results WHERE finished_at >= :d1) AS active_1d,
            (SELECT count(DISTINCT user_id) FROM game_results WHERE finished_at >= :d7) AS active_7d,
            (SELECT count(*) FROM recent WHERE finished_at >= :d1) AS games_1d,
            (SELECT count(*) FROM recent WHERE finished_at >= :d7) AS games_7d,
            (SELECT count(*) FROM recent) AS games_30d,
            (SELECT avg(players) FROM recent) AS avg_players_30d,
            (SELECT avg(extract(EPOCH FROM finished_at - zone_started_at) / 60) FROM recent
             WHERE zone_started_at IS NOT NULL) AS avg_search_minutes_30d,
            (SELECT avg((hiders_caught + hiders_eliminated >= players - seekers)::int) FROM recent
             WHERE players > seekers) AS seekers_win_rate_30d,
            (SELECT coalesce(sum(disputes), 0) FROM recent WHERE finished_at >= :d7) AS disputes_7d
        """.trimIndent(),
    )
        .param("d1", now.minusSeconds(DAY).toTimestamptz())
        .param("d7", now.minusSeconds(7 * DAY).toTimestamptz())
        .param("d30", now.minusSeconds(30 * DAY).toTimestamptz())
        .query { rs, _ ->
            val games30d = rs.getInt("games_30d")
            fun average(column: String): Double? =
                rs.getObject(column)?.let { (it as Number).toDouble() }?.takeIf { games30d >= MIN_GROUP }
            HistoryNumbers(
                users = rs.getInt("users"),
                usersNew7d = rs.getInt("users_new_7d"),
                usersNew30d = rs.getInt("users_new_30d"),
                activePlayers1d = rs.getInt("active_1d"),
                activePlayers7d = rs.getInt("active_7d"),
                games1d = rs.getInt("games_1d"),
                games7d = rs.getInt("games_7d"),
                games30d = games30d,
                avgPlayers30d = average("avg_players_30d"),
                avgSearchMinutes30d = average("avg_search_minutes_30d"),
                seekersWinRate30d = average("seekers_win_rate_30d"),
                disputes7d = rs.getInt("disputes_7d"),
            )
        }
        .single()

    private companion object {
        const val DAY = 24 * 60 * 60L

        /** docs/metrics.md: no aggregate over fewer than 5. */
        const val MIN_GROUP = 5
    }
}
