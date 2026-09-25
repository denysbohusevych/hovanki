package app.hovanki.e2e.scenarios

import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.VisibilityReason
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** Scenarios 6 and 7: suspicious signals reveal the player instead of punishing them. */
class FairPlayTest {
    private val rules = GameSetups.FAST_RULES

    @Test
    fun gpsOffWhileTheAppKeepsSyncing() = scenario("GPS off, app keeps syncing") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(northMeters = 60.0))
        val boris = player("Boris", at = PARK.offset(northMeters = -60.0))

        sam.createsGame(GameSetups.fast())
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        delay(3.seconds)
        check(sam.snapshot?.players?.none { it.id != sam.id && it.location != null } == true, "Sam sees no hider")

        anna.turnsGpsOff()
        val lastFix = checkNotNull(anna.onServer().latestFix)
        holdsFor("Anna stays hidden while her signal is fresh", (rules.staleLocationRevealSeconds - 5).seconds) {
            sam.snapshot?.players?.single { it.id == anna.id }?.location == null
        }
        val revealed = awaitReveal(anna, VisibilityReason.STALE_SIGNAL, to = sam, within = 10.seconds)
        check(revealed.point == lastFix.point, "Sam sees Anna's last known point")
        check(
            anna.onServer().lastFixReceivedMillis!! <
                state().serverTimeMillis - rules.staleLocationRevealSeconds * 1000L + 1500,
            "no fixes arrived meanwhile",
        )
        check(anna.state.connectionStatus.name == "ONLINE", "Anna's app kept syncing")
        check(boris.snapshot?.players?.none { it.location != null } == true, "hiders see nothing")

        anna.turnsGpsOn()
        awaitThat("Anna is hidden again once fixes arrive") {
            sam.snapshot?.players?.single { it.id == anna.id }?.location == null
        }
    }

    @Test
    fun mockLocationRevealsThePlayer() = scenario("Mock location") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 40.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -40.0))

        sam.createsGame(GameSetups.fast())
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        delay(3.seconds)
        val genuine = checkNotNull(anna.onServer().latestFix)

        anna.startsMockingLocation()
        anna.walksTo(PARK.offset(eastMeters = 120.0), speed = 3.0)
        val revealed = awaitReveal(anna, VisibilityReason.MOCK_LOCATION, to = sam, within = 5.seconds)
        delay(3.seconds)
        val server = anna.onServer()
        check(server.fixes.mock >= 3, "mocked fixes are counted (${server.fixes.mock})")
        check(
            server.latestFix?.timestampMillis!! <= genuine.timestampMillis + 2_000,
            "mocked fixes never enter the track",
        )
        check(revealed.point.distanceTo(genuine.point) < 10.0, "Sam sees the last genuine point, not the mocked one")
    }

    @Test
    fun teleportIsDropped() = scenario("Teleport") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 30.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -30.0))

        sam.createsGame(GameSetups.fast())
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        delay(3.seconds)
        val genuine = checkNotNull(anna.onServer().latestFix)

        // ~1 km in no time: the fixes stay implausible for more than a minute at 12 m/s.
        anna.teleportsTo(PARK.offset(eastMeters = 700.0, northMeters = 700.0))
        eventually("the teleported fixes are dropped") { anna.onServer().takeIf { it.fixes.implausible >= 3 } }
        val server = anna.onServer()
        check(server.latestFix!!.point.distanceTo(genuine.point) < 15.0, "the server keeps the last plausible position")
        check(server.outOfZoneSinceMillis == null, "no zone decision on dropped fixes")

        // Nothing accepted any more: the signal goes stale and seekers see the last genuine point.
        val revealed = awaitReveal(anna, VisibilityReason.STALE_SIGNAL, to = sam, within = 20.seconds)
        check(revealed.point.distanceTo(genuine.point) < 15.0, "Sam sees where Anna really was")
    }
}
