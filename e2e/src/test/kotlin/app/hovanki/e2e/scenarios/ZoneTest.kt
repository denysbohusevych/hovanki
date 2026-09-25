package app.hovanki.e2e.scenarios

import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.VisibilityReason
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Scenario 5. A fixed zone of 100 m: a fix is "clearly outside" when distance − accuracy > 110 m
 * (radius + zoneBorderMarginMeters). Decisions need 3 usable fixes within the 10 s window.
 */
class ZoneTest {
    private val longGrace = GameSetups.FAST_RULES.copy(outOfZoneGraceSeconds = 30)

    /** Exact positions with 8 m accuracy: every fix is where the scenario says, nothing random. */
    private val exact8 = GpsNoise(accuracyMeters = 8.0, accuracyJitterMeters = 0.0, exact = true)

    @Test
    fun leavesAndComesBack() = scenario("Out of the zone and back") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 50.0))

        sam.createsGame(GameSetups.fixedZone(100.0, rules = longGrace))
        join(anna)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        anna.walksTo(PARK.offset(eastMeters = 140.0), speed = 4.0)
        val deadline = eventually("Anna is warned", within = 40.seconds) { anna.snapshot?.me?.outOfZoneDeadlineMillis }
        awaitReveal(anna, VisibilityReason.OUT_OF_ZONE, to = sam, within = 5.seconds)
        check(anna.onServer().status == PlayerStatus.ACTIVE, "Anna is warned, not eliminated")

        anna.walksTo(PARK.offset(eastMeters = 50.0), speed = 4.0)
        awaitThat("the warning is lifted") { anna.snapshot?.me?.outOfZoneDeadlineMillis == null }
        awaitThat("Sam no longer sees Anna") { sam.snapshot?.players?.single { it.id == anna.id }?.location == null }

        delay((deadline - checkNotNull(anna.serverNow()) + 2_000).milliseconds)
        check(anna.onServer().status == PlayerStatus.ACTIVE, "past the old deadline Anna still plays")
    }

    @Test
    fun eliminatedWhenNotReturning() = scenario("Out of the zone for good") {
        val sam = player("Sam", at = PARK)
        val boris = player("Boris", at = PARK.offset(eastMeters = 50.0))
        val rules = GameSetups.FAST_RULES

        sam.createsGame(GameSetups.fixedZone(100.0))
        join(boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        boris.walksTo(PARK.offset(eastMeters = 150.0), speed = 4.0)
        val deadline =
            eventually("Boris is warned", within = 40.seconds) { boris.snapshot?.me?.outOfZoneDeadlineMillis }
        awaitReveal(boris, VisibilityReason.OUT_OF_ZONE, to = sam, within = 5.seconds)

        awaitStatus(boris, PlayerStatus.ELIMINATED, within = (rules.outOfZoneGraceSeconds + 5).seconds)
        check(state().serverTimeMillis >= deadline, "eliminated only after the grace period")
        awaitThat("Sam no longer sees Boris") { sam.snapshot?.players?.single { it.id == boris.id }?.location == null }
        // Boris was the only hider.
        awaitPhase(GamePhase.FINISHED)
    }

    /** One accepted fix clearly outside (a multipath jump) must not warn anybody. */
    @Test
    fun oneBadFixOutsideDecidesNothing() = scenario("One bad fix outside the border") {
        val sam = player("Sam", at = PARK)
        val vera = player("Vera", at = PARK.offset(eastMeters = 97.0), noise = exact8)
        val dana = player("Dana", at = PARK.offset(eastMeters = -92.0), noise = GpsNoise.city(seed = 11))

        sam.createsGame(GameSetups.fixedZone(100.0))
        join(vera, dana)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        delay(3.seconds)

        // 97 m + 25 m = 122 m: clearly outside (122 − 8 > 110), and plausible for the speed filter (25 − 16 ≤ 12 m/s).
        vera.gps.jumpOnce(eastMeters = 25.0, northMeters = 0.0)
        vera.log("GPS jumps 25 m out of the zone for one fix")
        holdsFor("nobody is warned or revealed", 15.seconds) {
            state().players.filter { it.id != sam.id }.all {
                it.outOfZoneSinceMillis == null &&
                    it.revealedToSeekers == null
            }
        }
        check(vera.onServer().fixes.implausible == 0, "the jump was an accepted fix, not filtered out")
        check(vera.snapshot?.me?.outOfZoneDeadlineMillis == null, "Vera's phone shows no warning")
    }

    /** Warned for real, then one fix jumps back inside: the warning and its deadline stay. */
    @Test
    fun oneBadFixInsideDoesNotLiftTheWarning() = scenario("One bad fix inside the border") {
        val sam = player("Sam", at = PARK)
        val pete = player("Pete", at = PARK.offset(eastMeters = 50.0), noise = exact8)

        sam.createsGame(GameSetups.fixedZone(100.0, rules = longGrace))
        join(pete)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        // 128 m: clearly outside (128 − 8 > 110).
        pete.walksToAndArrives(PARK.offset(eastMeters = 128.0), speed = 4.0)
        val since = eventually("Pete is warned", within = 20.seconds) { pete.onServer().outOfZoneSinceMillis }

        // 128 m − 25 m = 103 m: not clearly outside; plausible for the speed filter.
        pete.gps.jumpOnce(eastMeters = -25.0, northMeters = 0.0)
        pete.log("GPS jumps 25 m back into the zone for one fix")
        holdsFor("the warning stays with its deadline", 6.seconds) { pete.onServer().outOfZoneSinceMillis == since }
        check(pete.onServer().fixes.implausible == 0, "the jump was an accepted fix, not filtered out")
        awaitReveal(pete, VisibilityReason.OUT_OF_ZONE, to = sam, within = 2.seconds)
    }
}
