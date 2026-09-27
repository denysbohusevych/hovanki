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

    /** Apps killed in the lobby come back to it: the host is still the host and starts the game, nobody is doubled. */
    @Test
    fun relaunchedInTheLobby() = scenario("App relaunched in the lobby") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(northMeters = 30.0))
        val boris = player("Boris", at = PARK.offset(northMeters = -40.0))

        sam.createsGame(GameSetups.fast())
        join(anna, boris)
        sam.killApp()
        anna.killApp()
        sam.launchApp()
        anna.launchApp()
        awaitThat("both relaunched apps are back in the lobby", 10.seconds) {
            listOf(sam, anna).all { it.snapshot?.phase == GamePhase.LOBBY && !it.state.isResuming }
        }
        check(sam.state.session?.playerId == sam.id && anna.state.session?.playerId == anna.id, "the same players")
        check(sam.snapshot?.hostId == sam.id, "Sam is still the host")
        check(state().players.size == 3, "nobody was added")

        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.HIDING)
        awaitThat("Anna's relaunched app has her catch code") { anna.snapshot?.me?.catchCodeSecret != null }
    }

    /** The hider's app dies right after a claim: relaunched, it shows the code again, and the code catches. */
    @Test
    fun hiderRelaunchedDuringAClaim() = scenario("Hider relaunched during a claim") {
        val codeTimeout = 30
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 15.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -60.0))

        sam.createsGame(GameSetups.fast(rules = rules.copy(catchCodeTimeoutSeconds = codeTimeout)))
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        sam.claimsCatch(anna)
        awaitThat("Anna sees the claim") { anna.snapshot?.catches?.any { it.hiderId == anna.id } == true }
        anna.killApp()
        delay(5.seconds)
        anna.launchApp()
        awaitThat("Anna's relaunched app is back in the game", 10.seconds) {
            anna.snapshot?.phase == GamePhase.SEEKING && !anna.state.isResuming
        }
        sam.entersCodeShownBy(anna)
        val claim = awaitCatch(anna, CatchStatus.CONFIRMED)
        check(
            claim.deadlineMillis < claim.createdAtMillis + codeTimeout * 1000L,
            "confirmed by the code, before the code timeout",
        )
    }

    /** The seeker's app dies right after the claim: relaunched, it shows the open claim and takes the code. */
    @Test
    fun seekerRelaunchedAfterClaiming() = scenario("Seeker relaunched after claiming") {
        val codeTimeout = 30
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 15.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -60.0))

        sam.createsGame(GameSetups.fast(rules = rules.copy(catchCodeTimeoutSeconds = codeTimeout)))
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        sam.claimsCatch(anna)
        sam.killApp()
        delay(3.seconds)
        sam.launchApp()
        awaitThat("Sam's relaunched app shows the open claim", 10.seconds) {
            sam.snapshot?.catches?.any { it.hiderId == anna.id && it.status == CatchStatus.AWAITING_CODE } == true
        }
        sam.entersCodeShownBy(anna)
        val claim = awaitCatch(anna, CatchStatus.CONFIRMED)
        check(
            claim.deadlineMillis < claim.createdAtMillis + codeTimeout * 1000L,
            "confirmed by the code, before the code timeout",
        )
    }

    /**
     * The app starts without network: it keeps the saved game and keeps trying, like a running game. Once the network
     * is back it is in the game and fresh fixes hide the player again.
     */
    @Test
    fun relaunchedOffline() = scenario("App relaunched offline") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(northMeters = 30.0))
        val boris = player("Boris", at = PARK.offset(northMeters = -60.0))

        sam.createsGame(GameSetups.fast())
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        delay(2.seconds)

        anna.killApp()
        anna.losesNetwork()
        anna.launchApp()
        awaitThat("Anna's app resumes the saved game") { anna.state.isResuming }
        holdsFor("Anna's app keeps the saved game and keeps trying", 20.seconds) {
            anna.state.session?.playerId == anna.id && anna.storage.read("session") != null
        }
        awaitReveal(anna, VisibilityReason.STALE_SIGNAL, to = sam, within = 5.seconds)

        anna.regainsNetwork()
        awaitThat("Anna's app is back in the game", 20.seconds) {
            anna.snapshot?.phase == GamePhase.SEEKING && !anna.state.isResuming
        }
        awaitThat("fresh fixes hide Anna from Sam again", 10.seconds) {
            sam.snapshot?.players?.single { it.id == anna.id }?.location == null
        }
        check(anna.backgroundTracker.isRunning, "background tracking is back")
    }
}
