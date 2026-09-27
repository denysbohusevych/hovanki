package app.hovanki.server.social

import app.hovanki.server.db.getInstant
import app.hovanki.server.db.toTimestamptz
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.GroupView
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserSummary
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

/** A row of `user_groups`. */
data class GroupRecord(val id: GroupId, val name: String, val ownerId: UserId, val createdAt: Instant) {
    // Never the name (user-typed text) in logs.
    override fun toString(): String = "GroupRecord(${id.value})"
}

/**
 * `user_groups` and `group_members`. The owner is a member too (a row in `group_members`). Members are listed
 * longest-standing first; those who joined at the same moment (a group created with its members) owner first, then
 * by nickname: that order also picks the next owner.
 */
@Repository
class GroupRepository(private val jdbc: JdbcClient) {
    fun insert(group: GroupRecord) {
        jdbc.sql("INSERT INTO user_groups (id, name, owner_id, created_at) VALUES (:id, :name, :owner, :t)")
            .param("id", group.id.value)
            .param("name", group.name)
            .param("owner", group.ownerId.value)
            .param("t", group.createdAt.toTimestamptz())
            .update()
    }

    fun find(id: GroupId): GroupRecord? = jdbc.sql("SELECT * FROM user_groups WHERE id = :id")
        .param("id", id.value)
        .query(groupMapper)
        .optional()
        .orElse(null)

    /**
     * Inside a transaction: the group, its row locked until the transaction ends, so changes of one group run one
     * after the other. Lock the users first ([app.hovanki.server.account.UserRepository.lock]), then the group.
     */
    fun lock(id: GroupId): GroupRecord? = jdbc.sql("SELECT * FROM user_groups WHERE id = :id FOR NO KEY UPDATE")
        .param("id", id.value)
        .query(groupMapper)
        .optional()
        .orElse(null)

    fun rename(id: GroupId, name: String) {
        jdbc.sql("UPDATE user_groups SET name = :name WHERE id = :id")
            .param("id", id.value)
            .param("name", name)
            .update()
    }

    fun setOwner(id: GroupId, ownerId: UserId) {
        jdbc.sql("UPDATE user_groups SET owner_id = :owner WHERE id = :id")
            .param("id", id.value)
            .param("owner", ownerId.value)
            .update()
    }

    /** The group and its member rows. */
    fun delete(id: GroupId): Boolean =
        jdbc.sql("DELETE FROM user_groups WHERE id = :id").param("id", id.value).update() > 0

    fun countOwned(ownerId: UserId): Int = jdbc.sql("SELECT count(*) FROM user_groups WHERE owner_id = :u")
        .param("u", ownerId.value)
        .query(Int::class.java)
        .single()

    fun ownedGroupIds(ownerId: UserId): List<GroupId> =
        jdbc.sql("SELECT id FROM user_groups WHERE owner_id = :u ORDER BY created_at, id")
            .param("u", ownerId.value)
            .query(String::class.java)
            .list()
            .mapNotNull { it?.let(::GroupId) }

    /** Nothing happens for a member. */
    fun addMember(groupId: GroupId, userId: UserId, now: Instant) {
        jdbc.sql(
            "INSERT INTO group_members (group_id, user_id, joined_at) VALUES (:g, :u, :t) ON CONFLICT DO NOTHING",
        )
            .param("g", groupId.value)
            .param("u", userId.value)
            .param("t", now.toTimestamptz())
            .update()
    }

    /** Only the member row: handing the group over or deleting it is the caller's job. */
    fun removeMember(groupId: GroupId, userId: UserId): Boolean =
        jdbc.sql("DELETE FROM group_members WHERE group_id = :g AND user_id = :u")
            .param("g", groupId.value)
            .param("u", userId.value)
            .update() > 0

    /** Removes [userId] from every group [ownerId] owns (a block); returns how many. */
    fun removeFromOwnedGroups(ownerId: UserId, userId: UserId): Int = jdbc.sql(
        """
        DELETE FROM group_members m USING user_groups g
        WHERE g.id = m.group_id AND g.owner_id = :owner AND m.user_id = :u AND m.user_id <> g.owner_id
        """.trimIndent(),
    )
        .param("owner", ownerId.value)
        .param("u", userId.value)
        .update()

    fun isMember(groupId: GroupId, userId: UserId): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM group_members WHERE group_id = :g AND user_id = :u)")
            .param("g", groupId.value)
            .param("u", userId.value)
            .query(Boolean::class.java)
            .single()

    fun countMembers(groupId: GroupId): Int = jdbc.sql("SELECT count(*) FROM group_members WHERE group_id = :g")
        .param("g", groupId.value)
        .query(Int::class.java)
        .single()

    /** Everyone in the group, the owner included, longest-standing first. */
    fun memberIds(groupId: GroupId): List<UserId> = members(groupId).map { it.id }

    /** Everyone in the group, the owner included, longest-standing first. */
    fun members(groupId: GroupId): List<UserSummary> = jdbc.sql(
        """
        SELECT u.id, u.nickname
        FROM group_members m JOIN user_groups g ON g.id = m.group_id JOIN users u ON u.id = m.user_id
        WHERE m.group_id = :g
        ORDER BY $MEMBER_ORDER
        """.trimIndent(),
    )
        .param("g", groupId.value)
        .query(summaryMapper)
        .list()
        .filterNotNull()

    /** The groups [userId] is in, with their members, unsorted. */
    fun groupsOf(userId: UserId): List<GroupView> = jdbc.sql(
        """
        SELECT g.id AS group_id, g.name, g.owner_id, g.created_at, u.id, u.nickname
        FROM group_members mine
        JOIN user_groups g ON g.id = mine.group_id
        JOIN group_members m ON m.group_id = g.id
        JOIN users u ON u.id = m.user_id
        WHERE mine.user_id = :u
        ORDER BY g.id, $MEMBER_ORDER
        """.trimIndent(),
    )
        .param("u", userId.value)
        .query { rs, _ ->
            val group = GroupRecord(
                id = GroupId(rs.getString("group_id")),
                name = rs.getString("name"),
                ownerId = UserId(rs.getString("owner_id")),
                createdAt = rs.getInstant("created_at"),
            )
            group to UserSummary(UserId(rs.getString("id")), rs.getString("nickname"))
        }
        .list()
        .groupBy({ it.first }, { it.second })
        .map { (group, members) ->
            GroupView(group.id, group.name, group.ownerId, members, group.createdAt.toEpochMilli())
        }

    private companion object {
        /** Longest-standing first; at the same moment the owner first, then by nickname. */
        const val MEMBER_ORDER = "m.joined_at, (m.user_id = g.owner_id) DESC, u.nickname_key"

        val groupMapper = RowMapper { rs, _ ->
            GroupRecord(
                id = GroupId(rs.getString("id")),
                name = rs.getString("name"),
                ownerId = UserId(rs.getString("owner_id")),
                createdAt = rs.getInstant("created_at"),
            )
        }
    }
}
