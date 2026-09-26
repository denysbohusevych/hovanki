package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

// Friends, blocks, groups and game invites (docs/adr/0004-accounts-friends-chat.md). Account routes only.

/** The caller's friends, friend requests both ways and the users they blocked, each sorted by nickname. */
@Serializable
data class FriendsResponse(
    val friends: List<UserSummary> = emptyList(),
    /** Requests to the caller, waiting for accept or decline. */
    val incoming: List<UserSummary> = emptyList(),
    /** The caller's requests, waiting for the other side. */
    val outgoing: List<UserSummary> = emptyList(),
    val blocked: List<UserSummary> = emptyList(),
)

/**
 * A friend request by the exact nickname (case-insensitive) or by the id seen in a game ([PlayerView.userId]). A
 * request to someone who already asked the caller makes them friends right away.
 */
@Serializable
data class SendFriendRequest(val nickname: String? = null, val userId: UserId? = null)

/** A group of friends that plays together: its owner adds and removes members, anyone in it may start a game. */
@Serializable
data class GroupView(
    val id: GroupId,
    val name: String,
    val ownerId: UserId,
    /** Everyone in the group including the owner, longest-standing first. */
    val members: List<UserSummary>,
    val createdAtMillis: Long,
)

/** The groups the caller is in, sorted by name. */
@Serializable
data class GroupsResponse(val groups: List<GroupView> = emptyList())

/** The owner is the caller; [memberIds] must be the caller's friends. */
@Serializable
data class CreateGroupRequest(val name: String, val memberIds: List<UserId> = emptyList())

/** Owner only; the users must be the owner's friends. */
@Serializable
data class AddGroupMembersRequest(val userIds: List<UserId>)

@Serializable
data class RenameGroupRequest(val name: String)

/** An invitation into a game in its lobby; accepting it is joining with [joinCode] while logged in. */
@Serializable
data class GameInvite(
    val id: InviteId,
    val gameId: GameId,
    val joinCode: String,
    val from: UserSummary,
    /** Set when the whole group was invited. */
    val groupId: GroupId? = null,
    val groupName: String? = null,
    val createdAtMillis: Long,
    /** The invite is gone after this (or as soon as the game leaves its lobby). */
    val expiresAtMillis: Long,
)

/** What the start screen polls while it is open: game invites and incoming friend requests. */
@Serializable
data class Inbox(val invites: List<GameInvite> = emptyList(), val friendRequests: List<UserSummary> = emptyList())
