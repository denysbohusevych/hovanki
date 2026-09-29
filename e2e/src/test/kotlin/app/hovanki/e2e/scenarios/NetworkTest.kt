package app.hovanki.e2e.scenarios

import app.hovanki.client.session.ConnectionStatus
import app.hovanki.client.session.SessionError
import app.hovanki.e2e.bot.BotBehavior
import app.hovanki.e2e.bot.ClaimReaction
import app.hovanki.e2e.bot.CommandResult
import app.hovanki.e2e.route.Route
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.totp.catchCodeTotp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Scenarios 8 and 9: a lost network and wrong device clocks. */
class NetworkTest {
    private val rules = GameSetups.FAST_RULES

    @Test
    fun networkOutageOf30Seconds() = scenario("Network outage for 30 s") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(northMeters = 40.0))
        val boris = player("Boris", at = PARK.offset(northMeters = -40.0))

        sam.createsGame(GameSetups.fast())
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        anna.follows(Route.walk(anna.gps.truePosition, PARK.offset(eastMeters = 40.0, northMeters = 60.0), speed = 1.0))
        delay(2.seconds)
        val acceptedBefore = anna.onServer().fixes.accepted

        anna.losesNetwork()
        val outageStart = System.currentTimeMillis()
        // Meanwhile the seekers see Anna's last known point, like with GPS off.
        awaitReveal(
            anna,
            VisibilityReason.STALE_SIGNAL,
            to = sam,
            within = (rules.staleLocationRevealSeconds + 8).seconds,
        )
        check(anna.state.connectionStatus == ConnectionStatus.RECONNECTING, "Anna's app knows it is offline")
        delay(30_000 - (System.currentTimeMillis() - outageStart))
        val emittedOffline = anna.gps.fixesEmitted

        anna.regainsNetwork()
        eventually("everything queued offline reaches the server", within = 25.seconds) {
            anna.onServer().takeIf { it.fixes.accepted >= emittedOffline }
        }
        val server = anna.onServer()
        check(
            server.fixes.accepted - acceptedBefore >= 28,
            "the fixes of the outage arrived (${server.fixes.accepted - acceptedBefore})",
        )
        check(server.fixes.outOfOrder == 0 && server.fixes.implausible == 0, "none of them dropped")
        awaitThat("Anna's app is online again") { anna.state.connectionStatus == ConnectionStatus.ONLINE }
        awaitThat("Anna is hidden again") { sam.snapshot?.players?.single { it.id == anna.id }?.location == null }
        val latest = checkNotNull(anna.onServer().latestFix)
        check(abs(state().serverTimeMillis - latest.timestampMillis) < 3_000, "the server is up to date with Anna")
    }

    @Test
    fun deviceClocksOffByTwoMinutes() = scenario("Device clocks off by ±2 min") {
        val sam = player("Sam", at = PARK, clockSkew = (-2).minutes)
        val anna = player("Anna", at = PARK, clockSkew = 2.minutes)
        val boris = player("Boris", at = PARK, clockSkew = (-2).minutes)

        sam.createsGame(GameSetups.fast())
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        anna.walksTo(PARK.offset(eastMeters = 30.0))
        boris.walksTo(PARK.offset(eastMeters = -30.0))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        delay(3.seconds)

        val server = state()
        for (player in server.players) {
            val latest = checkNotNull(player.latestFix) { "${player.name} has no fix" }
            check(
                player.fixes.outOfOrder == 0 && player.fixes.accepted >= 10,
                "${player.name}: fixes accepted in order",
            )
            check(
                abs(server.serverTimeMillis - latest.timestampMillis) < 3_000,
                "${player.name}: fix timestamps are server time",
            )
        }
        check(
            abs(anna.clock.now() - anna.serverNow()!! - 2.minutes.inWholeMilliseconds) < 2_000,
            "Anna's ServerClock corrects +2 min",
        )

        sam.catchesUpWith(anna)
        sam.claimsCatch(anna)
        val claim = awaitCatch(anna, CatchStatus.AWAITING_CODE)
        awaitThat("Anna sees the claim") { anna.snapshot?.catches?.any { it.id == claim.id } == true }
        val secondsLeft = (claim.deadlineMillis - anna.serverNow()!!) / 1000.0
        check(secondsLeft in 5.0..rules.catchCodeTimeoutSeconds + 0.5, "Anna's countdown is right: $secondsLeft s")

        // A code from Anna's device clock would be four periods off.
        val secret = checkNotNull(anna.snapshot?.me?.catchCodeSecret)
        eventually("Anna shows the code") { anna.shownCode() }
        val totp = catchCodeTotp(secret, rules)
        val deviceClockCode = totp.codeAt(anna.clock.now())
        // Four digits: now and then that code is also one the server takes right now (it takes a period either side).
        if (!totp.verify(deviceClockCode, checkNotNull(anna.serverNow()))) {
            expectRejected(
                sam.confirmCatch(deviceClockCode),
                ErrorCode.INVALID_CODE,
                "a code made with the device clock",
            )
        }
        sam.entersCodeShownBy(anna)
        awaitCatch(anna, CatchStatus.CONFIRMED)

        sam.catches(boris)
    }

    /**
     * The server creates the claim, the answer is lost. Pressing again is refused (the claim is open already), and the
     * next poll brings the claim to the seeker's screen: nothing is stuck.
     */
    @Test
    fun claimResponseLost() = scenario("Claim response lost") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 15.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -60.0))

        sam.createsGame(GameSetups.fast(rules = rules.copy(catchCodeTimeoutSeconds = 30)))
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        sam.losesResponseTo("/catches")
        check(sam.claimCatch(anna) is CommandResult.Failed, "Sam's phone got no answer")
        check(lastClaimOn(anna)?.status == CatchStatus.AWAITING_CODE, "the server has the claim")
        expectRejected(sam.claimCatch(anna), ErrorCode.WRONG_STATE, "Sam presses again: the claim is open already")
        awaitThat("the next poll shows the open claim on Sam's phone") {
            sam.snapshot?.catches?.any { it.hiderId == anna.id && it.status == CatchStatus.AWAITING_CODE } == true
        }
        sam.entersCodeShownBy(anna)
        awaitCatch(anna, CatchStatus.CONFIRMED)
        check(sam.state.lastError == null, "no error left on Sam's screen")
    }

    /**
     * A guest's join goes through, the answer is lost, the guest presses "Join" again: the app sends the same request
     * id, and the server gives back the player it created. One Anna in the lobby, not a second one next to a player
     * nobody plays; she plays the game as usual.
     */
    @Test
    fun joinResponseLost() = scenario("Join response lost") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 15.0))

        sam.createsGame(GameSetups.fast())
        anna.losesResponseTo("/join")
        check(anna.join(joinCode) is CommandResult.Failed, "Anna's phone got no answer")
        val created = state().players.single { it.name == "Anna" }
        requireOk(anna.join(joinCode), "Anna presses Join again")
        val players = state().players
        check(players.map { it.name } == listOf("Sam", "Anna"), "one Anna in the lobby (${players.map { it.name }})")
        check(anna.id == created.id, "Anna's phone got the player the lost answer was about")

        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        sam.claimsCatch(anna)
        sam.entersCodeShownBy(anna)
        awaitCatch(anna, CatchStatus.CONFIRMED)
        awaitPhase(GamePhase.FINISHED)
    }

    /**
     * Answers lost after the server acted on a code, a vote and a chat message. Pressing again never breaks the game,
     * a message sent again is kept once, and every phone ends up with the server's state.
     */
    @Test
    fun otherResponsesLost() = scenario("Other responses lost") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 15.0))
        val boris = player(
            "Boris",
            at = PARK.offset(eastMeters = -15.0),
            behavior = BotBehavior(onClaim = ClaimReaction.Dispute()),
        )
        val vera = player("Vera", at = PARK.offset(northMeters = 30.0))

        sam.createsGame(GameSetups.fast(rules = rules.copy(catchCodeTimeoutSeconds = 30, disputeVoteSeconds = 30)))
        join(anna, boris, vera)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        // The code.
        sam.claimsCatch(anna)
        val code = eventually("Anna shows the code") { anna.shownCode() }
        sam.losesResponseTo("/confirm")
        check(sam.confirmCatch(code.code) is CommandResult.Failed, "Sam's phone got no answer")
        awaitCatch(anna, CatchStatus.CONFIRMED, within = 2.seconds)
        check(sam.confirmCatch(code.code) is CommandResult.Rejected, "typing it again is refused: the claim is closed")
        awaitThat("Sam's phone shows the catch") {
            sam.snapshot?.catches?.any { it.hiderId == anna.id && it.status == CatchStatus.CONFIRMED } == true
        }
        awaitThat("Anna's phone shows she is caught") { anna.snapshot?.me?.status == PlayerStatus.CAUGHT }

        // The vote: Anna (caught) and Vera vote on Boris's dispute.
        sam.claimsCatch(boris)
        val dispute = awaitCatch(boris, CatchStatus.DISPUTED, within = 5.seconds)
        vera.losesResponseTo("/vote")
        check(vera.vote(dispute.id, confirm = true) is CommandResult.Failed, "Vera's phone got no answer")
        check(lastClaimOn(boris)?.votes?.size == 1, "the server counted Vera's vote")
        requireOk(vera.vote(dispute.id, confirm = true), "Vera votes again")
        check(lastClaimOn(boris)?.votes?.size == 1, "still one vote of Vera")
        requireOk(anna.vote(dispute.id, confirm = true), "Anna votes")
        awaitCatch(boris, CatchStatus.CONFIRMED, within = 3.seconds)

        // The chat.
        vera.losesResponseTo("/chat")
        check(vera.sendChat("well played") is CommandResult.Failed, "Vera's phone got no answer")
        requireOk(vera.sendChat("well played"), "Vera sends it again")
        check(state().chat.count { it.text == "well played" } == 1, "the server keeps the message once")
        awaitThat("every phone shows the server's chat, in order") {
            val seqs = state().chat.map { it.seq }
            players.all { p -> p.state.chat.map { it.seq } == seqs }
        }
    }

    /**
     * Longer offline than the outbox holds (100 fixes, one per second): only the newest ones reach the server, the
     * request is never too big, the app is back within seconds of the network, and fresh fixes hide the player again.
     */
    @Test
    fun longOutage() = scenario("Network outage for 150 s", timeout = 4.minutes) {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(northMeters = 40.0))
        val boris = player("Boris", at = PARK.offset(northMeters = -40.0))

        sam.createsGame(GameSetups.fast())
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        delay(2.seconds)
        val acceptedBefore = anna.onServer().fixes.accepted

        anna.losesNetwork()
        val outageStart = System.currentTimeMillis()
        awaitReveal(
            anna,
            VisibilityReason.STALE_SIGNAL,
            to = sam,
            within = (rules.staleLocationRevealSeconds + 8).seconds,
        )
        delay((150_000 - (System.currentTimeMillis() - outageStart)).milliseconds)
        val emittedOffline = anna.gps.fixesEmitted
        check(emittedOffline > 120, "more fixes than the outbox holds ($emittedOffline)")

        anna.regainsNetwork()
        val back = state().serverTimeMillis
        // The polling waits at most 5 s between attempts, however long the outage was.
        val synced = eventually("Anna's app syncs again", within = 8.seconds) {
            anna.onServer().lastFixReceivedMillis?.takeIf { it > back }
        }
        check(synced - back <= 7_000, "back ${(synced - back) / 1000.0} s after the network")
        awaitThat("Anna is hidden again", within = 5.seconds) {
            sam.snapshot?.players?.single { it.id == anna.id }?.location == null
        }
        val arrived = anna.onServer().fixes.accepted - acceptedBefore
        check(arrived in 90..130, "the newest fixes of the outage arrived, the older ones were dropped ($arrived)")
        check(metrics.errors.none { it.endsWith("-> 400") }, "no request was too big")
        val latest = checkNotNull(anna.onServer().latestFix)
        check(abs(state().serverTimeMillis - latest.timestampMillis) < 3_000, "the server is up to date with Anna")
    }

    /** Commands without network fail on the phone and change nothing; the same step works once the network is back. */
    @Test
    fun commandsOffline() = scenario("Commands offline") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 15.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -60.0))

        sam.createsGame(GameSetups.fast(rules = rules.copy(catchCodeTimeoutSeconds = 30)))
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        sam.losesNetwork()
        check(sam.claimCatch(anna) is CommandResult.Failed, "no claim without network")
        check(sam.state.lastError is SessionError.Network, "Sam's phone says so")
        check(state().catches.isEmpty(), "nothing reached the server")
        sam.regainsNetwork()
        sam.claimsCatch(anna)

        val code = eventually("Anna shows the code") { anna.shownCode() }
        sam.losesNetwork()
        check(sam.confirmCatch(code.code) is CommandResult.Failed, "no code without network")
        check(lastClaimOn(anna)?.status == CatchStatus.AWAITING_CODE, "the claim is still open")
        sam.regainsNetwork()
        sam.entersCodeShownBy(anna)
        awaitCatch(anna, CatchStatus.CONFIRMED)

        boris.losesNetwork()
        check(boris.sendChat("anyone there?") is CommandResult.Failed, "no message without network")
        check(state().chat.none { it.text == "anyone there?" }, "the server has no such message")
        boris.regainsNetwork()
        requireOk(boris.sendChat("anyone there?"), "Boris sends it again")
        awaitThat("Sam reads it") { sam.chat.any { it.text == "anyone there?" } }
    }

    /**
     * The phone's clock jumps an hour ahead and then 90 minutes back in the middle of a game (a manual change, a time
     * zone mistake). The app follows the server's clock: at most the fixes taken before the next poll are dropped, the
     * player is never taken for silent, and the catch code still works.
     */
    @Test
    fun clockJumpsMidGame() = scenario("Phone clock jumps mid-game") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -60.0))

        sam.createsGame(GameSetups.fast())
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        delay(3.seconds)

        for (jump in listOf(1.hours, (-90).minutes)) {
            val before = anna.onServer().fixes
            anna.changesClockBy(jump)
            delay(6.seconds)
            val after = anna.onServer().fixes
            val dropped = after.outOfOrder - before.outOfOrder + after.implausible - before.implausible
            check(dropped <= 3, "at most the fixes before the next poll are dropped ($dropped)")
            check(after.accepted >= before.accepted + 3, "fixes keep being accepted")
            check(
                abs(checkNotNull(anna.serverNow()) - state().serverTimeMillis) < 2_000,
                "Anna's app still knows the server's time",
            )
            check(anna.onServer().revealedToSeekers == null, "Anna is not taken for silent")
        }

        sam.claimsCatch(anna)
        sam.entersCodeShownBy(anna)
        awaitCatch(anna, CatchStatus.CONFIRMED)
    }

    /**
     * Every phone on a slow and flaky network: 2 s per request, 20 % of the requests fail. Players press again when
     * something fails. The game still converges: fixes arrive, nobody is taken for silent (with the production
     * threshold of 45 s: after a failure the polling waits 1, 2, 4, then 5 s, plus the 2 s of every attempt, so a
     * few failures in a row are a quarter of a minute without a sync), the catch works, the chat has no gaps or
     * duplicates.
     */
    @Test
    fun slowAndFlakyNetwork() = scenario("Slow and flaky network") {
        val flakyRules = rules.copy(catchCodeTimeoutSeconds = 60, staleLocationRevealSeconds = 45)
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -40.0))
        val vera = player("Vera", at = PARK.offset(northMeters = 40.0))

        sam.createsGame(GameSetups.fast(rules = flakyRules))
        join(anna, boris, vera)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        for (player in players) {
            player.hasSlowNetwork(2.seconds)
            player.hasFlakyNetwork(0.2)
        }

        for (player in players) {
            for (n in 1..3) pressesUntilOk("${player.name} says #$n") { player.sendChat("${player.name} #$n") }
        }
        delay(10.seconds)
        val server = state()
        for (player in server.players) {
            val silentFor = server.serverTimeMillis - checkNotNull(player.lastFixReceivedMillis)
            check(silentFor < 30_000, "${player.name}'s fixes keep arriving (last ${silentFor / 1000} s ago)")
        }
        check(sam.revealsSeen.none { it.second == VisibilityReason.STALE_SIGNAL }, "nobody was taken for silent")

        pressesUntilOk("Sam claims Anna") { sam.claimCatch(anna) }
        val code = eventually("Anna shows the code", within = 30.seconds) { anna.shownCode() }
        pressesUntilOk("Sam types Anna's code") { sam.confirmCatch(code.code) }
        awaitCatch(anna, CatchStatus.CONFIRMED)

        awaitThat("every phone has the whole chat, in order, without duplicates", within = 30.seconds) {
            val seqs = state().chat.map { it.seq }
            seqs.size == 12 && players.all { p -> p.state.chat.map { it.seq } == seqs }
        }
        for (player in players) player.hasGoodNetwork()
    }
}
