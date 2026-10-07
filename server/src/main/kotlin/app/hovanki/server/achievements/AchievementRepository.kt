package app.hovanki.server.achievements

import app.hovanki.server.db.getInstant
import app.hovanki.server.db.getInstantOrNull
import app.hovanki.server.db.toTimestamptz
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.UserId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

/** One finished game of the player, as the achievements count it: a row of `game_results`. */
data class AchievementResult(
    val finishedAt: Instant,
    val role: Role,
    val status: PlayerStatus,
    val won: Boolean,
    val players: Int,
    val catches: Int,
    val survivedSeconds: Int?,
    val distanceMeters: Double,
)

/** Reads a player's `game_results` and keeps when they last looked at their achievements (docs/adr/0021-achievements.md). */
@Repository
class AchievementRepository(private val jdbc: JdbcClient) {
    /** Every finished game of [userId], in no particular order. */
    fun results(userId: UserId): List<AchievementResult> = jdbc.sql(
        """
        SELECT finished_at, role, status, won, players, catches, survived_seconds, distance_meters
        FROM game_results WHERE user_id = :u
        """.trimIndent(),
    )
        .param("u", userId.value)
        .query { rs, _ ->
            AchievementResult(
                finishedAt = rs.getInstant("finished_at"),
                role = Role.valueOf(rs.getString("role")),
                status = PlayerStatus.valueOf(rs.getString("status")),
                won = rs.getBoolean("won"),
                players = rs.getInt("players"),
                catches = rs.getInt("catches"),
                survivedSeconds = rs.getInt("survived_seconds").takeUnless { rs.wasNull() },
                distanceMeters = rs.getDouble("distance_meters"),
            )
        }
        .list()

    /** Up to when [userId] has seen their achievements; null: never. */
    fun seenAt(userId: UserId): Instant? = jdbc.sql("SELECT achievements_seen_at FROM users WHERE id = :u")
        .param("u", userId.value)
        .query { rs, _ -> rs.getInstantOrNull("achievements_seen_at") }
        .optional()
        .orElse(null)

    /** Moves [userId]'s seen time forward to [at]; never back. */
    fun markSeen(userId: UserId, at: Instant) {
        jdbc.sql(
            """
            UPDATE users SET achievements_seen_at = :at
            WHERE id = :u AND (achievements_seen_at IS NULL OR achievements_seen_at < :at)
            """.trimIndent(),
        )
            .param("u", userId.value)
            .param("at", at.toTimestamptz())
            .update()
    }
}
