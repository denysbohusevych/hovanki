package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

// Watching an open game without playing it (docs/adr/0011-spectators-and-recordings.md): with an account and the join
// code, [GameSettings.spectatorDelaySeconds] behind the game.

/** POST [ApiRoutes.WATCH] with the account token: watch the open game of [joinCode]. */
@Serializable
data class WatchRequest(val joinCode: String)

/** The spectator's token for the [ApiRoutes.SPECTATE] routes; it lives as long as the game or until they stop. */
@Serializable
data class SpectatorSession(val gameId: GameId, val spectatorId: SpectatorId, val token: String)

@Serializable
data class WatchResponse(val session: SpectatorSession, val snapshot: SpectatorSnapshot)

/**
 * The game as its spectators see it: everybody, [delaySeconds] behind. Every time in it is server time; [atMillis] is
 * the moment shown, the positions, statuses and phase are those of that moment.
 */
@Serializable
data class SpectatorSnapshot(
    val gameId: GameId,
    val settings: GameSettings,
    val serverTimeMillis: Long,
    /** The moment shown: [serverTimeMillis] minus the delay. */
    val atMillis: Long,
    val delaySeconds: Int,
    /** The phase at [atMillis]. */
    val phase: GamePhase,
    val phaseEndsAtMillis: Long? = null,
    /** Start of the zone schedule, when it had started by [atMillis]. */
    val zoneStartedAtMillis: Long? = null,
    /** When the round ended, when it had by [atMillis]. */
    val finishedAtMillis: Long? = null,
    val players: List<SpectatedPlayer> = emptyList(),
    /** How many watch, the caller included. */
    val spectators: Int = 0,
    /** The zone by streets ([ApiRoutes.SPECTATE_STREET_ZONE] once READY, again when [mapRevision] changes). */
    val streetZone: StreetZoneState? = null,
    val mapRevision: Int = 0,
)

/** A player as the spectators see them at [SpectatorSnapshot.atMillis]. */
@Serializable
data class SpectatedPlayer(
    val id: PlayerId,
    val name: String,
    val role: Role,
    /** At that moment: a hider caught later is still [PlayerStatus.ACTIVE] here. */
    val status: PlayerStatus,
    /** Where they were then: their last point of the round before it; null before the round or without one. */
    val location: TrackPoint? = null,
    /** Their way during the last minute before then, oldest first. */
    val trail: List<TrackPoint> = emptyList(),
    val outAtMillis: Long? = null,
    val caughtBy: PlayerId? = null,
)
