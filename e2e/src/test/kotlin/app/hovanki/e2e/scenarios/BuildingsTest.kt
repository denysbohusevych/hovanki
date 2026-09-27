package app.hovanki.e2e.scenarios

import app.hovanki.e2e.OWN_SERVER
import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.e2e.scenarioOnOwnServer
import app.hovanki.shared.debug.DebugBuildings
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.VisibilityReason
import kotlinx.coroutines.delay
import org.junit.jupiter.api.parallel.ResourceLock
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

    /** In the arch through the block, as deep in as [insideBlock]. */
    private val inTheArch = PARK.offset(DebugBuildings.ARCH_EAST, DebugBuildings.INSIDE_NORTH)

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

    @Test
    fun standingInAnArchIsOutdoors() = scenario("Standing in an arch") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK, noise = exact5)

        sam.createsGame(GameSetups.fast())
        join(anna)
        sam.startsGame(seekers = listOf(sam))
        // Around the block, then into the arch from the south: 4 m wide, 20 m of building on either side.
        anna.walksToAndArrives(PARK.offset(DebugBuildings.ARCH_EAST, DebugBuildings.SOUTH - 10), speed = 4.0)
        anna.walksTo(inTheArch, speed = 4.0)
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        holdsFor("no warning in the arch, Sam doesn't see Anna", (rules.insideBuildingRevealSeconds + 10).seconds) {
            anna.snapshot?.me?.insideBuildingRevealAtMillis == null &&
                sam.snapshot?.players?.single { it.id == anna.id }?.location == null
        }
        val stood = checkNotNull(anna.onServer().latestUsableFix).point.distanceTo(inTheArch)
        check(stood < 3.0, "Anna's fixes are in the arch (${stood.toInt()} m off)")
    }

    @Test
    fun withoutBuildingDataTheRuleIsOff() = scenario("No building data") {
        // The fake source has no buildings around Null Island, as when OpenStreetMap does not answer.
        val nowhere = DebugBuildings.NO_DATA_AT
        val sam = player("Sam", at = nowhere)
        val anna = player("Anna", at = nowhere, noise = exact5)

        sam.createsGame(GameSetups.fast(center = nowhere))
        awaitThat("the game has no building data", 10.seconds) { state().buildings == BuildingsState.UNAVAILABLE }
        join(anna)
        check(anna.snapshot?.buildings == BuildingsState.UNAVAILABLE, "Anna's app learns that the rule is off")
        sam.startsGame(seekers = listOf(sam))
        // Where the test quarter would be anywhere else.
        anna.walksTo(nowhere.offset(DebugBuildings.INSIDE_EAST, DebugBuildings.INSIDE_NORTH), speed = 4.0)
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        holdsFor("never warned, never seen", (rules.insideBuildingRevealSeconds + 10).seconds) {
            anna.snapshot?.me?.insideBuildingRevealAtMillis == null &&
                sam.snapshot?.players?.single { it.id == anna.id }?.location == null
        }
        check(anna.state.buildings == null && sam.state.buildings == null, "the apps have no buildings to draw")
        check(anna.onServer().insideBuildingSinceMillis == null, "the server never judged Anna inside")
    }

    @Test
    fun aHiderSeenInABuildingIsCaughtAsUsual() = scenario("Caught inside a building") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK, noise = exact5)
        val bob = player("Bob", at = PARK)

        sam.createsGame(GameSetups.fast())
        join(anna, bob)
        sam.startsGame(seekers = listOf(sam))
        anna.walksTo(insideBlock, speed = 4.0)
        bob.walksTo(PARK.offset(eastMeters = 60.0, northMeters = -40.0))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        awaitReveal(anna, VisibilityReason.INSIDE_BUILDING, to = sam, within = 90.seconds)

        // Sam goes in after her: the rule is about hiding, a seeker inside is fine.
        sam.catches(anna)
        awaitStatus(anna, PlayerStatus.CAUGHT)
        check(state().phase == GamePhase.SEEKING, "the game goes on: Bob still hides")
        awaitThat("Anna's app: caught, no building warning", 10.seconds) {
            anna.snapshot?.me?.let { it.status == PlayerStatus.CAUGHT && it.insideBuildingRevealAtMillis == null } ==
                true
        }
        awaitThat("Sam no longer sees Anna", 10.seconds) {
            sam.snapshot?.players?.single { it.id == anna.id }?.location == null
        }
    }

    /**
     * The quarter lies outside a 100 m zone: a hider inside it is both out of the zone and in a building. The zone wins
     * (the reveal says OUT_OF_ZONE, never INSIDE_BUILDING), and the zone's grace period eliminates the hider.
     */
    @Test
    fun aBuildingOutsideTheZone() = scenario("A building outside the zone") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK, noise = exact5)
        val boris = player("Boris", at = PARK.offset(eastMeters = 40.0))

        sam.createsGame(GameSetups.fixedZone(100.0))
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        anna.walksTo(insideBlock, speed = 4.0)
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        awaitReveal(anna, VisibilityReason.OUT_OF_ZONE, to = sam, within = 60.seconds)
        awaitStatus(anna, PlayerStatus.ELIMINATED, within = (rules.outOfZoneGraceSeconds + 30).seconds)
        check(
            sam.revealsSeen.none { it == anna.id to VisibilityReason.INSIDE_BUILDING },
            "Sam never saw Anna as inside a building",
        )
        check(boris.onServer().status == PlayerStatus.ACTIVE, "Boris plays on")
    }

    /**
     * The buildings are still loading when seeking starts (Overpass is slow; here the test source takes 25 s): the rule
     * is off meanwhile, a hider in the quarter is not warned. Once they arrive the rule is on and every app loads them.
     */
    @Test
    @ResourceLock(OWN_SERVER)
    fun buildingsStillLoading() = scenarioOnOwnServer(
        "Buildings still loading",
        properties = mapOf("hovanki.buildings.fake-delay" to "25s"),
    ) {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = insideBlock, noise = exact5)

        sam.createsGame(GameSetups.fast())
        check(sam.snapshot?.buildings == BuildingsState.LOADING, "the buildings are still loading")
        join(anna)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        check(state().buildings == BuildingsState.LOADING, "seeking starts before they arrive")
        holdsFor("Anna in the quarter is not warned: nothing to judge by yet", 6.seconds) {
            val server = state()
            server.buildings == BuildingsState.LOADING &&
                server.players.single { it.id == anna.id }.insideBuildingSinceMillis == null
        }

        eventually("the buildings arrive", within = 20.seconds) {
            state().takeIf { it.buildings == BuildingsState.READY }
        }
        awaitThat("every app loads the buildings its map draws") {
            players.all { bot ->
                bot.state.buildings?.buildings?.isNotEmpty() == true && bot.onServer().buildingsLoadedAtMillis != null
            }
        }
        val revealAt = eventually("now Anna is warned", within = 15.seconds) {
            anna.snapshot?.me?.insideBuildingRevealAtMillis
        }
        check(revealAt > state().serverTimeMillis, "and seen only later, as usual")
    }
}
