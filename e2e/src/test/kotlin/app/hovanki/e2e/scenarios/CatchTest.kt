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
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class CatchTest {
    private val settings = GameSetups.fast()
    private val rules = settings.rules

    /** Scenario 2: the hider does not react; the claim counts as caught when the code timeout passes. */
    @Test
    fun silenceCountsAsCaught() = scenario("Hider stays silent") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK, behavior = BotBehavior(onClaim = ClaimReaction.Ignore))

        sam.createsGame(settings)
        join(anna)
        sam.startsGame(seekers = listOf(sam))
        anna.walksTo(PARK.offset(eastMeters = 20.0))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        sam.catchesUpWith(anna)
        sam.claimsCatch(anna)
        val claim = awaitCatch(anna, CatchStatus.CONFIRMED, within = (rules.catchCodeTimeoutSeconds + 5).seconds)
        check(
            claim.deadlineMillis - claim.createdAtMillis == rules.catchCodeTimeoutSeconds * 1000L,
            "confirmed exactly at the code timeout",
        )
        awaitStatus(anna, PlayerStatus.CAUGHT)

        // Anna was the only hider: the game is over at the moment of the automatic confirmation.
        val end = awaitPhase(GamePhase.FINISHED)
        check(
            end.finishedAtMillis == claim.deadlineMillis,
            "the game finished when the last hider was caught (${end.finishedAtMillis} vs ${claim.deadlineMillis})",
        )
    }

    /** Scenario 3a: the hider disputes, the other players vote against the catch. */
    @Test
    fun disputeRejectedByVotes() = scenario("Dispute rejected by votes") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK, behavior = BotBehavior(onClaim = ClaimReaction.Dispute()))
        val voteNo = BotBehavior(onDispute = VoteReaction.Vote(confirm = false))
        val boris = player("Boris", at = PARK, behavior = voteNo)
        val vera = player("Vera", at = PARK, behavior = voteNo)

        sam.createsGame(settings)
        join(anna, boris, vera)
        sam.startsGame(seekers = listOf(sam))
        anna.walksTo(PARK.offset(northMeters = 20.0))
        boris.walksTo(PARK.offset(eastMeters = -60.0))
        vera.walksTo(PARK.offset(eastMeters = 60.0))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        sam.catchesUpWith(anna)
        sam.claimsCatch(anna)
        awaitCatch(anna, CatchStatus.DISPUTED)
        // Resolved as soon as everybody outside the dispute voted, long before the voting deadline.
        val claim = awaitCatch(anna, CatchStatus.REJECTED, within = 5.seconds)
        check(claim.votes.size == 2 && claim.votes.none { it.confirm }, "Boris and Vera voted against")
        check(anna.onServer().status == PlayerStatus.ACTIVE, "Anna plays on")
        requireOk(sam.claimCatch(anna), "Sam may claim Anna again")
    }

    /** Scenario 3b: nobody votes and GPS says they were far apart (while possibly close): rejected. */
    @Test
    fun disputeWithoutVotesFarApart() = scenario("Dispute without votes, far apart") {
        // Exact 16 m accuracy: 65 m apart is "possibly within 40 m" (65 − 16 − 16 = 33), so the claim is accepted,
        // but the most likely distance is 65 m, so the default rule rejects it.
        val poorGps = GpsNoise(accuracyMeters = 16.0, accuracyJitterMeters = 0.0, exact = true)
        val sam = player("Sam", at = PARK, noise = poorGps)
        val anna = player("Anna", at = PARK, noise = poorGps, behavior = BotBehavior(onClaim = ClaimReaction.Dispute()))
        val boris = player("Boris", at = PARK)

        sam.createsGame(settings)
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        anna.walksTo(PARK.offset(eastMeters = 65.0), speed = 4.0)
        boris.walksTo(PARK.offset(eastMeters = -60.0))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        anna.arrives()
        // The claim looks at the fixes of the last decision window: none of them from Anna's walk.
        delay((rules.decisionWindowSeconds + 2).seconds)

        sam.claimsCatch(anna)
        val disputed = awaitCatch(anna, CatchStatus.DISPUTED)
        val claim = awaitCatch(anna, CatchStatus.REJECTED, within = (rules.disputeVoteSeconds + 5).seconds)
        check(
            claim.deadlineMillis >= disputed.createdAtMillis + rules.disputeVoteSeconds * 1000L,
            "decided at the voting deadline",
        )
        check(
            (claim.estimatedDistanceAtClaimMeters ?: 0.0) > rules.catchMaxDistanceMeters,
            "most likely distance ${claim.estimatedDistanceAtClaimMeters} m",
        )
        check(anna.onServer().status == PlayerStatus.ACTIVE, "Anna plays on")
    }

    /** Scenario 3c: nobody votes, GPS says they were close: the default rule confirms. */
    @Test
    fun disputeWithoutVotesClose() = scenario("Dispute without votes, close") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK, behavior = BotBehavior(onClaim = ClaimReaction.Dispute()))
        val boris = player("Boris", at = PARK)

        sam.createsGame(settings)
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        anna.walksTo(PARK.offset(northMeters = 25.0))
        boris.walksTo(PARK.offset(eastMeters = -60.0))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        sam.catchesUpWith(anna)
        sam.claimsCatch(anna)
        awaitCatch(anna, CatchStatus.DISPUTED)
        val claim = awaitCatch(anna, CatchStatus.CONFIRMED, within = (rules.disputeVoteSeconds + 5).seconds)
        check(claim.votes.isEmpty(), "nobody voted")
        awaitStatus(anna, PlayerStatus.CAUGHT)
    }

    /** Scenario 4a: GPS proves the players are far apart: the claim is refused outright. */
    @Test
    fun claimFromFarAway() = scenario("Claim from far away") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK)
        val boris = player("Boris", at = PARK)

        sam.createsGame(settings)
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        anna.walksTo(PARK.offset(eastMeters = 150.0), speed = 5.0)
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        anna.arrives()
        delay((rules.decisionWindowSeconds + 2).seconds)

        expectRejected(sam.claimCatch(anna), ErrorCode.TOO_FAR, "claim from 150 m")
        check(state().catches.isEmpty(), "no claim was created")
        check(anna.snapshot?.catches.orEmpty().isEmpty(), "Anna never saw a claim")
    }

    /** Scenario 4b: the seeker's GPS never delivered a fix: nothing to check the distance with. */
    @Test
    fun claimWithoutSeekerLocation() = scenario("Claim without seeker location") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 10.0))
        sam.turnsGpsOff()

        sam.createsGame(settings)
        join(anna)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        check(sam.onServer().latestFix == null, "the server has no fix of Sam")

        expectRejected(sam.claimCatch(anna), ErrorCode.NO_LOCATION, "claim without a fix")
        check(state().catches.isEmpty(), "no claim was created")
    }
}
