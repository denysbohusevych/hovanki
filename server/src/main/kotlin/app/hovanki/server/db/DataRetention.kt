package app.hovanki.server.db

import app.hovanki.server.account.AccountProperties
import app.hovanki.server.moderation.ModerationProperties
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * Deletes stored data once its retention period is over (GDPR, docs/adr/0004-accounts-friends-chat.md): idle
 * sessions, accounts whose email was never confirmed, old reports and friend requests, expired email codes.
 * Runs once a day (`hovanki.retention.cron`); logs only counts.
 */
@Component
class DataRetention(
    private val jdbc: JdbcClient,
    private val accounts: AccountProperties,
    private val moderation: ModerationProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Deleted(
        val sessions: Int,
        val unverifiedAccounts: Int,
        val reports: Int,
        val friendRequests: Int,
        val emailCodes: Int,
    )

    @Scheduled(cron = "\${hovanki.retention.cron:0 17 3 * * *}")
    fun run(): Deleted {
        val now = clock.instant()
        fun before(retention: Duration) = now.minus(retention).toTimestamptz()

        val deleted = Deleted(
            sessions = delete(
                "DELETE FROM account_sessions WHERE last_used_at < :t",
                before(accounts.sessionIdleRetention),
            ),
            // An unconfirmed account has no friends, groups or games (they all need a confirmed email): a plain
            // delete is enough, no BeforeAccountDeletion.
            unverifiedAccounts = delete(
                "DELETE FROM users WHERE email_verified_at IS NULL AND created_at < :t",
                before(accounts.unverifiedRetention),
            ),
            reports = delete("DELETE FROM reports WHERE created_at < :t", before(moderation.reportRetention)),
            friendRequests = delete(
                "DELETE FROM friend_requests WHERE created_at < :t",
                before(accounts.requestRetention),
            ),
            emailCodes = delete("DELETE FROM email_codes WHERE expires_at < :t", now.toTimestamptz()),
        )
        log.info(
            "Data retention: deleted {} idle sessions, {} unconfirmed accounts, {} reports, {} friend requests, " +
                "{} expired email codes",
            deleted.sessions,
            deleted.unverifiedAccounts,
            deleted.reports,
            deleted.friendRequests,
            deleted.emailCodes,
        )
        return deleted
    }

    private fun delete(sql: String, threshold: Any): Int = jdbc.sql(sql).param("t", threshold).update()
}
