package app.hovanki.client.social

import app.hovanki.client.network.SocialApi
import app.hovanki.shared.protocol.CreateGroupRequest
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.GroupsResponse
import app.hovanki.shared.protocol.Inbox
import app.hovanki.shared.protocol.InviteId
import app.hovanki.shared.protocol.SendFriendRequest
import app.hovanki.shared.protocol.UserId

/**
 * [SocialApi] whose answers are the test's [friends], [groups] and [inbox] (the server's state after the call);
 * [failWith] makes every call fail. Records every call with its token and arguments.
 */
class FakeSocialApi : SocialApi {
    var friends = FriendsResponse()
    var groups = GroupsResponse()
    var inbox = Inbox()
    var failWith: Exception? = null

    val calls = mutableListOf<String>()

    val inboxCalls: Int get() = calls.count { it.startsWith("inbox ") }

    override suspend fun friends(token: String) = call("friends $token") { friends }

    override suspend fun sendFriendRequest(token: String, request: SendFriendRequest) =
        call("sendFriendRequest $token ${request.nickname} ${request.userId?.value}") { friends }

    override suspend fun acceptFriendRequest(token: String, userId: UserId) =
        call("acceptFriendRequest $token ${userId.value}") { friends }

    override suspend fun declineFriendRequest(token: String, userId: UserId) =
        call("declineFriendRequest $token ${userId.value}") { friends }

    override suspend fun removeFriend(token: String, userId: UserId) =
        call("removeFriend $token ${userId.value}") { friends }

    override suspend fun block(token: String, userId: UserId) = call("block $token ${userId.value}") { friends }

    override suspend fun unblock(token: String, userId: UserId) = call("unblock $token ${userId.value}") { friends }

    override suspend fun groups(token: String) = call("groups $token") { groups }

    override suspend fun createGroup(token: String, request: CreateGroupRequest) =
        call("createGroup $token ${request.name} ${request.memberIds.map { it.value }}") { groups }

    override suspend fun addGroupMembers(token: String, groupId: GroupId, userIds: List<UserId>) =
        call("addGroupMembers $token ${groupId.value} ${userIds.map { it.value }}") { groups }

    override suspend fun removeGroupMember(token: String, groupId: GroupId, userId: UserId) =
        call("removeGroupMember $token ${groupId.value} ${userId.value}") { groups }

    override suspend fun renameGroup(token: String, groupId: GroupId, name: String) =
        call("renameGroup $token ${groupId.value} $name") { groups }

    override suspend fun deleteGroup(token: String, groupId: GroupId) =
        call("deleteGroup $token ${groupId.value}") { groups }

    override suspend fun inbox(token: String) = call("inbox $token") { inbox }

    override suspend fun dismissInvite(token: String, inviteId: InviteId) =
        call("dismissInvite $token ${inviteId.value}") { inbox }

    private fun <T> call(description: String, answer: () -> T): T {
        calls += description
        failWith?.let { throw it }
        return answer()
    }
}
