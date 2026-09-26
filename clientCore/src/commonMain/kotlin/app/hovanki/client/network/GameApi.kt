package app.hovanki.client.network

import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.InviteRequest
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.SendChatRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.SyncRequest

/**
 * Request/response calls of the game server. Every call that changes the game returns the fresh
 * [GameSnapshot], so the UI updates right away instead of waiting for the next poll.
 *
 * Throws [ApiException] when the server rejects a call, and I/O or serialization exceptions on network problems.
 */
interface GameApi {
    /** [accountToken]: the logged-in player's account (the game then knows them by their nickname); null: a guest. */
    suspend fun createGame(request: CreateGameRequest, accountToken: String? = null): SessionResponse

    /** [accountToken] as in [createGame]; with it, joining a game the account is already in returns that player. */
    suspend fun joinGame(request: JoinGameRequest, accountToken: String? = null): SessionResponse

    suspend fun startGame(session: PlayerSession, request: StartGameRequest): GameSnapshot

    /** Sends new location samples and returns the current state; see [GameConnection]. */
    suspend fun sync(session: PlayerSession, request: SyncRequest): GameSnapshot

    suspend fun claimCatch(session: PlayerSession, hiderId: PlayerId): GameSnapshot

    suspend fun confirmCatch(session: PlayerSession, catchId: CatchId, code: String): GameSnapshot

    suspend fun disputeCatch(session: PlayerSession, catchId: CatchId): GameSnapshot

    suspend fun vote(session: PlayerSession, catchId: CatchId, confirm: Boolean): GameSnapshot

    /** The buildings the rule judges by; once per game, when the snapshot says they are ready. */
    suspend fun buildings(session: PlayerSession): BuildingsResponse

    /** Sends a chat message; the snapshot's chat has the messages after [SendChatRequest.chatAfter], this one too. */
    suspend fun sendChat(session: PlayerSession, request: SendChatRequest): GameSnapshot

    /** Reports the chat message [seq] to the moderators. */
    suspend fun reportChat(session: PlayerSession, seq: Long): GameSnapshot

    /** Invites friends or a group into the game (lobby, logged-in players only). */
    suspend fun invite(session: PlayerSession, request: InviteRequest): GameSnapshot
}

/**
 * The server answered with a non-2xx status; [error] is its [ApiError] body when it could be read.
 * [retryAfterSeconds] comes with rate limits (HTTP 429, [ErrorReason.TOO_MANY_REQUESTS]).
 */
class ApiException(val status: Int, val error: ApiError?, val retryAfterSeconds: Long? = null) :
    Exception(error?.message ?: "HTTP $status") {
    val reason: ErrorReason? get() = error?.reason
}
