package app.hovanki.e2e.scenarios

import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** The pause and the SOS (docs/adr/0019-pause-and-sos.md), with the phones and the server together. */
class PauseSosTest {
    /** The host's pause stops the round's clock on every phone; once it goes on, its end moves by the pause. */
    @Test
    fun aPauseStopsTheClock() = scenario("A pause stops the clock") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        val boris = player("Boris", at = PARK.offset(northMeters = -40.0))

        sam.createsGame(GameSetups.fast().copy(seekingSeconds = 120))
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        val endsAt = checkNotNull(state().phaseEndsAtMillis)

        requireOk(sam.setsPaused(true), "Sam puts the round on pause")
        awaitThat("every phone shows the pause") { players.all { it.snapshot?.pause != null } }
        expectRejected(anna.setsPaused(false), ErrorCode.FORBIDDEN, "only the host lets it go on")
        expectRejected(sam.claimCatch(anna), ErrorReason.GAME_PAUSED, "nobody is caught on pause")
        delay(4.seconds)
        check(state().phase == GamePhase.SEEKING, "the round still searches")

        requireOk(sam.setsPaused(false), "Sam lets the round go on")
        awaitThat("every phone goes on, the end moved by the pause") {
            players.all { bot ->
                val snapshot = bot.snapshot
                snapshot != null && snapshot.pause == null && (snapshot.phaseEndsAtMillis ?: 0) >= endsAt + 4_000
            }
        }
    }

    /**
     * An SOS stops the round and shows everybody where the caller is; the host lets it go on only once it is over, and
     * only the caller or the host ends it.
     */
    @Test
    fun anSosShowsEverybodyWhereTheCallerIs() = scenario("An SOS shows everybody where the caller is") {
        val annaAt = PARK.offset(eastMeters = 80.0)
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = annaAt)
        val boris = player("Boris", at = PARK.offset(northMeters = -60.0))

        sam.createsGame(GameSetups.fast().copy(seekingSeconds = 120))
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        awaitThat("Anna's phone sent where she is") { anna.onServer().latestUsableFix != null }

        requireOk(anna.callsSos(), "Anna calls for help")
        awaitThat("every phone sees Anna's SOS and where she is") {
            players.all { bot ->
                val call = bot.snapshot?.sos?.singleOrNull()
                call?.playerId == anna.id && call.location != null && bot.snapshot?.pause?.sos == true
            }
        }
        val seen = checkNotNull(sam.snapshot?.sos?.single()?.location)
        check(seen.point.distanceTo(annaAt) < 30.0, "the seeker sees where Anna really is")

        expectRejected(sam.setsPaused(false), ErrorReason.SOS_ACTIVE, "the round waits for the SOS")
        expectRejected(boris.endsSos(of = anna), ErrorCode.FORBIDDEN, "Boris can't end Anna's SOS")
        requireOk(anna.endsSos(), "Anna is fine")
        awaitThat("nobody sees an SOS any more, the round still waits") {
            players.all { it.snapshot?.sos?.isEmpty() == true && it.snapshot?.pause != null }
        }
        requireOk(sam.setsPaused(false), "Sam lets the round go on")
        awaitThat("every phone goes on") { players.all { it.snapshot?.pause == null } }
    }
}
