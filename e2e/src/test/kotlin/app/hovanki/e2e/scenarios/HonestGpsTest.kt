package app.hovanki.e2e.scenarios

import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.route.Route
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.debug.DebugBuildings
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * False alarms are the worst for players: honest hiders with bad city GPS (accuracy spikes, multipath jumps) where it
 * is hardest for the rules. Close to a wall of the test quarter, in its arch, just inside the zone border and walking
 * along it, two hiders each with their own noise, for two minutes of seeking. Nobody may be warned or revealed.
 */
class HonestGpsTest {
    @Test
    fun honestHidersWithBadGps() = scenario("Honest hiders with bad GPS", timeout = 4.minutes) {
        val zone = GameSetups.fixedZone(ZONE_METERS).copy(seekingSeconds = 150)
        // 12 m south of the quarter, in its arch, 5 m inside the zone border, and along the border 6 to 10 m inside.
        val nextToTheWall = PARK.offset(DebugBuildings.INSIDE_EAST, DebugBuildings.SOUTH - 12)
        val inTheArch = PARK.offset(DebugBuildings.ARCH_EAST, DebugBuildings.INSIDE_NORTH)
        val atTheBorder = PARK.offset(eastMeters = ZONE_METERS - 5)
        val south = PARK.offset(eastMeters = ZONE_METERS - 10, northMeters = -40.0)
        val north = PARK.offset(eastMeters = ZONE_METERS - 10, northMeters = 40.0)
        val alongTheBorder = Route.walk(south, north, south, north, south)
        val sam = player("Sam", at = PARK)
        val spots = listOf(nextToTheWall, inTheArch, atTheBorder, alongTheBorder.waypoints.first())
        val hiders = spots.flatMapIndexed { n, spot ->
            (1..2).map { k -> player("H${n + 1}.$k", at = spot, noise = GpsNoise.city(seed = 1_000L + n * 10 + k)) }
        }

        sam.createsGame(zone)
        join(*hiders.toTypedArray())
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        for (walker in hiders.takeLast(2)) walker.follows(alongTheBorder)

        holdsFor("no honest hider is warned or revealed", 2.minutes) {
            state().players.filter { it.role == Role.HIDER }.all {
                it.status == PlayerStatus.ACTIVE && it.outOfZoneSinceMillis == null &&
                    it.insideBuildingSinceMillis == null && it.revealedToSeekers == null
            }
        }
        val fixes = state().players.filter { it.role == Role.HIDER }.sumOf { it.fixes.accepted }
        note("$fixes fixes of honest hiders, not one false alarm")
        check(sam.snapshot?.players?.none { it.role == Role.HIDER && it.location != null } == true, "Sam saw nobody")
    }

    private companion object {
        /** Large enough for the quarter (up to 170 m out) to be inside, with room for walks along the border. */
        const val ZONE_METERS = 200.0
    }
}
