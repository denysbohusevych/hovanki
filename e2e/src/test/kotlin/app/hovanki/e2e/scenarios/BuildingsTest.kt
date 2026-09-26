package app.hovanki.e2e.scenarios

import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.debug.DebugBuildings
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.VisibilityReason
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * No hiding in buildings (docs/adr/0003-map-and-buildings.md), on the test quarter the server puts next to every zone
 * center with the `e2e` profile (DebugBuildings): a fix counts as inside when it is deeper inside than its accuracy
 * plus 5 m, a player when 3 usable fixes in the 10 s window are; the reveal follows after 20 s (FAST_RULES).
 */
class BuildingsTest {
    private val rules = GameSetups.FAST_RULES

    /** Exact positions with 5 m accuracy: inside the block means 14 m from the nearest wall. */
    private val exact5 = GpsNoise(accuracyMeters = 5.0, accuracyJitterMeters = 0.0, exact = true)
    private val insideBlock = PARK.offset(DebugBuildings.INSIDE_EAST, DebugBuildings.INSIDE_NORTH)

    /** 20 m south of the block, outdoors. */
    private val nextToBlock = PARK.offset(DebugBuildings.INSIDE_EAST, DebugBuildings.SOUTH - 20)

    @Test
    fun sittingInABuildingIsRevealed() = scenario("Hiding in a building") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK, noise = exact5)

        sam.createsGame(GameSetups.fast())
        check(sam.snapshot?.buildings == BuildingsState.READY, "the zone's buildings came with the game")
        eventually("Sam's app loaded the buildings its map draws") { sam.state.buildings?.buildings?.singleOrNull() }
        join(anna)
        sam.startsGame(seekers = listOf(sam))
        anna.walksTo(insideBlock, speed = 4.0)
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        val revealAt = eventually("Anna is warned", within = 60.seconds) {
            anna.snapshot?.me?.insideBuildingRevealAtMillis
        }
        check(sam.snapshot?.players?.single { it.id == anna.id }?.location == null, "Sam doesn't see Anna yet")

        val seen = awaitReveal(
            anna,
            VisibilityReason.INSIDE_BUILDING,
            to = sam,
            within = (rules.insideBuildingRevealSeconds + 5).seconds,
        )
        check(checkNotNull(sam.snapshot).serverTimeMillis >= revealAt, "not before the warned time")
        check(seen.reason == VisibilityReason.OUT_OF_ZONE, "the first app versions get a reason they know")
        check(anna.onServer().status == PlayerStatus.ACTIVE, "revealed, never eliminated")

        anna.walksTo(nextToBlock, speed = 4.0)
        awaitThat("out again: Sam no longer sees Anna", 20.seconds) {
            sam.snapshot?.players?.single { it.id == anna.id }?.location == null
        }
        check(anna.snapshot?.me?.insideBuildingRevealAtMillis == null, "the warning is lifted")
    }

    @Test
    fun oneFixThatJumpsIntoABuildingDecidesNothing() = scenario("One GPS jump into a building") {
        val sam = player("Sam", at = PARK)
        // 2 m outside the south wall.
        val atTheWall = PARK.offset(eastMeters = DebugBuildings.INSIDE_EAST, northMeters = DebugBuildings.SOUTH - 2)
        val anna = player("Anna", at = atTheWall, noise = exact5)

        sam.createsGame(GameSetups.fast())
        join(anna)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        delay((rules.decisionWindowSeconds + 2).seconds)

        // 20 m north: 18 m inside the block, 14 m from its nearest wall, clearly inside at 5 m accuracy; and plausible
        // for the speed filter (20 − 2 × 5 ≤ 12 m/s), so the rule really sees it.
        anna.gps.jumpOnce(eastMeters = 0.0, northMeters = 20.0)
        anna.log("one fix jumps 20 m into the block")
        holdsFor("no warning, Sam doesn't see Anna", (rules.insideBuildingRevealSeconds + 10).seconds) {
            anna.snapshot?.me?.insideBuildingRevealAtMillis == null &&
                sam.snapshot?.players?.single { it.id == anna.id }?.location == null
        }
        check(anna.onServer().fixes.implausible == 0, "the jump was an accepted fix, not filtered out")
    }

    @Test
    fun leavingBeforeTheRevealLiftsTheWarning() = scenario("Out of a building before the reveal") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK, noise = exact5)

        sam.createsGame(GameSetups.fast())
        join(anna)
        sam.startsGame(seekers = listOf(sam))
        anna.walksTo(insideBlock, speed = 4.0)
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        eventually("Anna is warned", within = 60.seconds) { anna.snapshot?.me?.insideBuildingRevealAtMillis }

        anna.walksTo(nextToBlock, speed = 4.0)
        awaitThat("the warning is lifted", 15.seconds) { anna.snapshot?.me?.insideBuildingRevealAtMillis == null }
        holdsFor("Sam never sees Anna", (rules.insideBuildingRevealSeconds + 5).seconds) {
            sam.snapshot?.players?.single { it.id == anna.id }?.location == null
        }
    }
}
