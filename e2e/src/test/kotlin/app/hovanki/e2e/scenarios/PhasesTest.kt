package app.hovanki.e2e.scenarios

import app.hovanki.e2e.bot.BotBehavior
import app.hovanki.e2e.bot.ClaimReaction
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.VisibilityReason
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** The phases of a game: how it starts, how it ends when nobody is caught, what the lobby refuses. */
class PhasesTest {
    /**
     * Nobody is caught before the seeking time is over: the game ends by the timer, the hiders still in it. A claim
     * still open at that moment (the hider ignores it, the code timeout is later) is rejected, not counted.
     */
    @Test
    fun seekingTimeRunsOut() = scenario("Seeking time runs out") {
        val rules = GameSetups.FAST_RULES.copy(catchCodeTimeoutSeconds = 20)
        val settings = GameSetups.fast(rules = rules).copy(seekingSeconds = 30)
        val sam = player("Sam", at = PARK)
        val anna = player(
            "Anna",
            at = PARK.offset(eastMeters = 20.0),
            behavior = BotBehavior(onClaim = ClaimReaction.Ignore),
        )
        val boris = player("Boris", at = PARK.offset(northMeters = -60.0))

        sam.createsGame(settings)
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        val seeking = awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        val seekingEnds = checkNotNull(seeking.zoneStartedAtMillis) + settings.seekingSeconds * 1000L
        check(seeking.phaseEndsAtMillis == seekingEnds, "the server says when seeking ends")

        // 8 s before the end: the code timeout (20 s) would come after it.
        awaitServerTime(seekingEnds - 8_000)
        sam.claimsCatch(anna)
        check(lastClaimOn(anna)?.status == CatchStatus.AWAITING_CODE, "the claim on Anna is open")

        val end = awaitPhase(GamePhase.FINISHED, within = 15.seconds)
        check(end.finishedAtMillis == seekingEnds, "the game finished exactly when the seeking time was over")
        check(lastClaimOn(anna)?.status == CatchStatus.REJECTED, "the open claim is rejected, not counted")
        check(
            end.players.filter { it.role == Role.HIDER }.all { it.status == PlayerStatus.ACTIVE },
            "Anna and Boris are still in the game: nobody was caught",
        )
        awaitThat("every phone shows the results") { players.all { it.snapshot?.phase == GamePhase.FINISHED } }
        check(anna.snapshot?.me?.status == PlayerStatus.ACTIVE, "Anna's phone shows she was not caught")
        awaitThat("apps stop background tracking") { players.none { it.backgroundTracker.isRunning } }
        check(players.none { it.storage.read("session") != null }, "no phone keeps the finished game to resume")
        for (player in players) requireOk(player.leave(), "${player.name} closes the results")
    }

    /** Without hiding time the game goes straight to SEEKING: the zone and the claims start at once. */
    @Test
    fun noHidingTime() = scenario("No hiding time") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 15.0))

        sam.createsGame(GameSetups.fast().copy(hidingSeconds = 0))
        join(anna)
        sam.startsGame(seekers = listOf(sam))
        check(sam.snapshot?.phase == GamePhase.SEEKING, "the start already answers with SEEKING")
        val seeking = state()
        check(seeking.phase == GamePhase.SEEKING, "the server is in SEEKING")
        check(seeking.zoneStartedAtMillis == seeking.phaseStartedAtMillis, "the zone starts with the game")
        awaitThat("Anna's phone has her catch code") { anna.snapshot?.me?.catchCodeSecret != null }
        awaitThat("both send usable fixes") {
            sam.onServer().latestUsableFix != null && anna.onServer().latestUsableFix != null
        }

        sam.claimsCatch(anna)
        sam.entersCodeShownBy(anna)
        awaitCatch(anna, CatchStatus.CONFIRMED)
        awaitPhase(GamePhase.FINISHED)
    }

    /** What the lobby and the hiding phase refuse, and a join code typed carelessly. */
    @Test
    fun lobbyMistakes() = scenario("Lobby mistakes") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -20.0))

        expectRejected(anna.join("222222"), ErrorCode.NOT_FOUND, "an unknown join code")
        sam.createsGame(GameSetups.fast())
        requireOk(anna.join("  ${joinCode.lowercase()} "), "Anna types the code in lower case, with spaces")
        join(boris)
        check(state().players.size == 3, "three players in the lobby")

        expectRejected(anna.startGame(listOf(sam)), ErrorCode.FORBIDDEN, "only the host starts the game")
        expectRejected(sam.startGame(emptyList()), ErrorCode.BAD_REQUEST, "no seeker picked")
        expectRejected(sam.startGame(listOf(sam, anna, boris)), ErrorCode.BAD_REQUEST, "everybody seeks, nobody hides")
        check(state().phase == GamePhase.LOBBY, "still in the lobby")

        sam.startsGame(seekers = listOf(sam))
        check(state().phase == GamePhase.HIDING, "hiding")
        expectRejected(sam.claimCatch(anna), ErrorCode.WRONG_STATE, "no claims while the hiders hide")
        check(state().catches.isEmpty(), "no claim on the server")
    }
}
