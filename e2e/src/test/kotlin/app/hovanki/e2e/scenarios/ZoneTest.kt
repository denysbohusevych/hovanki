package app.hovanki.e2e.scenarios

import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.protocol.ZoneCircle
import app.hovanki.shared.protocol.ZoneSchedule
import app.hovanki.shared.protocol.ZoneStage
import app.hovanki.shared.rules.ZoneRules
import app.hovanki.shared.rules.circleAt
import kotlinx.coroutines.delay
import kotlin.math.abs
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

    /**
     * The zone shrinks and moves away from a hider who stands still. She is warned once the shrinking circle leaves
     * her clearly outside, not before; the phones draw the same circle as the server judges by.
     */
    @Test
    fun theZoneShrinksOntoAHider() = scenario("The zone shrinks onto a hider") {
        // 300 m around the park; after 5 s it shrinks within 20 s to 100 m around a point 40 m west.
        val finalZone = ZoneCircle(PARK.offset(eastMeters = -40.0), 100.0)
        val zone = ZoneSchedule(
            initial = ZoneCircle(PARK, 300.0),
            stages = listOf(ZoneStage(holdSeconds = 5, shrinkSeconds = 20, target = finalZone)),
        )
        val annaAt = PARK.offset(eastMeters = 150.0)
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = annaAt, noise = exact8)
        val boris = player("Boris", at = PARK.offset(eastMeters = 20.0))

        sam.createsGame(GameSetups.fast(rules = longGrace).copy(zone = zone))
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        val seeking = awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        val zoneStart = checkNotNull(seeking.zoneStartedAtMillis)
        boris.walksTo(finalZone.center)

        val phone = eventually("Anna's phone knows when the zone started") {
            anna.snapshot?.takeIf { it.zoneStartedAtMillis != null }
        }
        check(phone.zoneStartedAtMillis == zoneStart && phone.settings.zone == zone, "phones got the server's schedule")
        val crossing = zoneStart + firstClearlyOutside(zone, annaAt, accuracyMeters = 8.0, longGrace)
        note("Anna is clearly outside from ${(crossing - zoneStart) / 1000.0} s into the zone")

        holdsFor(
            "Anna is not warned while the zone covers her, and her phone draws the server's circle",
            (crossing - 1_000 - state().serverTimeMillis).milliseconds,
        ) {
            val server = state()
            val drawn = zone.circleAt(server.serverTimeMillis - zoneStart)
            val judged = checkNotNull(server.zone)
            server.players.single { it.id == anna.id }.outOfZoneSinceMillis == null &&
                drawn.center.distanceTo(judged.center) < 1.0 &&
                abs(drawn.radiusMeters - judged.radiusMeters) < 1.0 &&
                abs(checkNotNull(anna.serverNow()) - server.serverTimeMillis) < 1_000
        }
        val since = eventually("Anna is warned once the zone passed her", within = 10.seconds) {
            anna.onServer().outOfZoneSinceMillis
        }
        check(since in crossing..crossing + 5_000, "warned ${since - crossing} ms after the zone passed her")
        awaitThat("Anna's phone shows the warning") { anna.snapshot?.me?.outOfZoneDeadlineMillis != null }
        awaitReveal(anna, VisibilityReason.OUT_OF_ZONE, to = sam, within = 5.seconds)

        awaitServerTime(zoneStart + 25_000)
        holdsFor("Boris, who went with the zone, is never warned", 5.seconds) {
            boris.onServer().outOfZoneSinceMillis == null
        }
    }

    /**
     * The zone starts with SEEKING: outside it while hiding is fine. Whoever is still outside when seeking starts is
     * warned at once and eliminated after the grace period; whoever came back in time is never warned.
     */
    @Test
    fun outsideTheZoneWhileHiding() = scenario("Outside the zone while hiding") {
        val rules = GameSetups.FAST_RULES
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 150.0), noise = exact8)
        val boris = player("Boris", at = PARK.offset(eastMeters = -130.0), noise = exact8)

        sam.createsGame(GameSetups.fixedZone(100.0, rules = rules).copy(hidingSeconds = 20))
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        // 90 m in 15 s: back inside well before seeking starts, and inside for the whole decision window.
        boris.walksTo(PARK.offset(eastMeters = -40.0), speed = 6.0)
        val hiding = state()
        val seekingStarts = checkNotNull(hiding.phaseEndsAtMillis)
        val untilSeeking = (seekingStarts - hiding.serverTimeMillis - 1_000).milliseconds
        holdsFor("nobody is warned or revealed while hiding", untilSeeking) {
            val hiders = state().players.filter { it.id != sam.id }
            hiders.all { it.outOfZoneSinceMillis == null && it.revealedToSeekers == null } &&
                anna.snapshot?.me?.outOfZoneDeadlineMillis == null
        }

        awaitPhase(GamePhase.SEEKING, within = 5.seconds)
        val since = eventually("Anna is warned as seeking starts", within = 5.seconds) {
            anna.onServer().outOfZoneSinceMillis
        }
        check(since - seekingStarts < 3_000, "warned ${since - seekingStarts} ms into seeking")
        awaitReveal(anna, VisibilityReason.OUT_OF_ZONE, to = sam, within = 5.seconds)
        awaitStatus(anna, PlayerStatus.ELIMINATED, within = (rules.outOfZoneGraceSeconds + 5).seconds)

        val server = boris.onServer()
        check(server.status == PlayerStatus.ACTIVE && server.outOfZoneSinceMillis == null, "Boris was never warned")
        check(state().phase == GamePhase.SEEKING, "the game goes on with Boris")
    }

    /**
     * The first millisecond into [zone] from which a player standing at [at] is clearly outside it, to the millisecond
     * (the server checks at any moment): a binary search, as the zone only closes in on the player.
     */
    private fun firstClearlyOutside(zone: ZoneSchedule, at: GeoPoint, accuracyMeters: Double, rules: GameRules): Long {
        val fix = LocationSample(at, accuracyMeters, timestampMillis = 0)
        fun outside(millis: Long) = ZoneRules.isClearlyOutside(fix, zone.circleAt(millis), rules)
        var inside = 0L
        var out = 10 * 60_000L
        check(!outside(inside) && outside(out)) { "the zone never passes $at" }
        while (out - inside > 1) {
            val middle = (inside + out) / 2
            if (outside(middle)) out = middle else inside = middle
        }
        return out
    }
}
