package app.hovanki.e2e.scenarios

import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.CapacityState
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.rules.Capacity
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * How many players a zone fits (docs/adr/0010-big-games.md): the host of a crowded game is warned in the lobby, plays
 * anyway, and is warned no more. The test source makes every zone built-up ground: one player per 1 000 m².
 */
class CapacityTest {
    @Test
    fun theHostPlaysAnywayInACrowdedZone() = scenario("Crowded zone") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 10.0))
        val boris = player("Boris", at = PARK.offset(northMeters = 10.0))
        val vera = player("Vera", at = PARK.offset(eastMeters = -10.0))
        // 30 m: about 2 800 m², room for 3.
        sam.createsGame(GameSetups.fixedZone(radiusMeters = 30.0))
        join(anna, boris, vera)

        awaitThat("Sam's lobby knows the zone fits 3") { sam.snapshot?.capacity?.players == 3 }
        awaitThat("Sam is warned: 4 players") {
            Capacity.needsWarning(sam.snapshot?.capacity, sam.snapshot?.players?.size ?: 0)
        }
        check(state().capacity?.state == CapacityState.READY, "the server has the estimate")
        expectRejected(anna.playsAnyway(), ErrorCode.FORBIDDEN, "only the host decides")

        requireOk(sam.playsAnyway(), "Sam plays anyway")

        awaitThat("the warning is gone on Sam's phone") {
            sam.snapshot?.let { !Capacity.needsWarning(it.capacity, it.players.size) } == true
        }
        check(state().capacity?.accepted == true, "the server remembers it")
        // A recommendation, not a limit: the game starts as usual.
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
    }
}
