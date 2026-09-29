package app.hovanki.server.admin

import app.hovanki.server.db.getInstant
import app.hovanki.server.db.getInstantOrNull
import app.hovanki.server.db.toTimestamptz
import app.hovanki.shared.protocol.UserId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * An admin session: [createdAt] bounds it absolutely, [lastUsedAt] by idle time. [tokenHash] is its current token,
 * replaced at [rotatedAt]; [previousHash], the one before, still works until [previousUntil].
 */
data class AdminSessionRecord(
    val tokenHash: String,
    val userId: UserId,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    val rotatedAt: Instant,
    val previousHash: String? = null,
    val previousUntil: Instant? = null,
)

/** `staff_totp` and `admin_sessions` (docs/adr/0008-admin.md). */
@Repository
class StaffRepository(private val jdbc: JdbcClient) {
    /** The sealed ([SecretBox]) secret of [userId]'s authenticator, or null if none is set up. */
    fun totpSecret(userId: UserId): String? = jdbc.sql("SELECT secret FROM staff_totp WHERE user_id = :u")
        .param("u", userId.value)
        .query(String::class.java)
        .optional()
        .orElse(null)

    fun enrolled(ids: Collection<UserId>): Set<UserId> {
        if (ids.isEmpty()) return emptySet()
        return jdbc.sql("SELECT user_id FROM staff_totp WHERE user_id IN (:ids)")
            .param("ids", ids.map { it.value })
            .query(String::class.java)
            .list()
            .mapNotNullTo(mutableSetOf()) { it?.let(::UserId) }
    }

    /** Sets up (or replaces) [userId]'s authenticator; [step] was just used to confirm it. */
    fun saveTotp(userId: UserId, sealedSecret: String, step: Long, now: Instant) {
        jdbc.sql(
            """
            INSERT INTO staff_totp (user_id, secret, last_step, confirmed_at) VALUES (:u, :s, :step, :now)
            ON CONFLICT (user_id) DO UPDATE SET secret = :s, last_step = :step, confirmed_at = :now
            """.trimIndent(),
        )
            .param("u", userId.value)
            .param("s", sealedSecret)
            .param("step", step)
            .param("now", now.toTimestamptz())
            .update()
    }

    /**
     * Takes time step [step] of [userId]'s authenticator: false if it (or a later one) was used before. Atomic, so one
     * code can't log in twice even in parallel.
     */
    fun useStep(userId: UserId, step: Long): Boolean =
        jdbc.sql("UPDATE staff_totp SET last_step = :step WHERE user_id = :u AND last_step < :step")
            .param("u", userId.value)
            .param("step", step)
            .update() > 0

    fun deleteTotp(userId: UserId): Boolean =
        jdbc.sql("DELETE FROM staff_totp WHERE user_id = :u").param("u", userId.value).update() > 0

    fun createSession(tokenHash: String, userId: UserId, now: Instant) {
        jdbc.sql(
            "INSERT INTO admin_sessions (token_hash, user_id, created_at, last_used_at, rotated_at) " +
                "VALUES (:h, :u, :t, :t, :t)",
        )
            .param("h", tokenHash)
            .param("u", userId.value)
            .param("t", now.toTimestamptz())
            .update()
    }

    /** The session whose current or previous token has [tokenHash]. */
    fun findSession(tokenHash: String): AdminSessionRecord? =
        jdbc.sql("SELECT * FROM admin_sessions WHERE token_hash = :h OR previous_hash = :h")
            .param("h", tokenHash)
            .query { rs, _ ->
                AdminSessionRecord(
                    tokenHash = rs.getString("token_hash"),
                    userId = UserId(rs.getString("user_id")),
                    createdAt = rs.getInstant("created_at"),
                    lastUsedAt = rs.getInstant("last_used_at"),
                    rotatedAt = rs.getInstant("rotated_at"),
                    previousHash = rs.getString("previous_hash"),
                    previousUntil = rs.getInstantOrNull("previous_until"),
                )
            }
            .optional()
            .orElse(null)

    /**
     * Replaces the session's current token [tokenHash] with [newHash]; the replaced one works until [previousUntil].
     * False when another request replaced it first (that one's answer carries the new token).
     */
    fun rotateSession(tokenHash: String, newHash: String, now: Instant, previousUntil: Instant): Boolean = jdbc.sql(
        "UPDATE admin_sessions SET token_hash = :new, previous_hash = token_hash, previous_until = :until, " +
            "rotated_at = :t, last_used_at = :t WHERE token_hash = :h",
    )
        .param("h", tokenHash)
        .param("new", newHash)
        .param("until", previousUntil.toTimestamptz())
        .param("t", now.toTimestamptz())
        .update() > 0

    fun touchSession(tokenHash: String, now: Instant) {
        jdbc.sql("UPDATE admin_sessions SET last_used_at = :t WHERE token_hash = :h")
            .param("h", tokenHash)
            .param("t", now.toTimestamptz())
            .update()
    }

    fun deleteSession(tokenHash: String): Boolean =
        jdbc.sql("DELETE FROM admin_sessions WHERE token_hash = :h").param("h", tokenHash).update() > 0

    fun deleteSessions(userId: UserId): Int =
        jdbc.sql("DELETE FROM admin_sessions WHERE user_id = :u").param("u", userId.value).update()

    /** When each of [ids] last logged in to the admin (their newest session), if they have a session left. */
    fun lastLogins(ids: Collection<UserId>): Map<UserId, Instant> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.sql(
            "SELECT user_id, max(created_at) AS at FROM admin_sessions WHERE user_id IN (:ids) GROUP BY user_id",
        )
            .param("ids", ids.map { it.value })
            .query { rs, _ -> UserId(rs.getString("user_id")) to rs.getInstant("at") }
            .list()
            .filterNotNull()
            .toMap()
    }
}
