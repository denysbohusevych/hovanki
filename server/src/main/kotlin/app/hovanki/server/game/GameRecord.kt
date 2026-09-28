package app.hovanki.server.game

import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.RoutePoint
import app.hovanki.shared.protocol.UserId

/**
 * A finished game as the history keeps it (docs/adr/0007-game-history-and-routes.md), taken from
 * [Game.takeFinishedRecord] under the game's lock and saved by `HistoryWriter` after it is released. Numbers about the
 * game, and a [PlayerResult] with the route for each player with an account: whether a route is kept is decided when
 * it is saved (the player's consent is in the database).
 */
class GameRecord(
    val gameId: GameId,
    val createdAtMillis: Long,
    /** Hiding started. */
    val startedAtMillis: Long,
    /** Seeking started (the zone schedule); null only if the game never got there. */
    val zoneStartedAtMillis: Long?,
    val finishedAtMillis: Long,
    val settings: GameSettings,
    val players: Int,
    val guests: Int,
    val seekers: Int,
    val hidersCaught: Int,
    val hidersEliminated: Int,
    val catchClaims: Int,
    val catches: Int,
    val disputes: Int,
    val chatMessages: Int,
    val buildings: BuildingsState,
    /** Players with an account only; guests have no history. */
    val results: List<PlayerResult>,
) {
    override fun toString(): String = "GameRecord(${gameId.value}, ${results.size} accounts)"
}

/** How the game went for one player with an account. */
class PlayerResult(
    val userId: UserId,
    val role: Role,
    val status: PlayerStatus,
    val won: Boolean,
    val catchClaims: Int,
    val catches: Int,
    /** A hider: seconds from the start of seeking until caught, eliminated or the end; null for seekers. */
    val survivedSeconds: Int?,
    val zoneWarnings: Int,
    val buildingWarnings: Int,
    val fixes: Int,
    val distanceMeters: Double,
    val movingSeconds: Int,
    val maxSpeedMetersPerSecond: Double?,
    /** Kept only with the player's consent. */
    val route: List<RoutePoint>,
    /** Sparks left at the end and quests done (docs/adr/0011-quests-sparks-and-sensors.md); numbers only. */
    val sparks: Int = 0,
    val questsDone: Int = 0,
) {
    // Never coordinates in logs.
    override fun toString(): String = "PlayerResult(${userId.value}, ${route.size} points)"
}
