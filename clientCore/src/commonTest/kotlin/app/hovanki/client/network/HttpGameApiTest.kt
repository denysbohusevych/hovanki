package app.hovanki.client.network

import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.shrinkingZone
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HttpGameApiTest {
    /** What the mock server received. */
    private class Recorded(val method: HttpMethod, val path: String, val authorization: String?, val body: String)

    private val recorded = mutableListOf<Recorded>()

    private fun api(handler: MockRequestHandler): HttpGameApi {
        val engine = MockEngine { request ->
            recorded += Recorded(
                method = request.method,
                path = request.url.encodedPath,
                authorization = request.headers[HttpHeaders.Authorization],
                body = request.body.toByteArray().decodeToString(),
            )
            handler(this, request)
        }
        return HttpGameApi(createHttpClient(engine), ServerUrl("http://game.test:8080/"))
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun syncPostsSamplesWithTheBearerToken() = runTest {
        val snapshot = testSnapshot(serverTimeMillis = 42)
        val api = api { json(protocolJson.encodeToString(GameSnapshot.serializer(), snapshot)) }

        val result = api.sync(testSession, SyncRequest(listOf(testSample(7))))

        assertEquals(snapshot, result)
        val request = recorded.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/v1/games/game1/sync", request.path)
        assertEquals("Bearer secret-token", request.authorization)
        assertEquals(
            SyncRequest(listOf(testSample(7))),
            protocolJson.decodeFromString(SyncRequest.serializer(), request.body),
        )
    }

    @Test
    fun createGameIsSentWithoutToken() = runTest {
        val response = SessionResponse(testSession, testSnapshot())
        val api = api { json(protocolJson.encodeToString(SessionResponse.serializer(), response)) }
        val request = CreateGameRequest("Anna", GameSettings(zone = shrinkingZone(GeoPoint(50.45, 30.52))))

        assertEquals(response, api.createGame(request))
        assertEquals("/api/v1/games", recorded.single().path)
        assertNull(recorded.single().authorization)
        assertEquals(request, protocolJson.decodeFromString(CreateGameRequest.serializer(), recorded.single().body))
    }

    @Test
    fun catchCallsUseTheCatchPaths() = runTest {
        val snapshot = protocolJson.encodeToString(GameSnapshot.serializer(), testSnapshot())
        val api = api { json(snapshot) }

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
    fun errorResponseBecomesApiException() = runTest {
        val error = ApiError(ErrorCode.WRONG_STATE, "Not possible in phase SEEKING")
        val api = api { json(protocolJson.encodeToString(ApiError.serializer(), error), HttpStatusCode.Conflict) }

        val exception = assertFailsWith<ApiException> {
            api.startGame(testSession, StartGameRequest(listOf(PlayerId("player2"))))
        }

        assertEquals(409, exception.status)
        assertEquals(error, exception.error)
        assertEquals("/api/v1/games/game1/start", recorded.single().path)
    }

    @Test
    fun errorWithoutApiErrorBodyStillHasTheStatus() = runTest {
        val api = api { respond("<html>Bad gateway</html>", HttpStatusCode.BadGateway) }

        val exception = assertFailsWith<ApiException> { api.sync(testSession, SyncRequest()) }

        assertEquals(502, exception.status)
        assertNull(exception.error)
    }
}
