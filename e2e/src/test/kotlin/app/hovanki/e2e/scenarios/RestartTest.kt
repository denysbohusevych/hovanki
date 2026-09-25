package app.hovanki.e2e.scenarios

import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.VisibilityReason
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class RestartTest {
    private val rules = GameSetups.FAST_RULES

    /**
     * Pins today's behavior: the session lives only in memory, so a killed app comes back on the start screen
     * and can't rejoin a running game. The server keeps the player; their silence reveals them and a claim
     * against them is confirmed by the code timeout.
     */
    @Test
    fun appKilledAndRelaunchedMidRound() = scenario("App killed and relaunched mid-round") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(northMeters = 30.0))
        val boris = player("Boris", at = PARK.offset(northMeters = -60.0))

        sam.createsGame(GameSetups.fast())
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        delay(2.seconds)

        anna.killApp()
        anna.launchApp()
        check(anna.state.session == null, "the relaunched app is back on the start screen")
        expectRejected(anna.join(joinCode), ErrorCode.WRONG_STATE, "joining the running game again")
        check(anna.onServer().status == PlayerStatus.ACTIVE, "the server still counts Anna in")

        awaitReveal(
            anna,
            VisibilityReason.STALE_SIGNAL,
            to = sam,
            within = (rules.staleLocationRevealSeconds + 5).seconds,
        )
        sam.catchesUpWith(anna)
        sam.claimsCatch(anna)
        awaitCatch(anna, CatchStatus.CONFIRMED, within = (rules.catchCodeTimeoutSeconds + 5).seconds)
        check(boris.onServer().status == PlayerStatus.ACTIVE, "the round goes on for the others")
    }
}
