package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

/**
 * Authoritative game state as seen by one player. The server filters it per viewer:
 * a client never receives a position it is not allowed to see, so a modified client can't cheat by reading it.
 */
@Serializable
data class GameSnapshot(
    val gameId: GameId,
    val joinCode: String,
    val hostId: PlayerId,
    val phase: GamePhase,
    val settings: GameSettings,
    /** Server clock when the snapshot was produced; clients derive their clock offset from it. */
    val serverTimeMillis: Long,
    val phaseEndsAtMillis: Long? = null,
    /** Start of the zone schedule (= start of SEEKING). */
    val zoneStartedAtMillis: Long? = null,
    val players: List<PlayerView>,
    val me: MyState,
    /** Catch claims the viewer is involved in or may vote on. */
    val catches: List<CatchView> = emptyList(),
    /** The "no hiding in buildings" rule: null from older servers (no rule), or when the value is unknown. */
    val buildings: BuildingsState? = null,
)

@Serializable
data class PlayerView(
    val id: PlayerId,
    val name: String,
    val role: Role,
    val status: PlayerStatus,
    /** Present only when the viewer is allowed to see this player right now. */
    val location: VisibleLocation? = null,
)

@Serializable
data class VisibleLocation(
    val point: GeoPoint,
    val accuracyMeters: Double,
    val atMillis: Long,
    /**
     * Why the viewer sees this player, as the first app versions understand it: only the values they know. Has no
     * default, so a new value here would break them: newer reasons go to [cause] and map to the closest old one
     * (a building reveal is [VisibilityReason.OUT_OF_ZONE] here).
     */
    val reason: VisibilityReason,
    /** The exact reason, including ones added later (e.g. [VisibilityReason.INSIDE_BUILDING]); null: see [reason]. */
    val cause: VisibilityReason? = null,
) {
    /** Why the viewer sees this player: [cause] when the server sent one this client understands, else [reason]. */
    val exactReason: VisibilityReason get() = cause ?: reason
}

@Serializable
data class MyState(
    val playerId: PlayerId,
    val role: Role,
    val status: PlayerStatus,
    /** Hex TOTP secret for the catch code. Only sent to the hider itself, once the round has started. */
    val catchCodeSecret: String? = null,
    /** Set while the server is confident the player is outside the zone: return before this time. */
    val outOfZoneDeadlineMillis: Long? = null,
    /**
     * Set while the server is confident the player is inside a building: the seekers see them from this time on
     * (already in the past: they see them now). Cleared once the player is out again.
     */
    val insideBuildingRevealAtMillis: Long? = null,
)

@Serializable
data class CatchView(
    val id: CatchId,
    val seekerId: PlayerId,
    val hiderId: PlayerId,
    val status: CatchStatus,
    val createdAtMillis: Long,
    /** End of the current step: code timeout (AWAITING_CODE) or voting (DISPUTED). */
    val deadlineMillis: Long? = null,
    val canVote: Boolean = false,
    val myVote: Boolean? = null,
)
