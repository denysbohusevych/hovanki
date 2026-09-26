package app.hovanki.server.moderation

import app.hovanki.server.db.getInstant
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

/** A row of `reports`: a reported chat message, its text copied (the game and its chat live in memory only). */
data class ReportRecord(
    val id: Long,
    val gameId: String,
    val messageSeq: Long,
    val reporterPlayerId: String,
    val reporterUserId: String?,
    val reportedUserId: String?,
    val reportedName: String,
    val text: String,
    val createdAt: Instant,
) {
    // Never the chat text in logs.
    override fun toString(): String = "ReportRecord($id, game $gameId, seq $messageSeq)"
}

/** `reports`, kept for `hovanki.moderation.report-retention` (DataRetention). */
@Repository
class ReportRepository(private val jdbc: JdbcClient) {
    /** The newest [limit] reports, newest first. */
    fun latest(limit: Int): List<ReportRecord> = jdbc.sql("SELECT * FROM reports ORDER BY id DESC LIMIT :limit")
        .param("limit", limit)
        .query { rs, _ ->
            ReportRecord(
                id = rs.getLong("id"),
                gameId = rs.getString("game_id"),
                messageSeq = rs.getLong("message_seq"),
                reporterPlayerId = rs.getString("reporter_player_id"),
                reporterUserId = rs.getString("reporter_user_id"),
                reportedUserId = rs.getString("reported_user_id"),
                reportedName = rs.getString("reported_name"),
                text = rs.getString("text"),
                createdAt = rs.getInstant("created_at"),
            )
        }
        .list()
        .filterNotNull()
}
