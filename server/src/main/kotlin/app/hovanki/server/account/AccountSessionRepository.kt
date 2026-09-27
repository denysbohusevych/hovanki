package app.hovanki.server.account

import app.hovanki.server.db.getInstant
import app.hovanki.server.db.toTimestamptz
import app.hovanki.shared.protocol.UserId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

/** A logged-in device. */
data class SessionRecord(val tokenHash: String, val userId: UserId, val lastUsedAt: Instant)

/** `account_sessions`: one row per logged-in device, keyed by the SHA-256 of its token ([AccountKeys.tokenHash]). */
@Repository
class AccountSessionRepository(private val jdbc: JdbcClient) {
    fun create(tokenHash: String, userId: UserId, now: Instant) {
        jdbc.sql("INSERT INTO account_sessions (token_hash, user_id, created_at, last_used_at) VALUES (:h, :u, :t, :t)")
            .param("h", tokenHash)
            .param("u", userId.value)
            .param("t", now.toTimestamptz())
            .update()
    }

    fun find(tokenHash: String): SessionRecord? =
        jdbc.sql("SELECT token_hash, user_id, last_used_at FROM account_sessions WHERE token_hash = :h")
            .param("h", tokenHash)
            .query { rs, _ ->
                SessionRecord(
                    tokenHash = rs.getString("token_hash"),
                    userId = UserId(rs.getString("user_id")),
                    lastUsedAt = rs.getInstant("last_used_at"),
                )
            }
            .optional()
            .orElse(null)

    fun touch(tokenHash: String, now: Instant) {
        jdbc.sql("UPDATE account_sessions SET last_used_at = :t WHERE token_hash = :h")
            .param("h", tokenHash)
            .param("t", now.toTimestamptz())
            .update()
    }

    fun delete(tokenHash: String): Boolean =
        jdbc.sql("DELETE FROM account_sessions WHERE token_hash = :h").param("h", tokenHash).update() > 0

    /** Logs [userId] out everywhere, except the session [exceptTokenHash] if given. */
    fun deleteAll(userId: UserId, exceptTokenHash: String? = null): Int = jdbc.sql(
        "DELETE FROM account_sessions WHERE user_id = :u AND token_hash IS DISTINCT FROM CAST(:except AS text)",
    )
        .param("u", userId.value)
        .param("except", exceptTokenHash)
        .update()
}
