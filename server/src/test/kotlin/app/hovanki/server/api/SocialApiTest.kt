package app.hovanki.server.api

import app.hovanki.server.MutableClock
import app.hovanki.server.account.AccountSessionRepository
import app.hovanki.server.account.AccountTestConfig
import app.hovanki.server.account.PasswordHasher
import app.hovanki.server.account.UserRepository
import app.hovanki.server.game.IdGenerator
import app.hovanki.server.social.TestUser
import app.hovanki.server.social.TestUsers
import app.hovanki.shared.protocol.AddGroupMembersRequest
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.CreateGroupRequest
import app.hovanki.shared.protocol.DeleteAccountRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.GroupsResponse
import app.hovanki.shared.protocol.RenameGroupRequest
import app.hovanki.shared.protocol.SendFriendRequest
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.protocolJson
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Friends, blocks and groups over HTTP with the shared DTOs and routes, as the app uses them. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountTestConfig::class)
class SocialApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired users: UserRepository,
    @Autowired sessions: AccountSessionRepository,
    @Autowired hasher: PasswordHasher,
    @Autowired ids: IdGenerator,
    @Autowired jdbc: JdbcClient,
    @Autowired clock: MutableClock,
) {
    private val testUsers = TestUsers(users, sessions, hasher, ids, jdbc, clock)

    @Test
    fun everyRouteNeedsAnAccount() {
        val someone = UserId("someone")
        val group = GroupId("group")
        val calls: List<(String?) -> Response> = listOf(
            { get(ApiRoutes.FRIENDS, it) },
            { post(ApiRoutes.FRIEND_REQUESTS, SendFriendRequest(userId = someone).toJson(), it) },
            { post(ApiRoutes.friendRequestAccept(someone), null, it) },
            { post(ApiRoutes.friendRequestDecline(someone), null, it) },
            { post(ApiRoutes.friendRemove(someone), null, it) },
            { post(ApiRoutes.userBlock(someone), null, it) },
            { post(ApiRoutes.userUnblock(someone), null, it) },
            { get(ApiRoutes.GROUPS, it) },
            { post(ApiRoutes.GROUPS, CreateGroupRequest("Group").toJson(), it) },
            { post(ApiRoutes.groupMembers(group), AddGroupMembersRequest(listOf(someone)).toJson(), it) },
            { post(ApiRoutes.groupMemberRemove(group, someone), null, it) },
            { post(ApiRoutes.groupRename(group), RenameGroupRequest("Group").toJson(), it) },
            { post(ApiRoutes.groupDelete(group), null, it) },
        )
        for (call in calls) {
            call(null).error(401, ErrorCode.UNAUTHORIZED)
            call("nope").error(401, ErrorCode.UNAUTHORIZED, ErrorReason.SESSION_EXPIRED)
        }
    }

    @Test
    fun accountsWithoutAConfirmedEmailTakePart() {
        // Confirming the email is optional: such accounts find others, are found, befriend, group and block.
        val alice = testUsers.create(verified = false)
        val bob = testUsers.create(verified = false)
        val carol = testUsers.create(verified = false)

        post(ApiRoutes.FRIEND_REQUESTS, SendFriendRequest(nickname = bob.nickname).toJson(), alice.token).expect(200)
        val friends = post(ApiRoutes.friendRequestAccept(alice.id), null, bob.token).ok<FriendsResponse>()
        assertEquals(FriendsResponse(friends = listOf(alice.summary)), friends)
        val asked = post(ApiRoutes.FRIEND_REQUESTS, SendFriendRequest(userId = carol.id).toJson(), alice.token)
            .ok<FriendsResponse>()
        assertEquals(listOf(carol.summary), asked.outgoing)

        val group = post(ApiRoutes.GROUPS, CreateGroupRequest("Crew", listOf(bob.id)).toJson(), alice.token)
            .ok<GroupsResponse>().groups.single()
        assertEquals(listOf(alice.summary, bob.summary), group.members)
        assertEquals(listOf(group), get(ApiRoutes.GROUPS, bob.token).ok<GroupsResponse>().groups)

        val blocked = post(ApiRoutes.userBlock(carol.id), null, alice.token).ok<FriendsResponse>()
        assertEquals(listOf(carol.summary), blocked.blocked)
    }

    @Test
    fun becomeFriendsAndPart() {
        val alice = testUsers.create()
        val bob = testUsers.create()

        val sent = post(ApiRoutes.FRIEND_REQUESTS, SendFriendRequest(nickname = bob.nickname).toJson(), alice.token)
            .ok<FriendsResponse>()
        assertEquals(FriendsResponse(outgoing = listOf(bob.summary)), sent)
        assertEquals(FriendsResponse(incoming = listOf(alice.summary)), get(ApiRoutes.FRIENDS, bob.token).ok())

        post(ApiRoutes.friendRequestAccept(bob.id), null, alice.token).error(404, ErrorCode.NOT_FOUND)
        val accepted = post(ApiRoutes.friendRequestAccept(alice.id), null, bob.token).ok<FriendsResponse>()
        assertEquals(FriendsResponse(friends = listOf(alice.summary)), accepted)

        assertEquals(FriendsResponse(), post(ApiRoutes.friendRemove(bob.id), null, alice.token).ok())
        assertEquals(FriendsResponse(), get(ApiRoutes.FRIENDS, bob.token).ok())
    }

    @Test
    fun declineAndWithdraw() {
        val alice = testUsers.create()
        val bob = testUsers.create()
        val request = SendFriendRequest(userId = bob.id).toJson()

        post(ApiRoutes.FRIEND_REQUESTS, request, alice.token).expect(200)
        assertEquals(FriendsResponse(), post(ApiRoutes.friendRequestDecline(alice.id), null, bob.token).ok())
        post(ApiRoutes.FRIEND_REQUESTS, request, alice.token).expect(200)
        assertEquals(FriendsResponse(), post(ApiRoutes.friendRequestDecline(bob.id), null, alice.token).ok())
        assertEquals(FriendsResponse(), get(ApiRoutes.FRIENDS, bob.token).ok())
    }

    @Test
    fun friendRequestErrors() {
        val alice = testUsers.create()
        val bob = testUsers.create()
        fun send(request: SendFriendRequest) = post(ApiRoutes.FRIEND_REQUESTS, request.toJson(), alice.token)

        send(SendFriendRequest()).error(400, ErrorCode.BAD_REQUEST)
        send(SendFriendRequest(nickname = bob.nickname, userId = bob.id)).error(400, ErrorCode.BAD_REQUEST)
        send(SendFriendRequest(userId = alice.id)).error(400, ErrorCode.BAD_REQUEST)
        send(
            SendFriendRequest(nickname = "${bob.nickname}x"),
        ).error(404, ErrorCode.NOT_FOUND, ErrorReason.USER_NOT_FOUND)
        post(ApiRoutes.FRIEND_REQUESTS, """{"nickname": 42, "userId": {}}""", alice.token)
            .error(400, ErrorCode.BAD_REQUEST)

        post(ApiRoutes.userBlock(bob.id), null, alice.token).expect(200)
        send(SendFriendRequest(userId = bob.id)).error(409, ErrorCode.WRONG_STATE, ErrorReason.BLOCKED_BY_YOU)
    }

    @Test
    fun blockAndUnblock() {
        val alice = testUsers.create()
        val bob = testUsers.create()
        post(ApiRoutes.FRIEND_REQUESTS, SendFriendRequest(userId = bob.id).toJson(), alice.token).expect(200)
        post(ApiRoutes.friendRequestAccept(alice.id), null, bob.token).expect(200)

        val blocked = post(ApiRoutes.userBlock(bob.id), null, alice.token).ok<FriendsResponse>()
        assertEquals(FriendsResponse(blocked = listOf(bob.summary)), blocked)
        assertEquals(FriendsResponse(), get(ApiRoutes.FRIENDS, bob.token).ok())
        // Bob's request looks sent to him; Alice never sees it.
        val bobs = post(ApiRoutes.FRIEND_REQUESTS, SendFriendRequest(userId = alice.id).toJson(), bob.token)
        assertEquals(FriendsResponse(outgoing = listOf(alice.summary)), bobs.ok())
        assertEquals(blocked, get(ApiRoutes.FRIENDS, alice.token).ok())

        post(ApiRoutes.userBlock(alice.id), null, alice.token).error(400, ErrorCode.BAD_REQUEST)
        post(ApiRoutes.userBlock(UserId("nobody")), null, alice.token)
            .error(404, ErrorCode.NOT_FOUND, ErrorReason.USER_NOT_FOUND)

        assertEquals(FriendsResponse(), post(ApiRoutes.userUnblock(bob.id), null, alice.token).ok())
        assertEquals(FriendsResponse(), get(ApiRoutes.FRIENDS, bob.token).ok())
    }

    @Test
    fun groups() {
        val owner = testUsers.create()
        val (amy, bob) = List(2) { friendOf(owner) }
        val stranger = testUsers.create()

        post(ApiRoutes.GROUPS, CreateGroupRequest(" ", listOf(amy.id)).toJson(), owner.token)
            .error(400, ErrorCode.BAD_REQUEST, ErrorReason.INVALID_GROUP_NAME)
        post(ApiRoutes.GROUPS, CreateGroupRequest("Crew", listOf(stranger.id)).toJson(), owner.token)
            .error(403, ErrorCode.FORBIDDEN, ErrorReason.NOT_FRIENDS)
        val created = post(ApiRoutes.GROUPS, CreateGroupRequest("Crew", listOf(amy.id)).toJson(), owner.token)
            .ok<GroupsResponse>()
        val group = created.groups.single()
        assertEquals("Crew", group.name)
        assertEquals(listOf(owner.summary, amy.summary), group.members)
        assertEquals(created, get(ApiRoutes.GROUPS, amy.token).ok())

        val added = post(ApiRoutes.groupMembers(group.id), AddGroupMembersRequest(listOf(bob.id)).toJson(), owner.token)
            .ok<GroupsResponse>()
        assertEquals(setOf(owner.id, amy.id, bob.id), added.groups.single().members.map { it.id }.toSet())
        post(ApiRoutes.groupRename(group.id), RenameGroupRequest("Mine").toJson(), amy.token)
            .error(403, ErrorCode.FORBIDDEN, ErrorReason.NOT_GROUP_OWNER)
        assertEquals(
            "Renamed",
            post(ApiRoutes.groupRename(group.id), RenameGroupRequest("Renamed").toJson(), owner.token)
                .ok<GroupsResponse>().groups.single().name,
        )

        post(ApiRoutes.groupMemberRemove(group.id, bob.id), null, amy.token)
            .error(403, ErrorCode.FORBIDDEN, ErrorReason.NOT_GROUP_OWNER)
        post(ApiRoutes.groupMemberRemove(group.id, stranger.id), null, owner.token)
            .error(403, ErrorCode.FORBIDDEN, ErrorReason.NOT_GROUP_MEMBER)
        post(ApiRoutes.groupMemberRemove(group.id, bob.id), null, owner.token).expect(200)
        assertEquals(GroupsResponse(), post(ApiRoutes.groupMemberRemove(group.id, amy.id), null, amy.token).ok())
        // Outsiders and unknown groups look the same.
        post(ApiRoutes.groupDelete(group.id), null, stranger.token).error(404, ErrorCode.NOT_FOUND)
        post(ApiRoutes.groupDelete(GroupId("nothing")), null, owner.token).error(404, ErrorCode.NOT_FOUND)

        assertEquals(GroupsResponse(), post(ApiRoutes.groupDelete(group.id), null, owner.token).ok())
        assertEquals(GroupsResponse(), get(ApiRoutes.GROUPS, owner.token).ok())
    }

    @Test
    fun deletingTheAccountHandsTheGroupOver() {
        val owner = testUsers.create()
        val friend = friendOf(owner)
        val group = post(ApiRoutes.GROUPS, CreateGroupRequest("Crew", listOf(friend.id)).toJson(), owner.token)
            .ok<GroupsResponse>().groups.single()

        post(ApiRoutes.ME_DELETE, DeleteAccountRequest(TestUsers.PASSWORD).toJson(), owner.token).expect(204)

        val handedOver = get(ApiRoutes.GROUPS, friend.token).ok<GroupsResponse>().groups.single()
        assertEquals(group.copy(ownerId = friend.id, members = listOf(friend.summary)), handedOver)
        assertEquals(FriendsResponse(), get(ApiRoutes.FRIENDS, friend.token).ok())
    }

    private fun friendOf(user: TestUser): TestUser {
        val friend = testUsers.create()
        post(ApiRoutes.FRIEND_REQUESTS, SendFriendRequest(userId = friend.id).toJson(), user.token).expect(200)
        post(ApiRoutes.friendRequestAccept(user.id), null, friend.token).expect(200)
        return friend
    }

    private data class Response(val status: Int, val body: String) {
        fun expect(status: Int) = also { assertEquals(status, this.status, body) }

        inline fun <reified T> ok(): T = protocolJson.decodeFromString(expect(200).body)

        /** An [ApiError] with [code] and exactly [reason] (null: none). */
        fun error(status: Int, code: ErrorCode, reason: ErrorReason? = null) {
            val error = protocolJson.decodeFromString<ApiError>(expect(status).body)
            assertEquals(code, error.code, body)
            if (reason == null) assertNull(error.reason, body) else assertEquals(reason, error.reason, body)
        }
    }

    private inline fun <reified T> T.toJson(): String = protocolJson.encodeToString(this)

    private fun post(path: String, json: String?, token: String?): Response {
        val response = mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            if (json != null) content = json
            if (token != null) header("Authorization", "${ApiRoutes.AUTH_SCHEME} $token")
        }.andReturn().response
        return Response(response.status, response.getContentAsString(Charsets.UTF_8))
    }

    private fun get(path: String, token: String?): Response {
        val response = mvc.get(path) {
            if (token != null) header("Authorization", "${ApiRoutes.AUTH_SCHEME} $token")
        }.andReturn().response
        return Response(response.status, response.getContentAsString(Charsets.UTF_8))
    }
}
