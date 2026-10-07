package app.hovanki.shared.rules

import app.hovanki.shared.protocol.AchievementProgress
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role

/**
 * The achievements and their levels (docs/adr/0021-achievements.md). The server computes them with these from the
 * history of a player's finished games; the app uses the ids and thresholds to name and draw them. Every value only
 * grows from game to game (a total, a best of one game or a best streak), so a level, once reached, stays.
 */
object AchievementRules {
    /** Finished games. */
    const val GAMES = "games"

    /** Games as a hider nobody found. */
    const val NEVER_FOUND = "never_found"

    /** The best streak of hider games nobody found you in; a seeker's game in between does not break it. */
    const val UNCATCHABLE = "uncatchable"

    /** Confirmed catches as a seeker, all games together. */
    const val CATCHES = "catches"

    /** The most catches in one game. */
    const val ROUNDUP = "roundup"

    /** Games as a seeker that found every hider. */
    const val SEEKERS_WON = "seekers_won"

    /** The longest a hider held out in one game, seconds from the start of seeking. */
    const val PATIENCE = "patience"

    /** Meters moved in the rounds, all games together. */
    const val MARATHON = "marathon"

    /** The most meters in one game. */
    const val SPRINTER = "sprinter"

    /** The most players in one game. */
    const val BIG_COMPANY = "big_company"

    /** The best streak of weeks in a row with a finished game (the leaderboard's weeks, [LeaderboardRules]). */
    const val WEEKLY = "weekly"

    /** One achievement: its id and the levels' targets, smallest first. */
    data class Definition(val id: String, val thresholds: List<Long>)

    /** Every achievement, in the order the app lists them. Thresholds may be raised or new levels added later. */
    val ALL: List<Definition> = listOf(
        Definition(GAMES, listOf(1, 10, 50, 100)),
        Definition(NEVER_FOUND, listOf(1, 5, 25)),
        Definition(UNCATCHABLE, listOf(3)),
        Definition(CATCHES, listOf(1, 10, 50, 200)),
        Definition(ROUNDUP, listOf(3)),
        Definition(SEEKERS_WON, listOf(1, 10)),
        Definition(PATIENCE, listOf(20 * 60)),
        Definition(MARATHON, listOf(5_000, 42_000, 100_000)),
        Definition(SPRINTER, listOf(2_000)),
        Definition(BIG_COMPANY, listOf(10)),
        Definition(WEEKLY, listOf(4)),
    )

    /** One finished game of the player, as the history keeps it. */
    data class Game(
        val finishedAtMillis: Long,
        /** The week it ended in, any numbering where the next week is one more (the server: Kyiv's Mondays). */
        val week: Long,
        val role: Role,
        val status: PlayerStatus,
        val won: Boolean,
        val players: Int,
        val catches: Int,
        /** A hider's time from the start of seeking until found, eliminated or the end; null for seekers. */
        val survivedSeconds: Int?,
        val distanceMeters: Double,
    )

    /**
     * Every achievement of [ALL] after [games] (in any order): the value, the level and when it was reached, and
     * whether that was after [seenAtMillis] (null: the player never looked, every reached level is new).
     */
    fun progress(games: Collection<Game>, seenAtMillis: Long?): List<AchievementProgress> {
        val tally = Tally()
        val reachedAt = ALL.associate { it.id to arrayOfNulls<Long>(it.thresholds.size) }
        for (game in games.sortedBy { it.finishedAtMillis }) {
            tally.add(game)
            for (definition in ALL) {
                val value = tally.value(definition.id)
                val times = reachedAt.getValue(definition.id)
                definition.thresholds.forEachIndexed { level, threshold ->
                    if (times[level] == null && value >= threshold) times[level] = game.finishedAtMillis
                }
            }
        }
        return ALL.map { definition ->
            val value = tally.value(definition.id)
            val level = definition.thresholds.count { value >= it }
            val unlockedAt = if (level == 0) null else reachedAt.getValue(definition.id)[level - 1]
            AchievementProgress(
                id = definition.id,
                thresholds = definition.thresholds,
                value = value,
                level = level,
                unlockedAtMillis = unlockedAt,
                isNew = unlockedAt != null && (seenAtMillis == null || unlockedAt > seenAtMillis),
            )
        }
    }

    /** A hider nobody found: won, or still [PlayerStatus.ACTIVE] at the end (as [LeaderboardRules.points]). */
    fun neverFound(role: Role, status: PlayerStatus, won: Boolean): Boolean =
        role == Role.HIDER && (won || status == PlayerStatus.ACTIVE)

    /** The running values, game after game, oldest first. */
    private class Tally {
        var games = 0L
        var neverFound = 0L
        var hiderStreak = 0L
        var bestHiderStreak = 0L
        var catches = 0L
        var maxCatches = 0L
        var seekerWins = 0L
        var maxSurvivedSeconds = 0L
        var meters = 0.0
        var maxMeters = 0.0
        var maxPlayers = 0L
        var lastWeek: Long? = null
        var weekStreak = 0L
        var bestWeekStreak = 0L

        fun add(game: Game) {
            games++
            when {
                neverFound(game.role, game.status, game.won) -> {
                    neverFound++
                    hiderStreak++
                    bestHiderStreak = maxOf(bestHiderStreak, hiderStreak)
                }

                game.role == Role.HIDER -> hiderStreak = 0
            }
            val gameCatches = game.catches.coerceAtLeast(0).toLong()
            catches += gameCatches
            maxCatches = maxOf(maxCatches, gameCatches)
            if (game.role == Role.SEEKER && game.won) seekerWins++
            maxSurvivedSeconds = maxOf(maxSurvivedSeconds, (game.survivedSeconds ?: 0).coerceAtLeast(0).toLong())
            val gameMeters = game.distanceMeters.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
            meters += gameMeters
            maxMeters = maxOf(maxMeters, gameMeters)
            maxPlayers = maxOf(maxPlayers, game.players.toLong())
            val previous = lastWeek
            when {
                previous == null || game.week > previous + 1 -> weekStreak = 1
                game.week == previous + 1 -> weekStreak++
            }
            if (previous == null || game.week > previous) lastWeek = game.week
            bestWeekStreak = maxOf(bestWeekStreak, weekStreak)
        }

        fun value(id: String): Long = when (id) {
            GAMES -> games
            NEVER_FOUND -> neverFound
            UNCATCHABLE -> bestHiderStreak
            CATCHES -> catches
            ROUNDUP -> maxCatches
            SEEKERS_WON -> seekerWins
            PATIENCE -> maxSurvivedSeconds
            MARATHON -> meters.toLong()
            SPRINTER -> maxMeters.toLong()
            BIG_COMPANY -> maxPlayers
            WEEKLY -> bestWeekStreak
            else -> error("Unknown achievement $id")
        }
    }
}
