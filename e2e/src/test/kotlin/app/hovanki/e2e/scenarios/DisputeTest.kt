package app.hovanki.e2e.scenarios

import app.hovanki.e2e.bot.BotBehavior
import app.hovanki.e2e.bot.ClaimReaction
import app.hovanki.e2e.bot.VoteReaction
import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.rules.ZoneRules
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** Disputed catches: who votes, what a dispute freezes, how it ends without voters. */
class DisputeTest {
    /**
     * While a dispute is open the hider is frozen: out of the zone for longer than the decision window, yet neither
     * warned, nor revealed, nor eliminated. Once the dispute is rejected the zone rule applies again.
     */
    @Test
    fun frozenDuringADispute() = scenario("Frozen during a dispute") {
        val rules = GameSetups.FAST_RULES.copy(disputeVoteSeconds = 40)
        val exact8 = GpsNoise(accuracyMeters = 8.0, accuracyJitterMeters = 0.0, exact = true)
        val lateNo = BotBehavior(onDispute = VoteReaction.Vote(confirm = false, after = 30.seconds))
        val sam = player("Sam", at = PARK)
        val anna = player(
            "Anna",
            at = PARK.offset(eastMeters = 60.0),
            noise = exact8,
            behavior = BotBehavior(onClaim = ClaimReaction.Dispute()),
        )
        val boris = player("Boris", at = PARK.offset(eastMeters = -50.0), behavior = lateNo)
        val vera = player("Vera", at = PARK.offset(northMeters = -50.0), behavior = lateNo)

        sam.createsGame(GameSetups.fixedZone(100.0, rules = rules))
        join(anna, boris, vera)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        sam.catchesUpWith(anna)
        sam.claimsCatch(anna)
        awaitCatch(anna, CatchStatus.DISPUTED, within = 5.seconds)
        // 90 m at 8 m/s: clearly outside (150 − 8 > 110) after ~7 s, confidently after one more decision window.
        anna.walksTo(PARK.offset(eastMeters = 150.0), speed = 8.0)
        holdsFor("Anna is frozen while the dispute is open", 26.seconds) {
            val server = anna.onServer()
            server.outOfZoneSinceMillis == null && server.revealedToSeekers == null &&
                server.status == PlayerStatus.ACTIVE && lastClaimOn(anna)?.status == CatchStatus.DISPUTED
        }
        val server = state()
        val fix = checkNotNull(server.players.single { it.id == anna.id }.latestUsableFix)
        check(ZoneRules.isClearlyOutside(fix, checkNotNull(server.zone), rules), "Anna is clearly outside meanwhile")

        awaitCatch(anna, CatchStatus.REJECTED, within = 10.seconds)
        eventually("Anna is warned once the dispute is over", within = 5.seconds) {
            anna.onServer().outOfZoneSinceMillis
        }
        awaitReveal(anna, VisibilityReason.OUT_OF_ZONE, to = sam, within = 5.seconds)
    }

    /** Two players only: nobody can vote, the dispute is decided at once by the default rule (close: caught). */
    @Test
    fun oneOnOne() = scenario("Dispute one on one") {
        val rules = GameSetups.FAST_RULES
        val sam = player("Sam", at = PARK)
        val anna = player(
            "Anna",
            at = PARK.offset(eastMeters = 15.0),
            behavior = BotBehavior(onClaim = ClaimReaction.Dispute()),
        )

        sam.createsGame(GameSetups.fast())
        join(anna)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        sam.claimsCatch(anna)
        val claim = awaitCatch(anna, CatchStatus.CONFIRMED, within = 5.seconds)
        check(claim.votes.isEmpty(), "nobody voted")
        check(
            claim.deadlineMillis - claim.createdAtMillis < rules.disputeVoteSeconds * 1000L,
            "decided when Anna disputed, without waiting for votes",
        )
        awaitPhase(GamePhase.FINISHED)
    }

    /**
     * Everybody outside the dispute votes: the other seeker, a hider, and a hider caught earlier. The two in the
     * dispute can't. The dispute ends with the last vote, by majority.
     */
    @Test
    fun whoVotes() = scenario("Who votes in a dispute") {
        val rules = GameSetups.FAST_RULES.copy(disputeVoteSeconds = 40)
        val sam = player("Sam", at = PARK)
        val yura = player("Yura", at = PARK.offset(eastMeters = -10.0))
        val anna = player(
            "Anna",
            at = PARK.offset(eastMeters = 15.0),
            behavior = BotBehavior(onClaim = ClaimReaction.Dispute()),
        )
        val boris = player("Boris", at = PARK.offset(northMeters = 30.0))
        val vera = player("Vera", at = PARK.offset(eastMeters = -20.0))

        sam.createsGame(GameSetups.fast(rules = rules))
        join(yura, anna, boris, vera)
        sam.startsGame(seekers = listOf(sam, yura))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        yura.catches(vera)

        sam.claimsCatch(anna)
        val claim = awaitCatch(anna, CatchStatus.DISPUTED, within = 5.seconds)
        val voters = listOf(yura, boris, vera)
        awaitThat("Yura, Boris and caught Vera may vote") {
            voters.all { voter -> voter.snapshot?.catches?.single { it.id == claim.id }?.canVote == true }
        }
        check(
            listOf(sam, anna).all { p -> p.snapshot?.catches?.single { it.id == claim.id }?.canVote == false },
            "Sam and Anna may not",
        )
        expectRejected(sam.vote(claim.id, confirm = true), ErrorCode.FORBIDDEN, "the seeker in the dispute votes")
        expectRejected(anna.vote(claim.id, confirm = false), ErrorCode.FORBIDDEN, "the hider in the dispute votes")

        requireOk(yura.vote(claim.id, confirm = true), "Yura votes to confirm")
        requireOk(boris.vote(claim.id, confirm = false), "Boris votes to reject")
        val yurasView = yura.snapshot?.catches?.single { it.id == claim.id }
        check(yurasView?.canVote == false && yurasView.myVote == true, "Yura's phone shows his vote")
        check(lastClaimOn(anna)?.status == CatchStatus.DISPUTED, "1 : 1 so far, Vera has not voted")

        requireOk(vera.vote(claim.id, confirm = true), "Vera votes to confirm")
        val decided = awaitCatch(anna, CatchStatus.CONFIRMED, within = 3.seconds)
        check(decided.votes.size == 3, "three votes")
        check(
            decided.deadlineMillis < claim.deadlineMillis,
            "decided by the last vote, before the voting deadline",
        )
        expectRejected(boris.vote(claim.id, confirm = false), ErrorCode.WRONG_STATE, "a vote after the dispute ended")
    }

    /** A tie (everybody voted, 1 : 1) is decided by the default rule: they were close, so the catch counts. */
    @Test
    fun aTieFallsBackToGps() = scenario("A tie falls back to GPS") {
        val rules = GameSetups.FAST_RULES.copy(disputeVoteSeconds = 40)
        val sam = player("Sam", at = PARK)
        val anna = player(
            "Anna",
            at = PARK.offset(eastMeters = 15.0),
            behavior = BotBehavior(onClaim = ClaimReaction.Dispute()),
        )
        val boris = player(
            "Boris",
            at = PARK.offset(northMeters = 40.0),
            behavior = BotBehavior(onDispute = VoteReaction.Vote(confirm = true)),
        )
        val vera = player(
            "Vera",
            at = PARK.offset(northMeters = -40.0),
            behavior = BotBehavior(onDispute = VoteReaction.Vote(confirm = false)),
        )

        sam.createsGame(GameSetups.fast(rules = rules))
        join(anna, boris, vera)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        sam.claimsCatch(anna)
        val claim = awaitCatch(anna, CatchStatus.CONFIRMED, within = 10.seconds)
        check(claim.votes.count { it.confirm } == 1 && claim.votes.count { !it.confirm } == 1, "one vote each way")
        check(
            (claim.estimatedDistanceAtClaimMeters ?: 0.0) <= rules.catchMaxDistanceMeters,
            "GPS says they were close (${claim.estimatedDistanceAtClaimMeters} m)",
        )
        check(
            claim.deadlineMillis - claim.createdAtMillis < rules.disputeVoteSeconds * 1000L,
            "decided with the last vote, not at the deadline",
        )
    }
}
