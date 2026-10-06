package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

// The leaderboard, «Рейтинг» (docs/adr/0020-leaderboard.md): weekly points of players with an account, computed from
// the history of finished games (docs/adr/0007-game-history-and-routes.md); nothing new is stored. Account token only.

/** Whose points the leaderboard ranks ([ApiRoutes.ME_LEADERBOARD]'s `scope`). */
@Serializable
enum class LeaderboardScope {
    /** The players with an account of the caller's most recent finished game, by that game's points. */
    LAST_GAME,

    /** The caller and their friends, by this week's points. */
    FRIENDS,

    /** Everyone with an account, by this week's points: the top `LeaderboardRules.WORLD_TOP`. */
    WORLD,
}

/** One line of the leaderboard. */
@Serializable
data class LeaderboardEntry(
    /** 1 is the top; players with the same points still get their own places (`LeaderboardRules.rank`). */
    val rank: Int,
    val userId: UserId,
    val nickname: String,
    val points: Int,
    /** This line is the caller's. */
    val isMe: Boolean = false,
)

/** [ApiRoutes.ME_LEADERBOARD]: one scope of the leaderboard as the caller sees it. */
@Serializable
data class LeaderboardResponse(
    val scope: LeaderboardScope = LeaderboardScope.WORLD,
    /**
     * The week counted (Monday 00:00 to the next Monday 00:00 in Kyiv, server time): this week, or for
     * [LeaderboardScope.LAST_GAME] the week the game ended in.
     */
    val weekStartMillis: Long = 0,
    val weekEndMillis: Long = 0,
    /** Best first. */
    val entries: List<LeaderboardEntry> = emptyList(),
    /** The caller's line, also when it is not among [entries]; null when they have no points in this scope. */
    val me: LeaderboardEntry? = null,
    /** The line right above [me] (the points to catch up with); null when [me] is first or missing. */
    val nextAbove: LeaderboardEntry? = null,
    /**
     * [LeaderboardScope.WORLD] and [LeaderboardScope.FRIENDS]: the caller's place last week minus this week's, positive
     * when they moved up; null when they were not ranked in one of the two weeks, and for the last game.
     */
    val rankChange: Int? = null,
)
