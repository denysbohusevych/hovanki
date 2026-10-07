package app.hovanki.e2e.scenarios

import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.rules.AchievementRules
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The achievements, «Достижения» (docs/adr/0021-achievements.md), after a whole game: a new account has none, the
 * game reaches the first levels by [AchievementRules], they are new until seen, and each player sees only their own.
 */
class AchievementsTest {
    @Test
    fun aFinishedGameReachesTheFirstLevels() = scenario("Achievements after a game") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -20.0))
        val guest = player("Guest", at = PARK.offset(northMeters = 10.0))
        sam.signsUp()
        anna.signsUp()
        boris.signsUp()

        requireOk(sam.openAchievements(), "Sam opens his achievements before any game")
        val before = checkNotNull(sam.achievements)
        check(before.achievements.map { it.id } == AchievementRules.ALL.map { it.id }, "every achievement listed")
        check(before.achievements.all { it.level == 0 && !it.isNew }, "nothing reached yet")

        sam.createsGame(GameSetups.fast())
        join(anna, boris, guest)
        sam.startsGame(seekers = listOf(sam))
        anna.walksTo(PARK.offset(eastMeters = 60.0), speed = 4.0)
        boris.walksTo(PARK.offset(eastMeters = -60.0), speed = 4.0)
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        anna.arrives()
        boris.arrives()
        sam.catches(guest)
        sam.catches(anna)
        sam.catches(boris)
        awaitPhase(GamePhase.FINISHED)

        // Sam found all three: the first game, the first catch, a roundup and a clean sweep.
        val samGot = eventually("Sam's game counts in his achievements", within = 10.seconds) {
            sam.openAchievements()
            sam.achievements?.achievements?.associateBy { it.id }
                ?.takeIf { it.getValue(AchievementRules.GAMES).level == 1 }
        }
        for (id in listOf(AchievementRules.GAMES, AchievementRules.CATCHES, AchievementRules.ROUNDUP)) {
            check(samGot.getValue(id).level == 1 && samGot.getValue(id).isNew, "Sam reached $id, new")
        }
        check(samGot.getValue(AchievementRules.SEEKERS_WON).level == 1, "Sam's team found everybody")
        check(samGot.getValue(AchievementRules.CATCHES).value == 3L, "three catches")
        check(samGot.getValue(AchievementRules.NEVER_FOUND).level == 0, "Sam never hid")

        // Anna was found: one game, nothing as a hider; Sam's catches are not hers.
        val annaGot = eventually("Anna's game counts in her achievements", within = 10.seconds) {
            anna.openAchievements()
            anna.achievements?.achievements?.associateBy { it.id }
                ?.takeIf { it.getValue(AchievementRules.GAMES).level == 1 }
        }
        check(annaGot.getValue(AchievementRules.CATCHES).value == 0L, "Anna found nobody")
        check(annaGot.getValue(AchievementRules.NEVER_FOUND).level == 0, "Anna was found")

        // Seen: no longer new.
        val newest = samGot.values.mapNotNull { it.unlockedAtMillis }.max()
        requireOk(sam.seesAchievements(newest), "Sam sees his new achievements")
        check(sam.achievements?.achievements.orEmpty().none { it.isNew }, "nothing new after seeing")
        requireOk(sam.openAchievements(), "Sam opens them again")
        check(sam.achievements?.achievements.orEmpty().none { it.isNew }, "still nothing new")
    }
}
