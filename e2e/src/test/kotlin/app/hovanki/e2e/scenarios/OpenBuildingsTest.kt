package app.hovanki.e2e.scenarios

import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.debug.DebugBuildings
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.rules.withOpenBuildings
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A building the host opens for hiding (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 4), on the
 * test quarter the server puts next to every zone center with the `e2e` profile (DebugBuildings): the rule leaves the
 * hider inside alone, and every phone draws the building as open without loading the buildings again.
 */
class OpenBuildingsTest {
    private val rules = GameSetups.FAST_RULES
    private val exact5 = GpsNoise(accuracyMeters = 5.0, accuracyJitterMeters = 0.0, exact = true)
    private val insideBlock = PARK.offset(DebugBuildings.INSIDE_EAST, DebugBuildings.INSIDE_NORTH)

    @Test
    fun hidingInAnOpenBuildingIsFine() = scenario("Hiding in a building the host opened") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK, noise = exact5)

        sam.createsGame(GameSetups.fast())
        join(anna)
        eventually("both apps loaded the block") {
            listOf(sam, anna).all { it.state.buildings?.buildings?.singleOrNull() != null }.takeIf { it }
        }
        requireOk(sam.togglesBuildingAt(insideBlock), "Sam opens the block for hiding")
        eventually("Anna's app draws the block as open, from the buildings it already has") {
            val snapshot = anna.snapshot ?: return@eventually null
            anna.state.buildings?.withOpenBuildings(snapshot.settings.openBuildings)?.open?.singleOrNull()
        }
        check(anna.state.buildings?.open?.isEmpty() == true, "loaded before, not again")

        sam.startsGame(seekers = listOf(sam))
        anna.walksTo(insideBlock, speed = 4.0)
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        holdsFor("no warning, Sam doesn't see Anna", (rules.insideBuildingRevealSeconds + 15).seconds) {
            anna.snapshot?.me?.insideBuildingRevealAtMillis == null &&
                sam.snapshot?.players?.single { it.id == anna.id }?.location == null
        }
    }
}
