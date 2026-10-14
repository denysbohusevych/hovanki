package app.hovanki.server.leaderboard

import app.hovanki.server.db.getInstant
import app.hovanki.server.db.toTimestamptz
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.rules.LeaderboardRules
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import javax.sql.DataSource

/** One finished game of one account, as the leaderboard counts it: a row of `game_results` and the nickname. */
data class LeaderboardResult(
    val userId: UserId,
    val nickname: String,
    val finishedAt: Instant,
    val role: Role,
    val status: PlayerStatus,
    val won: Boolean,
    val catches: Int,
    val survivedSeconds: Int?,
) {
    /** What this game is worth ([LeaderboardRules.points]). */
    val points: Int get() = LeaderboardRules.points(role, status, won, catches, survivedSeconds)
}

/**
 * Reads `game_results` for the leaderboard (docs/adr/0020-leaderboard.md); writes nothing. Accounts banned at `now`
 * are left out everywhere; deleted ones have no rows left.
 */
@Repository
class LeaderboardRepository(
    private val jdbc: JdbcClient,
    dataSource: DataSource,
    transactionManager: PlatformTransactionManager,
) {
    /** PostgreSQL hands a query's rows over in batches only with a fetch size, inside a transaction. */
    private val streaming = JdbcClient.create(JdbcTemplate(dataSource).apply { fetchSize = STREAM_BATCH })
    private val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    /** Every result of [only] that ended in [from, to). */
    fun results(from: Instant, to: Instant, now: Instant, only: Collection<UserId>): List<LeaderboardResult> {
        if (only.isEmpty()) return emptyList()
        return jdbc.sql(
            """
            SELECT r.user_id, u.nickname, r.finished_at, r.role, r.status, r.won, r.catches, r.survived_seconds
            FROM game_results r JOIN users u ON u.id = r.user_id
            WHERE r.finished_at >= :from AND r.finished_at < :to AND r.user_id IN (:ids) AND $NOT_BANNED
            """.trimIndent(),
        )
            .param("from", from.toTimestamptz())
            .param("to", to.toTimestamptz())
            .param("now", now.toTimestamptz())
            .param("ids", only.map { it.value }.distinct())
            .query(resultMapper)
            .list()
    }

    /**
     * Everybody's results that ended in [from, to), each handed to [each] with its player's city today (null: none) as
     * the rows arrive in batches: the world's weeks never sit in memory as rows.
     */
    fun eachResult(from: Instant, to: Instant, now: Instant, each: (LeaderboardResult, String?) -> Unit) {
        readOnly.executeWithoutResult {
            streaming.sql(
                """
                SELECT r.user_id, u.nickname, u.city, r.finished_at, r.role, r.status, r.won, r.catches,
                       r.survived_seconds
                FROM game_results r JOIN users u ON u.id = r.user_id
                WHERE r.finished_at >= :from AND r.finished_at < :to AND $NOT_BANNED
                """.trimIndent(),
            )
                .param("from", from.toTimestamptz())
                .param("to", to.toTimestamptz())
                .param("now", now.toTimestamptz())
                .query(RowCallbackHandler { rs -> each(resultMapper.mapRow(rs, rs.row)!!, rs.getString("city")) })
        }
    }

    /** The results of [gameId], without accounts banned at [now] or blocked by or blocking [viewer]. */
    fun gameResults(gameId: GameId, viewer: UserId, now: Instant): List<LeaderboardResult> = jdbc.sql(
        """
        SELECT r.user_id, u.nickname, r.finished_at, r.role, r.status, r.won, r.catches, r.survived_seconds
        FROM game_results r JOIN users u ON u.id = r.user_id
        WHERE r.game_id = :gameId AND $NOT_BANNED
          AND NOT EXISTS (
              SELECT 1 FROM blocks b
              WHERE (b.blocker_id = :viewer AND b.blocked_id = r.user_id)
                 OR (b.blocker_id = r.user_id AND b.blocked_id = :viewer)
          )
        """.trimIndent(),
    )
        .param("gameId", gameId.value)
        .param("viewer", viewer.value)
        .param("now", now.toTimestamptz())
        .query(resultMapper)
        .list()

    /** The most recent game [userId] finished with their account, and when it ended; null before their first. */
    fun lastGame(userId: UserId): Pair<GameId, Instant>? = jdbc.sql(
        "SELECT game_id, finished_at FROM game_results WHERE user_id = :u ORDER BY finished_at DESC, game_id LIMIT 1",
    )
        .param("u", userId.value)
        .query { rs, _ -> GameId(rs.getString("game_id")) to rs.getInstant("finished_at") }
        .optional()
        .orElse(null)

    /** [userId]'s city of the city leaderboard; null: none picked. */
    fun cityOf(userId: UserId): String? = jdbc.sql("SELECT city FROM users WHERE id = :u")
        .param("u", userId.value)
        .query { rs, _ -> rs.getString("city") }
        .optional()
        .orElse(null)

    /** [userId]'s friends, without anyone blocked either way (blocks end friendships; this is a second guard). */
    fun friends(userId: UserId): List<UserId> = jdbc.sql(
        """
        SELECT f.friend_id FROM friendships f
        WHERE f.user_id = :u
          AND NOT EXISTS (
              SELECT 1 FROM blocks b
              WHERE (b.blocker_id = :u AND b.blocked_id = f.friend_id)
                 OR (b.blocker_id = f.friend_id AND b.blocked_id = :u)
          )
        """.trimIndent(),
    )
        .param("u", userId.value)
        .query { rs, _ -> UserId(rs.getString("friend_id")) }
        .list()

    private companion object {
        /** Rows per batch of [eachResult]: a few hundred kilobytes at a time. */
        const val STREAM_BATCH = 2_000

        /** `r` is not under a ban at `:now` (the same test as `SanctionRepository.active`). */
        const val NOT_BANNED = """NOT EXISTS (
              SELECT 1 FROM sanctions s
              WHERE s.user_id = r.user_id AND s.kind = 'BAN' AND s.lifted_at IS NULL
                AND (s.until IS NULL OR s.until > :now)
          )"""

        val resultMapper = RowMapper { rs, _ ->
            LeaderboardResult(
                userId = UserId(rs.getString("user_id")),
                nickname = rs.getString("nickname"),
                finishedAt = rs.getInstant("finished_at"),
                role = Role.valueOf(rs.getString("role")),
                status = PlayerStatus.valueOf(rs.getString("status")),
                won = rs.getBoolean("won"),
                catches = rs.getInt("catches"),
                survivedSeconds = rs.getInt("survived_seconds").takeUnless { rs.wasNull() },
            )
        }
    }
}
