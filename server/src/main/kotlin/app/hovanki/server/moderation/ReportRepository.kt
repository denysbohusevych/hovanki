package app.hovanki.server.moderation

import app.hovanki.server.db.getInstant
import app.hovanki.server.db.toTimestamptz
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.UserId
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

/** A report to store: the message's text, the sender's name and account are copies from the game. */
data class NewReport(
    val gameId: GameId,
    val messageSeq: Long,
    val reporterPlayerId: PlayerId,
    /** Null: the reporter plays as a guest. */
    val reporterUserId: UserId?,
    /** Null: the sender plays as a guest. */
    val reportedUserId: UserId?,
    val reportedName: String,
    val text: String,
) {
    // Never the chat text in logs.
    override fun toString(): String = "NewReport(game ${gameId.value}, seq $messageSeq)"
}

/** `reports`, kept for `hovanki.moderation.report-retention` (DataRetention). */
@Repository
class ReportRepository(private val jdbc: JdbcClient) {
    /** Stores [report]; false if this player reported this message before (nothing changes then). */
    fun insert(report: NewReport, createdAt: Instant): Boolean = jdbc.sql(
        """
        INSERT INTO reports (game_id, message_seq, reporter_player_id, reporter_user_id, reported_user_id,
                             reported_name, text, created_at)
        VALUES (:gameId, :seq, :reporterPlayerId, :reporterUserId, :reportedUserId, :reportedName, :text, :createdAt)
        ON CONFLICT ON CONSTRAINT reports_once DO NOTHING
        """.trimIndent(),
    )
        .param("gameId", report.gameId.value)
        .param("seq", report.messageSeq)
        .param("reporterPlayerId", report.reporterPlayerId.value)
        .param("reporterUserId", report.reporterUserId?.value)
        .param("reportedUserId", report.reportedUserId?.value)
        .param("reportedName", report.reportedName)
        .param("text", report.text)
        .param("createdAt", createdAt.toTimestamptz())
        .update() > 0

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
