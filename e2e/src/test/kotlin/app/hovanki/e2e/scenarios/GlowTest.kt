package app.hovanki.e2e.scenarios

import app.hovanki.client.tracking.AlertKind
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

/**
 * The glow (docs/adr/0009-game-setup-glow-streets.md): every 20 s of the search the seekers see the hiders for 4 s,
 * and between glows only the spot where the last one left them. The privacy audit checks every response on the way:
 * never a spot newer than the glow, never a glow before the first one.
 */
class GlowTest {
    @Test
    fun theHidersGlowNowAndThen() = scenario("The glow") {
        val sam = player("Sam", at = PARK)
        val spot = PARK.offset(eastMeters = 80.0)
        val anna = player("Anna", at = spot)

        sam.createsGame(GameSetups.glowing(everySeconds = 20, forSeconds = 4))
        join(anna)
        sam.startsGame(seekers = listOf(sam))
        val start = checkNotNull(awaitPhase(GamePhase.SEEKING, within = 20.seconds).zoneStartedAtMillis)
        fun seen() = sam.snapshot?.players?.single { it.id == anna.id }?.location

        awaitServerTime(start + 2_000)
        holdsFor("Sam doesn't see Anna before the first glow", 12.seconds) { seen() == null }
        val first = awaitReveal(anna, VisibilityReason.GLOW, to = sam, within = 15.seconds)
        check(first.point.distanceTo(spot) < 30, "the glow shows where Anna is")

        // Right after the glow Anna walks off; Sam keeps seeing only where the glow left her.
        awaitServerTime(start + 24_500)
        anna.walksTo(spot.offset(northMeters = 120.0), speed = 5.0)
        delay(5.seconds)
        val mark = checkNotNull(seen()) { "Sam sees the spot between glows" }
        check(mark.exactReason == VisibilityReason.GLOW, "a glow spot")
        check(mark.point.distanceTo(spot) < 30, "the spot is where the glow left Anna, not where she is")
        check(mark.atMillis < start + 24_000, "taken during the glow")

        eventually("the next glow shows Anna on her way", within = 20.seconds) {
            seen()?.takeIf { it.exactReason == VisibilityReason.GLOW && it.point.distanceTo(spot) > 40 }
        }
        val alerts = anna.backgroundTracker.alerts.map { it.kind }
        check(AlertKind.GLOW_SOON in alerts, "Anna's phone warned her before a glow")
        check(AlertKind.GLOWING in alerts, "and buzzed when she glowed")
    }
}
