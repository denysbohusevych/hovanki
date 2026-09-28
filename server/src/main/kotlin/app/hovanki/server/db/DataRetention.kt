package app.hovanki.server.db

import app.hovanki.server.account.AccountProperties
import app.hovanki.server.history.HistoryProperties
import app.hovanki.server.moderation.ModerationProperties
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * Deletes stored data once its retention period is over (GDPR, docs/adr/0004-accounts-friends-chat.md,
 * docs/adr/0007-game-history-and-routes.md): idle sessions, old reports, friend requests and saved routes, expired
 * email codes. Accounts, and the history and statistics of their games, stay until their owners delete them, whether
 * their email is confirmed or not (confirming is optional). Runs once a day (`hovanki.retention.cron`); logs only
 * counts.
 */
@Component
class DataRetention(
    private val jdbc: JdbcClient,
    private val accounts: AccountProperties,
    private val moderation: ModerationProperties,
    private val history: HistoryProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Deleted(
        val sessions: Int,
        val reports: Int,
        val friendRequests: Int,
        val emailCodes: Int,
        val routes: Int,
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
            reports = delete("DELETE FROM reports WHERE created_at < :t", before(moderation.reportRetention)),
            friendRequests = delete(
                "DELETE FROM friend_requests WHERE created_at < :t",
                before(accounts.requestRetention),
            ),
            emailCodes = delete("DELETE FROM email_codes WHERE expires_at < :t", now.toTimestamptz()),
            routes = delete("DELETE FROM game_routes WHERE saved_at < :t", before(history.routeRetention)),
        )
        log.info(
            "Data retention: deleted {} idle sessions, {} reports, {} friend requests, {} expired email codes, " +
                "{} saved routes",
            deleted.sessions,
            deleted.reports,
            deleted.friendRequests,
            deleted.emailCodes,
            deleted.routes,
        )
        return deleted
    }

    private fun delete(sql: String, threshold: Any): Int = jdbc.sql(sql).param("t", threshold).update()
}
