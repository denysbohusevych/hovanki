package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class GamePhase {
    /** Players join, the host assigns roles. */
    LOBBY,

    /** Hiders hide, seekers wait. */
    HIDING,

    /** Seekers search, the zone shrinks. */
    SEEKING,

    FINISHED,
}

@Serializable
enum class Role { HIDER, SEEKER }

@Serializable
enum class PlayerStatus {
    ACTIVE,

    /** Hider was found (catch confirmed). */
    CAUGHT,

    /** Removed by the rules, e.g. did not return into the zone in time. */
    ELIMINATED,
}

@Serializable
enum class CatchStatus {
    /** Seeker claimed a catch, the hider has to show the code (or dispute) before the deadline. */
    AWAITING_CODE,

    /** Hider disputed; players outside the dispute vote until the deadline. */
    DISPUTED,

    CONFIRMED,
    REJECTED,
}

/** Why the viewer is allowed to see a player's position. */
@Serializable
enum class VisibilityReason {
    /** Seekers see each other. */
    TEAMMATE,

    /** No location updates for a while (GPS/internet off, app killed): last known point is revealed. */
    STALE_SIGNAL,

    /** Confidently outside the zone. */
    OUT_OF_ZONE,

    /** The device reported a mocked location. */
    MOCK_LOCATION,
}

@Serializable
enum class ErrorCode {
    BAD_REQUEST,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,

    /** The action is not allowed in the current game phase / catch state. */
    WRONG_STATE,

    /** The server has no usable GPS fix of the claiming player. */
    NO_LOCATION,

    /** GPS says the players are too far apart for a catch. */
    TOO_FAR,

    INVALID_CODE,
    INTERNAL,
}
