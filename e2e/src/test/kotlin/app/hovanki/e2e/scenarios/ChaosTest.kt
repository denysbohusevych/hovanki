package app.hovanki.e2e.scenarios

import app.hovanki.e2e.bot.BotBehavior
import app.hovanki.e2e.bot.BotPlayer
import app.hovanki.e2e.bot.ClaimReaction
import app.hovanki.e2e.bot.CommandResult
import app.hovanki.e2e.bot.VoteReaction
import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.route.Route
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameInvariants
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.e2e.scenario.Scenario
import app.hovanki.shared.debug.DebugBuildings
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assumptions
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Chaos games: two seekers chase six hiders with city GPS while the hiders' phones misbehave at random (GPS off,
 * network off, the app killed, mocked fixes, running out of the zone or into the test quarter for a while) and the
 * hiders react at random (show the code, dispute, stay silent; vote or not). Nothing in particular is expected. What
 * must hold is checked on the server's state every quarter of a second ([GameInvariants]); the game must end; and, as
 * in every scenario, no position leaks and the server never fails.
 *
 * Seeded: the report and a failure name the seed, `HOVANKI_E2E_CHAOS_SEED=<seed> ./gradlew :e2e:test --tests
 * '*ChaosTest*'` plays that game again (timing still differs a little from run to run).
 */
class ChaosTest {
    @Test
    fun seed11() = chaosGame(11)

    @Test
    fun seed22() = chaosGame(22)

    @Test
    fun seed33() = chaosGame(33)

    private fun chaosGame(defaultSeed: Long) {
        val forced = System.getenv("HOVANKI_E2E_CHAOS_SEED")?.toLongOrNull()
        // A seed given: one game with it is enough.
        Assumptions.assumeTrue(forced == null || defaultSeed == 11L, "HOVANKI_E2E_CHAOS_SEED=$forced plays once")
        val seed = forced ?: defaultSeed
        scenario("Chaos game, seed $seed", timeout = 4.minutes) { playChaos(seed) }
    }

    private suspend fun Scenario.playChaos(seed: Long) {
        note("chaos seed $seed; again: HOVANKI_E2E_CHAOS_SEED=$seed ./gradlew :e2e:test --tests '*ChaosTest*'")
        val random = Random(seed)
        val rules = GameSetups.FAST_RULES
        val seekers = (1..2).map { n ->
            player("Seeker$n", at = spot(random, 30.0), noise = GpsNoise.city(seed * 100 + n))
        }
        val hiders = (1..6).map { n ->
            val noise = GpsNoise.city(seed * 100 + 10 + n)
            player("Hider$n", at = spot(random, 60.0), noise = noise, behavior = behavior(random))
        }

        seekers.first().createsGame(GameSetups.fast(rules = rules).copy(seekingSeconds = SEEKING_SECONDS))
        join(*(seekers.drop(1) + hiders).toTypedArray())
        seekers.first().startsGame(seekers)
        for (hider in hiders) hider.walksTo(spot(random, OUTERMOST_METERS), speed = random.nextDouble(2.0, 4.0))

        val invariants = GameInvariants(graceMillis = rules.outOfZoneGraceSeconds * 1000L)
        // What the chaos got to, for the report.
        val warned = HashSet<PlayerId>()
        val inBuilding = HashSet<PlayerId>()
        val disputed = HashSet<CatchId>()
        coroutineScope {
            val chaos = launch {
                for (hider in hiders) launch { hiderChaos(hider, Random(random.nextLong())) }
                for (seeker in seekers) launch { seekerChaos(seeker, hiders, Random(random.nextLong())) }
            }
            eventually("the game ends", within = (SEEKING_SECONDS + 40).seconds) {
                val server = state()
                invariants.check(server)
                warned += server.players.filter { it.outOfZoneSinceMillis != null }.map { it.id }
                inBuilding += server.players.filter { it.insideBuildingSinceMillis != null }.map { it.id }
                disputed += server.catches.filter { it.status == CatchStatus.DISPUTED }.map { it.id }
                server.takeIf { it.phase == GamePhase.FINISHED }
            }
            chaos.cancel()
        }

        val end = state().also(invariants::check)
        val hidersEnd = end.players.filter { it.role == Role.HIDER }.groupingBy { it.status }.eachCount()
        val claimsEnd = end.catches.groupingBy { it.status }.eachCount()
        note(
            "the end: hiders $hidersEnd, claims $claimsEnd; warned out of the zone ${warned.size}, " +
                "inside a building ${inBuilding.size}, disputes ${disputed.size}",
        )
        check(
            invariants.violations.isEmpty(),
            "the rules held at all ${invariants.checks} checks" +
                invariants.violations.joinToString("") { "\n  $it" },
        )
    }

    /** A hider's phone and its owner misbehaving, every few seconds something else. */
    private suspend fun Scenario.hiderChaos(hider: BotPlayer, random: Random) {
        while (true) {
            delay(random.nextLong(3_000, 8_000).milliseconds)
            when (random.nextInt(100)) {
                in 0 until 25 -> hider.walksTo(spot(random, OUTERMOST_METERS), speed = random.nextDouble(1.5, 4.0))

                in 25 until 33 -> {
                    // Out of the zone for a while: warned, maybe eliminated.
                    hider.walksTo(outOfTheZone(random), speed = 4.0)
                    delay(random.nextLong(15_000, 35_000).milliseconds)
                }

                in 33 until 40 -> {
                    // Into the test quarter for a while: warned, maybe revealed.
                    hider.walksTo(INSIDE_THE_QUARTER, speed = 4.0)
                    delay(random.nextLong(20_000, 40_000).milliseconds)
                }

                in 40 until 50 -> {
                    hider.turnsGpsOff()
                    delay(random.nextLong(5_000, 20_000).milliseconds)
                    hider.turnsGpsOn()
                }

                in 50 until 62 -> {
                    hider.losesNetwork()
                    delay(random.nextLong(5_000, 20_000).milliseconds)
                    hider.regainsNetwork()
                }

                in 62 until 70 -> {
                    hider.killApp()
                    delay(random.nextLong(2_000, 8_000).milliseconds)
                    hider.launchApp()
                }

                in 70 until 75 -> {
                    hider.startsMockingLocation()
                    delay(3.seconds)
                    hider.stopsMockingLocation()
                }

                in 75 until 90 -> hider.sendChat(CHAT[random.nextInt(CHAT.size)], team = random.nextBoolean())

                else -> Unit
            }
        }
    }

    /**
     * A seeker runs after the nearest hider still in the game (by where the hider really is), claims when close, and
     * types the code if the hider shows it. Now and then the seeker's network drops.
     */
    private suspend fun Scenario.seekerChaos(seeker: BotPlayer, hiders: List<BotPlayer>, random: Random) {
        while (true) {
            val server = state()
            if (server.phase == GamePhase.FINISHED) return
            if (server.phase != GamePhase.SEEKING) {
                delay(1.seconds)
                continue
            }
            val active = server.players.filter { it.role == Role.HIDER && it.status == PlayerStatus.ACTIVE }
            val target = hiders.filter { hider -> active.any { it.id == hider.playerId } }
                .minByOrNull { it.gps.truePosition.distanceTo(seeker.gps.truePosition) } ?: return
            seeker.walksTo(target.gps.truePosition, speed = Route.RUNNING)
            delay(2.seconds)
            if (random.nextInt(100) < 10) {
                seeker.losesNetwork()
                delay(random.nextLong(3_000, 8_000).milliseconds)
                seeker.regainsNetwork()
            }
            val close = seeker.gps.truePosition.distanceTo(target.gps.truePosition) < CLAIM_METERS
            if (close && seeker.claimCatch(target) == CommandResult.Ok) typeTheCodeIfShown(seeker, target)
        }
    }

    /** Until the code timeout has surely passed: reads the code off the hider's screen, if it shows one. */
    private suspend fun typeTheCodeIfShown(seeker: BotPlayer, hider: BotPlayer) {
        val until = System.currentTimeMillis() + (GameSetups.FAST_RULES.catchCodeTimeoutSeconds + 2) * 1000L
        while (System.currentTimeMillis() < until) {
            val code = hider.shownCode()
            if (code != null && seeker.confirmCatch(code.code) == CommandResult.Ok) return
            delay(1.seconds)
        }
    }

    /** 50 to 90 m outside the final zone (200 m): clearly out, walking distance from the rest of the game. */
    private fun outOfTheZone(random: Random): GeoPoint {
        val angle = random.nextDouble(0.0, 2 * PI)
        val distance = random.nextDouble(250.0, 290.0)
        return PARK.offset(eastMeters = distance * cos(angle), northMeters = distance * sin(angle))
    }

    /** Anywhere within [withinMeters] of the zone's center, evenly over the area. */
    private fun spot(random: Random, withinMeters: Double): GeoPoint {
        val angle = random.nextDouble(0.0, 2 * PI)
        val distance = withinMeters * sqrt(random.nextDouble())
        return PARK.offset(eastMeters = distance * cos(angle), northMeters = distance * sin(angle))
    }

    private fun behavior(random: Random) = BotBehavior(
        onClaim = when (random.nextInt(100)) {
            in 0 until 50 -> ClaimReaction.ShowCode()
            in 50 until 85 -> ClaimReaction.Dispute()
            else -> ClaimReaction.Ignore
        },
        onDispute = if (random.nextInt(100) < 70) {
            VoteReaction.Vote(confirm = random.nextBoolean())
        } else {
            VoteReaction.Abstain
        },
    )

    private companion object {
        const val SEEKING_SECONDS = 90

        /** Hiders roam up to here: past the final zone (200 m), so some are warned and eliminated. */
        const val OUTERMOST_METERS = 230.0

        /** Close enough for a seeker to press "found". */
        const val CLAIM_METERS = 20.0

        val CHAT = listOf("where are you", "over here", "not telling", "gg", "hurry up", "lol")

        /** The middle of the test quarter the server puts next to every zone (profile `e2e`, [DebugBuildings]). */
        val INSIDE_THE_QUARTER = PARK.offset(DebugBuildings.INSIDE_EAST, DebugBuildings.INSIDE_NORTH)
    }
}
