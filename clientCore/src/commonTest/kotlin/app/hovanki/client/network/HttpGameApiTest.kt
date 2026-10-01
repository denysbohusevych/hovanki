package app.hovanki.client.network

import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.Audience
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.ClaimCatchRequest
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.CustomQuestRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.InviteRequest
import app.hovanki.shared.protocol.ItemId
import app.hovanki.shared.protocol.ItemKind
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.PlaceItemRequest
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerTrack
import app.hovanki.shared.protocol.QuestId
import app.hovanki.shared.protocol.QuestReviewRequest
import app.hovanki.shared.protocol.ScanCheckpointRequest
import app.hovanki.shared.protocol.SendChatRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.TrackPoint
import app.hovanki.shared.protocol.TracksResponse
import app.hovanki.shared.protocol.UsePerkRequest
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.shrinkingZone
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpGameApiTest {
    private lateinit var server: MockServer

    private val recorded get() = server.recorded

    private fun api(handler: MockRequestHandler): HttpGameApi {
        server = MockServer(handler)
        return HttpGameApi(server.client, server.serverUrl)
    }

    @Test
    fun syncPostsSamplesWithTheBearerToken() = runTest {
        val snapshot = testSnapshot(serverTimeMillis = 42)
        val api = api { jsonOf(snapshot) }

        val result = api.sync(testSession, SyncRequest(listOf(testSample(7)), chatAfter = 3))

        assertEquals(snapshot, result)
        val request = recorded.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/v1/games/game1/sync", request.path)
        assertEquals("Bearer secret-token", request.authorization)
        assertEquals(
            SyncRequest(listOf(testSample(7)), chatAfter = 3),
            protocolJson.decodeFromString(SyncRequest.serializer(), request.body),
        )
    }

    @Test
    fun syncMeasuredTellsTheSizeOfTheAnswer() = runTest {
        val snapshot = testSnapshot(serverTimeMillis = 42)
        val api = api { jsonOf(snapshot) }

        val measured = api.syncMeasured(testSession, SyncRequest(listOf(testSample(7))))

        assertEquals(snapshot, measured.snapshot)
        val text = protocolJson.encodeToString(app.hovanki.shared.protocol.GameSnapshot.serializer(), snapshot)
        assertEquals(text.encodeToByteArray().size, measured.bytes)
        assertEquals("/api/v1/games/game1/sync", recorded.single().path)
        assertEquals("Bearer secret-token", recorded.single().authorization)
    }

    @Test
    fun theBoardsRoutes() = runTest {
        val api = api { jsonOf(testSnapshot()) }
        val point = GeoPoint(50.45, 30.52)

        api.placeItem(testSession, PlaceItemRequest(ItemKind.PICKUP, point, perk = PerkKind.SENSE))
        api.removeItem(testSession, ItemId("i1"))
        api.scanCheckpoint(testSession, "ABCD2345")
        api.usePerk(testSession, UsePerkRequest(PerkKind.SPOTLIGHT, targetId = PlayerId("anna")))
        api.addQuest(testSession, CustomQuestRequest("Selfie", Audience.HIDERS, 4))
        api.questDone(testSession, QuestId("q1"))
        api.reviewQuest(testSession, QuestId("q1"), QuestReviewRequest(PlayerId("anna"), approved = true))

        assertEquals(
            listOf(
                "/api/v1/games/game1/items",
                "/api/v1/games/game1/items/i1/remove",
                "/api/v1/games/game1/checkpoints/scan",
                "/api/v1/games/game1/perks",
                "/api/v1/games/game1/quests",
                "/api/v1/games/game1/quests/q1/done",
                "/api/v1/games/game1/quests/q1/review",
            ),
            recorded.map { it.path },
        )
        assertTrue(recorded.all { it.method == HttpMethod.Post && it.authorization == "Bearer secret-token" })
        assertEquals(
            PlaceItemRequest(ItemKind.PICKUP, point, perk = PerkKind.SENSE),
            protocolJson.decodeFromString(PlaceItemRequest.serializer(), recorded[0].body),
        )
        assertEquals(
            ScanCheckpointRequest("ABCD2345"),
            protocolJson.decodeFromString(ScanCheckpointRequest.serializer(), recorded[2].body),
        )
        assertEquals(
            QuestReviewRequest(PlayerId("anna"), approved = true),
            protocolJson.decodeFromString(QuestReviewRequest.serializer(), recorded[6].body),
        )
    }

    @Test
    fun aScannedCodeGoesWithTheClaimAndTheTracksAreFetched() = runTest {
        val tracks = TracksResponse(listOf(PlayerTrack(testSession.playerId, listOf(TrackPoint(50.45, 30.52, 7)))))
        val api = api { request ->
            if (request.url.encodedPath.endsWith("/tracks")) jsonOf(tracks) else jsonOf(testSnapshot())
        }

        api.claimCatch(testSession, PlayerId("anna"), code = "1234")
        api.claimCatch(testSession, PlayerId("boris"))
        assertEquals(tracks, api.tracks(testSession))

        assertEquals(
            listOf(ClaimCatchRequest(PlayerId("anna"), "1234"), ClaimCatchRequest(PlayerId("boris"))),
            recorded.take(2).map { protocolJson.decodeFromString(ClaimCatchRequest.serializer(), it.body) },
        )
        assertEquals(HttpMethod.Get, recorded.last().method)
        assertEquals("/api/v1/games/game1/tracks", recorded.last().path)
        assertEquals("Bearer secret-token", recorded.last().authorization)
    }

    @Test
    fun createGameIsSentWithoutTokenByAGuest() = runTest {
        val response = SessionResponse(testSession, testSnapshot())
        val api = api { jsonOf(response) }
        val request = CreateGameRequest("Anna", GameSettings(zone = shrinkingZone(GeoPoint(50.45, 30.52))))

        assertEquals(response, api.createGame(request))
        assertEquals("/api/v1/games", recorded.single().path)
        assertNull(recorded.single().authorization)
        assertEquals(request, protocolJson.decodeFromString(CreateGameRequest.serializer(), recorded.single().body))
    }

    @Test
    fun createAndJoinCarryTheAccountToken() = runTest {
        val api = api { jsonOf(SessionResponse(testSession, testSnapshot())) }
        val settings = GameSettings(zone = shrinkingZone(GeoPoint(50.45, 30.52)))

        api.createGame(CreateGameRequest("Anna", settings), accountToken = "account-token")
        api.joinGame(JoinGameRequest("ABC234", "Anna"), accountToken = "account-token")
        api.joinGame(JoinGameRequest("ABC234", "Guest"))

        assertEquals(listOf("/api/v1/games", "/api/v1/games/join", "/api/v1/games/join"), recorded.map { it.path })
        assertEquals(listOf("Bearer account-token", "Bearer account-token", null), recorded.map { it.authorization })
    }

    @Test
    fun catchCallsUseTheCatchPaths() = runTest {
        val api = api { jsonOf(testSnapshot()) }

        api.confirmCatch(testSession, CatchId("catch1"), "0427")
        api.disputeCatch(testSession, CatchId("catch1"))
        api.vote(testSession, CatchId("catch1"), confirm = false)

        assertEquals(
            listOf(
                "/api/v1/games/game1/catches/catch1/confirm",
                "/api/v1/games/game1/catches/catch1/dispute",
                "/api/v1/games/game1/catches/catch1/vote",
            ),
            recorded.map { it.path },
        )
        assertEquals("""{"code":"0427"}""", recorded[0].body)
        assertEquals("", recorded[1].body)
        assertEquals("""{"confirm":false}""", recorded[2].body)
    }

    @Test
    fun chatReportAndInviteUseTheGamePaths() = runTest {
        val api = api { jsonOf(testSnapshot()) }

        api.sendChat(testSession, SendChatRequest("Hi all", team = true, chatAfter = 12))
        api.reportChat(testSession, seq = 7)
        api.invite(testSession, InviteRequest(listOf(UserId("u2")), GroupId("g1")))

        assertEquals(
            listOf("/api/v1/games/game1/chat", "/api/v1/games/game1/chat/7/report", "/api/v1/games/game1/invites"),
            recorded.map { it.path },
        )
        assertEquals(List(3) { "Bearer secret-token" }, recorded.map { it.authorization })
        assertEquals("""{"text":"Hi all","team":true,"chatAfter":12}""", recorded[0].body)
        assertEquals("", recorded[1].body)
        assertEquals("""{"userIds":["u2"],"groupId":"g1"}""", recorded[2].body)
    }

    @Test
    fun errorResponseBecomesApiException() = runTest {
        val error = ApiError(ErrorCode.WRONG_STATE, "Not possible in phase SEEKING")
        val api = api { apiError(HttpStatusCode.Conflict, error) }

        val exception = assertFailsWith<ApiException> {
            api.startGame(testSession, StartGameRequest(listOf(PlayerId("player2"))))
        }

        assertEquals(409, exception.status)
        assertEquals(error, exception.error)
        assertNull(exception.reason)
        assertNull(exception.retryAfterSeconds)
        assertEquals("/api/v1/games/game1/start", recorded.single().path)
    }

    @Test
    fun rateLimitCarriesTheReasonAndRetryAfter() = runTest {
        val error = ApiError(ErrorCode.WRONG_STATE, "Slow down", ErrorReason.TOO_MANY_REQUESTS)
        val api = api { apiError(HttpStatusCode.TooManyRequests, error, retryAfter = 8) }

        val exception = assertFailsWith<ApiException> { api.sendChat(testSession, SendChatRequest("Hi")) }

        assertEquals(429, exception.status)
        assertEquals(ErrorReason.TOO_MANY_REQUESTS, exception.reason)
        assertEquals(8L, exception.retryAfterSeconds)
    }

    @Test
    fun errorWithoutApiErrorBodyStillHasTheStatus() = runTest {
        val api = api { respond("<html>Bad gateway</html>", HttpStatusCode.BadGateway) }

        val exception = assertFailsWith<ApiException> { api.sync(testSession, SyncRequest()) }

        assertEquals(502, exception.status)
        assertNull(exception.error)
    }
}
