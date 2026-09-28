package app.hovanki.client.network

import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.ClaimCatchRequest
import app.hovanki.shared.protocol.ConfirmCatchRequest
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.InviteRequest
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.RolesRequest
import app.hovanki.shared.protocol.SendChatRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.SettingsRequest
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.StreetZoneResponse
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.TracksResponse
import app.hovanki.shared.protocol.VoteRequest
import io.ktor.client.HttpClient

/** [GameApi] over HTTP/JSON; paths and DTOs are shared with the server. */
class HttpGameApi(client: HttpClient, serverUrl: ServerUrl) : GameApi {
    private val http = HttpSupport(client, serverUrl)

    override suspend fun createGame(request: CreateGameRequest, accountToken: String?): SessionResponse =
        http.post(ApiRoutes.GAMES, accountToken, request)

    override suspend fun joinGame(request: JoinGameRequest, accountToken: String?): SessionResponse =
        http.post(ApiRoutes.JOIN, accountToken, request)

    override suspend fun startGame(session: PlayerSession, request: StartGameRequest): GameSnapshot =
        http.post(ApiRoutes.start(session.gameId), session.token, request)

    override suspend fun setRoles(session: PlayerSession, request: RolesRequest): GameSnapshot =
        http.post(ApiRoutes.roles(session.gameId), session.token, request)

    override suspend fun updateSettings(session: PlayerSession, request: SettingsRequest): GameSnapshot =
        http.post(ApiRoutes.settings(session.gameId), session.token, request)

    override suspend fun leave(session: PlayerSession): Unit = http.post(ApiRoutes.leave(session.gameId), session.token)

    override suspend fun sync(session: PlayerSession, request: SyncRequest): GameSnapshot =
        http.post(ApiRoutes.sync(session.gameId), session.token, request)

    override suspend fun claimCatch(session: PlayerSession, hiderId: PlayerId, code: String?): GameSnapshot =
        http.post(ApiRoutes.catches(session.gameId), session.token, ClaimCatchRequest(hiderId, code))

    override suspend fun confirmCatch(session: PlayerSession, catchId: CatchId, code: String): GameSnapshot =
        http.post(ApiRoutes.catchConfirm(session.gameId, catchId), session.token, ConfirmCatchRequest(code))

    override suspend fun disputeCatch(session: PlayerSession, catchId: CatchId): GameSnapshot =
        http.post(ApiRoutes.catchDispute(session.gameId, catchId), session.token)

    override suspend fun vote(session: PlayerSession, catchId: CatchId, confirm: Boolean): GameSnapshot =
        http.post(ApiRoutes.catchVote(session.gameId, catchId), session.token, VoteRequest(confirm))

    override suspend fun buildings(session: PlayerSession): BuildingsResponse =
        http.get(ApiRoutes.buildings(session.gameId), session.token)

    override suspend fun streetZone(session: PlayerSession): StreetZoneResponse =
        http.get(ApiRoutes.streetZone(session.gameId), session.token)

    override suspend fun tracks(session: PlayerSession): TracksResponse =
        http.get(ApiRoutes.tracks(session.gameId), session.token)

    override suspend fun sendChat(session: PlayerSession, request: SendChatRequest): GameSnapshot =
        http.post(ApiRoutes.chat(session.gameId), session.token, request)

    override suspend fun reportChat(session: PlayerSession, seq: Long): GameSnapshot =
        http.post(ApiRoutes.chatReport(session.gameId, seq), session.token)

    override suspend fun invite(session: PlayerSession, request: InviteRequest): GameSnapshot =
        http.post(ApiRoutes.gameInvites(session.gameId), session.token, request)
}
