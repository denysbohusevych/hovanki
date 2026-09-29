package app.hovanki.e2e.scenarios

import app.hovanki.e2e.bot.CommandResult
import app.hovanki.e2e.route.Route
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.RadarBand
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A claim only up close (docs/adr/0010-nearby-radar.md, section 2.5): with the radar on, GPS alone doesn't let a
 * seeker claim a hider from 30 m; the radar has to have heard the two phones «burning» for a few seconds.
 */
class ProximityCatchTest {
    @Test
    fun aClaimGoesThroughOnlyUpClose() = scenario("A claim only up close") {
        enableAllFeatures()
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 30.0))

        sam.createsGame(GameSetups.radar(proximityCatch = true))
        join(anna)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        delay(3.seconds)

        // 30 m by GPS would do without the radar; with it, the phones have never been next to each other.
        expectRejected(sam.claimCatch(anna), ErrorReason.NOT_NEARBY, "a claim from 30 m")

        sam.walksToAndArrives(anna.gps.truePosition.offset(eastMeters = 0.8), speed = Route.RUNNING)
        awaitBand(sam, anna, RadarBand.BURNING)
        // Burning for a moment is not enough: the pair has to stay burning for the dwell.
        eventually("the claim goes through once they stood together for a few seconds", within = 20.seconds) {
            sam.claimCatch(anna).takeIf { it == CommandResult.Ok }
        }
        sam.entersCodeShownBy(anna)
        awaitCatch(anna, CatchStatus.CONFIRMED)
    }
}
