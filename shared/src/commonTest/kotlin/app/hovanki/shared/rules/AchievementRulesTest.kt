package app.hovanki.shared.rules

import app.hovanki.shared.protocol.AchievementProgress
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.rules.AchievementRules.Game
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AchievementRulesTest {
    private fun hider(at: Long, found: Boolean, survived: Int = 300, week: Long = 0, meters: Double = 0.0) = Game(
        finishedAtMillis = at,
        week = week,
        role = Role.HIDER,
        status = if (found) PlayerStatus.CAUGHT else PlayerStatus.ACTIVE,
        won = !found,
        players = 4,
        catches = 0,
        survivedSeconds = survived,
        distanceMeters = meters,
    )

    private fun seeker(at: Long, catches: Int, won: Boolean = false, players: Int = 4, week: Long = 0) = Game(
        finishedAtMillis = at,
        week = week,
        role = Role.SEEKER,
        status = PlayerStatus.ACTIVE,
        won = won,
        players = players,
        catches = catches,
        survivedSeconds = null,
        distanceMeters = 0.0,
    )

    private fun List<AchievementProgress>.of(id: String) = single { it.id == id }

    @Test
    fun noGamesNothingReached() {
        val progress = AchievementRules.progress(emptyList(), seenAtMillis = null)
        assertEquals(AchievementRules.ALL.map { it.id }, progress.map { it.id })
        assertTrue(progress.all { it.level == 0 && it.value == 0L && it.unlockedAtMillis == null && !it.isNew })
    }

    @Test
    fun gamesCountAndLevelTimes() {
        val games = (1..12L).map { seeker(at = it * 100, catches = 0) }
        val played = AchievementRules.progress(games.shuffled(), seenAtMillis = null).of(AchievementRules.GAMES)
        assertEquals(12, played.value)
        assertEquals(2, played.level)
        // The 10th game reached the second level.
        assertEquals(1000, played.unlockedAtMillis)
        assertTrue(played.isNew)
    }

    @Test
    fun newOnlyAfterSeen() {
        val games = listOf(seeker(at = 100, catches = 1), seeker(at = 200, catches = 0))
        val progress = AchievementRules.progress(games, seenAtMillis = 150)
        assertFalse(progress.of(AchievementRules.CATCHES).isNew)
        assertEquals(100, progress.of(AchievementRules.CATCHES).unlockedAtMillis)
        // Two games: still level 1 of «games», reached at 100, before the player looked.
        assertFalse(progress.of(AchievementRules.GAMES).isNew)
        val later = AchievementRules.progress(games + seeker(at = 300, catches = 0, won = true), seenAtMillis = 150)
        assertTrue(later.of(AchievementRules.SEEKERS_WON).isNew)
    }

    @Test
    fun hidersNeverFoundAndTheStreak() {
        val games = listOf(
            hider(at = 1, found = false),
            hider(at = 2, found = false),
            hider(at = 3, found = true),
            hider(at = 4, found = false),
            // A seeker's game does not break the hider's streak.
            seeker(at = 5, catches = 2),
            hider(at = 6, found = false),
            hider(at = 7, found = false, survived = 21 * 60),
        )
        val progress = AchievementRules.progress(games, seenAtMillis = null)
        assertEquals(5, progress.of(AchievementRules.NEVER_FOUND).value)
        assertEquals(2, progress.of(AchievementRules.NEVER_FOUND).level)
        val uncatchable = progress.of(AchievementRules.UNCATCHABLE)
        assertEquals(3, uncatchable.value)
        assertEquals(1, uncatchable.level)
        assertEquals(7, uncatchable.unlockedAtMillis)
        assertEquals(1, progress.of(AchievementRules.PATIENCE).level)
        assertEquals(7, progress.of(AchievementRules.PATIENCE).unlockedAtMillis)
    }

    @Test
    fun catchesTotalAndBestGame() {
        val games = listOf(seeker(at = 1, catches = 2), seeker(at = 2, catches = 3, won = true, players = 11))
        val progress = AchievementRules.progress(games, seenAtMillis = null)
        assertEquals(5, progress.of(AchievementRules.CATCHES).value)
        assertEquals(1, progress.of(AchievementRules.CATCHES).level)
        assertEquals(1, progress.of(AchievementRules.CATCHES).unlockedAtMillis)
        assertEquals(1, progress.of(AchievementRules.ROUNDUP).level)
        assertEquals(1, progress.of(AchievementRules.SEEKERS_WON).level)
        assertEquals(1, progress.of(AchievementRules.BIG_COMPANY).level)
        assertEquals(11, progress.of(AchievementRules.BIG_COMPANY).value)
    }

    @Test
    fun distanceTotalAndBestGame() {
        val games = listOf(
            hider(at = 1, found = true, meters = 1_900.0),
            hider(at = 2, found = true, meters = 2_100.5),
            hider(at = 3, found = true, meters = Double.NaN),
            hider(at = 4, found = true, meters = 1_000.0),
        )
        val progress = AchievementRules.progress(games, seenAtMillis = null)
        assertEquals(5_000, progress.of(AchievementRules.MARATHON).value)
        assertEquals(1, progress.of(AchievementRules.MARATHON).level)
        assertEquals(4, progress.of(AchievementRules.MARATHON).unlockedAtMillis)
        assertEquals(2_100, progress.of(AchievementRules.SPRINTER).value)
        assertEquals(2, progress.of(AchievementRules.SPRINTER).unlockedAtMillis)
    }

    @Test
    fun weeksInARow() {
        val games = listOf(
            seeker(at = 1, catches = 0, week = 10),
            seeker(at = 2, catches = 0, week = 10),
            seeker(at = 3, catches = 0, week = 11),
            seeker(at = 4, catches = 0, week = 12),
            // A week missed: the streak starts again.
            seeker(at = 5, catches = 0, week = 14),
            seeker(at = 6, catches = 0, week = 15),
        )
        val weekly = AchievementRules.progress(games, seenAtMillis = null).of(AchievementRules.WEEKLY)
        assertEquals(3, weekly.value)
        assertEquals(0, weekly.level)
        assertNull(weekly.unlockedAtMillis)
        val more = games + listOf(16L, 17L).map { seeker(at = it, catches = 0, week = it) }
        val reached = AchievementRules.progress(more, seenAtMillis = null).of(AchievementRules.WEEKLY)
        assertEquals(4, reached.value)
        assertEquals(17, reached.unlockedAtMillis)
    }
}
