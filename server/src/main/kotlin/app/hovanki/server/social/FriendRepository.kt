package app.hovanki.server.social

import app.hovanki.server.db.toTimestamptz
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserSummary
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * `friendships` (two rows per friendship, one for each side) and `friend_requests`. Lists come unsorted; the services
 * sort them for the app.
 */
@Repository
class FriendRepository(private val jdbc: JdbcClient) {
    fun areFriends(a: UserId, b: UserId): Boolean = exists(
        "SELECT EXISTS (SELECT 1 FROM friendships WHERE user_id = :a AND friend_id = :b)",
        a,
        b,
    )

    /** Those of [candidates] who are friends of [userId]. */
    fun friendsAmong(userId: UserId, candidates: Collection<UserId>): Set<UserId> {
        if (candidates.isEmpty()) return emptySet()
        return jdbc.sql("SELECT friend_id FROM friendships WHERE user_id = :u AND friend_id IN (:ids)")
            .param("u", userId.value)
            .param("ids", candidates.map { it.value }.distinct())
            .query(String::class.java)
            .list()
            .mapNotNullTo(mutableSetOf()) { it?.let(::UserId) }
    }

    fun friends(userId: UserId): List<UserSummary> = summaries(
        "SELECT u.id, u.nickname FROM friendships f JOIN users u ON u.id = f.friend_id WHERE f.user_id = :u",
        userId,
    )

    fun countFriends(userId: UserId): Int = count("SELECT count(*) FROM friendships WHERE user_id = :u", userId)

    /** Both rows; nothing happens if they are friends already. */
    fun addFriendship(a: UserId, b: UserId, now: Instant) {
        jdbc.sql(
            """
            INSERT INTO friendships (user_id, friend_id, created_at) VALUES (:a, :b, :t), (:b, :a, :t)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        )
            .param("a", a.value)
            .param("b", b.value)
            .param("t", now.toTimestamptz())
            .update()
    }

    /** Both rows; false if they were not friends. */
    fun removeFriendship(a: UserId, b: UserId): Boolean = jdbc.sql(
        "DELETE FROM friendships WHERE (user_id = :a AND friend_id = :b) OR (user_id = :b AND friend_id = :a)",
    )
        .param("a", a.value)
        .param("b", b.value)
        .update() > 0

    fun hasRequest(from: UserId, to: UserId): Boolean = exists(
        "SELECT EXISTS (SELECT 1 FROM friend_requests WHERE from_user = :a AND to_user = :b)",
        from,
        to,
    )

    /** Nothing happens if the request is there already (it keeps its time). */
    fun addRequest(from: UserId, to: UserId, now: Instant) {
        jdbc.sql(
            "INSERT INTO friend_requests (from_user, to_user, created_at) VALUES (:a, :b, :t) ON CONFLICT DO NOTHING",
        )
            .param("a", from.value)
            .param("b", to.value)
            .param("t", now.toTimestamptz())
            .update()
    }

    fun removeRequest(from: UserId, to: UserId): Boolean =
        jdbc.sql("DELETE FROM friend_requests WHERE from_user = :a AND to_user = :b")
            .param("a", from.value)
            .param("b", to.value)
            .update() > 0

    /** The requests both ways between [a] and [b]. */
    fun removeRequestsBetween(a: UserId, b: UserId): Int = jdbc.sql(
        "DELETE FROM friend_requests WHERE (from_user = :a AND to_user = :b) OR (from_user = :b AND to_user = :a)",
    )
        .param("a", a.value)
        .param("b", b.value)
        .update()

    /**
     * The requests to [userId] that they may see: not those from users they blocked (sent after the block, see
     * [FriendService.sendRequest]). The friends screen and the inbox show these.
     */
    fun incoming(userId: UserId): List<UserSummary> = summaries(
        """
        SELECT u.id, u.nickname FROM friend_requests r JOIN users u ON u.id = r.from_user
        WHERE r.to_user = :u
          AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.blocker_id = :u AND b.blocked_id = r.from_user)
        """.trimIndent(),
        userId,
    )

    /** The requests [userId] sent that are not answered yet, including those the other side does not see. */
    fun outgoing(userId: UserId): List<UserSummary> = summaries(
        "SELECT u.id, u.nickname FROM friend_requests r JOIN users u ON u.id = r.to_user WHERE r.from_user = :u",
        userId,
    )

    fun countOutgoing(userId: UserId): Int = count("SELECT count(*) FROM friend_requests WHERE from_user = :u", userId)

    private fun summaries(sql: String, userId: UserId): List<UserSummary> =
        jdbc.sql(sql).param("u", userId.value).query(summaryMapper).list().filterNotNull()

    private fun count(sql: String, userId: UserId): Int =
        jdbc.sql(sql).param("u", userId.value).query(Int::class.java).single()

    private fun exists(sql: String, a: UserId, b: UserId): Boolean =
        jdbc.sql(sql).param("a", a.value).param("b", b.value).query(Boolean::class.java).single()
}

/** `id` and `nickname` of a user row. */
internal val summaryMapper = RowMapper { rs, _ -> UserSummary(UserId(rs.getString("id")), rs.getString("nickname")) }

/** Sorted for the app: by nickname, ignoring case (ties, which only differ in case forms, by id). */
internal fun List<UserSummary>.sortedByNickname(): List<UserSummary> =
    sortedWith(compareBy<UserSummary, String>(String.CASE_INSENSITIVE_ORDER) { it.nickname }.thenBy { it.id.value })
