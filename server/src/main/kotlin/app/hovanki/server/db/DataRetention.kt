package app.hovanki.server.db

import app.hovanki.server.account.AccountProperties
import app.hovanki.server.admin.AdminProperties
import app.hovanki.server.bigGames.BigGameProperties
import app.hovanki.server.history.HistoryProperties
import app.hovanki.server.lab.LabProperties
import app.hovanki.server.lab.LabRunRepository
import app.hovanki.server.moderation.ModerationProperties
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * Deletes stored data once its retention period is over (GDPR, docs/adr/0004-accounts-friends-chat.md,
 * docs/adr/0007-game-history-and-routes.md, docs/adr/0008-admin.md): idle sessions, old reports, friend requests and
 * saved routes, game recordings (docs/adr/0011-spectators-and-recordings.md), expired email codes, ended admin sessions,
 * bans and chat bans a year after their end, the audit log after a year, big games and their sign-ups 90 days after
 * their end (docs/adr/0010-big-games.md), the radio lab's logs 90 days after their run's end
 * (docs/adr/0017-radar-techniques-and-big-run.md §7; a run never finished ends with its join window) and the name of
 * the admin who made a run a year after, as the audit log's. Accounts, and
 * the history and statistics of their games, stay until their owners delete them, whether their email is confirmed or
 * not (confirming is optional). Runs once a day (`hovanki.retention.cron`); logs only counts.
 */
@Component
class DataRetention(
    private val jdbc: JdbcClient,
    private val accounts: AccountProperties,
    private val moderation: ModerationProperties,
    private val history: HistoryProperties,
    private val admin: AdminProperties,
    private val bigGames: BigGameProperties,
    private val lab: LabProperties,
    private val labRuns: LabRunRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Deleted(
        val sessions: Int,
        val reports: Int,
        val friendRequests: Int,
        val emailCodes: Int,
        val routes: Int,
        val adminSessions: Int = 0,
        val sanctions: Int = 0,
        val auditEntries: Int = 0,
        val bigGames: Int = 0,
        val recordings: Int = 0,
        val labChunks: Int = 0,
        val labRunNames: Int = 0,
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
            adminSessions = jdbc.sql("DELETE FROM admin_sessions WHERE created_at < :max OR last_used_at < :idle")
                .param("max", before(admin.sessionMax))
                .param("idle", before(admin.sessionIdle))
                .update(),
            sanctions = delete(
                "DELETE FROM sanctions WHERE coalesce(lifted_at, until) < :t",
                before(admin.auditRetention),
            ),
            auditEntries = delete("DELETE FROM admin_audit WHERE at < :t", before(admin.auditRetention)),
            // With their sign-ups (ON DELETE CASCADE).
            bigGames = delete("DELETE FROM big_games WHERE ended_at < :t", before(bigGames.retention)),
            // With everybody's way in them (ON DELETE CASCADE).
            recordings = delete(
                "DELETE FROM game_recordings WHERE saved_at < :t",
                before(history.recordingRetention),
            ),
            // The runs, their devices and reports stay: labels, models and numbers.
            labChunks = labRuns.deleteChunksOfRunsFinishedBefore(
                finishedBefore = now.minus(lab.chunkRetention),
                createdBefore = now.minus(lab.chunkRetention).minus(lab.joinWindow),
            ),
            // Who made a run: as long as the audit log keeps who did what.
            labRunNames = delete(
                "UPDATE lab_runs SET created_by_name = NULL WHERE created_at < :t AND created_by_name IS NOT NULL",
                before(admin.auditRetention),
            ),
        )
        log.info(
            "Data retention: deleted {} idle sessions, {} reports, {} friend requests, {} expired email codes, " +
                "{} saved routes, {} admin sessions, {} ended sanctions, {} audit entries, {} big games, " +
                "{} game recordings, {} lab log chunks, {} lab runs' makers",
            deleted.sessions,
            deleted.reports,
            deleted.friendRequests,
            deleted.emailCodes,
            deleted.routes,
            deleted.adminSessions,
            deleted.sanctions,
            deleted.auditEntries,
            deleted.bigGames,
            deleted.recordings,
            deleted.labChunks,
            deleted.labRunNames,
        )
        return deleted
    }

    private fun delete(sql: String, threshold: Any): Int = jdbc.sql(sql).param("t", threshold).update()
}
