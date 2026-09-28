package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

// Big games (docs/adr/0010-big-games.md): an admin schedules one anywhere in the world, with a drawn zone, a start
// time and up to BigGameLimits.MAX_PLAYERS players. Players sign up ahead from the list on the «Play» tab; the lobby
// opens half an hour before the start, the server hosts it and starts the round on time.

/** Where a big game is in its life. */
@Serializable
enum class BigGameStatus {
    /** Players sign up; the lobby is not open yet. */
    SCHEDULED,

    /** The lobby is open: signed-up players come in and wait for the start. */
    LOBBY,

    /** The round is on. */
    RUNNING,

    FINISHED,
    CANCELLED,

    /** The server stopped during the round: the round is lost, the admin may schedule it again. */
    INTERRUPTED,
    ;

    /** Still ahead or going on: shown in the app's list. */
    val isOpen: Boolean get() = this == SCHEDULED || this == LOBBY || this == RUNNING
}

/** What the admin chooses for a big game besides the place and the time. */
@Serializable
data class BigGameSetup(
    val hidingMinutes: Int = 10,
    val seekingMinutes: Int = 60,
    /** The drawn zone shrinks towards its center in three stages over the first 70 % of the search. */
    val shrinks: Boolean = true,
    /** 0: no glow. */
    val glowEveryMinutes: Int = 5,
    val glowForSeconds: Int = 10,
    /** How many seekers the server draws at the start among the players in the lobby. */
    val seekers: Int = 10,
)

/** A big game in the app's list (account token): what, when, where, how many, and the caller's part in it. */
@Serializable
data class BigGameCard(
    val id: BigGameId,
    val title: String,
    val status: BigGameStatus,
    val startsAtMillis: Long,
    /** The IANA time zone of the place: the start is shown in the place's time. */
    val timeZone: String,
    /** The drawn zone at the start: where to come. */
    val zone: ZonePolygon,
    val setup: BigGameSetup = BigGameSetup(),
    val signedUp: Int = 0,
    val playerLimit: Int = 0,
    val signedUpByMe: Boolean = false,
    /** The lobby is open and the caller signed up: they can come in ([ApiRoutes.bigGameJoin]). */
    val canJoin: Boolean = false,
    /** The caller's friends who signed up, by nickname. */
    val friends: List<UserSummary> = emptyList(),
)

@Serializable
data class BigGamesResponse(val games: List<BigGameCard> = emptyList())

/** Comes into the lobby of a big game the caller signed up for; the same retry and one-game rules as a join. */
@Serializable
data class JoinBigGameRequest(
    /** As [JoinGameRequest.requestId]. */
    val requestId: String? = null,
    /** As [JoinGameRequest.leaveOtherGame]. */
    val leaveOtherGame: Boolean = false,
)

/** In the snapshot of a big game: which one, when it starts, and how many signed up (the lobby shows no list). */
@Serializable
data class BigGameInfo(
    val id: BigGameId,
    val title: String,
    val startsAtMillis: Long,
    val timeZone: String,
    val signedUp: Int = 0,
    val playerLimit: Int = 0,
)
