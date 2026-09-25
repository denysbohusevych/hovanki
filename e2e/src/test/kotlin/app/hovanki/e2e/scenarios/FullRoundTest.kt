package app.hovanki.e2e.scenarios

import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class FullRoundTest {
    /** Scenario 1: a whole round from the lobby to the results, every catch confirmed with the code. */
    @Test
    fun fullRound() = scenario("Full round") {
        val anna = player("Anna", at = PARK)
        val sam = player("Sam", at = PARK)
        val boris = player("Boris", at = PARK, noise = GpsNoise.city(seed = 2))
        val vera = player("Vera", at = PARK)
        val hiders = listOf(anna, boris, vera)

        anna.createsGame(GameSetups.fast())
        join(sam, boris, vera)
        check(state().players.map { it.name } == listOf("Anna", "Sam", "Boris", "Vera"), "everybody is in the lobby")
        awaitThat("every phone shows the full lobby") { players.all { it.snapshot?.players?.size == 4 } }

        anna.startsGame(seekers = listOf(sam))
        val hiding = awaitPhase(GamePhase.HIDING)
        check(hiding.players.single { it.role == Role.SEEKER }.id == sam.id, "Sam seeks, the others hide")
        awaitThat("every app tracks in the background") { players.all { it.backgroundTracker.isRunning } }

        anna.walksTo(PARK.offset(eastMeters = 30.0, northMeters = 30.0), speed = 4.0)
        boris.walksTo(PARK.offset(eastMeters = -50.0), speed = 4.0)
        vera.walksTo(PARK.offset(northMeters = -45.0), speed = 4.0)

        val seeking = awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        val initialRadius = checkNotNull(seeking.zone).radiusMeters
        eventually("the zone shrinks") { state().zone?.radiusMeters?.takeIf { it < initialRadius - 10 } }

        for (hider in hiders) {
            hider.arrives()
            sam.catches(hider, speed = 5.0)
        }

        val end = awaitPhase(GamePhase.FINISHED, within = 10.seconds)
        check(
            end.players.filter {
                it.role == Role.HIDER
            }.all { it.status == PlayerStatus.CAUGHT },
            "all hiders caught",
        )
        awaitThat("every phone shows the results") { players.all { it.snapshot?.phase == GamePhase.FINISHED } }
        awaitThat("apps stop background tracking") { players.none { it.backgroundTracker.isRunning } }
    }
}
