package app.hovanki.e2e.scenarios

import app.hovanki.e2e.bot.BotBehavior
import app.hovanki.e2e.bot.BotPlayer
import app.hovanki.e2e.bot.ClaimReaction
import app.hovanki.e2e.bot.VoteReaction
import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.e2e.scenario.Scenario
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.totp.catchCodeTotp
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
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

    /**
     * Wrong codes count: four and then the right one still catch; five wrong ones reject the claim, the hider plays on
     * and may be claimed again.
     */
    @Test
    fun wrongCodes() = scenario("Wrong codes") {
        val rules = GameSetups.FAST_RULES.copy(catchCodeTimeoutSeconds = 60)
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 15.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -15.0))

        sam.createsGame(GameSetups.fast(rules = rules))
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        sam.claimsCatch(anna)
        repeat(rules.catchCodeMaxAttempts - 1) { attempt ->
            val wrong = wrongCodeFor(anna, rules)
            expectRejected(sam.confirmCatch(wrong), ErrorCode.INVALID_CODE, "wrong code ${attempt + 1}")
        }
        check(lastClaimOn(anna)?.failedAttempts == rules.catchCodeMaxAttempts - 1, "the server counted them")
        check(lastClaimOn(anna)?.status == CatchStatus.AWAITING_CODE, "the claim is still open")
        sam.entersCodeShownBy(anna)
        awaitCatch(anna, CatchStatus.CONFIRMED)

        sam.claimsCatch(boris)
        repeat(rules.catchCodeMaxAttempts) { attempt ->
            val wrong = wrongCodeFor(boris, rules)
            expectRejected(sam.confirmCatch(wrong), ErrorCode.INVALID_CODE, "wrong code ${attempt + 1}")
        }
        check(lastClaimOn(boris)?.status == CatchStatus.REJECTED, "five wrong codes reject the claim")
        check(boris.onServer().status == PlayerStatus.ACTIVE, "Boris plays on")
        awaitThat("Sam's phone shows the claim closed") {
            sam.snapshot?.catches?.none { it.hiderId == boris.id && it.status == CatchStatus.AWAITING_CODE } == true
        }
        sam.claimsCatch(boris)
        check(lastClaimOn(boris)?.status == CatchStatus.AWAITING_CODE, "a new claim on Boris")
        sam.entersCodeShownBy(boris)
        awaitCatch(boris, CatchStatus.CONFIRMED)
        awaitPhase(GamePhase.FINISHED)
    }

    /**
     * The hider reads the four digits aloud and the seeker types them a period later: still accepted. Two periods
     * later: rejected.
     */
    @Test
    fun codeReadAloudLate() = scenario("Code read aloud late") {
        val rules = GameSetups.FAST_RULES.copy(catchCodePeriodSeconds = 10, catchCodeTimeoutSeconds = 60)
        val period = rules.catchCodePeriodSeconds * 1000L
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 15.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -15.0))

        sam.createsGame(GameSetups.fast(rules = rules))
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        sam.claimsCatch(anna)
        val (annasCode, annasPeriod) = readMidPeriod(anna, period)
        awaitServerTime((annasPeriod + 1) * period + period / 2)
        requireOk(sam.confirmCatch(annasCode), "Sam types the code of the previous period")
        awaitCatch(anna, CatchStatus.CONFIRMED)

        sam.claimsCatch(boris)
        val (borisCode, borisPeriod) = readMidPeriod(boris, period)
        awaitServerTime((borisPeriod + 2) * period + period / 2)
        expectRejected(sam.confirmCatch(borisCode), ErrorCode.INVALID_CODE, "a code two periods old")
        sam.entersCodeShownBy(boris)
        awaitCatch(boris, CatchStatus.CONFIRMED)
    }

    /**
     * A hider without a single fix (GPS off all game): GPS can't disprove the claim, even from 150 m, and a dispute
     * without votes counts for the seeker. Turning GPS off doesn't save anybody.
     */
    @Test
    fun hiderWithoutGps() = scenario("Hider without GPS") {
        val sam = player("Sam", at = PARK)
        val anna = player(
            "Anna",
            at = PARK.offset(eastMeters = 150.0),
            behavior = BotBehavior(onClaim = ClaimReaction.Dispute()),
        )
        val boris = player("Boris", at = PARK.offset(eastMeters = -60.0))
        anna.turnsGpsOff()

        sam.createsGame(settings)
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        check(anna.onServer().latestFix == null, "the server has no fix of Anna")

        sam.claimsCatch(anna)
        awaitCatch(anna, CatchStatus.DISPUTED)
        val claim = awaitCatch(anna, CatchStatus.CONFIRMED, within = (rules.disputeVoteSeconds + 5).seconds)
        check(claim.estimatedDistanceAtClaimMeters == null, "no distance to judge by")
        awaitStatus(anna, PlayerStatus.CAUGHT)
    }

    /**
     * What the server refuses whatever the app offers: claims on players out of the game, claims by hiders, codes
     * and disputes by players the claim is not theirs.
     */
    @Test
    fun invalidClaims() = scenario("Invalid claims") {
        val rules = GameSetups.FAST_RULES.copy(catchCodeTimeoutSeconds = 60)
        val sam = player("Sam", at = PARK)
        val yura = player("Yura", at = PARK.offset(eastMeters = -10.0))
        val anna = player("Anna", at = PARK.offset(eastMeters = 15.0))
        val boris = player(
            "Boris",
            at = PARK.offset(northMeters = 15.0),
            behavior = BotBehavior(onClaim = ClaimReaction.Ignore),
        )
        val vera = player("Vera", at = PARK.offset(northMeters = -40.0))
        val gleb = player("Gleb", at = PARK.offset(northMeters = 30.0))

        sam.createsGame(GameSetups.fixedZone(100.0, rules = rules))
        join(yura, anna, boris, vera, gleb)
        sam.startsGame(seekers = listOf(sam, yura))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        vera.walksTo(PARK.offset(northMeters = -150.0), speed = 6.0)
        sam.claimsCatch(anna)
        sam.entersCodeShownBy(anna)
        awaitCatch(anna, CatchStatus.CONFIRMED)
        expectRejected(yura.claimCatch(anna), ErrorCode.WRONG_STATE, "a claim on a caught player")
        expectRejected(boris.claimCatch(gleb), ErrorCode.FORBIDDEN, "a hider claims a catch")

        sam.claimsCatch(boris)
        val claim = checkNotNull(lastClaimOn(boris))
        val code = catchCodeTotp(checkNotNull(boris.onServer().catchCodeSecret), rules).codeAt(state().serverTimeMillis)
        expectRejected(yura.confirmCatch(claim.id, code), ErrorCode.FORBIDDEN, "another seeker types the code")
        expectRejected(gleb.dispute(claim.id), ErrorCode.FORBIDDEN, "another hider disputes")
        check(lastClaimOn(boris)?.status == CatchStatus.AWAITING_CODE, "the claim is untouched")
        requireOk(sam.confirmCatch(code), "Sam types the code Boris reads aloud")
        awaitCatch(boris, CatchStatus.CONFIRMED)
        expectRejected(boris.dispute(claim.id), ErrorCode.WRONG_STATE, "a dispute after the claim closed")

        awaitStatus(vera, PlayerStatus.ELIMINATED, within = 40.seconds)
        expectRejected(sam.claimCatch(vera), ErrorCode.WRONG_STATE, "a claim on an eliminated player")
        check(state().phase == GamePhase.SEEKING, "the game goes on with Gleb")
    }

    /** A code the server does not accept from [hider] now (nor two periods around it). */
    private suspend fun Scenario.wrongCodeFor(hider: BotPlayer, rules: GameRules): String {
        val server = state()
        val secret = checkNotNull(server.players.single { it.id == hider.id }.catchCodeSecret)
        val totp = catchCodeTotp(secret, rules)
        val now = server.serverTimeMillis
        return (0..9999).map { it.toString().padStart(rules.catchCodeDigits, '0') }
            .first { !totp.verify(it, now, window = 2) }
    }

    /** The code on [hider]'s screen, read in the middle of a period, and that period's number. */
    private suspend fun Scenario.readMidPeriod(hider: BotPlayer, period: Long): Pair<String, Long> {
        while (true) {
            val code = eventually("${hider.name} shows the code") { hider.shownCode() }
            val now = checkNotNull(hider.serverNow())
            val into = now % period
            if (into in period / 4..period * 3 / 4) {
                note("✓ ${hider.name} reads ${code.code} aloud")
                return code.code to now / period
            }
            delay((period / 2 - into).mod(period).milliseconds)
        }
    }
}
