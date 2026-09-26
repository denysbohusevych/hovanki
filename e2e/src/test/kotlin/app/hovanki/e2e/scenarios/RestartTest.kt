package app.hovanki.e2e.scenarios

import app.hovanki.client.session.SessionError
import app.hovanki.e2e.bot.BotBehavior
import app.hovanki.e2e.bot.ClaimReaction
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.VisibilityReason
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class RestartTest {
    private val rules = GameSetups.FAST_RULES

    /**
     * The app is killed mid-round. While it is dead, the server keeps the player and reveals their last point to the
     * seekers (stale signal). The relaunched app resumes the session saved on the phone: back on the game screen, fresh
     * fixes hide the player again, and a claim against them is confirmed with the code on their screen, not by the
     * timeout.
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
        check(anna.onServer().status == PlayerStatus.ACTIVE, "the server still counts Anna in")
        awaitReveal(
            anna,
            VisibilityReason.STALE_SIGNAL,
            to = sam,
            within = (rules.staleLocationRevealSeconds + 5).seconds,
        )

        anna.launchApp()
        awaitThat("Anna's relaunched app is back in the game", 10.seconds) {
            anna.snapshot?.phase == GamePhase.SEEKING && !anna.state.isResuming
        }
        check(anna.state.session?.playerId == anna.id, "the same player as before the restart")
        check(anna.snapshot?.me?.catchCodeSecret != null, "Anna's phone can show the catch code again")
        awaitThat("fresh fixes hide Anna from Sam again", 10.seconds) {
            sam.snapshot?.players?.single { it.id == anna.id }?.location == null
        }
        check(anna.onServer().revealedToSeekers == null, "the server no longer reveals Anna")
        check(anna.backgroundTracker.isRunning, "background tracking is back")

        sam.catchesUpWith(anna)
        sam.claimsCatch(anna)
        sam.entersCodeShownBy(anna)
        val claim = awaitCatch(anna, CatchStatus.CONFIRMED)
        check(
            claim.deadlineMillis < claim.createdAtMillis + rules.catchCodeTimeoutSeconds * 1000L,
            "confirmed by the code, before the code timeout",
        )
        check(boris.onServer().status == PlayerStatus.ACTIVE, "the round goes on for the others")
    }

    /**
     * The app is killed and the game ends before it is opened again: the relaunched app finds the saved session,
     * learns from the server that the game is over, forgets it and shows the start screen with a message.
     */
    @Test
    fun relaunchedAfterTheGameEnded() = scenario("App relaunched after the game ended") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(northMeters = 30.0))
        val boris = player("Boris", at = PARK.offset(northMeters = -40.0))

        sam.createsGame(GameSetups.fast())
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        delay(2.seconds)

        anna.killApp()
        sam.catches(boris)
        // Nobody answers for Anna's dead app: the claim is confirmed by the timeout and the game ends.
        anna.behavior = BotBehavior(onClaim = ClaimReaction.Ignore)
        sam.catchesUpWith(anna)
        sam.claimsCatch(anna)
        awaitCatch(anna, CatchStatus.CONFIRMED, within = (rules.catchCodeTimeoutSeconds + 5).seconds)
        awaitPhase(GamePhase.FINISHED)

        anna.launchApp()
        awaitThat("Anna's relaunched app is on the start screen", 10.seconds) {
            anna.state.session == null && anna.state.lastError == SessionError.SavedGameFinished
        }
        check(anna.storage.read("session") == null, "the finished game is no longer saved on the phone")
    }
}
