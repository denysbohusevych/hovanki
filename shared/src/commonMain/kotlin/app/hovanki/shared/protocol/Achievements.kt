package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

// The player's achievements, «Достижения» (docs/adr/0021-achievements.md): computed by the server from the history of
// finished games (docs/adr/0007-game-history-and-routes.md) with `AchievementRules`; only the time the player last
// looked at them is stored. Their owner's only, account token only.

/** One achievement and how far the caller got. */
@Serializable
data class AchievementProgress(
    /**
     * Which one: an id of `AchievementRules` (`games`, `catches`…). A string, not an enum, so that a new achievement
     * never breaks an older app: it skips the ids it does not know.
     */
    val id: String = "",
    /** The levels' targets, smallest first, in the achievement's own unit (games, catches, meters, seconds…). */
    val thresholds: List<Long> = emptyList(),
    /** Where the player is now, in the same unit: a total, a best of one game or a best streak. */
    val value: Long = 0,
    /** How many of [thresholds] are reached, 0 to their count. */
    val level: Int = 0,
    /** When [level] was reached: the end of the game that got there (server time); null at level 0. */
    val unlockedAtMillis: Long? = null,
    /** [level] was reached after the player last looked ([AchievementsResponse.seenAtMillis]). */
    val isNew: Boolean = false,
)

/** [ApiRoutes.ME_ACHIEVEMENTS]: every achievement, in the order the app lists them. */
@Serializable
data class AchievementsResponse(
    val achievements: List<AchievementProgress> = emptyList(),
    /** Up to when the player has seen their achievements ([ApiRoutes.ME_ACHIEVEMENTS_SEEN]); null: never. */
    val seenAtMillis: Long? = null,
)

/** [ApiRoutes.ME_ACHIEVEMENTS_SEEN]: the player saw every level reached up to [upToMillis] (server time). */
@Serializable
data class AchievementsSeenRequest(val upToMillis: Long = 0)
