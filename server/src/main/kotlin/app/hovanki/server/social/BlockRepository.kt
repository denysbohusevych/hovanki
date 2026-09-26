package app.hovanki.server.social

import app.hovanki.server.db.toTimestamptz
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserSummary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

/** `blocks`: who blocked whom. A block works one way; most checks care about either direction. */
@Repository
class BlockRepository(private val jdbc: JdbcClient) {
    /** False if [blocker] had blocked [blocked] already. */
    fun block(blocker: UserId, blocked: UserId, now: Instant): Boolean = jdbc.sql(
        "INSERT INTO blocks (blocker_id, blocked_id, created_at) VALUES (:a, :b, :t) ON CONFLICT DO NOTHING",
    )
        .param("a", blocker.value)
        .param("b", blocked.value)
        .param("t", now.toTimestamptz())
        .update() > 0

    /** False if there was no such block. */
    fun unblock(blocker: UserId, blocked: UserId): Boolean =
        jdbc.sql("DELETE FROM blocks WHERE blocker_id = :a AND blocked_id = :b")
            .param("a", blocker.value)
            .param("b", blocked.value)
            .update() > 0

    fun isBlocked(blocker: UserId, blocked: UserId): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM blocks WHERE blocker_id = :a AND blocked_id = :b)")
            .param("a", blocker.value)
            .param("b", blocked.value)
            .query(Boolean::class.java)
            .single()

    /** Whether [a] blocked [b] or [b] blocked [a]. */
    fun isBlockedEitherWay(a: UserId, b: UserId): Boolean = blockedEitherWayAmong(a, listOf(b)).isNotEmpty()

    /** Those of [candidates] whom [userId] blocked or who blocked [userId]. */
    fun blockedEitherWayAmong(userId: UserId, candidates: Collection<UserId>): Set<UserId> {
        if (candidates.isEmpty()) return emptySet()
        return jdbc.sql(
            """
            SELECT blocked_id FROM blocks WHERE blocker_id = :u AND blocked_id IN (:ids)
            UNION
            SELECT blocker_id FROM blocks WHERE blocked_id = :u AND blocker_id IN (:ids)
            """.trimIndent(),
        )
            .param("u", userId.value)
            .param("ids", candidates.map { it.value }.distinct())
            .query(String::class.java)
            .list()
            .mapNotNullTo(mutableSetOf()) { it?.let(::UserId) }
    }

    /** The users [blocker] blocked, unsorted. */
    fun blocked(blocker: UserId): List<UserSummary> =
        jdbc.sql("SELECT u.id, u.nickname FROM blocks b JOIN users u ON u.id = b.blocked_id WHERE b.blocker_id = :u")
            .param("u", blocker.value)
            .query(summaryMapper)
            .list()
            .filterNotNull()
}
