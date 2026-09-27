package app.hovanki.server.account

import app.hovanki.server.db.toTimestamptz
import app.hovanki.server.mail.EmailPurpose
import app.hovanki.shared.protocol.UserId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * `email_codes`: at most one live code per user and purpose, stored as a hash ([AccountKeys.codeHash]). A new code
 * replaces the old one and its attempts.
 */
@Repository
class EmailCodeRepository(private val jdbc: JdbcClient) {
    /** One counted attempt at a code: the stored hash and the attempts so far, this one included. */
    data class Attempt(val codeHash: String, val attempts: Int)

    fun save(userId: UserId, purpose: EmailPurpose, codeHash: String, expiresAt: Instant, now: Instant) {
        jdbc.sql(
            """
            INSERT INTO email_codes (user_id, purpose, code_hash, expires_at, attempts, created_at)
            VALUES (:u, :p, :hash, :expires, 0, :now)
            ON CONFLICT (user_id, purpose)
            DO UPDATE SET code_hash = :hash, expires_at = :expires, attempts = 0, created_at = :now
            """.trimIndent(),
        )
            .param("u", userId.value)
            .param("p", purpose.name)
            .param("hash", codeHash)
            .param("expires", expiresAt.toTimestamptz())
            .param("now", now.toTimestamptz())
            .update()
    }

    /**
     * Counts one attempt at the live code and returns it; null when there is none: never sent, expired, or all
     * [maxAttempts] used. Atomic, so parallel guesses can't get past the limit. Call it outside a transaction that
     * may roll back: a wrong guess must stay counted.
     */
    fun useAttempt(userId: UserId, purpose: EmailPurpose, now: Instant, maxAttempts: Int): Attempt? = jdbc.sql(
        """
        UPDATE email_codes SET attempts = attempts + 1
        WHERE user_id = :u AND purpose = :p AND expires_at > :now AND attempts < :max
        RETURNING code_hash, attempts
        """.trimIndent(),
    )
        .param("u", userId.value)
        .param("p", purpose.name)
        .param("now", now.toTimestamptz())
        .param("max", maxAttempts)
        .query { rs, _ -> Attempt(rs.getString("code_hash"), rs.getInt("attempts")) }
        .optional()
        .orElse(null)

    /** Deletes the code if it is still [codeHash]; false when it is gone or was replaced meanwhile. */
    fun consume(userId: UserId, purpose: EmailPurpose, codeHash: String): Boolean =
        jdbc.sql("DELETE FROM email_codes WHERE user_id = :u AND purpose = :p AND code_hash = :hash")
            .param("u", userId.value)
            .param("p", purpose.name)
            .param("hash", codeHash)
            .update() > 0

    /** Every code of [userId], e.g. when the email they were sent to changes. */
    fun deleteAll(userId: UserId): Int =
        jdbc.sql("DELETE FROM email_codes WHERE user_id = :u").param("u", userId.value).update()
}
