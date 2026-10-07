package app.hovanki.client.network

import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.CityRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameHistoryResponse
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameRoute
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LeaderboardEntry
import app.hovanki.shared.protocol.LeaderboardResponse
import app.hovanki.shared.protocol.LeaderboardScope
import app.hovanki.shared.protocol.PlayerStats
import app.hovanki.shared.protocol.PrivacyRequest
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.RoutePoint
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.shrinkingZone
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpHistoryApiTest {
    private val stats = PlayerStats(games = 2, distanceMeters = 1500.0)
    private val route = GameRoute(
        gameId = GameId("g1"),
        role = Role.SEEKER,
        zone = shrinkingZone(GeoPoint(50.45, 30.52)),
        startedAtMillis = 1,
        finishedAtMillis = 2,
        points = listOf(RoutePoint(50.45, 30.52, 5.0, 1)),
        expiresAtMillis = 3,
    )
    private val leaderboard = LeaderboardResponse(
        scope = LeaderboardScope.FRIENDS,
        entries = listOf(LeaderboardEntry(1, UserId("u1"), "anna", 120, isMe = true)),
        me = LeaderboardEntry(1, UserId("u1"), "anna", 120, isMe = true),
        rankChange = 2,
    )
    private val queries = mutableListOf<String>()

    private val server = MockServer { request ->
        queries += request.url.encodedQuery
        val path = request.url.encodedPath
        when {
            path == "/api/v1/me/stats" -> jsonOf(stats)
            path == "/api/v1/me/games" -> jsonOf(GameHistoryResponse(nextBefore = 7))
            path == "/api/v1/me/games/g1/route" -> jsonOf(route)
            path.endsWith("/route/delete") -> noContent()
            path == "/api/v1/me/leaderboard" -> jsonOf(leaderboard)
            else -> apiError(HttpStatusCode.NotFound, ApiError(ErrorCode.NOT_FOUND, "No route"))
        }
    }
    private val api = HttpHistoryApi(server.client, server.serverUrl)

    @Test
    fun theCallerOwnHistoryWithTheAccountToken() = runTest {
        assertEquals(stats, api.stats("t"))
        assertEquals(7L, api.games("t").nextBefore)
        api.games("t", before = 1_700_000_000_000)
        assertEquals(route, api.route("t", GameId("g1")))
        api.deleteRoute("t", GameId("g1"))

        assertEquals(
            listOf(
                HttpMethod.Get to "/api/v1/me/stats",
                HttpMethod.Get to "/api/v1/me/games",
                HttpMethod.Get to "/api/v1/me/games",
                HttpMethod.Get to "/api/v1/me/games/g1/route",
                HttpMethod.Post to "/api/v1/me/games/g1/route/delete",
            ),
            server.recorded.map { it.method to it.path },
        )
        assertEquals(listOf("", "", "before=1700000000000", "", ""), queries)
        assertEquals(setOf("Bearer t"), server.recorded.map { it.authorization }.toSet())
    }

    @Test
    fun theLeaderboardOfAScope() = runTest {
        assertEquals(leaderboard, api.leaderboard("t", LeaderboardScope.FRIENDS))

        val sent = server.recorded.single()
        assertEquals(HttpMethod.Get to "/api/v1/me/leaderboard", sent.method to sent.path)
        assertEquals(listOf("scope=FRIENDS"), queries)
        assertEquals("Bearer t", sent.authorization)
    }

    @Test
    fun noSavedRouteIsA404() = runTest {
        val error = assertFailsWith<ApiException> { api.route("t", GameId("other")) }
        assertEquals(404, error.status)
    }

    @Test
    fun savingRoutesIsAPrivacyChoice() = runTest {
        val profile = UserProfile(UserId("u1"), "anna", "anna@example.org", true, 5, saveRoutes = true)
        val privacy = MockServer { jsonOf(profile) }

        assertEquals(profile, HttpAccountApi(privacy.client, privacy.serverUrl).setSaveRoutes("t", enabled = true))

        val sent = privacy.recorded.single()
        assertEquals(HttpMethod.Post to "/api/v1/me/privacy", sent.method to sent.path)
        assertEquals(PrivacyRequest(saveRoutes = true), protocolJson.decodeFromString<PrivacyRequest>(sent.body))
    }

    @Test
    fun theCityIsTheAccountsChoice() = runTest {
        val profile = UserProfile(UserId("u1"), "anna", "anna@example.org", true, 5, city = "lviv")
        val server = MockServer { jsonOf(profile) }

        assertEquals(profile, HttpAccountApi(server.client, server.serverUrl).setCity("t", "lviv"))

        val sent = server.recorded.single()
        assertEquals(HttpMethod.Post to "/api/v1/me/city", sent.method to sent.path)
        assertEquals(CityRequest("lviv"), protocolJson.decodeFromString<CityRequest>(sent.body))
    }
}
