package app.hovanki.shared.rules

import app.hovanki.shared.protocol.LeaderboardEntry
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.UserId

/**
 * The leaderboard's points and order (docs/adr/0020-leaderboard.md). The server computes the leaderboard with these
 * from the history of finished games; the app may use them to explain the points. Tweak the constants here only.
 */
object LeaderboardRules {
    /** Every finished game. */
    const val PLAYED = 20

    /** A hider: per full minute from the start of seeking until found, eliminated or the end. */
    const val HIDER_PER_MINUTE = 10

    /** A hider never found: won, still [PlayerStatus.ACTIVE] at the end. */
    const val HIDER_NEVER_FOUND = 100

    /** A seeker: per confirmed catch. */
    const val SEEKER_PER_CATCH = 100

    /** A seeker who won: no hider left at the end. */
    const val SEEKER_WON = 50

    /** The week starts on Monday at 00:00 in this time zone; a game counts in the week it ended in. */
    const val WEEK_TIME_ZONE = "Europe/Kyiv"

    /** How many lines the world's leaderboard lists. */
    const val WORLD_TOP = 50

    /** The points of one finished game; the arguments are those of a game's result in the history. */
    fun points(role: Role, status: PlayerStatus, won: Boolean, catches: Int, survivedSeconds: Int?): Int = PLAYED +
        when (role) {
            Role.HIDER -> {
                val minutes = (survivedSeconds ?: 0).coerceAtLeast(0) / SECONDS_PER_MINUTE
                val neverFound = won || status == PlayerStatus.ACTIVE
                minutes * HIDER_PER_MINUTE + if (neverFound) HIDER_NEVER_FOUND else 0
            }

            Role.SEEKER -> catches.coerceAtLeast(0) * SEEKER_PER_CATCH + if (won) SEEKER_WON else 0
        }

    /** A player's total in a scope, before [rank]. */
    data class Score(
        val userId: UserId,
        val nickname: String,
        val points: Int,
        /** When their last counted game ended: of two with the same points, who got there first is above. */
        val lastGameAtMillis: Long,
    )

    /**
     * Best first: more points; same points: the earlier last game, then the nickname ignoring case, then the id. Every
     * line gets its own place, 1 to n, by position. [me] marks the caller's line.
     */
    fun rank(scores: Collection<Score>, me: UserId?): List<LeaderboardEntry> = scores
        .sortedWith(
            compareByDescending<Score> { it.points }
                .thenBy { it.lastGameAtMillis }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.nickname }
                .thenBy { it.userId.value },
        )
        .mapIndexed { index, score ->
            LeaderboardEntry(index + 1, score.userId, score.nickname, score.points, isMe = score.userId == me)
        }

    private const val SECONDS_PER_MINUTE = 60
}
