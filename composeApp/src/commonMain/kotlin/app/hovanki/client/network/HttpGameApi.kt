package app.hovanki.client.network

import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.ClaimCatchRequest
import app.hovanki.shared.protocol.ConfirmCatchRequest
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.VoteRequest
import app.hovanki.shared.protocol.protocolJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/** [GameApi] over HTTP/JSON; paths and DTOs are shared with the server. */
class HttpGameApi(private val client: HttpClient, private val serverUrl: ServerUrl) : GameApi {
    override suspend fun createGame(request: CreateGameRequest): SessionResponse =
        post(ApiRoutes.GAMES, session = null) { jsonBody(request) }

    override suspend fun joinGame(request: JoinGameRequest): SessionResponse =
        post(ApiRoutes.JOIN, session = null) { jsonBody(request) }

    override suspend fun startGame(session: PlayerSession, request: StartGameRequest): GameSnapshot =
        post(ApiRoutes.start(session.gameId), session) { jsonBody(request) }

    override suspend fun sync(session: PlayerSession, request: SyncRequest): GameSnapshot =
        post(ApiRoutes.sync(session.gameId), session) { jsonBody(request) }

    override suspend fun claimCatch(session: PlayerSession, hiderId: PlayerId): GameSnapshot =
        post(ApiRoutes.catches(session.gameId), session) { jsonBody(ClaimCatchRequest(hiderId)) }

    override suspend fun confirmCatch(session: PlayerSession, catchId: CatchId, code: String): GameSnapshot =
        post(ApiRoutes.catchConfirm(session.gameId, catchId), session) { jsonBody(ConfirmCatchRequest(code)) }

    override suspend fun disputeCatch(session: PlayerSession, catchId: CatchId): GameSnapshot =
        post(ApiRoutes.catchDispute(session.gameId, catchId), session)

    override suspend fun vote(session: PlayerSession, catchId: CatchId, confirm: Boolean): GameSnapshot =
        post(ApiRoutes.catchVote(session.gameId, catchId), session) { jsonBody(VoteRequest(confirm)) }

    private suspend inline fun <reified T> post(
        path: String,
        session: PlayerSession?,
        crossinline configure: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val response = client.post(serverUrl.value + path) {
            if (session != null) header(HttpHeaders.Authorization, "${ApiRoutes.AUTH_SCHEME} ${session.token}")
            configure()
        }
        if (!response.status.isSuccess()) throw response.toApiException()
        return response.body()
    }

    private inline fun <reified B> HttpRequestBuilder.jsonBody(body: B) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.toApiException(): ApiException {
        val error = try {
            protocolJson.decodeFromString(ApiError.serializer(), bodyAsText())
        } catch (e: IllegalArgumentException) {
            // Not our JSON (proxy error page, empty body): the status alone has to do.
            null
        }
        return ApiException(status.value, error)
    }
}
