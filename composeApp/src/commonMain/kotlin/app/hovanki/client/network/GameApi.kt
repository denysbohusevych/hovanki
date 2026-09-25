package app.hovanki.client.network

import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerSession
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
    suspend fun createGame(request: CreateGameRequest): SessionResponse

    suspend fun joinGame(request: JoinGameRequest): SessionResponse

    suspend fun startGame(session: PlayerSession, request: StartGameRequest): GameSnapshot

    /** Sends new location samples and returns the current state; see [GameConnection]. */
    suspend fun sync(session: PlayerSession, request: SyncRequest): GameSnapshot

    suspend fun claimCatch(session: PlayerSession, hiderId: PlayerId): GameSnapshot

    suspend fun confirmCatch(session: PlayerSession, catchId: CatchId, code: String): GameSnapshot

    suspend fun disputeCatch(session: PlayerSession, catchId: CatchId): GameSnapshot

    suspend fun vote(session: PlayerSession, catchId: CatchId, confirm: Boolean): GameSnapshot
}

/** The server answered with a non-2xx status; [error] is its [ApiError] body when it could be read. */
class ApiException(val status: Int, val error: ApiError?) : Exception(error?.message ?: "HTTP $status")
