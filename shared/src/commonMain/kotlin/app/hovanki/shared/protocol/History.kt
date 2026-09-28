package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

// Game history, statistics and saved routes (docs/adr/0007-game-history-and-routes.md). Account routes only, and only
// about the caller: nobody gets another player's history, numbers or route.

/**
 * How one finished game went for the caller. The distance, moving time and top speed come from their accepted GPS
 * fixes during the round (hiding and seeking, not the lobby): zero or null when there were none.
 */
@Serializable
data class GameHistoryEntry(
    val gameId: GameId,
    /** When the round started (hiding) and ended, server time. */
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val role: Role,
    /** The caller's status at the end: an [PlayerStatus.ACTIVE] hider was never found. */
    val status: PlayerStatus,
    /** A hider: still [PlayerStatus.ACTIVE] at the end. A seeker: no hider left at the end. */
    val won: Boolean,
    /** Everyone in the game, guests included, and how many of them were seekers. */
    val players: Int,
    val seekers: Int,
    /** A seeker: their confirmed catches. */
    val catches: Int = 0,
    /** A hider: seconds from the start of seeking until caught or eliminated, or until the end. */
    val survivedSeconds: Int? = null,
    val distanceMeters: Double = 0.0,
    /** Time spent walking or running, not standing. */
    val movingSeconds: Int = 0,
    val maxSpeedMetersPerSecond: Double? = null,
    /** The route of this game is saved: [ApiRoutes.meGameRoute]. */
    val hasRoute: Boolean = false,
)

/** A page of the caller's games, newest first; [nextBefore] asks for the next page ([ApiRoutes.ME_GAMES]). */
@Serializable
data class GameHistoryResponse(
    val games: List<GameHistoryEntry> = emptyList(),
    /** `before` for the next page; null: this is the last one. */
    val nextBefore: Long? = null,
)

/** The caller's totals over all their games with an account. */
@Serializable
data class PlayerStats(
    val games: Int = 0,
    val gamesAsHider: Int = 0,
    val gamesAsSeeker: Int = 0,
    val wins: Int = 0,
    val winsAsHider: Int = 0,
    val winsAsSeeker: Int = 0,
    /** Hiders the caller found as a seeker. */
    val catches: Int = 0,
    val timesCaught: Int = 0,
    /** As a hider, out of the zone for too long. */
    val timesEliminated: Int = 0,
    /** The longest a hider of theirs stayed unfound after seeking began. */
    val longestHideSeconds: Int? = null,
    /** Round time: from hiding to the end, over all games. */
    val playedSeconds: Long = 0,
    val distanceMeters: Double = 0.0,
    val movingSeconds: Long = 0,
    /** [distanceMeters] over [movingSeconds]; null before the caller ever moved. */
    val averageSpeedMetersPerSecond: Double? = null,
    val maxSpeedMetersPerSecond: Double? = null,
    /** The longest distance in one game. */
    val longestGameMeters: Double? = null,
    val firstGameAtMillis: Long? = null,
    val lastGameAtMillis: Long? = null,
)

/** One point of a saved route: an accepted GPS fix. */
@Serializable
data class RoutePoint(val lat: Double, val lon: Double, val accuracyMeters: Double, val atMillis: Long) {
    val point: GeoPoint get() = GeoPoint(lat, lon)
}

/**
 * The caller's own route through one game, as saved (only with [UserProfile.saveRoutes] on), with what is needed to
 * draw it: the zone and its timeline.
 */
@Serializable
data class GameRoute(
    val gameId: GameId,
    val role: Role,
    val zone: ZoneSchedule,
    /** Hiding started; the route starts here. */
    val startedAtMillis: Long,
    /** Start of the zone schedule (= start of seeking); null: the game ended before seeking. */
    val zoneStartedAtMillis: Long? = null,
    val finishedAtMillis: Long,
    /**
     * The zone by streets, one polygon per stage of [zone] (docs/adr/0009-game-setup-glow-streets.md); null: the game
     * played with circles, or the route was saved before the polygons were kept with it.
     */
    val streetZone: List<ZonePolygon>? = null,
    /** Oldest first. */
    val points: List<RoutePoint> = emptyList(),
    /** The route is deleted after this (retention). */
    val expiresAtMillis: Long,
)

/** The caller's privacy choices ([ApiRoutes.ME_PRIVACY]). */
@Serializable
data class PrivacyRequest(
    /**
     * Keep the routes of my games (explicit consent, off by default). Turning it off deletes every route saved so far;
     * the history and statistics stay.
     */
    val saveRoutes: Boolean,
)
