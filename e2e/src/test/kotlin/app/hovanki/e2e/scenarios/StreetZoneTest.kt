package app.hovanki.e2e.scenarios

import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.rules.ZoneArea
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The zone by streets (docs/adr/0009-game-setup-glow-streets.md) on the test grid of streets every 100 m: what counts
 * is the polygon of whole blocks, not the circle. A spot outside the circle but in a block of the zone is fine; a spot
 * inside the circle but in a block left out is outside.
 */
class StreetZoneTest {
    private val exact = GpsNoise(accuracyMeters = 5.0, accuracyJitterMeters = 0.0, exact = true)

    @Test
    fun theStreetsDecide() = scenario("The zone by streets", timeout = 5.minutes) {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0), noise = exact)

        sam.createsGame(GameSetups.streets(radiusMeters = 300.0))
        join(anna)
        check(state().streetZone == StreetZoneState.READY, "the zone by streets is built")
        awaitThat("Anna's phone has the zone") { anna.state.streetZone?.stages?.size == 1 }
        val zone = ZoneArea.Polygon(checkNotNull(state().streetZonePolygon))

        // Around the center: a spot out of the circle but well inside the blocks, and one the other way round.
        val around = (0 until 72).flatMap { step ->
            val angle = step * 5.0 * Math.PI / 180
            (150..420 step 5).map { meters -> PARK.moveBy(meters * sin(angle), meters * cos(angle)) }
        }
        fun GeoPoint.fromCircle() = distanceTo(PARK) - 300.0
        val outOfCircleInZone = around.first { it.fromCircle() > 20 && zone.signedDistanceMeters(it) < -20 }
        // 25 m out of the blocks: the accuracy and the margin at the border (15 m) can't make it inside.
        val inCircleOutOfZone = around.first { it.fromCircle() < -5 && zone.signedDistanceMeters(it) > 25 }
        note("out of the circle, in the blocks: ${outOfCircleInZone.fromCircle().toInt()} m beyond the circle")

        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        anna.walksToAndArrives(outOfCircleInZone, speed = 10.0)
        holdsFor("Anna is fine beyond the circle, inside the blocks", 12.seconds) {
            anna.snapshot?.me?.outOfZoneDeadlineMillis == null
        }

        anna.walksToAndArrives(inCircleOutOfZone, speed = 10.0)
        eventually("Anna is warned inside the circle, out of the blocks", within = 30.seconds) {
            anna.snapshot?.me?.outOfZoneDeadlineMillis
        }
        awaitReveal(anna, VisibilityReason.OUT_OF_ZONE, to = sam, within = 5.seconds)
    }
}
