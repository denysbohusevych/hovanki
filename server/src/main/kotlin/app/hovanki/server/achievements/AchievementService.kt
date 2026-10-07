package app.hovanki.server.achievements

import app.hovanki.server.api.AuthenticatedUser
import app.hovanki.server.leaderboard.LeaderboardService
import app.hovanki.shared.protocol.AchievementsResponse
import app.hovanki.shared.rules.AchievementRules
import app.hovanki.shared.rules.LeaderboardRules
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The player's achievements, «Достижения» (docs/adr/0021-achievements.md): computed on every request from their
 * history of finished games with [AchievementRules]; only when they last looked is stored. Their owner's only.
 */
@Service
class AchievementService(private val repository: AchievementRepository, private val clock: Clock) {
    fun achievements(user: AuthenticatedUser): AchievementsResponse {
        val seenAt = repository.seenAt(user.userId)
        val games = repository.results(user.userId).map { result ->
            AchievementRules.Game(
                finishedAtMillis = result.finishedAt.toEpochMilli(),
                week = weekNumber(result.finishedAt),
                role = result.role,
                status = result.status,
                won = result.won,
                players = result.players,
                catches = result.catches,
                survivedSeconds = result.survivedSeconds,
                distanceMeters = result.distanceMeters,
            )
        }
        val seenAtMillis = seenAt?.toEpochMilli()
        return AchievementsResponse(AchievementRules.progress(games, seenAtMillis), seenAtMillis)
    }

    /** The caller saw every level reached up to [upToMillis]; a time ahead of the server's clock counts as now. */
    fun markSeen(user: AuthenticatedUser, upToMillis: Long): AchievementsResponse {
        val now = clock.instant()
        repository.markSeen(user.userId, minOf(Instant.ofEpochMilli(upToMillis.coerceAtLeast(0)), now))
        return achievements(user)
    }

    /** The leaderboard's week of [at] ([LeaderboardService.weekOf]), numbered one after another. */
    private fun weekNumber(at: Instant): Long {
        val monday = LeaderboardService.weekOf(at).start.atZone(ZONE).toLocalDate()
        return Math.floorDiv(monday.toEpochDay() - FIRST_MONDAY_EPOCH_DAY, DAYS_PER_WEEK)
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of(LeaderboardRules.WEEK_TIME_ZONE)

        /** 1970-01-05, the first Monday after the epoch. */
        const val FIRST_MONDAY_EPOCH_DAY = 4L
        const val DAYS_PER_WEEK = 7L
    }
}
