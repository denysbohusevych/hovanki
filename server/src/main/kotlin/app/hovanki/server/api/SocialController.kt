package app.hovanki.server.api

import app.hovanki.server.social.FriendService
import app.hovanki.server.social.GroupService
import app.hovanki.shared.protocol.AddGroupMembersRequest
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.CreateGroupRequest
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.GroupsResponse
import app.hovanki.shared.protocol.RenameGroupRequest
import app.hovanki.shared.protocol.SendFriendRequest
import app.hovanki.shared.protocol.UserId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Friends, blocks and groups: account token with a confirmed email on every route. All logic lives in
 * [FriendService] and [GroupService]; each call answers with the caller's fresh list.
 */
@RestController
class SocialController(private val friends: FriendService, private val groups: GroupService) {
    @GetMapping(ApiRoutes.FRIENDS)
    fun friends(user: AuthenticatedUser): FriendsResponse = friends.friends(user.userId)

    @PostMapping(ApiRoutes.FRIEND_REQUESTS)
    fun sendFriendRequest(user: AuthenticatedUser, @RequestBody request: SendFriendRequest): FriendsResponse =
        friends.sendRequest(user.userId, request)

    @PostMapping(ApiRoutes.FRIEND_REQUEST_ACCEPT)
    fun acceptFriendRequest(user: AuthenticatedUser, @PathVariable userId: String): FriendsResponse =
        friends.accept(user.userId, UserId(userId))

    @PostMapping(ApiRoutes.FRIEND_REQUEST_DECLINE)
    fun declineFriendRequest(user: AuthenticatedUser, @PathVariable userId: String): FriendsResponse =
        friends.decline(user.userId, UserId(userId))

    @PostMapping(ApiRoutes.FRIEND_REMOVE)
    fun removeFriend(user: AuthenticatedUser, @PathVariable userId: String): FriendsResponse =
        friends.remove(user.userId, UserId(userId))

    @PostMapping(ApiRoutes.USER_BLOCK)
    fun block(user: AuthenticatedUser, @PathVariable userId: String): FriendsResponse =
        friends.block(user.userId, UserId(userId))

    @PostMapping(ApiRoutes.USER_UNBLOCK)
    fun unblock(user: AuthenticatedUser, @PathVariable userId: String): FriendsResponse =
        friends.unblock(user.userId, UserId(userId))

    @GetMapping(ApiRoutes.GROUPS)
    fun groups(user: AuthenticatedUser): GroupsResponse = groups.groups(user.userId)

    @PostMapping(ApiRoutes.GROUPS)
    fun createGroup(user: AuthenticatedUser, @RequestBody request: CreateGroupRequest): GroupsResponse =
        groups.create(user.userId, request)

    @PostMapping(ApiRoutes.GROUP_MEMBERS)
    fun addGroupMembers(
        user: AuthenticatedUser,
        @PathVariable groupId: String,
        @RequestBody request: AddGroupMembersRequest,
    ): GroupsResponse = groups.addMembers(user.userId, GroupId(groupId), request)

    /** The owner removes a member; a member removes themselves (leaves). */
    @PostMapping(ApiRoutes.GROUP_MEMBER_REMOVE)
    fun removeGroupMember(
        user: AuthenticatedUser,
        @PathVariable groupId: String,
        @PathVariable userId: String,
    ): GroupsResponse = groups.removeMember(user.userId, GroupId(groupId), UserId(userId))

    @PostMapping(ApiRoutes.GROUP_RENAME)
    fun renameGroup(
        user: AuthenticatedUser,
        @PathVariable groupId: String,
        @RequestBody request: RenameGroupRequest,
    ): GroupsResponse = groups.rename(user.userId, GroupId(groupId), request)

    @PostMapping(ApiRoutes.GROUP_DELETE)
    fun deleteGroup(user: AuthenticatedUser, @PathVariable groupId: String): GroupsResponse =
        groups.delete(user.userId, GroupId(groupId))
}
