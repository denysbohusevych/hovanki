package app.hovanki.e2e.scenarios

import app.hovanki.client.session.ConnectionStatus
import app.hovanki.e2e.route.Route
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.totp.catchCodeTotp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.test.Test
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
        val shown = eventually("Anna shows the code") { anna.shownCode() }
        val deviceClockCode = catchCodeTotp(secret, rules).codeAt(anna.clock.now())
        if (deviceClockCode != shown.code) {
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
}
