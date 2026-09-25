package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

@Serializable
data class CreateGameRequest(val playerName: String, val settings: GameSettings)

@Serializable
data class JoinGameRequest(val joinCode: String, val playerName: String)

/** Credentials of one player in one game; the token goes to `Authorization: Bearer <token>`. */
@Serializable
data class PlayerSession(val gameId: GameId, val playerId: PlayerId, val token: String)

@Serializable
data class SessionResponse(val session: PlayerSession, val snapshot: GameSnapshot)

@Serializable
data class StartGameRequest(val seekers: List<PlayerId>)

/** Periodic position report; the response is the fresh [GameSnapshot]. */
@Serializable
data class SyncRequest(val samples: List<LocationSample> = emptyList())

@Serializable
data class ClaimCatchRequest(val hiderId: PlayerId)

@Serializable
data class ConfirmCatchRequest(val code: String)

@Serializable
data class VoteRequest(val confirm: Boolean)

@Serializable
data class ApiError(val code: ErrorCode, val message: String)
