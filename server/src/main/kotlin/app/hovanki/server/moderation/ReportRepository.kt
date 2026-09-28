package app.hovanki.server.moderation

import app.hovanki.server.db.getInstant
import app.hovanki.server.db.getInstantOrNull
import app.hovanki.server.db.toTimestamptz
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.UserId
import org.springframework.jdbc.core.RowMapper
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
    val resolvedAt: Instant? = null,
    /** The staff member's nickname at the time. */
    val resolvedBy: String? = null,
    val resolution: String? = null,
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
    fun latest(limit: Int): List<ReportRecord> = page(openOnly = false, before = null, limit = limit)

    /** Newest first, [limit] reports older than report [before] (null: the newest); [openOnly]: not handled yet. */
    fun page(openOnly: Boolean, before: Long?, limit: Int): List<ReportRecord> = jdbc.sql(
        """
        SELECT * FROM reports
        WHERE (NOT :openOnly OR resolved_at IS NULL)
          AND (CAST(:before AS bigint) IS NULL OR id < CAST(:before AS bigint))
        ORDER BY id DESC
        LIMIT :limit
        """.trimIndent(),
    )
        .param("openOnly", openOnly)
        .param("before", before)
        .param("limit", limit)
        .query(mapper)
        .list()
        .filterNotNull()

    fun find(id: Long): ReportRecord? =
        jdbc.sql("SELECT * FROM reports WHERE id = :id").param("id", id).query(mapper).optional().orElse(null)

    /** Closes every open report on message [seq] of game [gameId]; how many. */
    fun resolveMessage(gameId: String, seq: Long, by: String, resolution: String, at: Instant): Int = jdbc.sql(
        """
        UPDATE reports SET resolved_at = :at, resolved_by = :by, resolution = :resolution
        WHERE game_id = :gameId AND message_seq = :seq AND resolved_at IS NULL
        """.trimIndent(),
    )
        .param("gameId", gameId)
        .param("seq", seq)
        .param("by", by)
        .param("resolution", resolution)
        .param("at", at.toTimestamptz())
        .update()

    fun openCount(): Int =
        jdbc.sql("SELECT count(*) FROM reports WHERE resolved_at IS NULL").query(Int::class.java).single()

    fun countSince(since: Instant): Int = jdbc.sql("SELECT count(*) FROM reports WHERE created_at >= :since")
        .param("since", since.toTimestamptz())
        .query(Int::class.java)
        .single()

    /** Reports on the messages of each of [authors], and from how many different players (a guest by their player). */
    fun againstAuthors(authors: Collection<UserId>): Map<UserId, Pair<Int, Int>> {
        if (authors.isEmpty()) return emptyMap()
        return jdbc.sql(
            """
            SELECT reported_user_id, count(*) AS reports,
                   count(DISTINCT coalesce(reporter_user_id, reporter_player_id)) AS reporters
            FROM reports
            WHERE reported_user_id IN (:ids)
            GROUP BY reported_user_id
            """.trimIndent(),
        )
            .param("ids", authors.map { it.value })
            .query { rs, _ ->
                UserId(rs.getString("reported_user_id")) to
                    (rs.getInt("reports") to rs.getInt("reporters"))
            }
            .list()
            .filterNotNull()
            .toMap()
    }

    /** Reports [userId] sent. */
    fun countBy(userId: UserId): Int = jdbc.sql("SELECT count(*) FROM reports WHERE reporter_user_id = :u")
        .param("u", userId.value)
        .query(Int::class.java)
        .single()

    private companion object {
        val mapper = RowMapper { rs, _ ->
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
                resolvedAt = rs.getInstantOrNull("resolved_at"),
                resolvedBy = rs.getString("resolved_by"),
                resolution = rs.getString("resolution"),
            )
        }
    }
}
