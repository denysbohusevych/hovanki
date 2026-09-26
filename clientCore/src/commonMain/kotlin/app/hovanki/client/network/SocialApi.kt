package app.hovanki.client.network

import app.hovanki.shared.protocol.AddGroupMembersRequest
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.CreateGroupRequest
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.GroupsResponse
import app.hovanki.shared.protocol.Inbox
import app.hovanki.shared.protocol.InviteId
import app.hovanki.shared.protocol.RenameGroupRequest
import app.hovanki.shared.protocol.SendFriendRequest
import app.hovanki.shared.protocol.UserId
import io.ktor.client.HttpClient

/**
 * Friends, blocks, groups and the inbox (docs/adr/0004-accounts-friends-chat.md). Every call takes the account token
 * of a user with a confirmed email and returns the caller's fresh list, so the UI updates from the response.
 *
 * Throws [ApiException] when the server rejects a call, and I/O or serialization exceptions on network problems.
 */
interface SocialApi {
    suspend fun friends(token: String): FriendsResponse

    /** By exact nickname or by user id; a request to someone who already asked the caller makes them friends. */
    suspend fun sendFriendRequest(token: String, request: SendFriendRequest): FriendsResponse

    suspend fun acceptFriendRequest(token: String, userId: UserId): FriendsResponse

    /** Declines a request from [userId], or withdraws the caller's own request to them. */
    suspend fun declineFriendRequest(token: String, userId: UserId): FriendsResponse

    suspend fun removeFriend(token: String, userId: UserId): FriendsResponse

    suspend fun block(token: String, userId: UserId): FriendsResponse

    suspend fun unblock(token: String, userId: UserId): FriendsResponse

    suspend fun groups(token: String): GroupsResponse

    suspend fun createGroup(token: String, request: CreateGroupRequest): GroupsResponse

    /** Owner only; the users must be the owner's friends. */
    suspend fun addGroupMembers(token: String, groupId: GroupId, userIds: List<UserId>): GroupsResponse

    /** The owner removes a member, or a member removes themselves (leaves). */
    suspend fun removeGroupMember(token: String, groupId: GroupId, userId: UserId): GroupsResponse

    suspend fun renameGroup(token: String, groupId: GroupId, name: String): GroupsResponse

    suspend fun deleteGroup(token: String, groupId: GroupId): GroupsResponse

    /** Game invites and incoming friend requests. */
    suspend fun inbox(token: String): Inbox

    suspend fun dismissInvite(token: String, inviteId: InviteId): Inbox
}

/** [SocialApi] over HTTP/JSON. */
class HttpSocialApi(client: HttpClient, serverUrl: ServerUrl) : SocialApi {
    private val http = HttpSupport(client, serverUrl)

    override suspend fun friends(token: String): FriendsResponse = http.get(ApiRoutes.FRIENDS, token)

    override suspend fun sendFriendRequest(token: String, request: SendFriendRequest): FriendsResponse =
        http.post(ApiRoutes.FRIEND_REQUESTS, token, request)

    override suspend fun acceptFriendRequest(token: String, userId: UserId): FriendsResponse =
        http.post(ApiRoutes.friendRequestAccept(userId), token)

    override suspend fun declineFriendRequest(token: String, userId: UserId): FriendsResponse =
        http.post(ApiRoutes.friendRequestDecline(userId), token)

    override suspend fun removeFriend(token: String, userId: UserId): FriendsResponse =
        http.post(ApiRoutes.friendRemove(userId), token)

    override suspend fun block(token: String, userId: UserId): FriendsResponse =
        http.post(ApiRoutes.userBlock(userId), token)

    override suspend fun unblock(token: String, userId: UserId): FriendsResponse =
        http.post(ApiRoutes.userUnblock(userId), token)

    override suspend fun groups(token: String): GroupsResponse = http.get(ApiRoutes.GROUPS, token)

    override suspend fun createGroup(token: String, request: CreateGroupRequest): GroupsResponse =
        http.post(ApiRoutes.GROUPS, token, request)

    override suspend fun addGroupMembers(token: String, groupId: GroupId, userIds: List<UserId>): GroupsResponse =
        http.post(ApiRoutes.groupMembers(groupId), token, AddGroupMembersRequest(userIds))

    override suspend fun removeGroupMember(token: String, groupId: GroupId, userId: UserId): GroupsResponse =
        http.post(ApiRoutes.groupMemberRemove(groupId, userId), token)

    override suspend fun renameGroup(token: String, groupId: GroupId, name: String): GroupsResponse =
        http.post(ApiRoutes.groupRename(groupId), token, RenameGroupRequest(name))

    override suspend fun deleteGroup(token: String, groupId: GroupId): GroupsResponse =
        http.post(ApiRoutes.groupDelete(groupId), token)

    override suspend fun inbox(token: String): Inbox = http.get(ApiRoutes.INBOX, token)

    override suspend fun dismissInvite(token: String, inviteId: InviteId): Inbox =
        http.post(ApiRoutes.inviteDismiss(inviteId), token)
}
