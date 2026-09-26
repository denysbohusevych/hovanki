package app.hovanki.server.social

import app.hovanki.server.account.BeforeAccountDeletion
import app.hovanki.server.account.UserRepository
import app.hovanki.server.game.IdGenerator
import app.hovanki.server.social.SocialErrors.groupNotFound
import app.hovanki.server.social.SocialErrors.invalidGroupName
import app.hovanki.server.social.SocialErrors.limitReached
import app.hovanki.server.social.SocialErrors.notFriends
import app.hovanki.server.social.SocialErrors.notGroupMember
import app.hovanki.server.social.SocialErrors.notGroupOwner
import app.hovanki.shared.protocol.AddGroupMembersRequest
import app.hovanki.shared.protocol.CreateGroupRequest
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.GroupView
import app.hovanki.shared.protocol.GroupsResponse
import app.hovanki.shared.protocol.RenameGroupRequest
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.rules.GroupRules
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * Groups of friends ("компании", docs/adr/0004-accounts-friends-chat.md): the owner adds their friends and removes
 * members, renames and deletes the group; any member may leave. When the owner leaves (or deletes the account), the
 * longest-standing member takes the group over; when the last member leaves, the group is gone. Every command answers
 * with the caller's fresh [GroupsResponse]. A group the caller is not in answers 404 like one that does not exist.
 *
 * Changes lock the users whose relations they read, then the group ([UserRepository.lock], [GroupRepository.lock]),
 * so the limits hold under parallel requests and a block can't race a new member in.
 */
@Service
class GroupService(
    private val groups: GroupRepository,
    private val users: UserRepository,
    private val friends: FriendRepository,
    private val blocks: BlockRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) : BeforeAccountDeletion {
    private val transactions = TransactionTemplate(transactionManager)

    fun groups(userId: UserId): GroupsResponse = inTransaction { view(userId) }

    /** A new group owned by the caller, with some of their friends. */
    fun create(userId: UserId, request: CreateGroupRequest): GroupsResponse {
        val name = validName(request.name)
        // The owner is a member anyway.
        val memberIds = request.memberIds.distinct() - userId
        if (memberIds.size + 1 > GroupRules.MAX_MEMBERS) throw tooManyMembers()
        return inTransaction {
            users.lock(memberIds + userId)
            if (groups.countOwned(userId) >= GroupRules.MAX_OWNED_GROUPS) {
                throw limitReached("You own ${GroupRules.MAX_OWNED_GROUPS} groups already")
            }
            checkMayJoin(userId, memberIds)
            val now = clock.instant()
            val group = GroupRecord(ids.groupId(), name, userId, now)
            groups.insert(group)
            for (memberId in listOf(userId) + memberIds) groups.addMember(group.id, memberId, now)
            view(userId)
        }
    }

    /** Owner only: adds some of their friends; those in the group already are skipped. */
    fun addMembers(userId: UserId, groupId: GroupId, request: AddGroupMembersRequest): GroupsResponse {
        val requested = request.userIds.distinct()
        if (requested.size > GroupRules.MAX_MEMBERS) throw tooManyMembers()
        return inTransaction {
            users.lock(requested + userId)
            val group = groupOfMember(groupId, userId)
            if (group.ownerId != userId) throw notGroupOwner()
            val newIds = requested - groups.memberIds(groupId).toSet()
            checkMayJoin(userId, newIds)
            if (groups.countMembers(groupId) + newIds.size > GroupRules.MAX_MEMBERS) throw tooManyMembers()
            val now = clock.instant()
            for (memberId in newIds) groups.addMember(groupId, memberId, now)
            view(userId)
        }
    }

    /**
     * The caller leaves ([memberId] is the caller: any member), or the owner removes a member (anyone else:
     * [SocialErrors.notGroupOwner]; not in the group: [SocialErrors.notGroupMember]).
     */
    fun removeMember(userId: UserId, groupId: GroupId, memberId: UserId): GroupsResponse = inTransaction {
        val group = groupOfMember(groupId, userId)
        when {
            memberId == userId -> leave(group, userId)
            group.ownerId != userId -> throw notGroupOwner()
            !groups.removeMember(groupId, memberId) -> throw notGroupMember()
        }
        view(userId)
    }

    /** Owner only. */
    fun rename(userId: UserId, groupId: GroupId, request: RenameGroupRequest): GroupsResponse {
        val name = validName(request.name)
        return inTransaction {
            val group = groupOfMember(groupId, userId)
            if (group.ownerId != userId) throw notGroupOwner()
            groups.rename(groupId, name)
            view(userId)
        }
    }

    /** Owner only: the group is gone for everyone. */
    fun delete(userId: UserId, groupId: GroupId): GroupsResponse = inTransaction {
        val group = groupOfMember(groupId, userId)
        if (group.ownerId != userId) throw notGroupOwner()
        groups.delete(groupId)
        view(userId)
    }

    /**
     * In the account deletion's transaction: the user leaves every group they own, so each goes to its
     * longest-standing other member, or is deleted when the user is alone in it. Their memberships in other groups go
     * with the account (the foreign keys cascade).
     */
    override fun beforeDelete(userId: UserId) {
        users.lock(listOf(userId))
        for (groupId in groups.ownedGroupIds(userId)) groups.lock(groupId)?.let { leave(it, userId) }
    }

    /** Takes [userId] out of [group]; hands it over if they owned it, deletes it if nobody is left. */
    private fun leave(group: GroupRecord, userId: UserId) {
        groups.removeMember(group.id, userId)
        val remaining = groups.memberIds(group.id)
        when {
            remaining.isEmpty() -> groups.delete(group.id)
            group.ownerId == userId -> groups.setOwner(group.id, remaining.first())
        }
    }

    /** The group, locked, if [userId] is in it; otherwise 404, as if there was no such group. */
    private fun groupOfMember(groupId: GroupId, userId: UserId): GroupRecord =
        groups.lock(groupId)?.takeIf { groups.isMember(groupId, userId) } ?: throw groupNotFound()

    /**
     * Only [ownerId]'s friends join their groups. A block ends the friendship, so a friend is never blocked either
     * way; the block check is a second guard.
     */
    private fun checkMayJoin(ownerId: UserId, userIds: List<UserId>) {
        if (userIds.isEmpty()) return
        if (friends.friendsAmong(ownerId, userIds).size < userIds.size ||
            blocks.blockedEitherWayAmong(ownerId, userIds).isNotEmpty()
        ) {
            throw notFriends("Only your friends can join your group")
        }
    }

    private fun validName(name: String): String {
        if (!GroupRules.isValidName(name)) throw invalidGroupName()
        return GroupRules.normalizeName(name)
    }

    private fun tooManyMembers() = limitReached("A group has at most ${GroupRules.MAX_MEMBERS} members")

    /** Sorted by name, ignoring case. */
    private fun view(userId: UserId) = GroupsResponse(
        groups.groupsOf(userId).sortedWith(
            compareBy<GroupView, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.createdAtMillis }
                .thenBy { it.id.value },
        ),
    )

    private fun <T : Any> inTransaction(block: () -> T): T = checkNotNull(transactions.execute { block() })
}
