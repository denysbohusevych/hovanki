package app.hovanki.e2e.scenarios

import app.hovanki.e2e.bot.BotPlayer
import app.hovanki.e2e.bot.CommandResult
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.VisibilityReason
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** Games with more than one seeker. */
class SeekersTest {
    /**
     * Two seekers see each other in both phases. Both press "found" on the same hider at the same moment: exactly one
     * claim is created. A seeker with an open claim can't open another one.
     */
    @Test
    fun twoSeekers() = scenario("Two seekers") {
        val sam = player("Sam", at = PARK)
        val yura = player("Yura", at = PARK.offset(eastMeters = 10.0))
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -20.0))

        sam.createsGame(GameSetups.fast())
        join(yura, anna, boris)
        sam.startsGame(seekers = listOf(sam, yura))
        awaitPhase(GamePhase.HIDING)
        awaitThat("the seekers see each other while the hiders hide") { seeTeammates(sam, yura) }
        check(anna.snapshot?.players?.none { it.location != null } == true, "Anna sees nobody")

        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        awaitThat("the seekers see each other while seeking") {
            sam.snapshot?.phase == GamePhase.SEEKING && seeTeammates(sam, yura)
        }

        val (bySam, byYura) = coroutineScope {
            val first = async { sam.claimCatch(anna) }
            val second = async { yura.claimCatch(anna) }
            first.await() to second.await()
        }
        check(listOf(bySam, byYura).count { it == CommandResult.Ok } == 1, "exactly one claim is accepted")
        val (claimer, other) = if (bySam == CommandResult.Ok) sam to yura else yura to sam
        expectRejected(if (claimer == sam) byYura else bySam, ErrorCode.WRONG_STATE, "${other.name}'s claim")
        check(
            state().catches.count { it.hiderId == anna.id && it.status == CatchStatus.AWAITING_CODE } == 1,
            "one open claim on Anna on the server",
        )
        expectRejected(claimer.claimCatch(boris), ErrorCode.WRONG_STATE, "${claimer.name} has an open claim already")

        claimer.entersCodeShownBy(anna)
        awaitCatch(anna, CatchStatus.CONFIRMED)
        other.catches(boris)
        awaitPhase(GamePhase.FINISHED)
    }

    private fun seeTeammates(a: BotPlayer, b: BotPlayer): Boolean =
        a.snapshot?.players?.single { it.id == b.id }?.location?.exactReason == VisibilityReason.TEAMMATE &&
            b.snapshot?.players?.single { it.id == a.id }?.location?.exactReason == VisibilityReason.TEAMMATE
}
