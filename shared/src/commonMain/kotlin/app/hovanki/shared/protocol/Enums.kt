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

    /**
     * Confidently inside a building for longer than allowed (docs/adr/0003-map-and-buildings.md). Only ever sent in
     * [VisibleLocation.cause], never in [VisibleLocation.reason]: older clients would fail to read it there.
     */
    INSIDE_BUILDING,

    /**
     * The glow (docs/adr/0009-game-setup-glow-streets.md): during a glow the seekers see every active hider live,
     * afterwards the spot where the last glow left them. Only in [VisibleLocation.cause], like [INSIDE_BUILDING].
     */
    GLOW,

    /**
     * The radar is required in this game and the hider's phone has had Bluetooth off for too long
     * (docs/adr/0012-nearby-radar.md, section 2.6). Only in [VisibleLocation.cause], like [INSIDE_BUILDING].
     */
    RADAR_OFF,

    /** A seeker's «Spotlight» perk shows the hider for a few seconds (docs/adr/0013). Only in [VisibleLocation.cause]. */
    SPOTLIGHT,

    /**
     * A seeker's «Fresh trail» perk: the hider's spot is a fix of a minute ago, newer than the last glow (docs/adr/0013).
     * Only in [VisibleLocation.cause].
     */
    FRESH_TRAIL,
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

/**
 * Who sees a chat message. Set by the server from the sender's role: players only ever choose "everyone" or "my team".
 */
@Serializable
enum class ChatChannel {
    /** Everyone in the game; the only channel in the lobby. */
    ALL,

    /** Seekers only. */
    SEEKERS,

    /** Hiders only (including caught and eliminated ones). */
    HIDERS,
}

/**
 * The exact reason of an error, next to its [ErrorCode] ([ApiError.reason]). Added after the first app versions: they
 * only read the code, and a value they don't know is read as null (the property has a default).
 */
@Serializable
enum class ErrorReason {
    NICKNAME_TAKEN,
    EMAIL_TAKEN,
    INVALID_NICKNAME,
    INVALID_EMAIL,
    INVALID_PASSWORD,

    /** Unknown login, wrong password, or a wrong current password when changing it or deleting the account. */
    WRONG_CREDENTIALS,

    /** The emailed code is too old, used up (too many attempts) or was never sent: ask for a new one. */
    CODE_EXPIRED,

    /** The account token is unknown, revoked or expired: log in again. */
    SESSION_EXPIRED,

    /** Only for players with an account (e.g. inviting friends into a game). */
    ACCOUNT_REQUIRED,
    USER_NOT_FOUND,
    NOT_FRIENDS,

    /** The caller blocked this user. */
    BLOCKED_BY_YOU,
    NOT_GROUP_OWNER,
    NOT_GROUP_MEMBER,
    INVALID_GROUP_NAME,

    /** Too many friends, requests, groups or group members. */
    LIMIT_REACHED,

    /** Empty or too long chat message. */
    INVALID_MESSAGE,

    /** Rate limit hit (HTTP 429): try again later. */
    TOO_MANY_REQUESTS,

    /** The account is banned (403, login and games); until [ApiError.untilMillis], null: forever. */
    ACCOUNT_BANNED,

    /** The player may not write in the chat (403) until [ApiError.untilMillis], null: forever. */
    CHAT_MUTED,

    /** Needs a confirmed email first, e.g. setting up the staff authenticator. */
    EMAIL_NOT_VERIFIED,

    /**
     * The account still plays in a round of another game: leave it first (`CreateGameRequest.leaveOtherGame`). Lobbies
     * of other games are left by themselves.
     */
    IN_ANOTHER_GAME,

    /** The zone by streets is still being built: the game can start once it is ready (or given up on). */
    ZONE_NOT_READY,

    /** A claim only up close: the radar has not heard the two phones «burning» lately (on [ErrorCode.TOO_FAR]). */
    NOT_NEARBY,

    /** A required feature (the radar) is missing on somebody's phone: the game can't start. */
    FEATURE_MISSING,

    /** The setup uses a feature the operator has not turned on for this server. */
    FEATURE_DISABLED,

    /** The perk costs more sparks than the player has. */
    NOT_ENOUGH_SPARKS,

    /** The perk can't be used now: used up, on cooldown, not for this role, or not in this phase. */
    PERK_UNAVAILABLE,

    /** The player already took this checkpoint (or the pickup is gone). */
    CHECKPOINT_TAKEN,

    /** No such quest for this player, or it is not active. */
    QUEST_NOT_ACTIVE,

    /** The host placed as many items as a game may have. */
    ITEM_LIMIT,

    /** Only players who signed up for a big game come into its lobby (docs/adr/0010-big-games.md). */
    BIG_GAME_SIGNUP_REQUIRED,

    /** The game is not open to spectators (docs/adr/0011-spectators-and-recordings.md). */
    GAME_NOT_OPEN,

    /** The caller plays in this game: players never watch their own game as spectators. */
    PLAYING_THIS_GAME,

    /**
     * The host moved the zone farther from where the game was made than it may go
     * (docs/adr/0014-settings-lobby-redesign-open-buildings.md).
     */
    ZONE_TOO_FAR,

    /**
     * The radio lab's run is over: finished, or older than the join window
     * (docs/adr/0017-radar-techniques-and-big-run.md §5); a phone neither joins it nor uploads to it any more.
     */
    LAB_RUN_CLOSED,

    /** The round is on pause (docs/adr/0019-pause-and-sos.md): no claims, codes, perks or quests until it goes on. */
    GAME_PAUSED,

    /** Somebody's SOS is on (docs/adr/0019-pause-and-sos.md): the round goes on only once every SOS is over. */
    SOS_ACTIVE,

    /**
     * The action needs a paid extra the account doesn't have (on [ErrorCode.FORBIDDEN]; docs/adr/0023-entitlements.md):
     * the app shows the lock instead of an error.
     */
    ENTITLEMENT_REQUIRED,
}
