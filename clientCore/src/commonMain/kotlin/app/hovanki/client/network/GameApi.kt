package app.hovanki.client.network

import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.BigGameId
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.CustomQuestRequest
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.InviteRequest
import app.hovanki.shared.protocol.ItemId
import app.hovanki.shared.protocol.JoinBigGameRequest
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.PlaceItemRequest
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.QuestId
import app.hovanki.shared.protocol.QuestReviewRequest
import app.hovanki.shared.protocol.RolesRequest
import app.hovanki.shared.protocol.SendChatRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.SettingsRequest
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.StreetZoneResponse
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.TracksResponse
import app.hovanki.shared.protocol.UsePerkRequest

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

    /**
     * Into the open lobby of big game [id] the account of [accountToken] signed up for, or back to its player
     * (docs/adr/0010-big-games.md).
     */
    suspend fun joinBigGame(id: BigGameId, request: JoinBigGameRequest, accountToken: String): SessionResponse

    suspend fun startGame(session: PlayerSession, request: StartGameRequest): GameSnapshot

    /** The host picks the roles in the lobby, or has the server draw them ([RolesRequest.randomSeekers]). */
    suspend fun setRoles(session: PlayerSession, request: RolesRequest): GameSnapshot

    /** The host changes the setup in the lobby. */
    suspend fun updateSettings(session: PlayerSession, request: SettingsRequest): GameSnapshot

    /**
     * The host plays anyway in a zone that fits fewer players than there are, or has few places to hide
     * (docs/adr/0010-big-games.md): the lobby warns no more in this game.
     */
    suspend fun acceptCrowding(session: PlayerSession): GameSnapshot

    /** Leaves the game for good: the server takes the player out, the session's token stops working. */
    suspend fun leave(session: PlayerSession)

    /** Sends new location samples and returns the current state; see [GameConnection]. */
    suspend fun sync(session: PlayerSession, request: SyncRequest): GameSnapshot

    /**
     * A claim on [hiderId]; with [code] (the seeker scanned the hider's QR code), the code is checked in the same step
     * ([app.hovanki.shared.protocol.ClaimCatchRequest.code]).
     */
    suspend fun claimCatch(session: PlayerSession, hiderId: PlayerId, code: String? = null): GameSnapshot

    suspend fun confirmCatch(session: PlayerSession, catchId: CatchId, code: String): GameSnapshot

    suspend fun disputeCatch(session: PlayerSession, catchId: CatchId): GameSnapshot

    suspend fun vote(session: PlayerSession, catchId: CatchId, confirm: Boolean): GameSnapshot

    /** The buildings the rule judges by; once per map revision, when the snapshot says they are ready. */
    suspend fun buildings(session: PlayerSession): BuildingsResponse

    /** The zone by streets, one polygon per stage; once per map revision, when the snapshot says it is ready. */
    suspend fun streetZone(session: PlayerSession): StreetZoneResponse

    /** Every player's track of the round, for the replay on the results screen: once the game is finished. */
    suspend fun tracks(session: PlayerSession): TracksResponse

    /** Sends a chat message; the snapshot's chat has the messages after [SendChatRequest.chatAfter], this one too. */
    suspend fun sendChat(session: PlayerSession, request: SendChatRequest): GameSnapshot

    /** Reports the chat message [seq] to the moderators. */
    suspend fun reportChat(session: PlayerSession, seq: Long): GameSnapshot

    /** Invites friends or a group into the game (lobby, logged-in players only). */
    suspend fun invite(session: PlayerSession, request: InviteRequest): GameSnapshot

    // The board and the perks (docs/adr/0013-quests-sparks-and-sensors.md).

    /** The host places an item on the map, in the lobby. */
    suspend fun placeItem(session: PlayerSession, request: PlaceItemRequest): GameSnapshot

    suspend fun removeItem(session: PlayerSession, itemId: ItemId): GameSnapshot

    /** The code of a checkpoint's QR code the camera read. */
    suspend fun scanCheckpoint(session: PlayerSession, code: String): GameSnapshot

    suspend fun usePerk(session: PlayerSession, request: UsePerkRequest): GameSnapshot

    /** The host makes up a quest in words. */
    suspend fun addQuest(session: PlayerSession, request: CustomQuestRequest): GameSnapshot

    /** The player says they did the host's quest. */
    suspend fun questDone(session: PlayerSession, questId: QuestId): GameSnapshot

    /** The host confirms or refuses what a player said about their quest. */
    suspend fun reviewQuest(session: PlayerSession, questId: QuestId, request: QuestReviewRequest): GameSnapshot
}

/**
 * The server answered with a non-2xx status; [error] is its [ApiError] body when it could be read.
 * [retryAfterSeconds] comes with rate limits (HTTP 429, [ErrorReason.TOO_MANY_REQUESTS]).
 */
class ApiException(val status: Int, val error: ApiError?, val retryAfterSeconds: Long? = null) :
    Exception(error?.message ?: "HTTP $status") {
    val reason: ErrorReason? get() = error?.reason
}
