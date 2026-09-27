package app.hovanki.client.network

import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.CreateGroupRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameInvite
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.GroupView
import app.hovanki.shared.protocol.GroupsResponse
import app.hovanki.shared.protocol.Inbox
import app.hovanki.shared.protocol.InviteId
import app.hovanki.shared.protocol.SendFriendRequest
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserSummary
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpSocialApiTest {
    private val bo = UserSummary(UserId("u2"), "bo")
    private val friends = FriendsResponse(friends = listOf(bo))
    private val groups = GroupsResponse(listOf(GroupView(GroupId("g1"), "Park", UserId("u1"), listOf(bo), 5)))
    private val inbox = Inbox(
        invites = listOf(
            GameInvite(InviteId("i1"), GameId("game1"), "ABC234", bo, createdAtMillis = 1, expiresAtMillis = 2),
        ),
    )

    private lateinit var server: MockServer

    private fun api(handler: MockRequestHandler): HttpSocialApi {
        server = MockServer(handler)
        return HttpSocialApi(server.client, server.serverUrl)
    }

    /** Answers like the server: the caller's friends, groups or inbox, depending on the route. */
    private fun api(): HttpSocialApi = api { request ->
        val path = request.url.encodedPath
        when {
            path.startsWith("/api/v1/groups") -> jsonOf(groups)
            path.startsWith("/api/v1/me/") -> jsonOf(inbox)
            else -> jsonOf(friends)
        }
    }

    @Test
    fun friendCallsUseTheirPaths() = runTest {
        val api = api()

        assertEquals(friends, api.friends("t"))
        assertEquals(friends, api.sendFriendRequest("t", SendFriendRequest(nickname = "bo")))
        assertEquals(friends, api.sendFriendRequest("t", SendFriendRequest(userId = UserId("u2"))))
        assertEquals(friends, api.acceptFriendRequest("t", UserId("u2")))
        assertEquals(friends, api.declineFriendRequest("t", UserId("u2")))
        assertEquals(friends, api.removeFriend("t", UserId("u2")))
        assertEquals(friends, api.block("t", UserId("u2")))
        assertEquals(friends, api.unblock("t", UserId("u2")))

        assertEquals(
            listOf(
                HttpMethod.Get to "/api/v1/friends",
                HttpMethod.Post to "/api/v1/friends/requests",
                HttpMethod.Post to "/api/v1/friends/requests",
                HttpMethod.Post to "/api/v1/friends/requests/u2/accept",
                HttpMethod.Post to "/api/v1/friends/requests/u2/decline",
                HttpMethod.Post to "/api/v1/friends/u2/remove",
                HttpMethod.Post to "/api/v1/users/u2/block",
                HttpMethod.Post to "/api/v1/users/u2/unblock",
            ),
            server.recorded.map { it.method to it.path },
        )
        assertEquals(List(8) { "Bearer t" }, server.recorded.map { it.authorization })
        assertEquals("""{"nickname":"bo"}""", server.recorded[1].body)
        assertEquals("""{"userId":"u2"}""", server.recorded[2].body)
        assertEquals("", server.recorded[3].body)
    }

    @Test
    fun groupCallsUseTheirPaths() = runTest {
        val api = api()

        assertEquals(groups, api.groups("t"))
        assertEquals(groups, api.createGroup("t", CreateGroupRequest("Park", listOf(UserId("u2")))))
        assertEquals(groups, api.addGroupMembers("t", GroupId("g1"), listOf(UserId("u3"))))
        assertEquals(groups, api.removeGroupMember("t", GroupId("g1"), UserId("u3")))
        assertEquals(groups, api.renameGroup("t", GroupId("g1"), "Old park"))
        assertEquals(groups, api.deleteGroup("t", GroupId("g1")))

        assertEquals(
            listOf(
                HttpMethod.Get to "/api/v1/groups",
                HttpMethod.Post to "/api/v1/groups",
                HttpMethod.Post to "/api/v1/groups/g1/members",
                HttpMethod.Post to "/api/v1/groups/g1/members/u3/remove",
                HttpMethod.Post to "/api/v1/groups/g1/rename",
                HttpMethod.Post to "/api/v1/groups/g1/delete",
            ),
            server.recorded.map { it.method to it.path },
        )
        assertEquals(List(6) { "Bearer t" }, server.recorded.map { it.authorization })
        assertEquals(
            listOf(
                "",
                """{"name":"Park","memberIds":["u2"]}""",
                """{"userIds":["u3"]}""",
                "",
                """{"name":"Old park"}""",
                "",
            ),
            server.recorded.map { it.body },
        )
    }

    @Test
    fun inboxCallsUseTheirPaths() = runTest {
        val api = api()

        assertEquals(inbox, api.inbox("t"))
        assertEquals(inbox, api.dismissInvite("t", InviteId("i1")))

        assertEquals(
            listOf(HttpMethod.Get to "/api/v1/me/inbox", HttpMethod.Post to "/api/v1/me/invites/i1/dismiss"),
            server.recorded.map { it.method to it.path },
        )
        assertEquals(List(2) { "Bearer t" }, server.recorded.map { it.authorization })
    }

    @Test
    fun rejectionsKeepTheirReason() = runTest {
        val cases = listOf(
            HttpStatusCode.NotFound to ApiError(ErrorCode.NOT_FOUND, "No such user", ErrorReason.USER_NOT_FOUND),
            HttpStatusCode.Forbidden to ApiError(ErrorCode.FORBIDDEN, "Friends only", ErrorReason.NOT_FRIENDS),
            HttpStatusCode.Conflict to ApiError(ErrorCode.WRONG_STATE, "Blocked", ErrorReason.BLOCKED_BY_YOU),
            HttpStatusCode.Unauthorized to ApiError(ErrorCode.UNAUTHORIZED, "Log in", ErrorReason.SESSION_EXPIRED),
        )
        for ((status, error) in cases) {
            val api = api { apiError(status, error) }

            val exception = assertFailsWith<ApiException> {
                api.sendFriendRequest("t", SendFriendRequest(nickname = "nobody"))
            }

            assertEquals(status.value, exception.status)
            assertEquals(error.reason, exception.reason)
        }
    }

    @Test
    fun rateLimitSaysWhenToTryAgain() = runTest {
        val error = ApiError(ErrorCode.WRONG_STATE, "Too many requests", ErrorReason.TOO_MANY_REQUESTS)
        val api = api { apiError(HttpStatusCode.TooManyRequests, error, retryAfter = 120) }

        val exception = assertFailsWith<ApiException> { api.sendFriendRequest("t", SendFriendRequest("bo")) }

        assertEquals(429, exception.status)
        assertEquals(ErrorReason.TOO_MANY_REQUESTS, exception.reason)
        assertEquals(120L, exception.retryAfterSeconds)
    }
}
