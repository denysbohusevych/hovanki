package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

@Serializable
data class CreateGameRequest(
    val playerName: String,
    val settings: GameSettings,
    /**
     * An account plays in one game at a time: its lobbies elsewhere are left by themselves, a round in progress only
     * with this set ([ErrorReason.IN_ANOTHER_GAME] otherwise). Guests: no effect.
     */
    val leaveOtherGame: Boolean = false,
)

@Serializable
data class JoinGameRequest(
    val joinCode: String,
    val playerName: String,
    /**
     * Made up by the app for one press of "Join" and sent again when that press got no answer
     * ([app.hovanki.shared.rules.RequestIds]): the server gives back the player it created for it, instead of a second
     * one. Null (older apps): every request is a new player.
     */
    val requestId: String? = null,
    /** As [CreateGameRequest.leaveOtherGame]. */
    val leaveOtherGame: Boolean = false,
)

/** Credentials of one player in one game; the token goes to `Authorization: Bearer <token>`. */
@Serializable
data class PlayerSession(val gameId: GameId, val playerId: PlayerId, val token: String)

@Serializable
data class SessionResponse(val session: PlayerSession, val snapshot: GameSnapshot)

@Serializable
data class StartGameRequest(val seekers: List<PlayerId>)

/**
 * The host picks the roles in the lobby, everybody sees them: [seekers] seek, the others hide. With [randomSeekers]
 * the server draws that many seekers at random instead (and [seekers] is ignored).
 */
@Serializable
data class RolesRequest(val seekers: List<PlayerId> = emptyList(), val randomSeekers: Int? = null)

/**
 * The host changes the game's setup in the lobby. The thresholds ([GameSettings.rules]) stay as the game was created
 * with; a new zone loads the buildings (and the zone by streets) again ([GameSnapshot.mapRevision]).
 */
@Serializable
data class SettingsRequest(val settings: GameSettings)

/** Periodic position report; the response is the fresh [GameSnapshot]. */
@Serializable
data class SyncRequest(
    val samples: List<LocationSample> = emptyList(),
    /**
     * Chat cursor: the highest [ChatMessage.seq] the client has, 0 for none. The response carries the newer messages
     * the player may see in [GameSnapshot.chat]. Null (clients without chat): no chat in the response.
     */
    val chatAfter: Long? = null,
)

/**
 * A seeker says they found [hiderId]. With [code] (the seeker scanned the hider's QR code before any claim, "one
 * scan"), the claim is opened and the code checked in the same step: the right code confirms the catch at once, a wrong
 * one leaves the claim open with one failed attempt, as if it was typed. Null (older apps, or picking the hider by
 * name): the hider shows the code afterwards.
 */
@Serializable
data class ClaimCatchRequest(val hiderId: PlayerId, val code: String? = null)

@Serializable
data class ConfirmCatchRequest(val code: String)

@Serializable
data class VoteRequest(val confirm: Boolean)

/** A chat message; the server picks the channel from the sender's role ([team] = own team only, not in the lobby). */
@Serializable
data class SendChatRequest(
    val text: String,
    val team: Boolean = false,
    /** Chat cursor like [SyncRequest.chatAfter]: the response brings the new messages, including this one. */
    val chatAfter: Long? = null,
    /**
     * Made up by the app for the message and sent again when sending got no answer
     * ([app.hovanki.shared.rules.RequestIds]): the server keeps the message once. Null (older apps): no check.
     */
    val clientMessageId: String? = null,
)

/** Invites friends into the game (lobby only): [userIds] and/or every member of [groupId]. */
@Serializable
data class InviteRequest(val userIds: List<UserId> = emptyList(), val groupId: GroupId? = null)

/**
 * Every failed call answers with this body. Clients decide on [code] (the first app versions know only that) and,
 * when present, on the more precise [reason].
 */
@Serializable
data class ApiError(
    val code: ErrorCode,
    val message: String,
    val reason: ErrorReason? = null,
    /** When a ban or a chat ban ends ([ErrorReason.ACCOUNT_BANNED], [ErrorReason.CHAT_MUTED]); null: forever. */
    val untilMillis: Long? = null,
)
