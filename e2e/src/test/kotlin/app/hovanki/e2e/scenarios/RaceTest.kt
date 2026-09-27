package app.hovanki.e2e.scenarios

import app.hovanki.e2e.bot.BotBehavior
import app.hovanki.e2e.bot.ClaimReaction
import app.hovanki.e2e.bot.CommandResult
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.server.game.Game
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.totp.catchCodeTotp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** Things that happen at the same moment: the server decides each of them once and consistently. */
class RaceTest {
    /** More players than seats press "Join" at once: exactly the seats are taken, the others are told it's full. */
    @Test
    fun raceForTheLastSeats() = scenario("Race for the last seats") {
        val host = player("Host", at = PARK, logChanges = false)
        val crowd = (1..Game.MAX_PLAYERS + 4).map { player("J$it", at = PARK, logChanges = false) }
        host.createsGame(GameSetups.fast())

        val results = coroutineScope { crowd.map { bot -> async { bot.join(joinCode) } }.awaitAll() }
        val joined = results.count { it == CommandResult.Ok }
        check(joined == Game.MAX_PLAYERS - 1, "${Game.MAX_PLAYERS - 1} got in next to the host ($joined)")
        check(
            results.filter { it != CommandResult.Ok }
                .all { it is CommandResult.Rejected && it.code == ErrorCode.WRONG_STATE },
            "the other ${results.size - joined} were told the game is full",
        )
        check(state().players.size == Game.MAX_PLAYERS, "${Game.MAX_PLAYERS} players on the server")
        awaitThat("the host's lobby shows them all") { host.snapshot?.players?.size == Game.MAX_PLAYERS }
    }

    /** Everybody outside the dispute votes at the same moment: every vote counts, the dispute is decided once. */
    @Test
    fun everybodyVotesAtOnce() = scenario("Everybody votes at once") {
        val rules = GameSetups.FAST_RULES.copy(disputeVoteSeconds = 30)
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 15.0), behavior = BotBehavior(ClaimReaction.Dispute()))
        val voters = (1..5).map { player("V$it", at = PARK.offset(northMeters = 10.0 * it)) }

        sam.createsGame(GameSetups.fast(rules = rules))
        join(anna, *voters.toTypedArray())
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        sam.claimsCatch(anna)
        val dispute = awaitCatch(anna, CatchStatus.DISPUTED, within = 5.seconds)
        awaitThat("every voter's phone offers the vote") {
            voters.all { v -> v.snapshot?.catches?.any { it.id == dispute.id && it.canVote } == true }
        }

        val results = coroutineScope {
            voters.mapIndexed { n, voter -> async { voter.vote(dispute.id, confirm = n < 3) } }.awaitAll()
        }
        check(results.all { it == CommandResult.Ok }, "every vote was taken ($results)")
        val decided = awaitCatch(anna, CatchStatus.CONFIRMED, within = 3.seconds)
        check(decided.votes.size == voters.size, "all ${voters.size} votes counted")
        check(decided.deadlineMillis < dispute.deadlineMillis, "decided by the last vote, not by the deadline")
        awaitStatus(anna, PlayerStatus.CAUGHT)
    }

    /**
     * The code arrives in the same moment the code timeout confirms the catch by silence. Either way the claim is
     * decided once, as a catch, and the game (Anna was the last hider) ends once, at that moment.
     */
    @Test
    fun theCodeAtTheTimeout() = scenario("The code at the timeout") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 15.0), behavior = BotBehavior(ClaimReaction.Ignore))

        sam.createsGame(GameSetups.fast())
        join(anna)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        sam.claimsCatch(anna)
        val claim = checkNotNull(lastClaimOn(anna))
        val secret = checkNotNull(anna.onServer().catchCodeSecret)

        awaitServerTime(claim.deadlineMillis - 20)
        val code = catchCodeTotp(secret, GameSetups.FAST_RULES).codeAt(checkNotNull(sam.serverNow()))
        // Straight to the server, whatever Sam's screen shows by now.
        val typed = sam.confirmCatch(claim.id, code)
        note("the code arrived ${if (typed == CommandResult.Ok) "in time" else "too late: $typed"}")
        check(
            typed == CommandResult.Ok || (typed is CommandResult.Rejected && typed.code == ErrorCode.WRONG_STATE),
            "the code was taken, or the claim was closed already",
        )
        val decided = awaitCatch(anna, CatchStatus.CONFIRMED, within = 5.seconds)
        check(decided.deadlineMillis <= claim.deadlineMillis, "decided no later than the timeout")
        val end = awaitPhase(GamePhase.FINISHED)
        check(end.finishedAtMillis == decided.deadlineMillis, "the game ended once, when the catch was decided")
    }

    /**
     * The code for the last hider arrives in the same moment the seeking time runs out. Whichever came first, the
     * outcome is consistent: caught and ended at the catch, or not caught and ended by the timer.
     */
    @Test
    fun theLastCatchAtTheEndOfSeeking() = scenario("The last catch at the end of seeking") {
        val rules = GameSetups.FAST_RULES.copy(catchCodeTimeoutSeconds = 60)
        val settings = GameSetups.fast(rules = rules).copy(seekingSeconds = 20)
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 15.0), behavior = BotBehavior(ClaimReaction.Ignore))

        sam.createsGame(settings)
        join(anna)
        sam.startsGame(seekers = listOf(sam))
        val seeking = awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        val seekingEnds = checkNotNull(seeking.zoneStartedAtMillis) + settings.seekingSeconds * 1000L
        sam.claimsCatch(anna)
        val claimId = checkNotNull(lastClaimOn(anna)).id
        val secret = checkNotNull(anna.onServer().catchCodeSecret)

        awaitServerTime(seekingEnds - 20)
        val code = catchCodeTotp(secret, rules).codeAt(checkNotNull(sam.serverNow()))
        val typed = sam.confirmCatch(claimId, code)
        val end = awaitPhase(GamePhase.FINISHED, within = 5.seconds)
        val claim = checkNotNull(lastClaimOn(anna))
        val caught = anna.onServer().status == PlayerStatus.CAUGHT
        note("the code arrived ${if (typed == CommandResult.Ok) "in time" else "too late: $typed"}")
        if (caught) {
            check(typed == CommandResult.Ok && claim.status == CatchStatus.CONFIRMED, "caught by the code")
            check(end.finishedAtMillis == claim.deadlineMillis, "the game ended with the catch")
            check(checkNotNull(end.finishedAtMillis) <= seekingEnds, "before the time ran out")
        } else {
            check(claim.status == CatchStatus.REJECTED, "the open claim was closed with the game")
            check(end.finishedAtMillis == seekingEnds, "the game ended by the timer")
        }
    }
}
