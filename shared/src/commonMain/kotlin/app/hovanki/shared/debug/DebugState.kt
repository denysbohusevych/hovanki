package app.hovanki.shared.debug

import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.protocol.ZoneCircle
import kotlinx.serialization.Serializable

/**
 * Observer API for end-to-end tests (`:e2e`): the full, unfiltered state of a game, including every position.
 *
 * The server serves these routes only with the Spring profile `e2e`; a production server has no such endpoint.
 * The mobile app never calls them. Not part of the versioned game protocol: may change together with the e2e tests.
 */
object DebugRoutes {
    const val GAMES = "/api/v1/debug/games"
    const val GAME = "$GAMES/{gameId}"

    /** Emails the server sent to an address (the e2e tests read the codes there). */
    const val EMAILS = "/api/v1/debug/emails/{email}"

    /** Chat messages reported to the moderators. */
    const val REPORTS = "/api/v1/debug/reports"

    fun game(gameId: GameId): String = GAME.replace("{gameId}", gameId.value)

    fun emails(email: String): String = EMAILS.replace("{email}", email)
}

@Serializable
data class DebugGameList(val serverTimeMillis: Long, val games: List<DebugGameSummary>)

@Serializable
data class DebugGameSummary(
    val gameId: GameId,
    val joinCode: String,
    val phase: GamePhase,
    val hostId: PlayerId,
    val playerNames: List<String>,
)

@Serializable
data class DebugGameState(
    val gameId: GameId,
    val joinCode: String,
    val hostId: PlayerId,
    val phase: GamePhase,
    val settings: GameSettings,
    val serverTimeMillis: Long,
    val phaseStartedAtMillis: Long,
    val phaseEndsAtMillis: Long? = null,
    val zoneStartedAtMillis: Long? = null,
    /** Zone circle at [serverTimeMillis]; null before SEEKING. */
    val zone: ZoneCircle? = null,
    val finishedAtMillis: Long? = null,
    val players: List<DebugPlayer>,
    val catches: List<DebugCatch>,
    val buildings: BuildingsState? = null,
    /** Every chat message the game keeps, of every channel. */
    val chat: List<ChatMessage> = emptyList(),
)

@Serializable
data class DebugPlayer(
    val id: PlayerId,
    val name: String,
    val role: Role,
    val status: PlayerStatus,
    /** Last accepted fix of any accuracy. */
    val latestFix: LocationSample? = null,
    /** Last accepted fix good enough for rule checks. */
    val latestUsableFix: LocationSample? = null,
    /** Server time when the last fix was accepted (drives [VisibilityReason.STALE_SIGNAL]). */
    val lastFixReceivedMillis: Long? = null,
    val lastMockAtMillis: Long? = null,
    val outOfZoneSinceMillis: Long? = null,
    val outOfZoneDeadlineMillis: Long? = null,
    /** Since when the server is confident the player is inside a building. */
    val insideBuildingSinceMillis: Long? = null,
    /** When the player's app fetched the buildings its map draws; null if it has not. */
    val buildingsLoadedAtMillis: Long? = null,
    /** Why seekers see this player right now; null when hidden from them. */
    val revealedToSeekers: VisibilityReason? = null,
    val catchCodeSecret: String? = null,
    /** What happened to the fixes this player sent (see `LocationTrack.Result`). */
    val fixes: DebugFixCounts = DebugFixCounts(),
    /** The player's account; null for a guest. */
    val userId: UserId? = null,
    /** When the hider was caught or eliminated, and by whom they were caught. */
    val outAtMillis: Long? = null,
    val caughtBy: PlayerId? = null,
    /** Points of the player's replay track so far (`GET /tracks` after the game). */
    val replayPoints: Int = 0,
)

@Serializable
data class DebugFixCounts(val accepted: Int = 0, val mock: Int = 0, val outOfOrder: Int = 0, val implausible: Int = 0)

@Serializable
data class DebugCatch(
    val id: CatchId,
    val seekerId: PlayerId,
    val hiderId: PlayerId,
    val status: CatchStatus,
    val createdAtMillis: Long,
    /** Deadline of the current step; for a resolved claim, when it was resolved. */
    val deadlineMillis: Long,
    val failedAttempts: Int,
    val votes: List<DebugVote> = emptyList(),
    val estimatedDistanceAtClaimMeters: Double? = null,
)

@Serializable
data class DebugVote(val voterId: PlayerId, val confirm: Boolean)

/** Emails sent to one address, oldest first; the recording sender of tests and the `e2e` profile keeps them. */
@Serializable
data class DebugEmails(val emails: List<DebugEmail> = emptyList())

@Serializable
data class DebugEmail(
    val to: String,
    /** What the email is for: `VERIFY_EMAIL` or `RESET_PASSWORD`. */
    val purpose: String,
    val language: String,
    val subject: String,
    val text: String,
    /** The code in the email. */
    val code: String? = null,
    val sentAtMillis: Long,
)

@Serializable
data class DebugReportList(val reports: List<DebugReport> = emptyList())

@Serializable
data class DebugReport(
    val id: String,
    val gameId: GameId,
    val messageSeq: Long,
    val reporterPlayerId: PlayerId,
    val reporterUserId: UserId? = null,
    val reportedUserId: UserId? = null,
    val reportedName: String,
    val text: String,
    val createdAtMillis: Long,
)
