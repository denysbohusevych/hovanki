package app.hovanki.server.admin

import app.hovanki.server.db.getInstant
import app.hovanki.server.db.toTimestamptz
import app.hovanki.shared.protocol.AdminAction
import app.hovanki.shared.protocol.AdminAuditEntry
import app.hovanki.shared.protocol.UserId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * `admin_audit`: what staff did, who, when and why (docs/adr/0008-admin.md). Written in the same transaction as the
 * action it describes, where there is one: no action without its entry. Deleted after a year (DataRetention).
 */
@Repository
class AuditLog(private val jdbc: JdbcClient) {
    fun record(
        actor: Staff,
        action: AdminAction,
        at: Instant,
        targetUserId: UserId? = null,
        target: String? = null,
        reason: String? = null,
    ) {
        jdbc.sql(
            """
            INSERT INTO admin_audit (at, actor_id, actor_name, action, target_user_id, target, reason)
            VALUES (:at, :actorId, :actorName, :action, :targetUserId, :target, :reason)
            """.trimIndent(),
        )
            .param("at", at.toTimestamptz())
            .param("actorId", actor.userId.value)
            .param("actorName", actor.nickname)
            .param("action", action.name)
            .param("targetUserId", targetUserId?.value)
            .param("target", target)
            .param("reason", reason)
            .update()
    }

    /** Newest first, [limit] entries older than entry [before] (null: the newest). */
    fun page(before: Long?, limit: Int): List<AdminAuditEntry> = jdbc.sql(
        """
        SELECT * FROM admin_audit
        WHERE CAST(:before AS bigint) IS NULL OR id < CAST(:before AS bigint)
        ORDER BY id DESC
        LIMIT :limit
        """.trimIndent(),
    )
        .param("before", before)
        .param("limit", limit)
        .query { rs, _ ->
            AdminAuditEntry(
                id = rs.getLong("id"),
                atMillis = rs.getInstant("at").toEpochMilli(),
                actorId = UserId(rs.getString("actor_id")),
                actorName = rs.getString("actor_name"),
                action = AdminAction.valueOf(rs.getString("action")),
                targetUserId = rs.getString("target_user_id")?.let(::UserId),
                target = rs.getString("target"),
                reason = rs.getString("reason"),
            )
        }
        .list()
        .filterNotNull()
}
